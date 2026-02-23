@file:Suppress("RemoveUnnecessaryParentheses")

package com.github.mr3zee.kefs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros.WORKSPACE_FILE
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.util.io.createDirectories
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.forEach
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes

internal sealed interface ArtifactStatus {
    data class Success(
        val requestedVersion: RequestedVersion,
        // intentionally the only place where resolved plays a part in equality check
        //  because and is used to update the status in UI
        val resolvedVersion: ResolvedVersion,
        val criteria: KotlinPluginDescriptor.VersionMatching,
    ) : ArtifactStatus

    object PartialSuccess : ArtifactStatus

    object InProgress : ArtifactStatus
    data class FailedToFetch(
        @NlsContexts.Label val shortMessage: String,
    ) : ArtifactStatus

    data class ExceptionInRuntime(
        val jarId: JarId,
    ) : ArtifactStatus

    object Disabled : ArtifactStatus
    object Skipped : ArtifactStatus
}

internal sealed interface ArtifactState {
    class Cached(
        val jar: Jar,
        val requestedVersion: RequestedVersion,
        val resolvedVersion: ResolvedVersion,
        val criteria: KotlinPluginDescriptor.VersionMatching,
        val origin: KotlinArtifactsRepository,
    ) : ArtifactState

    class FailedToFetch(
        val message: String,
    ) : ArtifactState

    class NotFound(
        val message: String,
    ) : ArtifactState

    object FoundButBundleIsIncomplete : ArtifactState
}

private fun ArtifactState.toStatus(): ArtifactStatus {
    return when (this) {
        is ArtifactState.Cached -> ArtifactStatus.Success(requestedVersion, resolvedVersion, criteria)
        is ArtifactState.FoundButBundleIsIncomplete -> ArtifactStatus.PartialSuccess
        is ArtifactState.FailedToFetch -> ArtifactStatus.FailedToFetch(
            KefsBundle.message("status.failedToFetch")
        )

        is ArtifactState.NotFound -> ArtifactStatus.FailedToFetch(
            KefsBundle.message("status.notFound")
        )
    }
}

internal interface KotlinPluginStatusUpdater {
    fun updatePlugin(pluginName: String, status: ArtifactStatus)
    fun updateArtifact(pluginName: String, mavenId: String, status: ArtifactStatus)
    fun updateVersion(pluginName: String, mavenId: String, requestedVersion: RequestedVersion, status: ArtifactStatus)

    fun reset()
    fun redraw()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic("Kotlin Plugins Status Change", KotlinPluginStatusUpdater::class.java)
    }
}

internal class KotlinVersionMismatch(
    val ideVersion: String,
    val jarVersion: String,
) {
    override fun toString(): String {
        return "Used version: $jarVersion, expected version: $ideVersion"
    }
}

internal interface KotlinPluginDiscoveryUpdater {
    suspend fun discoveredSync(discovery: Discovery, redrawAfterUpdate: Boolean)

    class Discovery(
        val pluginName: String,
        val mavenId: String,
        val requestedVersion: RequestedVersion,
        val resolvedVersion: ResolvedVersion,
        val origin: KotlinArtifactsRepository,
        val jar: Path,
        val checksum: String,
        val isLocal: Boolean,
        val kotlinVersionMismatch: KotlinVersionMismatch?,
    ) {
        val jarId = JarId(pluginName, mavenId, requestedVersion, resolvedVersion)
    }
}

internal suspend fun KotlinPluginDiscoveryUpdater.discoveredSync(
    pluginName: String,
    mavenId: String,
    requestedVersion: RequestedVersion,
    resolvedVersion: ResolvedVersion,
    origin: KotlinArtifactsRepository,
    jar: Path,
    checksum: String,
    isLocal: Boolean,
    kotlinVersionMismatch: KotlinVersionMismatch?,
) {
    discoveredSync(
        KotlinPluginDiscoveryUpdater.Discovery(
            pluginName = pluginName,
            mavenId = mavenId,
            requestedVersion = requestedVersion,
            resolvedVersion = resolvedVersion,
            origin = origin,
            jar = jar,
            checksum = checksum,
            isLocal = isLocal,
            kotlinVersionMismatch = kotlinVersionMismatch,
        ),
        redrawAfterUpdate = true,
    )
}

