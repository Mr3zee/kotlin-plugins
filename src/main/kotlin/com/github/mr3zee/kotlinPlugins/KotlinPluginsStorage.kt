@file:Suppress("RemoveUnnecessaryParentheses")

package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.provider.asNioPathOrNull
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.upgradeBlocking
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.collections.forEach
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes

sealed interface ArtifactStatus {
    class Success(
        val requestedVersion: String,
        val actualVersion: String,
        val criteria: KotlinPluginDescriptor.VersionMatching,
    ) : ArtifactStatus

    object InProgress : ArtifactStatus
    class FailedToLoad(val shortMessage: String) : ArtifactStatus
    object ExceptionInRuntime : ArtifactStatus

    object Disabled : ArtifactStatus
    object Skipped: ArtifactStatus
}

sealed interface ArtifactState {
    class Cached(
        val path: Path,
        val requestedVersion: String,
        val actualVersion: String,
        val criteria: KotlinPluginDescriptor.VersionMatching,
    ) : ArtifactState

    class FailedToFetch(
        val message: String,
    ) : ArtifactState

    class NotFound(
        val message: String,
    ) : ArtifactState
}

private fun ArtifactState.toStatus(): ArtifactStatus {
    return when (this) {
        is ArtifactState.Cached -> ArtifactStatus.Success(requestedVersion, actualVersion, criteria)
        is ArtifactState.FailedToFetch -> ArtifactStatus.FailedToLoad("Failed to Fetch")
        is ArtifactState.NotFound -> ArtifactStatus.FailedToLoad("Not Found")
    }
}

interface KotlinPluginStatusChangeListener {
    fun updatePlugin(pluginName: String, status: ArtifactStatus)
    fun updateArtifact(pluginName: String, mavenId: String, status: ArtifactStatus)
    fun updateVersion(pluginName: String, mavenId: String, version: String, status: ArtifactStatus)

    fun reset()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic("Kotlin Plugins Status Change", KotlinPluginStatusChangeListener::class.java)
    }
}

