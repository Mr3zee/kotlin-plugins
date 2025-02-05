package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.provider.getEelApi
import com.intellij.platform.eel.toNioPath
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path

const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"

@Service
@State(
    name = "KotlinPluginsStorage",
    storages = [
        Storage(
            "${StoragePathMacros.CACHE_FILE}/kotlin-plugins-storage.xml",
            roamingType = RoamingType.DISABLED,
        ),
    ]
)
class KotlinPluginsStorageService(
    private val scope: CoroutineScope,
) : SimplePersistentStateComponent<KotlinPluginsStorage>(KotlinPluginsStorage()) {
    private var resolvedCacheDir = false
    private var _cacheDir: Path? = null

    @Suppress("UnstableApiUsage")
    suspend fun cacheDir(project: Project?): Path? {
        if (resolvedCacheDir) {
            return _cacheDir
        }

        val eel = project?.getEelApi()

        val userHome = if (eel == null) {
            when {
                SystemInfo.isWindows ->  System.getenv("USERPROFILE")
                else -> System.getenv("HOME")
            }?.let(Path::of)
        } else {
            eel.fs.userHome()?.toNioPath(eel) ?: Path("/") // user is nobody
        }

        _cacheDir = userHome?.resolve(KOTLIN_PLUGINS_STORAGE_DIRECTORY)?.toAbsolutePath()
        resolvedCacheDir = true

        return _cacheDir
    }

    private val logger by lazy { thisLogger() }

    private val cacheMisses = ConcurrentHashMap<KotlinPluginDescriptor, Boolean>()

    private val runningActualizeJob = AtomicReference<Job?>(null)
    private val pluginPathsLock = Mutex()

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

                        pluginPathsLock.withLock {
                            val map = state.pluginPaths.getOrPut(kotlinIdeVersion) { emptyMap() }
                            state.pluginPaths[kotlinIdeVersion] = map + (plugin.id to jarPath.toString())
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

    fun clearCacheMisses() {
        cacheMisses.clear()
    }

    fun getPluginPath(project: Project?, descriptor: KotlinPluginDescriptor): Path? {
        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        return state.pluginPaths[kotlinVersion]?.get(descriptor.id)?.let(Path::of).also { result ->
            if (result == null) {
                // cache miss, but no other version is present
                cacheMisses[descriptor] = false
                actualizePlugins(project)
            } else {
                cacheMisses.remove(descriptor)
            }
        }
    }
}

internal fun invalidateKotlinPluginsCache(project: Project) {
    val provider = KotlinCompilerPluginsProvider.getInstance(project)

    if (provider is Disposable) {
        provider.dispose() // clear Kotlin plugin caches
    }

    service<KotlinPluginsStorageService>().clearCacheMisses()
}

internal fun KotlinPluginDescriptor.getPluginGroupPath(): Path {
    val group = groupId.split(".")
    return Path.of(group[0], *group.drop(1).toTypedArray())
}

class KotlinPluginsStorage : BaseState() {
    /**
     * <Kotlin IDE Version> to <plugin id> to <plugin jar path>
     * ```
     * {
     *    "2.1.0-dev-8741": {
     *        "org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-cli" : "/path/to/plugin.jar"
     *     }
     * }
     * ```
     */
    val pluginPaths by map<String, Map<String, String>>()
}
