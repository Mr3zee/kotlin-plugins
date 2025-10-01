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

const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"

@Service(Service.Level.PROJECT)
class KotlinPluginsStorageService(
    private val project: Project,
    parentScope: CoroutineScope,
) {
    private var resolvedCacheDir = false
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
    private val actualizerJobs = ConcurrentHashMap<KotlinPluginDescriptorVersioned, Job>()

    private val logger by lazy { thisLogger() }

    private val pluginsCache =
        ConcurrentHashMap<String, ConcurrentHashMap<KotlinPluginDescriptor, ConcurrentHashMap<String, Path>>>()

    fun clearCaches() {
        scope.launch(CoroutineName("clear-caches")) {
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
                            it.forEach { (plugin, versions) ->
                                versions.keys.forEach { version ->
                                    scope.actualize(KotlinPluginDescriptorVersioned(plugin, version))
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
            context = CoroutineName("jar-fetcher-${descriptor.groupId}-${descriptor.artifactId}"),
            start = CoroutineStart.LAZY,
        ) {
            actualizerLock.withLock {
                body()
            }
        }
    }

    private fun CoroutineScope.actualize(
        plugin: KotlinPluginDescriptorVersioned,
        attempt: Int = 1,
    ) {
        if (attempt > 3) {
            logger.debug("Actualize plugins job failed after ${attempt - 1} attempts")
            return
        }

        val descriptor = plugin.descriptor
        val currentJob = actualizerJobs[plugin]
        if (currentJob != null && currentJob.isActive) {
            logger.debug("Actualize plugins job is already running")
            return
        }

        val changed = AtomicBoolean(false)

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        var failed = false
        val nextJob = launchActualizer(descriptor) {
            val destination = cacheDir()
                ?.resolve(kotlinIdeVersion)
                ?.resolve(descriptor.getPluginGroupPath())
                ?: return@launchActualizer

            logger.debug("Actualize plugins job started ${descriptor.groupId}-${descriptor.artifactId}")

            val jarResult = runCatching {
                withContext(Dispatchers.IO) {
                    KotlinPluginJarLocator.locateArtifact(
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

            val jar = jarResult.getOrThrow() ?: return@launchActualizer

            if (jar.isNew) {
                changed.compareAndSet(false, true)
            }

            pluginsCache.getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                .getOrPut(descriptor) { ConcurrentHashMap() }
                .compute(jar.libVersion) { _, _ -> jar.path }
        }

        scope.launch(CoroutineName("actualize-plugins-job-starter-${descriptor.groupId}-${descriptor.artifactId}")) {
            actualizerLock.withLock {
                val running = actualizerJobs.computeIfAbsent(plugin) { nextJob }
                if (running != nextJob) {
                    nextJob.cancel()
                    return@withLock
                }

                nextJob.invokeOnCompletion {
                    actualizerJobs.remove(plugin)

                    if (failed) {
                        scope.actualize(plugin, attempt + 1)
                    } else if (changed.get()) {
                        invalidateKotlinPluginsCache()
                    }
                }

                nextJob.start()
            }
        }
    }

    fun getPluginPath(versioned: KotlinPluginDescriptorVersioned): Path? {
        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val map = pluginsCache.getOrPut(kotlinVersion) { ConcurrentHashMap() }
        val pluginMap = map.getOrPut(versioned.descriptor) { ConcurrentHashMap() }

        val foundInFiles by lazy {
            findJarPath(versioned, kotlinVersion)
        }

        var searchedInFiles = false

        val path = pluginMap.compute(versioned.version) { _, old ->
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
            versioned.version
        }

        logger.debug("Requested version is ${versioned.version} for ${versioned.descriptor}, located version: $locatedVersion, path: $path")

        if (path == null || locatedVersion != versioned.version) {
            // no requested version is present
            scope.actualize(versioned)
        }

        return path
    }

    private fun findJarPath(
        versioned: KotlinPluginDescriptorVersioned,
        kotlinVersion: String,
    ): Pair<String, Path>? {
        val basePath = cacheDirBlocking()
            ?.resolve(kotlinVersion)
            ?.resolve(versioned.descriptor.getPluginGroupPath())
            ?: return null

        if (!Files.exists(basePath)) {
            return null
        }

        val candidates = basePath
            .listDirectoryEntries("${versioned.descriptor.artifactId}-$kotlinVersion-*.jar")
            .toList()

        logger.debug("Candidates for ${versioned.descriptor}:${versioned.version} -> ${candidates.map { it.fileName }}")

        val versionToPath = candidates
            .associateBy {
                it.name
                    .substringAfter("${versioned.descriptor.artifactId}-$kotlinVersion-")
                    .substringBefore(".jar")
            }

        val latest = getMatching(versionToPath.keys.toList(), "", versioned.asMatchFilter())

        return latest?.let { it to versionToPath.getValue(it) }
    }

    @Suppress("UnstableApiUsage")
    private inline fun resolveCacheDir(getApi: () -> EelApi): Path? {
        if (resolvedCacheDir) {
            return _cacheDir
        }

        val userHome = getApi().fs.user.home.asNioPathOrNull() ?: Path("/") // user is nobody
        _cacheDir = userHome.resolve(KOTLIN_PLUGINS_STORAGE_DIRECTORY)?.toAbsolutePath()
        resolvedCacheDir = true

        return _cacheDir
    }

    private fun invalidateKotlinPluginsCache() {
        try {
            val provider = KotlinCompilerPluginsProvider.getInstance(project)

            if (provider is Disposable) {
                provider.dispose() // clear Kotlin plugin caches
            }

            logger.debug("Invalidated KotlinCompilerPluginsProvider and cleared cache misses")
        } catch (_: ProcessCanceledException) {
            // fixes "Container 'ProjectImpl@341936598 services' was disposed"
        }
    }

    private fun KotlinPluginDescriptor.getPluginGroupPath(): Path {
        val group = groupId.split(".")
        return Path.of(group[0], *group.drop(1).toTypedArray())
    }
}