internal class KefsStorageState : BaseState() {
    var autoUpdate by property(true)
    var updateInterval by property(20)
    var extendedInvalidationDebounce by property(false)
}

@Service(Service.Level.PROJECT)
@State(
    name = "com.github.mr3zee.kotlinPlugins.KotlinPluginsStorageState",
    storages = [Storage(WORKSPACE_FILE)],
)
internal class KefsStorage(
    private val project: Project,
    parentScope: CoroutineScope,
) : SimplePersistentStateComponent<KefsStorageState>(KefsStorageState()) {
    fun updateState(autoUpdate: Boolean, updateInterval: Int) {
        val oldAutoUpdate = state.autoUpdate
        val oldUpdateInterval = state.updateInterval

        state.autoUpdate = autoUpdate

        if (updateInterval > 0) {
            state.updateInterval = updateInterval
        } else {
            state.updateInterval = 20
        }

        if (oldAutoUpdate != autoUpdate || oldUpdateInterval != updateInterval) {
            resetAutoupdates()
        }
    }

    private val resolvedCacheDir = AtomicBoolean(false)
    private var _cacheDir: CompletableDeferred<Path?> = CompletableDeferred()
    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext.job)

    internal val fileWatcher = KefsFileWatcher(object : FileWatcherCallback {
        override fun onLocalRepoChange(repoRoot: Path) {
            handleLocalRepoChange(repoRoot)
        }

        override fun onCacheDirExternalChange() {
            clearState()
        }
    })

    @Suppress("UnstableApiUsage")
    suspend fun cacheDir(): Path? {
        return resolveCacheDir {
            vsApi { project.getEelDescriptor().toEelApiVs() }
        }
    }

    private val actualizerLock = Mutex()
    private val actualizerJobs = ConcurrentHashMap<VersionedKotlinPluginDescriptor, Job>()

    private val indexLock = Mutex()
    private val indexJobs = ConcurrentHashMap<VersionedKotlinPluginDescriptor, Job>()

    private val logger by lazy { thisLogger() }

    private val pluginsCache = ConcurrentHashMap<String, ConcurrentHashMap<RequestedPluginKey, ArtifactState>>()

    private val lifecycleCache = ConcurrentHashMap<String, Map<String, Path>>()

    private val lastProvideCallTimestamp = AtomicLong(0)
    private val providerCallInProgress = AtomicLong(0L)
    private val invalidationJob = AtomicReference<Job?>(null)

    companion object {
        const val KEFS_STORAGE_DIRECTORY = ".kefs"

        private const val INVALIDATION_DEBOUNCE_BASE_MS = 750L
        private const val INVALIDATION_DEBOUNCE_EXTENDED_MS = 2000L
    }

    private val invalidationDebounceMs: Long
        get() = if (state.extendedInvalidationDebounce) INVALIDATION_DEBOUNCE_EXTENDED_MS else INVALIDATION_DEBOUNCE_BASE_MS

    private val statusPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC)
    }

    private val discoveryPublisher: KotlinPluginDiscoveryUpdater
        get() {
            return project.service<KefsExceptionReporter>()
        }

    private val fileWatcherThread by lazy {
        Executors.newSingleThreadExecutor()
    }

    private suspend inline fun internalClearStateNoInvalidate(and: () -> Unit = {}) {
        try {
            while (true) {
                actualizerJobs.values.forEach { it.cancelAndJoin() }
                if (actualizerLock.tryLock()) {
                    break
                }
            }

            while (true) {
                indexJobs.values.forEach { it.cancelAndJoin() }
                if (indexLock.tryLock()) {
                    break
                }
            }

            actualizerJobs.values.forEach { it.cancelAndJoin() }
            actualizerJobs.clear()
            indexJobs.values.forEach { it.cancelAndJoin() }
            indexJobs.clear()

            pluginsCache.clear()
            lifecycleCache.clear()

            fileWatcher.cancelAllWatchKeys()

            and()
        } finally {
            actualizerLock.unlock()
            indexLock.unlock()
        }
    }

    fun clearState() {
        scope.launch(CoroutineName("clear-state")) {
            logger.debug("Clearing state")

            internalClearStateNoInvalidate {
                reregisterWatchers()
                invalidateKotlinPluginCache()
            }
        }
    }

    fun clearCaches() {
        scope.launch(CoroutineName("clear-caches")) {
            logger.debug("Clearing caches")

            internalClearStateNoInvalidate {
                cacheDir()?.let {
                    val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

                    @OptIn(ExperimentalPathApi::class)
                    runCatchingExceptCancellation {
                        withContext(Dispatchers.IO) {
                            it.resolve(kotlinIdeVersion).deleteRecursively()
                        }
                    }
                }

                reregisterWatchers()
                invalidateKotlinPluginCache()
            }
        }
    }

    private val autoUpdateJob = AtomicReference<Job?>(null)
    private fun resetAutoupdates() {
        autoUpdateJob.getAndSet(null)?.cancel()

        if (!state.autoUpdate) {
            return
        }

        val newJob = scope.launch(CoroutineName("actualizer-root"), start = CoroutineStart.LAZY) {
            supervisorScope {
                launch(CoroutineName("actualizer-loop")) {
                    while (isActive) {
                        delay(state.updateInterval.minutes)
                        logger.debug("Scheduled actualize triggered")

                        runActualization()
                    }
                }
            }
        }

        if (!autoUpdateJob.compareAndSet(null, newJob)) {
            newJob.cancel()
        } else {
            newJob.start()
        }
    }

    init {
        resetAutoupdates()

        val fileWatcherDispatcher = fileWatcherThread.asCoroutineDispatcher()

        scope.launch(fileWatcherDispatcher + CoroutineName("file-watcher")) {
            try {
                reregisterWatchers()
                while (true) {
                    try {
                        fileWatcher.processOneEvent()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("File watcher loop failed", e)
                        throw e
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }

        scope.coroutineContext.job.invokeOnCompletion {
            pluginsCache.clear()
            lifecycleCache.clear()
            actualizerJobs.clear()
            indexJobs.clear()
            fileWatcher.close()
            fileWatcherThread.shutdown()
            invalidationJob.get()?.cancel()
            logger.debug("Storage closed")
        }

        project.service<KefsExceptionAnalyzerService>().start()
    }

    private suspend fun reregisterWatchers() {
        val settings = project.service<KefsSettings>().safeState()
        val localRepoPaths = settings.repositories
            .filter { it.type == KotlinArtifactsRepository.Type.PATH }
            .mapNotNull { runCatching { Path(it.value).toAbsolutePath().normalize() }.getOrNull() }
            .distinct()

        for (repoPath in localRepoPaths) {
            fileWatcher.registerLocalRepo(repoPath)
        }

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
        val versionCacheDir = cacheDir()?.resolve(kotlinIdeVersion) ?: return

        withContext(Dispatchers.IO) {
            versionCacheDir.createDirectories()
        }

        fileWatcher.registerCacheDir(versionCacheDir)
    }

    private fun handleLocalRepoChange(repoRoot: Path) {
        logger.debug("File watcher detected changes in local repo: $repoRoot")

        val settings = project.service<KefsSettings>().safeState()
        val matchingRepoNames = settings.repositories
            .filter { it.type == KotlinArtifactsRepository.Type.PATH }
            .filter { runCatching { Path(it.value).toAbsolutePath().normalize() }.getOrNull() == repoRoot }
            .map { it.name }
            .toSet()

        if (matchingRepoNames.isNotEmpty()) {
            val pluginsToActualize = settings.plugins.filter { plugin ->
                plugin.enabled && plugin.repositories.any { it.name in matchingRepoNames }
            }

            for (plugin in pluginsToActualize) {
                val cached = pluginsCache[plugin.name] ?: continue
                actualizePlugin(plugin, cached)
            }
        }
    }

    fun runActualization() {
        logger.debug("Requested actualization")
        pluginsCache.forEach { (pluginName, artifacts) ->
            val plugin = project.service<KefsSettings>().pluginByName(pluginName)
                ?: return@forEach

            actualizePlugin(plugin, artifacts)
        }
    }

    fun requestStatuses() {
        logger.debug("Requested statuses")

        val reporter = project.service<KefsExceptionReporter>()
        forEachState { pluginName, mavenId, requestedVersion, state ->
            val status =
                if (state is ArtifactState.Cached && reporter.hasExceptions(pluginName, mavenId, requestedVersion)) {
                    ArtifactStatus.ExceptionInRuntime(
                        jarId = JarId(pluginName, mavenId, requestedVersion, state.resolvedVersion)
                    )
                } else {
                    state.toStatus()
                }

            statusPublisher.updateVersion(
                pluginName = pluginName,
                mavenId = mavenId,
                requestedVersion = requestedVersion,
                status = status,
            )
        }
    }

    fun requestDiscovery(): List<KotlinPluginDiscoveryUpdater.Discovery> {
        logger.debug("Requested discovery")

        return forEachState { pluginName, mavenId, _, state ->
            if (state is ArtifactState.Cached) {
                KotlinPluginDiscoveryUpdater.Discovery(
                    pluginName = pluginName,
                    mavenId = mavenId,
                    requestedVersion = state.requestedVersion,
                    resolvedVersion = state.resolvedVersion,
                    origin = state.origin,
                    jar = state.jar.path,
                    checksum = state.jar.checksum,
                    isLocal = state.jar.isLocal,
                    kotlinVersionMismatch = state.jar.kotlinVersionMismatch,
                )
            } else {
                null
            }
        }.filterNotNull()
    }

    suspend fun getLocationFor(partial: PartialJarId): Path? {
        if (partial.pluginName == null) {
            return null
        }

        if (partial.mavenId != null && partial.requested != null) {
            val pluginKey = RequestedPluginKey(partial.mavenId, partial.requested)
            return (pluginsCache[partial.pluginName]?.get(pluginKey) as? ArtifactState.Cached)?.jar?.path
        }

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
        return cacheDir()
            ?.resolve(kotlinIdeVersion)
            ?.resolve(partial.pluginName)
            ?.let { if (partial.requested != null) it.resolve(partial.requested.value) else it }
    }

    fun getFailedToFetchMessageFor(pluginName: String, mavenId: String, requestedVersion: RequestedVersion): String? {
        val resolved = RequestedPluginKey(mavenId, requestedVersion)

        return when (val state = pluginsCache[pluginName]?.get(resolved)) {
            is ArtifactState.FailedToFetch -> state.message
            is ArtifactState.NotFound -> state.message
            else -> null
        }
    }

    private fun <T> forEachState(doSomething: (String, String, RequestedVersion, ArtifactState) -> T): List<T> {
        val result = mutableListOf<T>()
        pluginsCache.entries.forEach { (pluginName, artifacts) ->
            artifacts.forEach { (artifact, state) ->
                val item = doSomething(pluginName, artifact.mavenId, artifact.requestedVersion, state)
                result.add(item)
            }
        }
        return result
    }

    private fun actualizePlugin(
        plugin: KotlinPluginDescriptor,
        artifacts: Map<RequestedPluginKey, ArtifactState>,
    ) {
        artifacts.keys.distinctBy { it.requestedVersion }.forEach { key ->
            scope.actualize(VersionedKotlinPluginDescriptor(plugin, key.requestedVersion))
        }
    }

    private fun CoroutineScope.actualize(plugin: VersionedKotlinPluginDescriptor) {
        val descriptor = plugin.descriptor
        val currentJob = actualizerJobs[plugin]
        if (currentJob != null && currentJob.isActive) {
            logger.debug("Actualize plugins job is already running (${plugin.descriptor.name}, ${plugin.requestedVersion})")
            return
        }

        val anyJarChanged = AtomicBoolean(false)

        val nextJob = launch(context = CoroutineName("jar-fetcher-${descriptor.name}"), start = CoroutineStart.LAZY) {
            statusPublisher.updatePlugin(descriptor.name, ArtifactStatus.InProgress)

            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

            val destination = cacheDirectory(
                kotlinIdeVersion = kotlinIdeVersion,
                pluginName = descriptor.name,
                requestedVersion = plugin.requestedVersion,
            ) ?: run {
                statusPublisher.updatePlugin(descriptor.name, ArtifactStatus.FailedToFetch("Internal error"))
                logger.error("Failed to find cache directory for ${descriptor.name} ${plugin.requestedVersion}")
                return@launch
            }

            val artifactsMap = pluginsCache
                .getOrPut(descriptor.name) { ConcurrentHashMap() }

            logger.debug("Actualize plugins job started (${plugin.descriptor.name}, ${plugin.requestedVersion})")

            val known = artifactsMap.entries
                .filter { (key, value) ->
                    plugin.descriptor.hasArtifact(key.mavenId) && key.requestedVersion == plugin.requestedVersion && value is ArtifactState.Cached
                }.associate { (k, v) ->
                    k.mavenId to (v as ArtifactState.Cached).jar
                }

            fileWatcher.markSelfUpdateStart()
            val bundleResult = runCatchingExceptCancellation(
                onCancellation = {
                    fileWatcher.markSelfUpdateEnd()
                    statusPublisher.updatePlugin(
                        pluginName = descriptor.name,
                        status = ArtifactStatus.FailedToFetch("Job was cancelled"),
                    )
                }
            ) {
                try {
                    KefsJarLocator.locateArtifacts(
                        versioned = plugin,
                        kotlinIdeVersion = kotlinIdeVersion,
                        dest = destination,
                        known = known,
                    )
                } finally {
                    fileWatcher.markSelfUpdateEnd()
                }
            }

            if (bundleResult.isFailure) {
                statusPublisher.updatePlugin(
                    pluginName = descriptor.name,
                    status = ArtifactStatus.FailedToFetch("Unexpected error"),
                )
                logger.error("Actualize plugins job failed (${plugin.descriptor.name})", bundleResult.exceptionOrNull())
                return@launch
            }

            val bundle = bundleResult.getOrThrow()

            logger.debug(
                """
                    |Actualize bundle (${plugin.descriptor.name}, ${plugin.requestedVersion}):
                    ${bundle.locatorResults.entries.joinToString("\n") { "| - ${it.key.id}: ${it.value.logStatus()}" }}
                """.trimMargin()
            )

            bundle.locatorResults.entries.forEach { (id, locatorResult) ->
                val resolvedKey = RequestedPluginKey(id.id, plugin.requestedVersion)

                var resolvedIsNew = false
                artifactsMap.compute(resolvedKey) { _, old ->
                    val oldChecksum = (old as? ArtifactState.Cached)?.jar?.checksum
                    if (locatorResult is LocatorResult.Cached && locatorResult.jar.checksum != oldChecksum) {
                        resolvedIsNew = true
                        anyJarChanged.compareAndSet(false, true)
                    }

                    locatorResult.state
                }

                if (resolvedIsNew) {
                    locatorResult as LocatorResult.Cached

                    discoveryPublisher.discoveredSync(
                        pluginName = descriptor.name,
                        mavenId = id.id,
                        requestedVersion = locatorResult.state.requestedVersion,
                        resolvedVersion = locatorResult.state.resolvedVersion,
                        origin = locatorResult.state.origin,
                        jar = locatorResult.jar.path,
                        checksum = locatorResult.jar.checksum,
                        isLocal = locatorResult.jar.isLocal,
                        kotlinVersionMismatch = locatorResult.jar.kotlinVersionMismatch,
                    )
                }

                val reporter = project.service<KefsExceptionReporter>()

                val hasExceptions = !resolvedIsNew && locatorResult is LocatorResult.Cached &&
                        reporter.hasExceptions(plugin.descriptor.name, id.id, plugin.requestedVersion)

                val status = if (hasExceptions) {
                    ArtifactStatus.ExceptionInRuntime(
                        jarId = JarId(
                            pluginName = plugin.descriptor.name,
                            mavenId = id.id,
                            requestedVersion = plugin.requestedVersion,
                            resolvedVersion = locatorResult.state.resolvedVersion,
                        )
                    )
                } else {
                    locatorResult.state.toStatus()
                }

                statusPublisher.updateVersion(
                    pluginName = descriptor.name,
                    mavenId = id.id,
                    requestedVersion = plugin.requestedVersion,
                    status = status,
                )
            }

            statusPublisher.redraw()
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

                    if (anyJarChanged.get()) {
                        invalidateKotlinPluginCache()
                    }
                }

                nextJob.start()
            }
        }
    }

    fun getPluginPath(requested: RequestedKotlinPluginDescriptor): Path? {
        // If invalidation is pending, return null
        if (invalidationJob.get() != null) {
            logger.debug("Invalidation pending ${requested.descriptor.name}:${requested.artifact.id} (${requested.requestedVersion})")
            return null
        }

        // Check lifecycle cache first - returns cached value if lifecycle is active
        lifecycleCache[requested.descriptor.name]?.let { cachedResult ->
            val cachedPath = cachedResult.getValue(requested.artifact.id)
            logger.debug("Lifecycle cached value found for ${requested.descriptor.name}:${requested.artifact.id} (${requested.requestedVersion})")
            return cachedPath
        }

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val pluginMap = pluginsCache.getOrPut(requested.descriptor.name) {
            ConcurrentHashMap()
        }

        val paths = requested.descriptor.ids.map {
            it.id to pluginMap[RequestedPluginKey(it.id, requested.requestedVersion)] as? ArtifactState.Cached
        }

        logger.debug(
            "Cached versions for ${requested.requestedVersion} (${requested.descriptor.name}): " +
                    "${
                        paths.filter { (_, cached) -> cached != null }
                            .map { (id, cached) -> "$id -> ${cached?.resolvedVersion}" }
                    }"
        )

        val allExist = paths.all { (_, cached) -> cached?.jar?.path?.exists() == true }
        val differentVersions = paths.distinctBy { (_, cached) -> cached?.resolvedVersion }.count()

        // return not null only when all requested plugins are present and have the same version
        if (!allExist || differentVersions != 1) {
            logger.debug("Requested plugins not found in full (${requested.descriptor.name}, ${requested.requestedVersion})")
            // no requested version is present for all requested plugins, or versions are not equal
            updateCacheFromDisk(requested, pluginMap, kotlinIdeVersion)
            return null
        }

        lifecycleCache[requested.descriptor.name] = paths
            .associate { it }
            .mapValues { (_, state) ->
                state!!.jar.path // non-null by allExist guaranteed
            }

        logger.debug("All ${paths.size} version(s) for ${requested.requestedVersion} (${requested.descriptor.name}) are present")

        val state = paths.find { (id, _) -> id == requested.artifact.id }?.second
            ?: error("Should not happen")

        val reporter = project.service<KefsExceptionReporter>()

        val hasExceptions = reporter.hasExceptions(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            requestedVersion = requested.requestedVersion,
        )

        val status = if (hasExceptions) {
            ArtifactStatus.ExceptionInRuntime(
                jarId = JarId(
                    pluginName = requested.descriptor.name,
                    mavenId = requested.artifact.id,
                    requestedVersion = requested.requestedVersion,
                    resolvedVersion = state.resolvedVersion,
                )
            )
        } else {
            state.toStatus()
        }

        statusPublisher.updateVersion(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            requestedVersion = requested.requestedVersion,
            status = status,
        )

        return state.jar.path
    }

    private fun updateCacheFromDisk(
        requested: RequestedKotlinPluginDescriptor,
        pluginMap: ConcurrentHashMap<RequestedPluginKey, ArtifactState>,
        kotlinIdeVersion: String,
    ) {
        if (indexJobs[requested]?.isActive == true) {
            logger.debug("Update cache from disk job is already running for ${requested.descriptor.name} (${requested.requestedVersion})")
            return
        }

        val nextJob = scope.launch(
            context = CoroutineName(
                "update-cache-from-disk-${requested.descriptor.name}-${requested.requestedVersion}"
            ),
            start = CoroutineStart.LAZY,
        ) {
            logger.debug("Updating cache from disk for ${requested.descriptor.name} (${requested.requestedVersion})")

            val paths = requested.descriptor.ids.map {
                val artifact = RequestedKotlinPluginDescriptor(
                    descriptor = requested.descriptor,
                    requestedVersion = requested.requestedVersion,
                    artifact = it,
                )

                findArtifactAndUpdateMap(
                    pluginMap = pluginMap,
                    requested = artifact,
                    kotlinIdeVersion = kotlinIdeVersion,
                )
            }

            logger.debug(
                "On disk versions for ${requested.descriptor.name}:${requested.requestedVersion}: " +
                        "${paths.map { "${it.mavenId} -> ${it.jar?.path?.name}" }}"
            )

            val distinct = paths.distinctBy { it.resolvedVersion }
            if (paths.any { it.jar == null || it.resolvedVersion == null } || distinct.size != 1) {
                logger.debug("Some versions are missing for ${requested.descriptor.name} (${requested.requestedVersion})")
                scope.actualize(requested)

                return@launch
            }

            invalidateKotlinPluginCache()

            logger.debug("Cache from disk update finished for ${requested.descriptor.name} (${requested.requestedVersion})")
        }

        scope.launch(CoroutineName("index-plugins-job-starter-${requested.descriptor.name}-${requested.requestedVersion}")) {
            indexLock.withLock {
                val running = indexJobs.computeIfAbsent(requested) { nextJob }
                if (running != nextJob) {
                    nextJob.cancel()
                    return@withLock
                }

                nextJob.invokeOnCompletion {
                    indexJobs.compute(requested) { _, it ->
                        if (it === nextJob) null else it
                    }
                }

                nextJob.start()
            }
        }
    }

    private suspend fun findArtifactAndUpdateMap(
        pluginMap: ConcurrentHashMap<RequestedPluginKey, ArtifactState>,
        requested: RequestedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
    ): StoredJar = withContext(Dispatchers.IO) {
        val requestedPlugin = RequestedPluginKey(requested.artifact.id, requested.requestedVersion)
        val state = pluginMap.compute(requestedPlugin) { _, old ->
            when {
                old is ArtifactState.Cached && Files.exists(old.jar.path) -> old
                else -> null
            }
        }

        if (state != null) {
            val cached = (state as ArtifactState.Cached)
            return@withContext StoredJar(
                mavenId = requested.artifact.id,
                resolvedVersion = cached.resolvedVersion,
                jar = cached.jar,
            )
        }

        val basePath = cacheDirectory(
            kotlinIdeVersion = kotlinIdeVersion,
            pluginName = requested.descriptor.name,
            requestedVersion = requested.requestedVersion,
        ) ?: return@withContext StoredJar(requested.artifact.id, null, null)

        val scanResult = runCatchingExceptCancellation {
            KefsDiskScanner.findMatchingJar(
                basePath = basePath,
                artifact = requested.artifact,
                kotlinIdeVersion = kotlinIdeVersion,
                replacement = requested.descriptor.replacement,
                matchFilter = requested.asMatchFilter(),
            )
        }.getOrNull() ?: return@withContext StoredJar(requested.artifact.id, null, null)

        val (resolvedVersion, resolvedKotlinVersion, found) = scanResult

        val validated = KefsDiskScanner.validateCachedJar(
            jarPath = found,
            kotlinIdeVersion = kotlinIdeVersion,
            resolvedKotlinVersion = resolvedKotlinVersion,
            resolvedVersion = resolvedVersion,
            repositories = requested.descriptor.repositories,
        ) ?: return@withContext StoredJar(requested.artifact.id, null, null)

        val newState = ArtifactState.Cached(
            jar = validated.jar,
            requestedVersion = requested.requestedVersion,
            resolvedVersion = resolvedVersion,
            criteria = requested.descriptor.versionMatching,
            origin = validated.origin,
        )

        pluginMap[requestedPlugin] = newState

        discoveryPublisher.discoveredSync(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            resolvedVersion = resolvedVersion,
            requestedVersion = requested.requestedVersion,
            origin = validated.origin,
            jar = validated.jar.path,
            checksum = validated.jar.checksum,
            isLocal = validated.jar.isLocal,
            kotlinVersionMismatch = validated.jar.kotlinVersionMismatch,
        )

        StoredJar(
            mavenId = requested.artifact.id,
            resolvedVersion = resolvedVersion,
            jar = validated.jar,
        )
    }

    @Suppress("UnstableApiUsage")
    private suspend inline fun resolveCacheDir(getApi: () -> EelApi): Path? {
        if (!resolvedCacheDir.compareAndSet(false, true)) {
            return _cacheDir.await()
        }

        val userHome = getApi().fs.user.home.asNioPath()
        val cacheDir = userHome.resolve(KEFS_STORAGE_DIRECTORY).toAbsolutePath()
        _cacheDir.complete(cacheDir)

        return cacheDir
    }

    fun recordProviderCallStart() {
        providerCallInProgress.getAndIncrement()
    }

    fun recordProviderCallEnd() {
        lastProvideCallTimestamp.set(System.currentTimeMillis())
        providerCallInProgress.getAndDecrement()
    }

    fun invalidateKotlinPluginCache() {
        if (project.isDisposed) {
            return
        }

        // TODO: this is a workaround for KTIJ-37664 for IDE versions before 261
        //  remove when we drop support for them
        val newJob = scope.launch(
            context = CoroutineName("provider-debounce"),
            start = CoroutineStart.LAZY,
        ) {
            while (true) {
                if (providerCallInProgress.get() > 0) {
                    delay(invalidationDebounceMs)
                    continue
                }

                val timeSinceLastCall = System.currentTimeMillis() - lastProvideCallTimestamp.get()
                if (timeSinceLastCall >= invalidationDebounceMs) {
                    break
                }

                delay(invalidationDebounceMs - timeSinceLastCall)
            }

            doInvalidate()
        }.apply {
            invokeOnCompletion {
                invalidationJob.set(null)
            }
        }

        // Mark invalidation as pending, otherwise leave
        if (invalidationJob.compareAndSet(null, newJob)) {
            // Clear the lifecycle immediately
            lifecycleCache.clear()
            statusPublisher.reset()

            newJob.start()
            logger.debug("Invalidation job started")
        } else {
            logger.debug("Invalidation already pending")
        }
    }

    @OptIn(KaPlatformInterface::class)
    private fun doInvalidate() {
        val provider = KotlinCompilerPluginsProvider.getInstance(project)

        // clear Kotlin plugin caches
        if (provider is Disposable) {
            provider.dispose()
            logger.debug("Invalidated KotlinCompilerPluginsProvider after debounce")
        } else {
            logger.warn("Failed to invalidate the KotlinCompilerPluginsProvider")
        }
    }

    suspend fun reportsDir(): Path? {
        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
        return cacheDir()
            ?.resolve(kotlinIdeVersion)
            ?.resolve("reports")
            ?.apply {
                withContext(Dispatchers.IO) {
                    createDirectories()
                }
            }
    }

    private suspend fun cacheDirectory(
        kotlinIdeVersion: String,
        pluginName: String,
        requestedVersion: RequestedVersion,
    ): Path? {
        return cacheDir()
            ?.resolve(kotlinIdeVersion)
            ?.resolve(pluginName)
            ?.resolve(requestedVersion.value)
            ?.apply {
                withContext(Dispatchers.IO) {
                    createDirectories()
                }
            }
    }
}

internal inline fun <T> runCatchingExceptCancellation(
    onCancellation: (Throwable) -> Unit = {},
    block: () -> T,
): Result<T> {
    return runCatching(block).also {
        if (it.isFailure) {
            val exception = it.exceptionOrNull() ?: return@also
            onCancellation(exception)
            if (exception is CancellationException) {
                throw exception
            }
        }
    }
}
