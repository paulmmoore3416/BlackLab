package com.blacklab.app.link

import com.blacklab.app.core.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LinkWatchTest {
    private fun store() = JsonStore("Test", version = 1, empty = { 0 },
        decode = { it.optInt("n") }, encode = { JSONObject().put("n", it) })

    /** Waits until [n] reaches [want] (the collector runs on another thread). */
    private fun awaitCount(n: AtomicInteger, want: Int) = runBlocking {
        withTimeout(2_000) { while (n.get() < want) yield() }
    }

    @Test fun aSavedStoreChangeBumpsTheLinkButTheCurrentValueDoesNot() {
        val s = store()
        val file = File(Files.createTempDirectory("lw").toFile(), "t.json")
        val bumps = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        val ready = java.util.concurrent.CountDownLatch(1)
        LinkWatch.launch(scope, listOf(s.state), minGapMs = 20, onStart = { ready.countDown() }) { bumps.incrementAndGet() }
        ready.await()
        Thread.sleep(100)
        assertEquals("subscribing alone must not bump", 0, bumps.get())

        s.update(file) { it + 1 }
        awaitCount(bumps, 1)
        s.update(file) { it + 1 }
        awaitCount(bumps, 2)
        scope.cancel()
    }

    /** Live car readings arrive every second: the link is told at most once per gap, but never misses the last change. */
    @Test fun aBurstOfChangesIsThrottledButTheLastOneStillArrives() {
        val src = kotlinx.coroutines.flow.MutableStateFlow(0)
        val bumps = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        val ready = java.util.concurrent.CountDownLatch(1)
        LinkWatch.launch(scope, listOf(src), minGapMs = 300, onStart = { ready.countDown() }) { bumps.incrementAndGet() }
        ready.await()
        Thread.sleep(100)
        repeat(50) { src.value = it + 1; Thread.sleep(5) } // ~250 ms burst
        Thread.sleep(800)
        val n = bumps.get()
        assertEquals("one leading + one trailing bump, got $n", true, n in 1..3)
        assertEquals("the trailing bump must come", true, n >= 2)
        scope.cancel()
    }

    /** Every JsonStore in the app must be watched, or the desktop goes stale when it changes (5.13.1 bug). */
    @Test fun everyJsonStoreInTheAppIsWatched() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val declared = src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""JsonStore\("([^"]+)"""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertEquals(declared, LinkWatch.stores().map { it.name }.toSet())
    }
}
