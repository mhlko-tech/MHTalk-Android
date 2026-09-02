package com.mhlko.talk.ui

import io.getstream.video.android.core.call.state.LeaveCall
import io.getstream.video.android.core.call.state.ToggleCamera
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCallActionTest {
    @Test
    fun leaveActionIsHandledByMHTalkSession() {
        assertTrue(isStreamLeaveAction(LeaveCall))
        assertFalse(isStreamLeaveAction(ToggleCamera(true)))
    }
}
