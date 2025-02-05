package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.Delegates

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
    private var cacheFolder: Path by Delegates.notNull()
    private val compatible: Boolean = SystemInfo.isLinux || SystemInfo.isWindows || SystemInfo.isMac
    private val logger by lazy { thisLogger() }

    init {
        val folderName = ".kotlinPlugins"
        when {
            SystemInfo.isLinux -> System.getenv("XDG_CACHE_HOME") ?: System.getenv("HOME")?.plus("/.cache") ?: ".cache"
            SystemInfo.isWindows ->  System.getenv("LOCALAPPDATA") ?: "${System.getenv("USERPROFILE") ?: ""}/AppData/Local"
            SystemInfo.isMac -> (System.getenv("HOME")?.plus("/") ?: "") + "Library/Caches/"
            else -> null
        }?.let(Path::of)?.resolve(folderName)?.toAbsolutePath()?.let {
            cacheFolder = it
        }
    }

    private val cacheMisses = ConcurrentHashMap<KotlinPluginDescriptor, Boolean>()

//    fun updateProjectAwareState(project: Project) {
//        if (!compatible || !cacheMisses.values.any()) {
//            return
//        }
//
//        val projectAware = KotlinPluginsProjectAware.getInstance(project)
//
//        ExternalSystemProjectNotificationAware
//            .getInstance(project)
//            .notificationNotify(projectAware)
//    }

    private val runningActualizeJob = AtomicReference<Job?>(null)
    private val pluginPathsLock = Mutex()

    fun actualizePlugins() {
        if (!compatible) {
            return
        }

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
                    launch(CoroutineName("jar-fetcher-${plugin.groupId}-${plugin.artifactId}")) {
                        val destination = cacheFolder
                            .resolve(kotlinIdeVersion)
                            .resolve(plugin.getPluginGroupPath())

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

                        val old = pluginPathsLock.withLock {
                            state.pluginPaths.getOrPut(kotlinIdeVersion) { emptyMap() }.also {
                                state.pluginPaths[kotlinIdeVersion] = it + (plugin.id to jarPath.toString())
                            }
                        }

                        val oldJar = old[plugin.id]?.let(Path::of)
                        if (oldJar != null && File(oldJar.toUri()).exists()) {
                            logger.info("Requested deletion of the old plugin jar: $oldJar")
                            deletePlugin(oldJar)
                        }
                    }
                }
            }
        }

        if (runningActualizeJob.compareAndSet(currentJob, nextJob)) {
            nextJob.invokeOnCompletion {
                runningActualizeJob.compareAndSet(nextJob, null)

                if (cacheMisses.any { !it.value }) {
                    actualizePlugins()
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

    fun getPluginPath(descriptor: KotlinPluginDescriptor): Path? {
        if (!compatible) {
            return null
        }

        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        return state.pluginPaths[kotlinVersion]?.get(descriptor.id)?.let(Path::of).also { result ->
            if (result == null) {
                // cache miss, but no other version is present
                cacheMisses[descriptor] = false
                actualizePlugins()
            } else {
                cacheMisses.remove(descriptor)
            }
        }
    }

    private fun deletePlugin(path: Path) {
        scope.launch(CoroutineName("jar-deleter-${path.fileName}")) {
            withContext(Dispatchers.IO) {
                if (path.toFile().exists()) {
                    path.toFile().delete()
                }
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
