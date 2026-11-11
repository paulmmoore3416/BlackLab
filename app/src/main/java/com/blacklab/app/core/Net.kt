package com.blacklab.app.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * One way to talk about network trouble everywhere (weather, recalls, Bluelink, updates, Google, the desktop):
 * a [Problem] kind, the same plain-words message, and the same short retry for the failures that are worth
 * retrying (no connection yet, timeouts, 429 and 5xx) — never for sign-in or "not found".
 */
object Net {
    enum class Problem { OFFLINE, TIMEOUT, SIGN_IN, NOT_FOUND, RATE_LIMITED, SERVER, UNKNOWN }

    /** A non-2xx answer, so callers and [retry] can tell 401 from 503. */
    class HttpStatus(val code: Int, service: String) : IOException("$service answered $code")

    fun classify(t: Throwable): Problem = when (t) {
        is HttpStatus -> when (t.code) {
            401, 403 -> Problem.SIGN_IN
            404 -> Problem.NOT_FOUND
            429 -> Problem.RATE_LIMITED
            in 500..599 -> Problem.SERVER
            else -> Problem.UNKNOWN
        }
        is SecurityException -> Problem.SIGN_IN
        is UnknownHostException, is ConnectException -> Problem.OFFLINE
        is SocketTimeoutException, is InterruptedIOException -> Problem.TIMEOUT
        is SSLException -> Problem.OFFLINE
        is IOException -> Problem.OFFLINE
        else -> Problem.UNKNOWN
    }

    fun retryable(p: Problem) = p in setOf(Problem.OFFLINE, Problem.TIMEOUT, Problem.RATE_LIMITED, Problem.SERVER)

    fun message(p: Problem, service: String): String = when (p) {
        Problem.OFFLINE -> "No connection to $service right now. It'll try again when you're back online."
        Problem.TIMEOUT -> "$service is taking too long to answer. Try again in a minute."
        Problem.SIGN_IN -> "$service needs you to sign in again (or the key was rejected)."
        Problem.NOT_FOUND -> "$service doesn't have that."
        Problem.RATE_LIMITED -> "$service is busy (too many requests). Try again shortly."
        Problem.SERVER -> "$service is having trouble on its end. Try again later."
        Problem.UNKNOWN -> "Something went wrong talking to $service."
    }

    /**
     * Runs [block] up to [times] times, waiting [firstDelayMs], then twice as long… between tries, for retryable
     * problems only. Blocking: call it on an IO thread. [sleep] is replaceable for tests.
     */
    fun <T> retry(times: Int = 3, firstDelayMs: Long = 600, sleep: (Long) -> Unit = { Thread.sleep(it) }, block: () -> T): T {
        var delay = firstDelayMs
        var last: Throwable? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                last = t
                if (!retryable(classify(t)) || attempt == times - 1) throw t
                sleep(delay)
                delay *= 2
            }
        }
        throw last ?: IllegalStateException("retry")
    }
}
