package com.mhlko.talk.ui

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
}
