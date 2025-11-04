@file:Suppress("RemoveUnnecessaryParentheses")

package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.provider.asNioPathOrNull
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.upgradeBlocking
import com.intellij.util.io.createDirectories
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.Watchable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.forEach
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes

internal sealed interface ArtifactStatus {
    class Success(
        val requestedVersion: String,
        val actualVersion: String,
        val criteria: KotlinPluginDescriptor.VersionMatching,
    ) : ArtifactStatus

    object PartialSuccess : ArtifactStatus

    object InProgress : ArtifactStatus
    class FailedToLoad(@NlsContexts.Label val shortMessage: String) : ArtifactStatus
    object ExceptionInRuntime : ArtifactStatus

    object Disabled : ArtifactStatus
    object Skipped : ArtifactStatus
}

internal sealed interface ArtifactState {
    class Cached(
        val jar: Jar,
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

    object FoundButBundleIsIncomplete : ArtifactState
}

private fun ArtifactState.toStatus(): ArtifactStatus {
    return when (this) {
        is ArtifactState.Cached -> ArtifactStatus.Success(requestedVersion, actualVersion, criteria)
        is ArtifactState.FoundButBundleIsIncomplete -> ArtifactStatus.PartialSuccess
        is ArtifactState.FailedToFetch -> ArtifactStatus.FailedToLoad(
            KotlinPluginsBundle.message("status.failedToFetch")
        )
        is ArtifactState.NotFound -> ArtifactStatus.FailedToLoad(
            KotlinPluginsBundle.message("status.notFound")
        )
    }
}

internal interface KotlinPluginStatusUpdater {
    fun updatePlugin(pluginName: String, status: ArtifactStatus)
    fun updateArtifact(pluginName: String, mavenId: String, status: ArtifactStatus)
    fun updateVersion(pluginName: String, mavenId: String, version: String, status: ArtifactStatus)

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
)

internal interface KotlinPluginDiscoveryUpdater {
    suspend fun discoveredSync(discovery: Discovery, redrawAfterUpdate: Boolean)

    class Discovery(
        val pluginName: String,
        val mavenId: String,
        val version: String,
        val jar: Path,
        val checksum: String,
        val isLocal: Boolean,
        val kotlinVersionMismatch: KotlinVersionMismatch?,
    )

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic("Kotlin Plugins Discovery", KotlinPluginDiscoveryUpdater::class.java)
    }
}

internal suspend fun KotlinPluginDiscoveryUpdater.discoveredSync(
    pluginName: String,
    mavenId: String,
    version: String,
    jar: Path,
    checksum: String,
    isLocal: Boolean,
    kotlinVersionMismatch: KotlinVersionMismatch?,
) {
    discoveredSync(
        KotlinPluginDiscoveryUpdater.Discovery(
            pluginName = pluginName,
            mavenId = mavenId,
            version = version,
            jar = jar,
            checksum = checksum,
            isLocal = isLocal,
            kotlinVersionMismatch = kotlinVersionMismatch,
        ),
        redrawAfterUpdate = true,
    )
}

internal data class VersionedPluginKey(
    val pluginName: String,
    val version: String,
)

