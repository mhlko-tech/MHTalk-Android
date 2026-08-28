package com.mhlko.talk.auth

internal enum class SessionFailureKind { TRANSIENT, TERMINAL }

/** Distinguishes recoverable connectivity failures from a revoked session. */
internal fun sessionFailureKind(status: Int?, code: String?, message: String?): SessionFailureKind {
    val detail = "${code.orEmpty()} ${message.orEmpty()}".lowercase()
    if (
        listOf(
            "invalid refresh token",
            "refresh token not found",
            "refresh token already used",
            "session not found",
            "session has expired",
            "session revoked",
        ).any(detail::contains)
    ) return SessionFailureKind.TERMINAL

    if (status == null || status in listOf(408, 425, 429) || status >= 500) {
        return SessionFailureKind.TRANSIENT
    }
    return if (status in 400..499) SessionFailureKind.TERMINAL else SessionFailureKind.TRANSIENT
}

internal fun sessionRetryDelayMs(attempt: Int): Long =
    listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)[attempt.coerceIn(0, 4)]
