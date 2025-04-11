package com.github.mr3zee.kotlinPlugins

import com.github.mr3zee.kotlinPlugins.KotlinPluginsJarDownloader.lockedFiles
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.tools.projectWizard.core.ignore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"
private const val CACHE_MISS_ANY_KEY = "<any>"

@Service(Service.Level.PROJECT)
class KotlinPluginsStorageService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private var resolvedCacheDir = false
    private var _cacheDir: Path? = null

    @Suppress("UnstableApiUsage")
    suspend fun cacheDir(): Path? {
        return resolveCacheDir { project.getEelDescriptor().upgrade() }
    }

    @Suppress("UnstableApiUsage")
    fun cacheDirBlocking(): Path? {
        return resolveCacheDir { project.getEelDescriptor().upgradeBlocking() }
    }

    fun clearCaches() {
        scope.launch(CoroutineName("clear-caches")) {
            try {
                while (true) {
                    runningActualizeJob.get()?.cancelAndJoin()
                    if (actualizerLock.tryLock()) {
                        break
                    }
                }

                runningActualizeJob.get()?.cancelAndJoin()
                runningActualizeJob.set(null)

                pluginRequests.clear()
                cacheMisses.clear()
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

    private val logger by lazy { thisLogger() }

    private val cacheMisses = ConcurrentHashMap<KotlinPluginDescriptor, ConcurrentHashMap<String, Boolean>>()
    private val pluginsCache = ConcurrentHashMap<String, ConcurrentHashMap<KotlinPluginDescriptor, ConcurrentHashMap<String, Path>>>()

    private val runningActualizeJob = AtomicReference<Job?>(null)

    private val pluginRequests = ConcurrentHashMap<KotlinPluginDescriptor, Set<String>>()
    private val actualizeRequestQueue = Channel<Unit>(1024)

    init {
        scope.launch(CoroutineName("actualizer-root")) {
            try {
                supervisorScope {
                    launch(CoroutineName("actualizer-loop")) {
                        try {
                            while (isActive) {
                                delay(2.minutes)
                                logger.debug("Scheduled actualize job started")
                                actualizePlugins()
                                // wait until the job is finished, when 2-min delay
                            }
                        } catch (_: CancellationException) {
                            // ignore
                        }
                    }

                    launch(CoroutineName("actualizer-consumer")) {
                        try {
                            while (isActive) {
                                actualizeRequestQueue.receive()
                                actualizePlugins()
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            logger.debug("Actualize request queue closed")
                        } catch (_: CancellationException) {
                            // ignore
                        }
                    }
                }
            } catch (_: CancellationException) {
                // ignore
            }
        }

        scope.coroutineContext.job.invokeOnCompletion {
            pluginRequests.clear()
            cacheMisses.clear()
            pluginsCache.clear()
            runningActualizeJob.set(null)
            actualizeRequestQueue.close()
            logger.debug("Storage closed")
        }
    }

    // aggregates multiple requests
    private fun requestActualizePlugins(versioned: KotlinPluginDescriptorVersioned) {
        versioned.version?.let { pluginRequests.compute(versioned.descriptor) { _, old -> old?.plus(it) ?: setOf(it) } }

        scope.launch(CoroutineName("actualize-plugins-request")) {
            delay(50.milliseconds) // allow for aggregation
            actualizeRequestQueue.send(Unit)
        }
    }

    private val actualizerLock = Mutex()

    private fun CoroutineScope.launchFetcher(body: suspend CoroutineScope.() -> Unit): Job {
        return launch(CoroutineName("jar-fetcher-root"), start = CoroutineStart.LAZY) {
            actualizerLock.withLock {
                body()
            }
        }
    }

    private fun CoroutineScope.actualizePlugins() {
        val currentJob = runningActualizeJob.get()
        if (currentJob != null && currentJob.isActive) {
            logger.debug("Actualize plugins job is already running")
            return
        }

        val changed = AtomicBoolean(false)
        val nextJob = launchFetcher {
            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
            val plugins = service<KotlinPluginsSettingsService>().state.plugins

            logger.debug("Actualize plugins job started (jar-fetcher-root), $kotlinIdeVersion: ${plugins.joinToString()}")

            supervisorScope {
                plugins.forEach { plugin ->
                    val destination = cacheDir()
                        ?.resolve(kotlinIdeVersion)
                        ?.resolve(plugin.getPluginGroupPath())
                        ?: return@forEach

                    launch(CoroutineName("jar-fetcher-${plugin.groupId}-${plugin.artifactId}")) {
                        val pluginCacheMisses = cacheMisses.computeIfAbsent(plugin) { ConcurrentHashMap() }

                        val jarResults = runCatching {
                            withContext(Dispatchers.IO) {
                                KotlinPluginsJarDownloader.downloadArtifactIfNotExists(
                                    project = project,
                                    repoUrl = plugin.repoUrl,
                                    groupId = plugin.groupId,
                                    artifactId = plugin.artifactId,
                                    kotlinIdeVersion = kotlinIdeVersion,
                                    dest = destination,
                                    optionalPreferredLibVersions = { pluginRequests[plugin].orEmpty() }
                                )
                            }
                        }

                        if (jarResults.isFailure) {
                            pluginCacheMisses.computeIfPresent(CACHE_MISS_ANY_KEY) { _, _ -> true }
                        }

                        jarResults.getOrNull()?.forEach { jar ->
                            if (jar.downloaded) {
                                changed.compareAndSet(false, true)
                            }

                            val libVersion = jar.artifactVersion.removePrefix("$kotlinIdeVersion-")

                            pluginRequests.compute(plugin) { _, old ->
                                old.orEmpty() - libVersion
                            }

                            // no recalculation needed
                            pluginCacheMisses[libVersion] = true

                            pluginsCache.getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                                .getOrPut(plugin) { ConcurrentHashMap() }
                                .compute(libVersion) { _, _ -> jar.path }
                        }
                    }
                }
            }
        }

        if (runningActualizeJob.compareAndSet(currentJob, nextJob)) {
            nextJob.invokeOnCompletion {
                runningActualizeJob.compareAndSet(nextJob, null)

                if (cacheMisses.flatMap { it.value.values }.any { !it }) {
                    logger.debug("Actualize plugins job self-launch: ${cacheMisses.entries.joinToString { (k, v) -> "{$k: [${v.entries.joinToString { (k1, v1) -> "[$k1 -> $v1]" }}]" }}")
                    actualizePlugins()
                } else if (changed.get()) {
                    invalidateKotlinPluginsCache()
                }
            }

            nextJob.start()
        } else {
            nextJob.cancel()
        }
    }

    fun getPluginPath(versioned: KotlinPluginDescriptorVersioned): Path? {
        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val map = pluginsCache.getOrPut(kotlinVersion) { ConcurrentHashMap() }
        val pluginMap = map.getOrPut(versioned.descriptor) { ConcurrentHashMap() }

        val (locatedVersion, path) = if (versioned.version == null) {
            findJarPath(versioned, kotlinVersion)?.also { (version, path) ->
                pluginMap.compute(version) { _, old ->
                    when {
                        old != null && Files.exists(old) -> old
                        else -> path
                    }
                }
            }
        } else {
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

            if (searchedInFiles) {
                foundInFiles?.first
            } else {
                versioned.version
            } to path

        } ?: (null to null)

        logger.debug("Requested version is ${versioned.version} for ${versioned.descriptor}, located version: $locatedVersion, path: $path")

        val descriptorCacheMisses = cacheMisses.computeIfAbsent(versioned.descriptor) { ConcurrentHashMap() }
        if (path == null || lockedFiles.contains(path.absolutePathString()) || locatedVersion != versioned.version) {
            // cache miss, no requested version is present
            descriptorCacheMisses[versioned.version ?: CACHE_MISS_ANY_KEY] = false
            requestActualizePlugins(versioned)
        } else {
            descriptorCacheMisses.remove(versioned.version)
            descriptorCacheMisses.remove(CACHE_MISS_ANY_KEY)
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

        if (versioned.version != null) {
            candidates.find {
                it.name.endsWith("-${versioned.version}-$FOR_IDE_CLASSIFIER.jar") ||
                        it.name.endsWith("-${versioned.version}.jar")
            }?.let { return versioned.version to it }
        }

        val versionToPath = candidates.filter { it.name.endsWith("-$FOR_IDE_CLASSIFIER.jar") }
            .associateBy {
                it.name
                    .substringAfter("${versioned.descriptor.artifactId}-$kotlinVersion-")
                    .substringBefore("-$FOR_IDE_CLASSIFIER.jar")
                    .substringBefore(".jar")
            }

        val latest = getLatestVersion(versionToPath.keys.toList(), "")

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

            cacheMisses.clear()
            logger.debug("Invalidated KotlinCompilerPluginsProvider and cleared cache misses")
        } catch (_ : ProcessCanceledException) {
            // fixes "Container 'ProjectImpl@341936598 services' was disposed"
        }
    }

    private fun KotlinPluginDescriptor.getPluginGroupPath(): Path {
        val group = groupId.split(".")
        return Path.of(group[0], *group.drop(1).toTypedArray())
    }
}
