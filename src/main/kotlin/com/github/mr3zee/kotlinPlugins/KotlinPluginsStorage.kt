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
    fun discovered(discovery: Discovery)
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

fun KotlinPluginDiscoveryUpdater.discovered(pluginName: String, mavenId: String, version: String, jar: Path) {
    discovered(KotlinPluginDiscoveryUpdater.Discovery(pluginName, mavenId, version, jar))
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

    private val statusPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginStatusUpdater.TOPIC)
    }

    private val discoveryPublisher by lazy {
        project.messageBus.syncPublisher(KotlinPluginDiscoveryUpdater.TOPIC)
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

                cacheDir()?.let {
                    @OptIn(ExperimentalPathApi::class)
                    runCatching {
                        it.deleteRecursively()
                    }
                }

                invalidateKotlinPluginCache()
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

        forEachState { pluginName, mavenId, libVersion, state ->
            statusPublisher.updateVersion(
                pluginName = pluginName,
                mavenId = mavenId,
                version = libVersion,
                status = state.toStatus(),
            )
        }
    }

    fun requestJars(): List<KotlinPluginDiscoveryUpdater.Discovery> {
        logger.debug("Requested discovery")

        return forEachState { pluginName, mavenId, _, state ->
            if (state is ArtifactState.Cached) {
                KotlinPluginDiscoveryUpdater.Discovery(pluginName, mavenId, state.actualVersion, state.path)
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

        val jarChanged = AtomicBoolean(false)

        var failedToLocate = false
        val nextJob = launch(context = CoroutineName("jar-fetcher-${descriptor.name}"), start = CoroutineStart.LAZY) {
            statusPublisher.updatePlugin(descriptor.name, ArtifactStatus.InProgress)

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
                val status = locatorResult.state.toStatus()

                statusPublisher.updateVersion(
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
                        discoveryPublisher.discovered(descriptor.name, id.id, new.actualVersion, new.path)
                    }

                    new
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
                    } else if (jarChanged.get()) {
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
            it.id to pluginMap[ResolvedPlugin(it, requested.version)] as? ArtifactState.Cached?
        }

        logger.debug(
            "Cached versions for ${requested.version} (${requested.descriptor.name}): " +
                    "${paths.map { "${it.first} -> ${it.second?.actualVersion}" }}"
        )

        val allExist = paths.all { it.second?.path?.exists() == true }
        val differentVersions = paths.distinctBy { it.second?.actualVersion }.count()

        // return not null only when all requested plugins are present and have the same version
        if (!allExist || differentVersions != 1) {
            // no requested version is present for all requested plugins, or versions are not equal
            updateCacheFromDisk(requested, pluginMap, kotlinIdeVersion, paths.associate { it })
            return null
        }

        val state = paths.find { it.first == requested.artifact.id }?.second
            ?: error("Should not happen")

        statusPublisher.updateVersion(
            pluginName = requested.descriptor.name,
            mavenId = requested.artifact.id,
            version = requested.version,
            status = state.toStatus(),
        )

        discoveryPublisher.discovered(requested.descriptor.name, requested.artifact.id, requested.version, state.path)

        return state.path
    }

    class StoredJar(
        val mavenId: String,
        val locatedVersion: String?,
        val path: Path?,
    )

    private fun updateCacheFromDisk(
        requested: RequestedKotlinPluginDescriptor,
        pluginMap: ConcurrentHashMap<ResolvedPlugin, ArtifactState>,
        kotlinIdeVersion: String,
        knownByIde: Map<String, ArtifactState.Cached?>,
    ) {
        scope.launch(CoroutineName("update-cache-from-disk-${requested.descriptor.name}-${requested.version}")) {
            logger.debug("Updating cache from disk for ${requested.descriptor.name} (${requested.version})")

            val paths = requested.descriptor.ids.map {
                val artifact = RequestedKotlinPluginDescriptor(
                    descriptor = requested.descriptor,
                    version = requested.version,
                    artifact = it,
                )

                findArtifact(pluginMap, artifact, kotlinIdeVersion)
            }

            logger.debug(
                "On disk versions for ${requested.version} (${requested.descriptor.name}): " +
                        "${paths.map { "${it.mavenId} -> ${it.path}" }}"
            )

            if (paths.any { it.path == null } || paths.distinctBy { it.locatedVersion }.size != 1) {
                logger.debug("Some versions are missing for ${requested.descriptor.name} (${requested.version})")
                scope.actualize(requested)
            }

            if (paths.any { knownByIde[it.mavenId]?.path != it.path }) {
                logger.debug("Found new versions on disk for ${requested.descriptor.name} (${requested.version})")

                invalidateKotlinPluginCache()
            }
        }
    }

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
            mavenId = requested.artifact.id,
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
        }

        statusPublisher.reset()
        discoveryPublisher.reset()
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