class KotlinPluginStatusChangeListenerWrapper(
    private val listener: KotlinPluginStatusChangeListener,
) {
    suspend fun updatePlugin(pluginName: String, status: ArtifactStatus) {
        edt { listener.updatePlugin(pluginName, status) }
    }

    suspend fun updateArtifactVersion(pluginName: String, mavenId: String, version: String, status: ArtifactStatus) {
        edt { listener.updateVersion(pluginName, mavenId, version, status) }
    }

    private suspend inline fun edt(crossinline action: () -> Unit) {
        withContext(Dispatchers.EDT) { action() }
    }
}

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
        ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<ResolvedPlugin, ArtifactState>>>()

    private val publisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginStatusChangeListener.TOPIC)
    }

    private val asyncPublisher by lazy {
        KotlinPluginStatusChangeListenerWrapper(publisher)
    }

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
                invalidateKotlinPluginCache()

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
                        logger.debug("Scheduled actualize triggered")

                        runActualization()
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

    fun runActualization() {
        logger.debug("Requested actualization")
        pluginsCache.values.forEach {
            it.forEach { (pluginName, artifacts) ->
                val plugin = project.service<KotlinPluginsSettings>().pluginByName(pluginName)
                    ?: return@forEach

                artifacts.keys.distinctBy { k -> k.libVersion }.forEach { artifact ->
                    scope.actualize(VersionedKotlinPluginDescriptor(plugin, artifact.libVersion))
                }
            }
        }
    }

    fun requestStatuses() {
        // todo
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

        val jarChanged = AtomicBoolean(false)

        var failedToLocate = false
        val nextJob = launch(context = CoroutineName("jar-fetcher-${descriptor.name}"), start = CoroutineStart.LAZY) {
            asyncPublisher.updatePlugin(descriptor.name, ArtifactStatus.InProgress)

            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

            val destination = cacheDir()?.resolve(kotlinIdeVersion)
                ?: return@launch

            logger.debug("Actualize plugins job started (${plugin.descriptor.name}), attempt: $attempt")

            val bundleResult = runCatching {
                withContext(Dispatchers.IO) {
                    KotlinPluginJarLocator.locateArtifacts(
                        versioned = plugin,
                        kotlinIdeVersion = kotlinIdeVersion,
                        dest = destination,
                    )
                }
            }

            if (bundleResult.isFailure) {
                asyncPublisher.updatePlugin(
                    pluginName = descriptor.name,
                    status = ArtifactStatus.FailedToLoad("Unexpected error"),
                )
                failedToLocate = true
                return@launch
            }

            val bundle = bundleResult.getOrThrow()

            logger.debug(
                "Actualize bundle ${plugin.descriptor.name}: " +
                    "${bundle.locatorResults.count { it.value !is LocatorResult.Cached }} not found"
            )

            failedToLocate = !bundle.allFound()

            bundle.locatorResults.entries.forEach { (id, locatorResult) ->
                val status = locatorResult.state.toStatus()

                asyncPublisher.updateArtifactVersion(
                    pluginName = descriptor.name,
                    mavenId = id.id,
                    version = locatorResult.libVersion,
                    status = status,
                )

                val artifactsMap = pluginsCache
                    .getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                    .getOrPut(descriptor.name) { ConcurrentHashMap() }

                val resolvedPlugin = ResolvedPlugin(id, locatorResult.libVersion)

                artifactsMap.compute(resolvedPlugin) { _, old ->
                    val new = locatorResult.state
                    if (new is ArtifactState.Cached && (old == null || old is ArtifactState.Cached && old.path != new.path)) {
                        jarChanged.compareAndSet(false, true)
                    }

                    new
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

                nextJob.invokeOnCompletion { cause ->
                    actualizerJobs.compute(plugin) { _, it ->
                        if (it === nextJob) null else it
                    }

                    if (cause != null || nextJob.isCancelled) {
                        return@invokeOnCompletion
                    }

                    if (failedToLocate) {
                        logger.debug("Actualize plugins job self restart (${plugin.descriptor.name})")
                        scope.actualize(plugin, attempt + 1)
                    } else if (jarChanged.get()) {
                        invalidateKotlinPluginCache()
                    }
                }

                nextJob.start()
            }
        }
    }

    fun getPluginPath(requested: RequestedKotlinPluginDescriptor): Path? {
        val kotlinVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val map = pluginsCache.getOrPut(kotlinVersion) { ConcurrentHashMap() }
        val pluginMap = map.getOrPut(requested.descriptor.name) { ConcurrentHashMap() }

        val paths = requested.descriptor.ids.map {
            val artifact = RequestedKotlinPluginDescriptor(
                descriptor = requested.descriptor,
                version = requested.version,
                artifact = it,
            )

            findArtifact(pluginMap, artifact, kotlinVersion)
        }

        logger.debug(
            "Stored versions for ${requested.version} (${requested.descriptor.name}): " +
                    "${paths.map { "${it.artifactId} -> ${it.locatedVersion}" }}"
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
        pluginMap: ConcurrentHashMap<ResolvedPlugin, ArtifactState>,
        requested: RequestedKotlinPluginDescriptor,
        kotlinVersion: String,
    ): StoredJar {
        val foundInFiles by lazy {
            findJarPath(requested, kotlinVersion)
        }

        var searchedInFiles = false

        val resolvedPlugin = ResolvedPlugin(requested.artifact, requested.version)
        val path = pluginMap.compute(resolvedPlugin) { _, old ->
            when {
                old is ArtifactState.Cached && Files.exists(old.path) -> old
                else -> {
                    searchedInFiles = true
                    val found = foundInFiles?.second ?: return@compute null

                    ArtifactState.Cached(
                        path = found,
                        requestedVersion = requested.version,
                        actualVersion = requested.version,
                        criteria = requested.descriptor.versionMatching,
                    )
                }
            }
        }

        val locatedVersion = if (searchedInFiles) {
            foundInFiles?.first
        } else {
            requested.version
        }

        return StoredJar(
            artifactId = requested.artifact.artifactId,
            locatedVersion = locatedVersion,
            path = (path as? ArtifactState.Cached)?.path,
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

    fun invalidateKotlinPluginCache() {
        if (project.isDisposed) {
            return
        }

        val provider = KotlinCompilerPluginsProvider.getInstance(project)

        if (provider is Disposable) {
            provider.dispose() // clear Kotlin plugin caches
            SwingUtilities.invokeLater {
                publisher.reset()
            }
        }

        logger.debug("Invalidated KotlinCompilerPluginsProvider")
    }

    companion object {
        const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"
    }

    private data class ResolvedPlugin(
        val mavenId: MavenId,
        val libVersion: String,
    )
}
