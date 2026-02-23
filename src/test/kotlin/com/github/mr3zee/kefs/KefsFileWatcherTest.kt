package com.github.mr3zee.kefs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class KefsFileWatcherTest {
    private lateinit var tempDir: Path
    private lateinit var localRepoDir: Path
    private lateinit var cacheDir: Path
    private lateinit var watcher: KefsFileWatcher

    private val localRepoChanges = CopyOnWriteArrayList<Path>()
    private val cacheDirChangeCount = AtomicInteger(0)

    @Before
    fun setUp(): Unit = runBlocking {
        tempDir = Files.createTempDirectory("kefs-watcher-test")
        localRepoDir = tempDir.resolve("local-repo").also { it.createDirectories() }
        cacheDir = tempDir.resolve("cache").also { it.createDirectories() }

        watcher = KefsFileWatcher(object : FileWatcherCallback {
            override fun onLocalRepoChange(repoRoot: Path) {
                localRepoChanges.add(repoRoot)
            }

            override fun onCacheDirExternalChange() {
                cacheDirChangeCount.incrementAndGet()
            }
        })

        watcher.registerCacheDir(cacheDir)
        watcher.registerLocalRepo(localRepoDir)
    }

    @OptIn(ExperimentalPathApi::class)
    @After
    fun tearDown() {
        watcher.close()
        tempDir.deleteRecursively()
    }

    // --- Local Repo Tests ---

    @Test
    fun testLocalRepoFileCreation(): Unit = runBlocking {
        createFile(localRepoDir.resolve("artifact.jar"), "content")

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event", localRepoChanges.isNotEmpty())
        assertEquals(localRepoDir.toAbsolutePath().normalize(), localRepoChanges.first())
    }

    @Test
    fun testLocalRepoFileModification(): Unit = runBlocking {
        val file = localRepoDir.resolve("artifact.jar")
        createFile(file, "original")

        awaitCondition { localRepoChanges.isNotEmpty() }
        localRepoChanges.clear()

        modifyFile(file, "modified")

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event on modification", localRepoChanges.isNotEmpty())
    }

    @Test
    fun testLocalRepoFileDeletion(): Unit = runBlocking {
        val file = localRepoDir.resolve("artifact.jar")
        createFile(file, "content")

        awaitCondition { localRepoChanges.isNotEmpty() }
        localRepoChanges.clear()

        deleteFile(file)

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event on deletion", localRepoChanges.isNotEmpty())
    }

    @Test
    fun testLocalRepoNewSubdirectory(): Unit = runBlocking {
        val subdir = localRepoDir.resolve("org/example/1.0")

        withContext(Dispatchers.IO) {
            subdir.createDirectories()
        }

        // Wait for directory creation events to propagate and subdirectory to be registered
        awaitCondition { localRepoChanges.isNotEmpty() }
        localRepoChanges.clear()

        // Give the watcher time to register the new subdirectory
        processEventsFor(500)

        // Now create a file in the new subdirectory
        createFile(subdir.resolve("artifact.jar"), "content")

        awaitCondition { localRepoChanges.isNotEmpty() }

        assertTrue("Expected local repo change event for file in new subdirectory", localRepoChanges.isNotEmpty())
    }

    // --- Cache Dir Tests ---

    @Test
    fun testCacheDirExternalFileCreation(): Unit = runBlocking {
        createFile(cacheDir.resolve("plugin.jar"), "content")

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("Expected cache dir external change event", cacheDirChangeCount.get() > 0)
    }

    @Test
    fun testCacheDirExternalFileModification(): Unit = runBlocking {
        val file = cacheDir.resolve("plugin.jar")
        createFile(file, "original")

        awaitCondition { cacheDirChangeCount.get() > 0 }
        cacheDirChangeCount.set(0)

        modifyFile(file, "modified")

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("Expected cache dir external change event on modification", cacheDirChangeCount.get() > 0)
    }

    @Test
    fun testCacheDirDeletion(): Unit = runBlocking {
        val file = cacheDir.resolve("plugin.jar")
        createFile(file, "content")

        awaitCondition { cacheDirChangeCount.get() > 0 || cacheDirChangeCount.get() > 0 }
        cacheDirChangeCount.set(0)
        cacheDirChangeCount.set(0)

        deleteFile(file)

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("Expected cache dir deleted event", cacheDirChangeCount.get() > 0)
    }

    // --- Self-update suppression tests ---

    @Test
    fun testCacheDirSelfUpdateSuppression(): Unit = runBlocking {
        watcher.markSelfUpdateStart()
        try {
            createFile(cacheDir.resolve("self-update.jar"), "content")

            // Process events for a while - should NOT trigger callback
            processEventsFor(2000)
        } finally {
            watcher.markSelfUpdateEnd()
        }

        assertEquals("Self-update should NOT trigger cache dir external change", 0, cacheDirChangeCount.get())
        assertEquals("Self-update should NOT trigger cache dir deleted", 0, cacheDirChangeCount.get())
    }

    @Test
    fun testCacheDirSelfUpdateThenExternalChange(): Unit = runBlocking {
        // Self-update: should be suppressed
        watcher.markSelfUpdateStart()
        createFile(cacheDir.resolve("self-update.jar"), "self-content")
        processEventsFor(1500)
        watcher.markSelfUpdateEnd()

        assertEquals("Self-update should be suppressed", 0, cacheDirChangeCount.get())

        // External change: should fire
        createFile(cacheDir.resolve("external.jar"), "external-content")

        awaitCondition { cacheDirChangeCount.get() > 0 }

        assertTrue("External change after self-update should trigger callback", cacheDirChangeCount.get() > 0)
    }

    @Test
    fun testNoSpuriousEvents(): Unit = runBlocking {
        // Just wait without making changes
        processEventsFor(2000)

        assertTrue("No local repo changes expected", localRepoChanges.isEmpty())
        assertEquals("No cache dir changes expected", 0, cacheDirChangeCount.get())
        assertEquals("No cache dir deletions expected", 0, cacheDirChangeCount.get())
    }

    // --- Helpers ---

    private suspend fun createFile(path: Path, content: String) {
        withContext(Dispatchers.IO) {
            path.parent.createDirectories()
            path.writeText(content)
        }
    }

    private suspend fun modifyFile(path: Path, content: String) {
        withContext(Dispatchers.IO) {
            path.writeText(content)
        }
    }

    private suspend fun deleteFile(path: Path) {
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(path)
        }
    }

    private suspend fun processEventsFor(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            watcher.processOneEvent()
        }
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 15000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            watcher.processOneEvent()

            if (condition()) {
                return
            }
        }

        // One final check
        if (!condition()) {
            throw AssertionError("Condition not met within ${timeoutMs}ms")
        }
    }
}
