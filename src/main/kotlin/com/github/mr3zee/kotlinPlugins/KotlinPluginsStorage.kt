package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.fs.EelFileSystemApi
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.getEelApi
import com.intellij.platform.eel.provider.getEelApiBlocking
import com.intellij.platform.eel.provider.utils.userHomeBlocking
import com.intellij.platform.eel.toNioPath
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"

@Service
class KotlinPluginsStorageService(private val scope: CoroutineScope) {
    private var resolvedCacheDir = false
    private var _cacheDir: Path? = null

    @Suppress("UnstableApiUsage")
    suspend fun cacheDir(project: Project?): Path? {
        return resolveCacheDir(
            getApi = { project?.getEelApi() },
            getUserHome = { userHome() },
        )
    }

    @Suppress("UnstableApiUsage")
    fun cacheDirBlocking(project: Project?): Path? {
        return resolveCacheDir(
            getApi = { project?.getEelApiBlocking() },
            getUserHome = { userHomeBlocking() },
        )
    }

    private val logger by lazy { thisLogger() }

    private val cacheMisses = ConcurrentHashMap<KotlinPluginDescriptor, Boolean>()
    private val pluginsCache = ConcurrentHashMap<String, ConcurrentHashMap<KotlinPluginDescriptor, Path?>>()

    private val runningActualizeJob = AtomicReference<Job?>(null)

    private val pluginRequests = ConcurrentHashMap<KotlinPluginDescriptor, String>()
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
                                actualizePlugins(null)
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
                                actualizePlugins(null)
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            logger.debug("Actualize request queue closed")
                        } catch (_: CancellationException) {
                            // ignore
                        }
                    }
                }
            } catch (_ : CancellationException) {
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
        versioned.version?.let { pluginRequests[versioned.descriptor] = it }

        scope.launch(CoroutineName("actualize-plugins-request")) {
            delay(50.milliseconds) // allow for aggregation
            actualizeRequestQueue.send(Unit)
        }
    }

    private fun CoroutineScope.actualizePlugins(project: Project?) {
        val currentJob = runningActualizeJob.get()
        if (currentJob != null && currentJob.isActive) {
            logger.debug("Actualize plugins job is already running")
            return
        }

        val nextJob = launch(CoroutineName("jar-fetcher-root"), start = CoroutineStart.LAZY) {
            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
            val plugins = service<KotlinPluginsSettingsService>().state.plugins

            logger.debug("Actualize plugins job started (jar-fetcher-root), $kotlinIdeVersion: ${plugins.joinToString()}")

            supervisorScope {
                plugins.forEach { plugin ->
                    val destination = cacheDir(project)
                        ?.resolve(kotlinIdeVersion)
                        ?.resolve(plugin.getPluginGroupPath())
                        ?: return@forEach

                    launch(CoroutineName("jar-fetcher-${plugin.groupId}-${plugin.artifactId}")) {
                        val jarResult = runCatching {
                            withContext(Dispatchers.IO) {
                                KotlinPluginsJarDownloader.downloadArtifactIfNotExists(
                                    repoUrl = plugin.repoUrl,
                                    groupId = plugin.groupId,
                                    artifactId = plugin.artifactId,
                                    kotlinIdeVersion = kotlinIdeVersion,
                                    dest = destination,
                                    optionalPreferredLibVersion = { pluginRequests[plugin] }
                                )
                            }
                        }

                        if (jarResult.isFailure) {
                            cacheMisses.computeIfPresent(plugin) { _, _ -> true }
                        }

                        val jar = jarResult.getOrNull()

                        if (jar == null) {
                            return@launch
                        }

                        val current = pluginRequests.compute(plugin) { _, old ->
                            if (old == jar.preferredVersion) {
                                null
                            } else {
                                old
                            }
                        }

                        val valid = cacheMisses.computeIfPresent(plugin) { _, _ ->
                            // true - no new request needed
                            // false - another version was requested, needs to recalculate
                            current == jar.preferredVersion
                        } == true

                        if (valid) {
                            pluginsCache.getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                                .compute(plugin) { _, _ -> jar.path }
                        }
                    }
                }
            }
        }

        if (runningActualizeJob.compareAndSet(currentJob, nextJob)) {
            nextJob.invokeOnCompletion {
                runningActualizeJob.compareAndSet(nextJob, null)

                if (cacheMisses.any { !it.value }) {
                    actualizePlugins(project)
                } else {
                    if (project != null) {
                        invalidateKotlinPluginsCache(project)
                    } else {
                        KotlinPluginsProjectsMap.instances.forEach { (project, _) ->
                            if (project.isDisposed) {
                                KotlinPluginsProjectsMap.instances.remove(project)
                            }

                            invalidateKotlinPluginsCache(project)
                        }
                    }
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
        val path = map.compute(versioned.descriptor) { _, old ->
            if (old == null || !Files.exists(old)) {
                findJarPath(versioned, kotlinVersion)
            } else {
                old
            }
        }

        if (path == null) {
            // cache miss, but no other version is present
            cacheMisses[versioned.descriptor] = false
            requestActualizePlugins(versioned)
        } else {
            cacheMisses.remove(versioned.descriptor)
        }

        return path
    }

    private fun findJarPath(
        versioned: KotlinPluginDescriptorVersioned,
        kotlinVersion: String,
    ): Path? {
        val basePath = cacheDirBlocking(null)
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
            }?.let { return it }
        }

        candidates.find { it.name.endsWith("-$FOR_IDE_CLASSIFIER.jar") }?.let { return it }

        return candidates.firstOrNull()
    }

    @Suppress("UnstableApiUsage")
    private inline fun resolveCacheDir(
        getApi: () -> EelApi?,
        getUserHome: EelFileSystemApi.() -> EelPath.Absolute?,
    ): Path? {
        if (resolvedCacheDir) {
            return _cacheDir
        }

        val eel = getApi()

        val userHome = if (eel == null) {
            when {
                SystemInfo.isWindows -> System.getenv("USERPROFILE")
                else -> System.getenv("HOME")
            }?.let(Path::of)
        } else {
            eel.fs.getUserHome()?.toNioPath(eel) ?: Path("/") // user is nobody
        }

        _cacheDir = userHome?.resolve(KOTLIN_PLUGINS_STORAGE_DIRECTORY)?.toAbsolutePath()
        resolvedCacheDir = true

        return _cacheDir
    }

    private fun invalidateKotlinPluginsCache(project: Project) {
        val provider = KotlinCompilerPluginsProvider.getInstance(project)

        if (provider is Disposable) {
            provider.dispose() // clear Kotlin plugin caches
        }

        cacheMisses.clear()
    }

    private fun KotlinPluginDescriptor.getPluginGroupPath(): Path {
        val group = groupId.split(".")
        return Path.of(group[0], *group.drop(1).toTypedArray())
    }
}
