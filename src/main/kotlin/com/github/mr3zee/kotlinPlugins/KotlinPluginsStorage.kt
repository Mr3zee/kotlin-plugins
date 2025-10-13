@file:Suppress("RemoveUnnecessaryParentheses")

package com.github.mr3zee.kotlinPlugins

import com.intellij.openapi.Disposable
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
import kotlin.collections.forEach
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
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
    object Skipped : ArtifactStatus
}

sealed interface ArtifactState {
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
}

private fun ArtifactState.toStatus(): ArtifactStatus {
    return when (this) {
        is ArtifactState.Cached -> ArtifactStatus.Success(requestedVersion, actualVersion, criteria)
        is ArtifactState.FailedToFetch -> ArtifactStatus.FailedToLoad("Failed to Fetch")
        is ArtifactState.NotFound -> ArtifactStatus.FailedToLoad("Not Found")
    }
}

interface KotlinPluginStatusUpdater {
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

interface KotlinPluginDiscoveryUpdater {
    fun discoveredSync(discovery: Discovery)
    fun reset()

    class Discovery(
        val pluginName: String,
        val mavenId: String,
        val version: String,
        val jar: Path,
    )

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic("Kotlin Plugins Discovery", KotlinPluginDiscoveryUpdater::class.java)
    }
}

