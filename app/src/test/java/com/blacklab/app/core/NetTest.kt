package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetTest {
    @Test fun classifiesFailures() {
        assertEquals(Net.Problem.OFFLINE, Net.classify(UnknownHostException("api")))
        assertEquals(Net.Problem.TIMEOUT, Net.classify(SocketTimeoutException()))
        assertEquals(Net.Problem.SIGN_IN, Net.classify(Net.HttpStatus(401, "X")))
        assertEquals(Net.Problem.SERVER, Net.classify(Net.HttpStatus(503, "X")))
        assertEquals(Net.Problem.NOT_FOUND, Net.classify(Net.HttpStatus(404, "X")))
        assertTrue(Net.message(Net.Problem.OFFLINE, "NHTSA").contains("NHTSA"))
    }

    @Test fun retriesOnlyWhatsWorthRetrying() {
        var calls = 0
        val waits = ArrayList<Long>()
        val out = Net.retry(times = 3, firstDelayMs = 100, sleep = { waits += it }) { calls++; if (calls < 3) throw Net.HttpStatus(503, "X"); "ok" }
        assertEquals("ok", out); assertEquals(3, calls); assertEquals(listOf(100L, 200L), waits)

        calls = 0
        runCatching { Net.retry(sleep = {}) { calls++; throw Net.HttpStatus(401, "X") } }
        assertEquals("sign-in errors are not retried", 1, calls)

        calls = 0
        runCatching { Net.retry(times = 2, sleep = {}) { calls++; throw UnknownHostException() } }
        assertEquals(2, calls)
    }
}
