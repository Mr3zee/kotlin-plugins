package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries

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

    private val runningActualizeJob = AtomicReference<Job?>(null)

    fun actualizePlugins(project: Project?) {
        val currentJob = runningActualizeJob.get()
        if (currentJob != null && currentJob.isActive) {
            logger.info("Actualize plugins job is already running")
            return
        }

        val nextJob = scope.launch(CoroutineName("jar-fetcher-root"), start = CoroutineStart.LAZY) {
            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
            val plugins = service<KotlinPluginsSettingsService>().state.plugins

            logger.info("Actualize plugins job started (jar-fetcher-root), $kotlinIdeVersion: ${plugins.joinToString()}")

            supervisorScope {
                plugins.forEach { plugin ->
                    val destination = cacheDir(project)
                        ?.resolve(kotlinIdeVersion)
                        ?.resolve(plugin.getPluginGroupPath())
                        ?: return@forEach

                    launch(CoroutineName("jar-fetcher-${plugin.groupId}-${plugin.artifactId}")) {
                        val jarPath = try {
                            withContext(Dispatchers.IO) {
                                KotlinPluginsJarDownloader.downloadLatestIfNotExists(
                                    repoUrl = plugin.repoUrl,
                                    groupId = plugin.groupId,
                                    artifactId = plugin.artifactId,
                                    kotlinIdeVersion = kotlinIdeVersion,
                                    dest = destination,
                                )
                            }
                        } finally {
                            cacheMisses.computeIfPresent(plugin) { _, _ -> true }
                        }

                        if (jarPath == null) {
                            return@launch
                        }

                        pluginsCache.getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                            .compute(plugin) { _, _ -> jarPath }
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

    private val pluginsCache = ConcurrentHashMap<String, ConcurrentHashMap<KotlinPluginDescriptor, Path?>>()

    fun getPluginPath(project: Project?, descriptor: KotlinPluginDescriptor): Path? {
        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val map = pluginsCache.getOrPut(kotlinVersion) { ConcurrentHashMap() }
        val path = map.compute(descriptor) { _, old ->
            if (old == null || !Files.exists(old)) {
                findJarPath(descriptor, kotlinVersion)
            } else {
                old
            }
        }

        if (path == null) {
            // cache miss, but no other version is present
            cacheMisses[descriptor] = false
            actualizePlugins(project)
        } else {
            cacheMisses.remove(descriptor)
        }

        return path
    }

    private fun findJarPath(
        descriptor: KotlinPluginDescriptor,
        kotlinVersion: String,
    ): Path? {
        val basePath = cacheDirBlocking(null)
            ?.resolve(kotlinVersion)
            ?.resolve(descriptor.getPluginGroupPath())
            ?: return null

        if (!Files.exists(basePath)) {
            return null
        }

        basePath.listDirectoryEntries("${descriptor.artifactId}-$kotlinVersion-*.jar").forEach {
            // todo match exact
            return it
        }

        return null
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
