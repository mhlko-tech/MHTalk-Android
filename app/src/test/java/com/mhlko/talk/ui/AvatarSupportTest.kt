package com.mhlko.talk.ui

import com.mhlko.talk.data.isImageAvatar
import com.mhlko.talk.data.normalizeRoomAvatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarSupportTest {
    @Test
    fun recognizesSupportedImageSourcesCaseInsensitively() {
        assertTrue(isImageAvatar("data:image/png;base64,AAAA"))
        assertTrue(isImageAvatar("CONTENT://media/profile/1"))
        assertTrue(isImageAvatar("https://cdn.example.com/avatar.png"))
    }

    @Test
    fun rejectsTextFallbacksAndUntrustedSchemes() {
        assertFalse(isImageAvatar("MH"))
        assertFalse(isImageAvatar("javascript:alert(1)"))
    }

    @Test
    fun keepsHttpsRoomAvatarsAndArabicInitials() {
        assertEquals("https://cdn.example.com/avatar.png", normalizeRoomAvatar("  https://cdn.example.com/avatar.png "))
        assertEquals("م", normalizeRoomAvatar("م"))
        assertEquals("", normalizeRoomAvatar("javascript:alert(1)"))
    }
}