fun KotlinPluginDiscoveryUpdater.discoveredSync(pluginName: String, mavenId: String, version: String, jar: Path) {
    discoveredSync(KotlinPluginDiscoveryUpdater.Discovery(pluginName, mavenId, version, jar))
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

    private val indexLock = Mutex()
    private val indexJobs = ConcurrentHashMap<VersionedKotlinPluginDescriptor, Job>()

    private val logger by lazy { thisLogger() }

    private val pluginsCache =
        ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<ResolvedPluginKey, ArtifactState>>>()

    private val statusPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC)
    }

    private val discoveryPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginDiscoveryUpdater.TOPIC)
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
            discoveryPublisher.reset()

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

        scope.coroutineContext.job.invokeOnCompletion {
            pluginsCache.clear()
            actualizerJobs.clear()
            indexJobs.clear()
            discoveryPublisher.reset()
            statusPublisher.reset()
            logger.debug("Storage closed")
        }

        project.service<KotlinPluginsExceptionAnalyzerService>().start()
    }

    fun runActualization() {
        logger.debug("Requested actualization")
        pluginsCache.values.forEach {
            it.forEach { (pluginName, artifacts) ->
                val plugin = project.service<KotlinPluginsSettings>().pluginByName(pluginName)
                    ?: return@forEach

                artifacts.keys.forEach { artifact ->
                    scope.actualize(VersionedKotlinPluginDescriptor(plugin, artifact.libVersion))
                }
            }
        }
    }

    fun requestStatuses() {
        logger.debug("Requested statuses")

        val reporter = project.service<KotlinPluginsExceptionReporter>()
        forEachState { pluginName, mavenId, libVersion, state ->
            val status = if (state is ArtifactState.Cached && reporter.hasExceptions(pluginName, mavenId)) {
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
                KotlinPluginDiscoveryUpdater.Discovery(pluginName, mavenId, state.actualVersion, state.jar.path)
            } else {
                null
            }
        }.filterNotNull()
    }

    private fun <T> forEachState(doSomething: (String, String, String, ArtifactState) -> T): List<T> {
        val result = mutableListOf<T>()
        pluginsCache.values.forEach { plugins ->
            plugins.forEach { (pluginName, artifacts) ->
                artifacts.entries.forEach { (artifact, state) ->
                    val item = doSomething(pluginName, artifact.mavenId.id, artifact.libVersion, state)
                    result.add(item)
                }
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
            statusPublisher.updatePlugin(descriptor.name, ArtifactStatus.InProgress)

            val kotlinIdeVersion = service<KotlinVersionService>().getKotlinIdePluginVersion()

            val destination = cacheDir()?.resolve(kotlinIdeVersion)
                ?: return@launch

            val artifactsMap = pluginsCache
                .getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
                .getOrPut(descriptor.name) { ConcurrentHashMap() }

            logger.debug("Actualize plugins job started (${plugin.descriptor.name}), attempt: $attempt")

            val known = artifactsMap.entries
                .filter { (key, value) ->
                    key.mavenId in plugin.descriptor.ids && key.libVersion == plugin.version && value is ArtifactState.Cached
                }.associate { (k, v) ->
                    k.mavenId to (v as ArtifactState.Cached).jar
                }

            val bundleResult = runCatching {
                withContext(Dispatchers.IO) {
                    KotlinPluginJarLocator.locateArtifacts(
                        versioned = plugin,
                        kotlinIdeVersion = kotlinIdeVersion,
                        dest = destination,
                        known = known,
                    )
                }
            }

            if (bundleResult.isFailure) {
                statusPublisher.updatePlugin(
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
                val reporter = project.service<KotlinPluginsExceptionReporter>()

                val status = if (
                    locatorResult is LocatorResult.Cached &&
                    reporter.hasExceptions(plugin.descriptor.name, id.id)
                ) {
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

                val resolvedKey = ResolvedPluginKey(id, locatorResult.libVersion)
                val requestedKey = ResolvedPluginKey(id, plugin.version)

                fun updateMap(key: ResolvedPluginKey, updateDiscovery: Boolean) {
                    var isNew = false

                    artifactsMap.compute(key) { _, old ->
                        val oldChecksum = (old as? ArtifactState.Cached)?.jar?.checksum
                        if (locatorResult is LocatorResult.Cached && locatorResult.jar.checksum != oldChecksum) {
                            isNew = true
                            anyJarChanged.compareAndSet(false, true)
                        }

                        locatorResult.state
                    }

                    if (updateDiscovery && isNew) {
                        locatorResult as LocatorResult.Cached

                        discoveryPublisher.discoveredSync(
                            pluginName = descriptor.name,
                            mavenId = id.id,
                            version = locatorResult.state.actualVersion,
                            jar = locatorResult.jar.path,
                        )
                    }
                }

                if (resolvedKey.libVersion != requestedKey.libVersion) {
                    updateMap(resolvedKey, updateDiscovery = true)
                    updateMap(requestedKey, updateDiscovery = false)
                } else {
                    updateMap(resolvedKey, updateDiscovery = true)
                }
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

        val map = pluginsCache.getOrPut(kotlinIdeVersion) { ConcurrentHashMap() }
        val pluginMap = map.getOrPut(requested.descriptor.name) { ConcurrentHashMap() }

        val paths = requested.descriptor.ids.map {
            it.id to pluginMap[ResolvedPluginKey(it, requested.version)] as? ArtifactState.Cached?
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

        statusPublisher.updateVersion(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            version = requested.version,
            status = state.toStatus(),
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
            context = Dispatchers.IO + CoroutineName(
                "update-cache-from-disk-${requested.descriptor.name}-${requested.version}"
            ),
            start = CoroutineStart.LAZY
        ) {
            logger.debug("Updating cache from disk for ${requested.descriptor.name} (${requested.version})")

            val paths = requested.descriptor.ids.map {
                val artifact = RequestedKotlinPluginDescriptor(
                    descriptor = requested.descriptor,
                    version = requested.version,
                    artifact = it,
                )

                findArtifactAndUpdateMap(pluginMap, artifact, kotlinIdeVersion)
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

    private fun findArtifactAndUpdateMap(
        pluginMap: ConcurrentHashMap<ResolvedPluginKey, ArtifactState>,
        requested: RequestedKotlinPluginDescriptor,
        kotlinVersion: String,
    ): StoredJar {
        val foundInFiles by lazy {
            findJarPath(requested, kotlinVersion)
        }

        var searchedInFiles = false

        val resolvedPlugin = ResolvedPluginKey(requested.artifact, requested.version)
        val path = pluginMap.compute(resolvedPlugin) { _, old ->
            when {
                old is ArtifactState.Cached && Files.exists(old.jar.path) -> old
                else -> {
                    searchedInFiles = true

                    val found = foundInFiles?.second
                        ?: return@compute null
                    val checksum = runCatching { md5(found).asChecksum() }.getOrNull()
                        ?: return@compute null

                    val jar = Jar(
                        path = found,
                        checksum = checksum,
                    )

                    discoveryPublisher.discoveredSync(
                        pluginName = requested.descriptor.name,
                        mavenId = requested.artifact.id,
                        version = requested.version,
                        jar = jar.path,
                    )

                    ArtifactState.Cached(
                        jar = jar,
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
            mavenId = requested.artifact.id,
            locatedVersion = locatedVersion,
            jar = (path as? ArtifactState.Cached)?.jar,
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
        val mavenId: MavenId,
        val libVersion: String,
    )
}
