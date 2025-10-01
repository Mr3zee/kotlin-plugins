@file:Suppress("RemoveUnnecessaryParentheses")

package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.provider.asNioPathOrNull
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.upgradeBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.forEach
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes

@Service(Service.Level.PROJECT)
class KotlinPluginsStorage(
    private val project: Project,
    parentScope: CoroutineScope,
) {
    private val resolvedCacheDir = AtomicBoolean(false)
    private var _cacheDir: Path? = null
    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext.job)

    @Suppress("UnstableApiUsage")
    suspend fun cacheDir(): Path? {
        return resolveCacheDir { project.getEelDescriptor().upgrade() }
    }

    @Suppress("UnstableApiUsage")
    fun cacheDirBlocking(): Path? {
        return resolveCacheDir { project.getEelDescriptor().upgradeBlocking() }
    }

    private val actualizerLock = Mutex()
    private val actualizerJobs = ConcurrentHashMap<VersionedKotlinPluginDescriptor, Job>()

    private val logger by lazy { thisLogger() }

    private val pluginsCache =
        ConcurrentHashMap<String, ConcurrentHashMap<KotlinPluginDescriptor, ConcurrentHashMap<ResolvedPlugin, Path>>>()

    fun clearCaches() {
        scope.launch(CoroutineName("clear-caches")) {
            logger.debug("Clearing caches")
            try {
                while (true) {
                    actualizerJobs.values.forEach { it.cancelAndJoin() }
                    if (actualizerLock.tryLock()) {
                        break
                    }
                }

                actualizerJobs.values.forEach { it.cancelAndJoin() }
                actualizerJobs.clear()

                pluginsCache.clear()
                invalidateKotlinPluginsCache()

                cacheDir()?.let {
                    @OptIn(ExperimentalPathApi::class)
                    runCatching {
                        it.deleteRecursively()
                    }
                }
            } finally {
                actualizerLock.unlock()
            }
        }
    }

    init {
        scope.launch(CoroutineName("actualizer-root")) {
            supervisorScope {
                launch(CoroutineName("actualizer-loop")) {
                    while (isActive) {
                        delay(2.minutes)
                        logger.debug("Scheduled actualize job started")

                        pluginsCache.values.forEach {
                            it.forEach { (plugin, artifacts) ->
                                artifacts.keys.distinctBy { k -> k.libVersion }.forEach { artifact ->
                                    scope.actualize(VersionedKotlinPluginDescriptor(plugin, artifact.libVersion))
                                }
                            }
                        }
                    }
                }
            }
        }

        scope.coroutineContext.job.invokeOnCompletion {
            pluginsCache.clear()
            actualizerJobs.clear()
            logger.debug("Storage closed")
        }
    }

    private fun CoroutineScope.launchActualizer(
        descriptor: KotlinPluginDescriptor,
        body: suspend CoroutineScope.() -> Unit,
    ): Job {
        return launch(
            context = CoroutineName("jar-fetcher-${descriptor.name}"),
            start = CoroutineStart.LAZY,
        ) {
            actualizerLock.withLock {
                body()
            }
        }
    }

    private fun CoroutineScope.actualize(
        plugin: VersionedKotlinPluginDescriptor,
        attempt: Int = 1,
    ) {
        if (attempt > 3) {
            logger.debug("Actualize plugins job failed after ${attempt - 1} attempts (${plugin.descriptor.name})")
            return
        }

        val descriptor = plugin.descriptor
        val currentJob = actualizerJobs[plugin]
        if (currentJob != null && currentJob.isActive) {
            logger.debug("Actualize plugins job is already running (${plugin.descriptor.name})")
            return
        }

        val changed = AtomicBoolean(false)

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        var failed = false
        val nextJob = launchActualizer(descriptor) {
            val destination = cacheDir()?.resolve(kotlinIdeVersion)
                ?: return@launchActualizer

            logger.debug("Actualize plugins job started (${plugin.descriptor.name})")

            val jarResult = runCatching {
                withContext(Dispatchers.IO) {
                    KotlinPluginJarLocator.locateArtifacts(
                        project = project,
                        versioned = plugin,
                        kotlinIdeVersion = kotlinIdeVersion,
                        dest = destination,
                    )
                }
            }

            if (jarResult.isFailure) {
                failed = true
                return@launchActualizer
            }

            val bundle = jarResult.getOrThrow()

            logger.debug("Actualize bundle ${plugin.descriptor.name}: ${bundle.jars.count { it.value == null }} not found")

            failed = !bundle.allFound()

            bundle.jars.values.filterNotNull().forEach { jar ->
                val artifactsMap = pluginsCache.getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                    .getOrPut(descriptor) { ConcurrentHashMap() }

                val resolvedPlugin = ResolvedPlugin(jar.artifactId, jar.libVersion)

                artifactsMap.compute(resolvedPlugin) { _, old ->
                    if (old != jar.path) {
                        changed.compareAndSet(false, true)
                    }

                    jar.path
                }
            }
        }

        scope.launch(CoroutineName("actualize-plugins-job-starter-${descriptor.name}")) {
            actualizerLock.withLock {
                val running = actualizerJobs.computeIfAbsent(plugin) { nextJob }
                if (running != nextJob) {
                    nextJob.cancel()
                    return@withLock
                }

                nextJob.invokeOnCompletion {
                    actualizerJobs.remove(plugin)

                    if (failed) {
                        logger.debug("Actualize plugins job self restart (${plugin.descriptor.name})")
                        scope.actualize(plugin, attempt + 1)
                    } else if (changed.get()) {
                        invalidateKotlinPluginsCache()
                    }
                }

                nextJob.start()
            }
        }
    }

    fun getPluginPath(requested: RequestedKotlinPluginDescriptor): Path? {
        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val map = pluginsCache.getOrPut(kotlinVersion) { ConcurrentHashMap() }
        val pluginMap = map.getOrPut(requested.descriptor) { ConcurrentHashMap() }

        val paths = requested.descriptor.ids.map {
            val artifact = RequestedKotlinPluginDescriptor(
                descriptor = requested.descriptor,
                version = requested.version,
                artifact = it,
            )

            findArtifact(pluginMap, artifact, kotlinVersion)
        }

        logger.debug(
            "Requested version is ${requested.version} for ${requested.descriptor.name}, " +
                "versions: ${paths.map { "${it.artifactId} -> ${it.locatedVersion}" }}"
        )

        if (paths.any { it.path == null } || paths.distinctBy { it.locatedVersion }.size != 1) {
            // no requested version is present for all requested plugins, or versions are not equal
            scope.actualize(requested)
            return null
        }

        // return not null only when all requested plugins are present
        return paths.find { it.artifactId == requested.artifact.artifactId }?.path
    }

    class StoredJar(
        val artifactId: String,
        val locatedVersion: String?,
        val path: Path?,
    )

    private fun findArtifact(
        pluginMap: ConcurrentHashMap<ResolvedPlugin, Path>,
        requested: RequestedKotlinPluginDescriptor,
        kotlinVersion: String,
    ): StoredJar {
        val foundInFiles by lazy {
            findJarPath(requested, kotlinVersion)
        }

        var searchedInFiles = false

        val resolvedPlugin = ResolvedPlugin(requested.artifact.artifactId, requested.version)
        val path = pluginMap.compute(resolvedPlugin) { _, old ->
            when {
                old != null && Files.exists(old) -> old
                else -> {
                    searchedInFiles = true
                    foundInFiles?.second
                }
            }
        }

        val locatedVersion = if (searchedInFiles) {
            foundInFiles?.first
        } else {
            requested.version
        }

        logger.debug(
            "Requested version is ${requested.version} for ${requested.descriptor.name} (${requested.artifact.artifactId}), " +
                    "located version: $locatedVersion, path: $path"
        )

        return StoredJar(
            artifactId = requested.artifact.artifactId,
            locatedVersion = locatedVersion,
            path = path,
        )
    }

    private fun findJarPath(
        requested: RequestedKotlinPluginDescriptor,
        kotlinVersion: String,
    ): Pair<String, Path>? {
        val basePath = cacheDirBlocking()
            ?.resolve(kotlinVersion)
            ?.resolve(requested.artifact.getPluginGroupPath())
            ?: return null

        if (!Files.exists(basePath)) {
            return null
        }

        val candidates = basePath
            .listDirectoryEntries("${requested.artifact.artifactId}-$kotlinVersion-*.jar")
            .toList()

        logger.debug("Candidates for ${requested.artifact.id}:${requested.version} -> ${candidates.map { it.fileName }}")

        val versionToPath = candidates
            .associateBy {
                it.name
                    .substringAfter("${requested.artifact.artifactId}-$kotlinVersion-")
                    .substringBefore(".jar")
            }

        val latest = getMatching(versionToPath.keys.toList(), "", requested.asMatchFilter())

        return latest?.let { it to versionToPath.getValue(it) }
    }

    @Suppress("UnstableApiUsage")
    private inline fun resolveCacheDir(getApi: () -> EelApi): Path? {
        if (!resolvedCacheDir.compareAndSet(false, true)) {
            return _cacheDir
        }

        val userHome = getApi().fs.user.home.asNioPathOrNull() ?: Path("/") // user is nobody
        _cacheDir = userHome.resolve(KOTLIN_PLUGINS_STORAGE_DIRECTORY).toAbsolutePath()

        return _cacheDir
    }

    private fun invalidateKotlinPluginsCache() {
        try {
            val provider = KotlinCompilerPluginsProvider.getInstance(project)

            if (provider is Disposable) {
                provider.dispose() // clear Kotlin plugin caches
            }

            logger.debug("Invalidated KotlinCompilerPluginsProvider")
        } catch (_: ProcessCanceledException) {
            // fixes "Container 'ProjectImpl@341936598 services' was disposed"
        }
    }

    companion object {
        const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"
    }

    private data class ResolvedPlugin(
        val artifactId: String,
        val libVersion: String,
    )
}
