package com.mhlko.talk.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentExitPolicyTest {
    @Test fun removalAndRoomDismissalStayTerminalAfterGenericNetworkCallbacks() {
        for (reason in listOf(1, 2)) {
            val policy = TencentExitPolicy()
            val failure = policy.onExit(reason)
            assertFalse(canRetryRtcConnection(failure))
            assertFalse(policy.permitsRecovery)
            assertSame(failure, policy.onExit(3))
            assertFalse(policy.permitsRecovery)
        }
    }

    @Test fun serverFailureCanRecoverAndExplicitJoinResetsTerminalLatch() {
        val policy = TencentExitPolicy()
        assertTrue(canRetryRtcConnection(policy.onExit(3)))
        assertTrue(policy.permitsRecovery)
        policy.onExit(1)
        policy.reset()
        assertTrue(policy.permitsRecovery)
        assertTrue(canRetryRtcConnection(policy.onExit(3)))
    }
}
