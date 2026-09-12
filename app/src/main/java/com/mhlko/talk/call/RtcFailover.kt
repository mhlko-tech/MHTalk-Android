package com.mhlko.talk.call

import com.mhlko.talk.data.RoomCredentials
import com.mhlko.talk.data.MHTalkApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal class RtcRecoveryBudget {
    private var windowStartedAt = 0L
    private var recoveries = 0

    fun reset(now: Long) {
        windowStartedAt = now
        recoveries = 0
    }

    fun consume(now: Long): Boolean {
        if (now - windowStartedAt >= 180_000 || now < windowStartedAt) reset(now)
        if (recoveries >= 2) return false
        recoveries++
        return true
    }
}

internal fun canRetryRtcConnection(error: Throwable): Boolean {
    if (error is TimeoutCancellationException) return true
    if (error is CancellationException || error is SecurityException) return false
    val text = generateSequence(error) { it.cause }.take(6)
        .joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }.lowercase()
    if (Regex("permission|notallowed|notfound|notreadable|device.*(busy|missing)|microphone.*(denied|missing)|unauthori[sz]ed|forbidden|invalid.*(token|key|credential|app.?id)|expired.*token|token.*expired|authentication|\\b(400|401|403)\\b").containsMatchIn(text)) return false
    return error is IOException || Regex("gateway|network|connect|socket|signali?ng|timed? ?out|timeout|unavailable|disconnect|ice.*fail|\\b(408|429|5\\d\\d)\\b").containsMatchIn(text)
}

internal suspend fun <T> waitForRtcRoomHandoff(
    timeoutMillis: Long = 90_000,
    retryDelayMillis: Long = 5_000,
    request: suspend () -> T,
): T = withTimeout(timeoutMillis) {
    while (true) {
        try {
            return@withTimeout request()
        } catch (failure: MHTalkApiException) {
            if (failure.status != 409 || failure.code != "RTC_ROOM_PROVIDER_LOCKED") throw failure
            delay(retryDelayMillis)
        }
    }
    @Suppress("UNREACHABLE_CODE")
    error("The room has not finished reconnecting")
}

/** Only the broker chooses a replacement; every failed allocation is released first. */
internal suspend fun connectWithRtcFailover(
    providers: List<String>,
    initiallyExcluded: List<String> = emptyList(),
    connectTimeoutMillis: Long = 20_000,
    credentials: suspend (List<String>) -> RoomCredentials,
    connect: suspend (RoomCredentials) -> RtcConnectionResult,
    release: suspend (RoomCredentials) -> Unit,
    onRetry: (String) -> Unit = {},
): Pair<RoomCredentials, RtcConnectionResult> {
    val excluded = initiallyExcluded.toMutableSet()
    val maxAttempts = providers.distinct().count { it !in excluded }.coerceAtMost(3)
    check(maxAttempts > 0) { "No compatible replacement server is available" }
    repeat(maxAttempts) { attempt ->
        currentCoroutineContext().ensureActive()
        // Broker errors (including a room locked to active participants) are terminal.
        val allocation = waitForRtcRoomHandoff { credentials(excluded.toList()) }
        try {
            require(allocation.provider in providers && allocation.provider !in excluded) {
                "The server selected an unsupported or already failed RTC provider"
            }
            val result = withTimeout(connectTimeoutMillis) { connect(allocation) }
            currentCoroutineContext().ensureActive()
            return allocation to result
        } catch (failure: Throwable) {
            withContext(NonCancellable) { release(allocation) }
            currentCoroutineContext().ensureActive()
            if (!canRetryRtcConnection(failure) || attempt + 1 == maxAttempts) throw failure
            excluded += allocation.provider
            onRetry(allocation.provider)
        }
    }
    error("No compatible replacement server is available")
}
