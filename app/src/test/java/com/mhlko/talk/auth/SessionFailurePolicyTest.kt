package com.mhlko.talk.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFailurePolicyTest {
    @Test fun keepsSessionForTransientFailures() {
        listOf(null, 408, 425, 429, 500, 503).forEach { status ->
            assertEquals(SessionFailureKind.TRANSIENT, sessionFailureKind(status, null, "temporary failure"))
        }
    }

    @Test fun endsSessionOnlyAfterTerminalRejection() {
        assertEquals(SessionFailureKind.TERMINAL, sessionFailureKind(400, "refresh_token_not_found", "Invalid Refresh Token"))
        assertEquals(SessionFailureKind.TERMINAL, sessionFailureKind(401, null, "Session not found"))
        assertEquals(SessionFailureKind.TERMINAL, sessionFailureKind(403, null, "Session revoked"))
    }

    @Test fun usesBoundedRetryBackoff() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 60_000L), (0..5).map(::sessionRetryDelayMs))
    }
}