@Service(Service.Level.PROJECT)
internal class KotlinPluginsStorage(
    private val project: Project,
    parentScope: CoroutineScope,
) {
    private val resolvedCacheDir = AtomicBoolean(false)
    private var _cacheDir: Path? = null
    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext.job)

    private val watchService = FileSystems.getDefault().newWatchService()

    private val pluginWatchKeys = ConcurrentHashMap<VersionedPluginKey, WatchKey>()
    private val pluginWatchKeysReverse = ConcurrentHashMap<WatchKey, VersionedPluginKey>()

    private class WatchKeyWithChecksum(
        val key: WatchKey,
        val checksum: String,
    )

    private val originalWatchKeys = ConcurrentHashMap<JarId, WatchKeyWithChecksum>()
    private val originalWatchKeysReverse = ConcurrentHashMap<WatchKey, JarId>()

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

    private val indexLock = Mutex()
    private val indexJobs = ConcurrentHashMap<VersionedKotlinPluginDescriptor, Job>()

    private val logger by lazy { thisLogger() }

    private val pluginsCache = ConcurrentHashMap<String, ConcurrentHashMap<ResolvedPluginKey, ArtifactState>>()

    private val statusPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC)
    }

    private val discoveryPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginDiscoveryUpdater.TOPIC)
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

            pluginWatchKeys.values.forEach { it.cancel() }
            pluginWatchKeys.clear()
            pluginWatchKeysReverse.clear()
            originalWatchKeys.values.forEach { it.key.cancel() }
            originalWatchKeys.clear()
            originalWatchKeysReverse.clear()

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
                    runCatching {
                        it.resolve(kotlinIdeVersion).deleteRecursively()
                    }
                }

                invalidateKotlinPluginCache()
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

        val fileWatcherDispatcher = fileWatcherThread.asCoroutineDispatcher()

        scope.launch(fileWatcherDispatcher + CoroutineName("file-watcher")) {
            try {
                while (true) {
                    try {
                        fileWatcherLoop()
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
            actualizerJobs.clear()
            indexJobs.clear()
            pluginWatchKeys.clear()
            pluginWatchKeysReverse.clear()
            originalWatchKeys.clear()
            originalWatchKeysReverse.clear()
            runCatching { watchService.close() } // can throw IOException
            fileWatcherThread.shutdown()
            logger.debug("Storage closed")
        }

        project.service<KotlinPluginsExceptionAnalyzerService>().start()
    }

    private suspend fun fileWatcherLoop() {
        val key = withContext(Dispatchers.IO) {
            watchService.take()
        }

        if (!key.isValid) {
            return
        }

        val path = key.watchable() as? Path
        if (path == null) {
            key.cancel()
            return
        }

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
        val reversed = pluginWatchKeysReverse[key]
        if (reversed == null) {
            // original
            val jarId = originalWatchKeysReverse[key]
            if (jarId == null) {
                key.cancel()
                originalWatchKeysReverse.remove(key)
                return
            }

            val plugin = project.service<KotlinPluginsSettings>()
                .pluginByName(jarId.pluginName)

            if (plugin == null) {
                key.cancel()
                originalWatchKeysReverse.remove(key)?.let {
                    originalWatchKeys.remove(it)
                }
                return
            }

            val artifactId = jarId.mavenId.substringAfter(":")
            val detected = key.pollEvents().find { event ->
                val context = event.context() as? Path ?: return@find false

                val name = context.name
                !context.isDirectory() &&
                        name.contains(artifactId) &&
                        name.contains("$kotlinIdeVersion-${jarId.version}") &&
                        name.endsWith(".jar")
            }

            if (detected != null) {
                logger.debug("File watcher detected a change in the original path for $jarId: ${detected.context()}")
                scope.actualize(VersionedKotlinPluginDescriptor(plugin, jarId.version))
            }
            key.reset()
        } else {
            val cacheDir = reversed.directory(kotlinIdeVersion)
            if (cacheDir == null) {
                key.cancel()
                pluginWatchKeysReverse.remove(key)?.let {
                    pluginWatchKeys.remove(it)
                }
                return
            }

            if (path == cacheDir) {
                val plugin = project.service<KotlinPluginsSettings>()
                    .pluginByName(reversed.pluginName)

                if (plugin == null) {
                    key.cancel()
                    pluginWatchKeysReverse.remove(key)?.let {
                        pluginWatchKeys.remove(it)
                    }
                    return
                }

                val detected = key.pollEvents().filter { event ->
                    val context = event.context() as? Path ?: return@filter false

                    val name = context.name
                    !context.isDirectory() &&
                            plugin.ids.any { name.startsWith(it.artifactId) } &&
                            name.contains("$kotlinIdeVersion-${reversed.version}") &&
                            (name.endsWith(".jar") || name.endsWith(".link"))
                }

                if (detected.isNotEmpty()) {
                    logger.debug(
                        "File watcher detected changes in the cached path for $reversed: ${
                            detected.joinToString {
                                it.context().toString()
                            }
                        }"
                    )
                    scope.actualize(VersionedKotlinPluginDescriptor(plugin, reversed.version))
                }
                key.reset()
            } else {
                logger.debug("Unexpected cached path and watch key path inequality: $path != $cacheDir")
                key.cancel()
                pluginWatchKeysReverse.remove(key)?.let {
                    pluginWatchKeys.remove(it)
                }
            }
        }
    }

    fun runActualization() {
        logger.debug("Requested actualization")
        pluginsCache.forEach { (pluginName, artifacts) ->
            val plugin = project.service<KotlinPluginsSettings>().pluginByName(pluginName)
                ?: return@forEach

            artifacts.keys.forEach { artifact ->
                scope.actualize(VersionedKotlinPluginDescriptor(plugin, artifact.libVersion))
            }
        }
    }

    fun requestStatuses() {
        logger.debug("Requested statuses")

        val reporter = project.service<KotlinPluginsExceptionReporter>()
        forEachState { pluginName, mavenId, libVersion, state ->
            val status = if (state is ArtifactState.Cached && reporter.hasExceptions(pluginName, mavenId, libVersion)) {
                ArtifactStatus.ExceptionInRuntime
            } else {
                state.toStatus()
            }

            statusPublisher.updateVersion(
                pluginName = pluginName,
                mavenId = mavenId,
                version = libVersion,
                status = status,
            )
        }
    }

    fun requestJars(): List<KotlinPluginDiscoveryUpdater.Discovery> {
        logger.debug("Requested discovery")

        return forEachState { pluginName, mavenId, _, state ->
            if (state is ArtifactState.Cached) {
                KotlinPluginDiscoveryUpdater.Discovery(
                    pluginName = pluginName,
                    mavenId = mavenId,
                    version = state.actualVersion,
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

    fun getLocationFor(pluginName: String, mavenId: String?, version: String?): Path? {
        if (mavenId != null && version != null) {
            val pluginKey = ResolvedPluginKey(mavenId, version)
            return (pluginsCache[pluginName]?.get(pluginKey) as? ArtifactState.Cached)?.jar?.path
        }

        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()
        return cacheDirBlocking()
            ?.resolve(kotlinIdeVersion)
            ?.resolve(pluginName)
            ?.let { if (version != null) it.resolve(version) else it }
    }

    fun getFailureMessageFor(pluginName: String, mavenId: String, version: String): String? {
        val resolved = ResolvedPluginKey(mavenId, version)

        return when (val state = pluginsCache[pluginName]?.get(resolved)) {
            is ArtifactState.FailedToFetch -> state.message
            is ArtifactState.NotFound -> state.message
            else -> null
        }
    }

    private fun <T> forEachState(doSomething: (String, String, String, ArtifactState) -> T): List<T> {
        val result = mutableListOf<T>()
        pluginsCache.entries.forEach { (pluginName, artifacts) ->
            artifacts.forEach { (artifact, state) ->
                val item = doSomething(pluginName, artifact.mavenId, artifact.libVersion, state)
                result.add(item)
            }
        }
        return result
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

        val anyJarChanged = AtomicBoolean(false)

        var failedToLocate = false
        val nextJob = launch(context = CoroutineName("jar-fetcher-${descriptor.name}"), start = CoroutineStart.LAZY) {
            val pluginWatchKey = VersionedPluginKey(descriptor.name, plugin.version)

            pluginWatchKeys.remove(pluginWatchKey)?.also {
                it.cancel()
                pluginWatchKeysReverse.remove(it)
            }

            statusPublisher.updatePlugin(descriptor.name, ArtifactStatus.InProgress)

            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

            val destination = pluginWatchKey.directory(kotlinIdeVersion) ?: return@launch

            val artifactsMap = pluginsCache
                .getOrPut(descriptor.name) { ConcurrentHashMap() }

            logger.debug("Actualize plugins job started (${plugin.descriptor.name}), attempt: $attempt")

            val known = artifactsMap.entries
                .filter { (key, value) ->
                    plugin.descriptor.hasArtifact(key.mavenId) && key.libVersion == plugin.version && value is ArtifactState.Cached
                }.associate { (k, v) ->
                    k.mavenId to (v as ArtifactState.Cached).jar
                }

            val bundleResult = runCatching {
                KotlinPluginsJarLocator.locateArtifacts(
                    versioned = plugin,
                    kotlinIdeVersion = kotlinIdeVersion,
                    dest = destination,
                    known = known,
                )
            }

            if (bundleResult.isFailure) {
                statusPublisher.updatePlugin(
                    pluginName = descriptor.name,
                    status = ArtifactStatus.FailedToLoad("Unexpected error"),
                )
                logger.error("Actualize plugins job failed (${plugin.descriptor.name})", bundleResult.exceptionOrNull())
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
                val resolvedKey = ResolvedPluginKey(id.id, locatorResult.libVersion)

                var resolvedIsNew = false
                artifactsMap.compute(resolvedKey) { _, old ->
                    val oldChecksum = (old as? ArtifactState.Cached)?.jar?.checksum
                    if (locatorResult is LocatorResult.Cached && locatorResult.jar.checksum != oldChecksum) {
                        resolvedIsNew = true
                        anyJarChanged.compareAndSet(false, true)
                    }

                    locatorResult.state
                }

                if (locatorResult is LocatorResult.Cached) {
                    val original = locatorResult.original
                    if (original != null) {
                        val jarKey = JarId(plugin.descriptor.name, id.id, locatorResult.libVersion)

                        val watchKeyWithChecksum = withContext(Dispatchers.IO) {
                            originalWatchKeys.compute(jarKey) { _, old ->
                                if (old?.checksum != locatorResult.state.jar.checksum) {
                                    old?.key?.cancel()

                                    original.parent?.registerSafe(
                                        StandardWatchEventKinds.ENTRY_MODIFY,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                    )?.let {
                                        WatchKeyWithChecksum(it, locatorResult.state.jar.checksum)
                                    }
                                } else old
                            }
                        }

                        if (watchKeyWithChecksum != null) {
                            originalWatchKeysReverse[watchKeyWithChecksum.key] = jarKey
                        }
                    }
                }

                if (resolvedIsNew) {
                    locatorResult as LocatorResult.Cached

                    discoveryPublisher.discoveredSync(
                        pluginName = descriptor.name,
                        mavenId = id.id,
                        version = locatorResult.state.actualVersion,
                        jar = locatorResult.jar.path,
                        checksum = locatorResult.jar.checksum,
                        isLocal = locatorResult.jar.isLocal,
                        kotlinVersionMismatch = locatorResult.jar.kotlinVersionMismatch,
                    )
                }

                val reporter = project.service<KotlinPluginsExceptionReporter>()

                val hasExceptions = reporter.hasExceptions(plugin.descriptor.name, id.id, locatorResult.libVersion)
                val status = if (!resolvedIsNew && locatorResult is LocatorResult.Cached && hasExceptions) {
                    ArtifactStatus.ExceptionInRuntime
                } else {
                    locatorResult.state.toStatus()
                }

                statusPublisher.updateVersion(
                    pluginName = descriptor.name,
                    mavenId = id.id,
                    version = locatorResult.libVersion,
                    status = status,
                )
            }

            statusPublisher.redraw()

            val watchKey = withContext(Dispatchers.IO) {
                destination.registerSafe(
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
            } ?: return@launch

            pluginWatchKeys[pluginWatchKey] = watchKey
            pluginWatchKeysReverse[watchKey] = pluginWatchKey
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
                    } else if (anyJarChanged.get()) {
                        invalidateKotlinPluginCache()
                    }
                }

                nextJob.start()
            }
        }
    }

    fun getPluginPath(requested: RequestedKotlinPluginDescriptor): Path? {
        val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

        val pluginMap = pluginsCache.getOrPut(requested.descriptor.name) {
            ConcurrentHashMap()
        }

        val paths = requested.descriptor.ids.map {
            it.id to pluginMap[ResolvedPluginKey(it.id, requested.version)] as? ArtifactState.Cached?
        }

        logger.debug(
            "Cached versions for ${requested.version} (${requested.descriptor.name}): " +
                    "${paths.filter { it.second != null }.map { "${it.first} -> ${it.second?.actualVersion}" }}"
        )

        val allExist = paths.all { it.second?.jar?.path?.exists() == true }
        val differentVersions = paths.distinctBy { it.second?.actualVersion }.count()

        // return not null only when all requested plugins are present and have the same version
        if (!allExist || differentVersions != 1) {
            // no requested version is present for all requested plugins, or versions are not equal
            updateCacheFromDisk(requested, pluginMap, kotlinIdeVersion, paths.associate { it })
            return null
        }

        logger.debug("All versions for ${requested.version} (${requested.descriptor.name}) are present")

        val state = paths.find { it.first == requested.artifact.id }?.second
            ?: error("Should not happen")

        val reporter = project.service<KotlinPluginsExceptionReporter>()
        val hasExceptions = reporter.hasExceptions(requested.descriptor.name, requested.artifact.id, requested.version)

        val status = if (hasExceptions) {
            ArtifactStatus.ExceptionInRuntime
        } else {
            state.toStatus()
        }

        statusPublisher.updateVersion(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            version = requested.version,
            status = status,
        )

        return state.jar.path
    }

    private class StoredJar(
        val mavenId: String,
        val locatedVersion: String?,
        val jar: Jar?,
    )

    private fun updateCacheFromDisk(
        requested: RequestedKotlinPluginDescriptor,
        pluginMap: ConcurrentHashMap<ResolvedPluginKey, ArtifactState>,
        kotlinIdeVersion: String,
        knownByIde: Map<String, ArtifactState.Cached?>,
    ) {
        if (indexJobs[requested]?.isActive == true) {
            return
        }

        val nextJob = scope.launch(
            context = CoroutineName(
                "update-cache-from-disk-${requested.descriptor.name}-${requested.version}"
            ),
            start = CoroutineStart.LAZY,
        ) {
            logger.debug("Updating cache from disk for ${requested.descriptor.name} (${requested.version})")

            val pluginWatchKey = VersionedPluginKey(requested.descriptor.name, requested.version)

            val paths = requested.descriptor.ids.map {
                val artifact = RequestedKotlinPluginDescriptor(
                    descriptor = requested.descriptor,
                    version = requested.version,
                    artifact = it,
                )

                findArtifactAndUpdateMap(
                    pluginMap = pluginMap,
                    requested = artifact,
                    kotlinIdeVersion = kotlinIdeVersion,
                    pluginWatchKey = pluginWatchKey,
                )
            }

            logger.trace(
                "On disk versions for ${requested.version} (${requested.descriptor.name}): " +
                        "${paths.map { "${it.mavenId} -> ${it.jar?.path}" }}"
            )

            if (paths.any { it.jar == null } || paths.distinctBy { it.locatedVersion }.size != 1) {
                logger.debug("Some versions are missing for ${requested.descriptor.name} (${requested.version})")
                scope.actualize(requested)

                return@launch
            }

            val new = withContext(Dispatchers.IO) {
                pluginWatchKeys.compute(pluginWatchKey) { _, old ->
                    old ?: pluginWatchKey.directoryBlocking(kotlinIdeVersion)?.registerSafe(
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                    )
                }
            }

            if (new != null) {
                pluginWatchKeysReverse[new] = pluginWatchKey
            }

            if (paths.any { knownByIde[it.mavenId]?.jar?.checksum != it.jar?.checksum }) {
                logger.debug("Found new versions on disk for ${requested.descriptor.name} (${requested.version})")

                invalidateKotlinPluginCache()
            }
        }

        scope.launch(CoroutineName("index-plugins-job-starter-${requested.descriptor.name}")) {
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
        pluginMap: ConcurrentHashMap<ResolvedPluginKey, ArtifactState>,
        requested: RequestedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
        pluginWatchKey: VersionedPluginKey,
    ): StoredJar = withContext(Dispatchers.IO) {
        val resolvedPlugin = ResolvedPluginKey(requested.artifact.id, requested.version)
        val state = pluginMap.compute(resolvedPlugin) { _, old ->
            when {
                old is ArtifactState.Cached && Files.exists(old.jar.path) -> old
                else -> null
            }
        }

        if (state != null) {
            return@withContext StoredJar(
                mavenId = requested.artifact.id,
                locatedVersion = requested.version,
                jar = (state as ArtifactState.Cached).jar,
            )
        }

        val (foundVersion, foundKotlinVersion, found) = findJarPath(requested, kotlinIdeVersion, pluginWatchKey)
            ?: return@withContext StoredJar(
                mavenId = requested.artifact.id,
                locatedVersion = requested.version,
                jar = null,
            )

        val checksum = runCatching { md5(found).asChecksum() }.getOrNull()
            ?: return@withContext StoredJar(
                mavenId = requested.artifact.id,
                locatedVersion = requested.version,
                jar = null,
            )

        val original = found.resolveOriginalJarFile()

        if (original != null && checksum != md5(original).asChecksum()) {
            runCatching { Files.deleteIfExists(found) }

            logger.debug("Checksums don't match with the original jar for ${requested.artifact.id} (${requested.version})")

            return@withContext StoredJar(
                mavenId = requested.artifact.id,
                locatedVersion = requested.version,
                jar = null,
            )
        }

        val jarKey = JarId(requested.descriptor.name, requested.artifact.id, foundVersion)

        val watchKeyWithChecksum = originalWatchKeys.compute(jarKey) { _, old ->
            if (old?.checksum != checksum) {
                old?.key?.cancel()

                original?.parent?.registerSafe(
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                )?.let { WatchKeyWithChecksum(it, checksum) }
            } else old
        }

        if (watchKeyWithChecksum != null) {
            originalWatchKeysReverse[watchKeyWithChecksum.key] = jarKey
        }

        val kotlinVersionMismatch = if (foundKotlinVersion != kotlinIdeVersion) {
            KotlinVersionMismatch(
                ideVersion = kotlinIdeVersion,
                jarVersion = foundKotlinVersion,
            )
        } else {
            null
        }

        val jar = Jar(
            path = found,
            checksum = checksum,
            isLocal = original != null,
            kotlinVersionMismatch = kotlinVersionMismatch,
        )

        val newState = ArtifactState.Cached(
            jar = jar,
            requestedVersion = requested.version,
            actualVersion = foundVersion,
            criteria = requested.descriptor.versionMatching,
        )

        pluginMap[resolvedPlugin] = newState

        discoveryPublisher.discoveredSync(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            version = requested.version,
            jar = jar.path,
            checksum = checksum,
            isLocal = jar.isLocal,
            kotlinVersionMismatch = kotlinVersionMismatch,
        )

        StoredJar(
            mavenId = requested.artifact.id,
            locatedVersion = foundVersion,
            jar = jar,
        )
    }

    private fun findJarPath(
        requested: RequestedKotlinPluginDescriptor,
        kotlinIdeVersion: String,
        pluginWatchKey: VersionedPluginKey,
    ): Triple<String, String, Path>? = runCatching {
        val basePath = pluginWatchKey.directoryBlocking(kotlinIdeVersion)
            ?: return null

        if (!Files.exists(basePath)) {
            return null
        }

        val candidates = basePath
            .listDirectoryEntries("${requested.artifact.artifactId}-$kotlinIdeVersion-*.jar")
            .toList()

        logger.debug("Candidates for ${requested.artifact.id}:${requested.version} -> ${candidates.map { it.fileName }}")

        val versionToPath = candidates
            .associateBy {
                it.name
                    .substringAfter("${requested.artifact.artifactId}-$kotlinIdeVersion-")
                    .substringBefore(".jar")
            }

        // the same version check will happen later
        val matched = getMatching(listOf(versionToPath.keys.toList()), "", requested.asMatchFilter())

        return matched?.let { libVersion ->
            val path = versionToPath.getValue(libVersion)

            // todo support fallbacks
            Triple(libVersion, kotlinIdeVersion, path)
        }
    }.getOrNull()

    @Suppress("UnstableApiUsage")
    private inline fun resolveCacheDir(getApi: () -> EelApi): Path? {
        if (!resolvedCacheDir.compareAndSet(false, true)) {
            return _cacheDir
        }

        val userHome = getApi().fs.user.home.asNioPathOrNull() ?: Path("/") // user is nobody
        _cacheDir = userHome.resolve(KOTLIN_PLUGINS_STORAGE_DIRECTORY).toAbsolutePath()

        return _cacheDir
    }

    private fun invalidateKotlinPluginCache() {
        if (project.isDisposed) {
            return
        }

        statusPublisher.reset()

        val provider = KotlinCompilerPluginsProvider.getInstance(project)

        if (provider is Disposable) {
            provider.dispose() // clear Kotlin plugin caches
        }

        logger.debug("Invalidated KotlinCompilerPluginsProvider")
    }

    companion object {
        const val KOTLIN_PLUGINS_STORAGE_DIRECTORY = ".kotlinPlugins"
    }

    private data class ResolvedPluginKey(
        val mavenId: String,
        val libVersion: String,
    )

    private fun Watchable.registerSafe(vararg events: WatchEvent.Kind<*>): WatchKey? {
        return try {
            register(watchService, *events)
        } catch (e: Exception) {
            logger.warn("Failed to register watch key for $this", e)
            null
        }
    }

    private suspend fun VersionedPluginKey.directory(kotlinIdeVersion: String): Path? {
        return cacheDir()
            ?.resolve(kotlinIdeVersion)
            ?.resolve(pluginName)
            ?.resolve(version)
            ?.apply {
                createDirectories()
            }
    }

    private fun VersionedPluginKey.directoryBlocking(kotlinIdeVersion: String): Path? {
        return cacheDirBlocking()
            ?.resolve(kotlinIdeVersion)
            ?.resolve(pluginName)
            ?.resolve(version)
            ?.apply {
                createDirectories()
            }
    }
}
