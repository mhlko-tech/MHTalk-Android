package com.mhlko.talk.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthRulesTest {
    @Test fun acceptsValidUsernames() {
        listOf("husam_98", "User123", "abc").forEach { assertNull(AuthRules.usernameError(it)) }
    }

    @Test fun rejectsInvalidAndReservedUsernames() {
        assertEquals("Username must be 3-32 letters, numbers, or underscores", AuthRules.usernameError("ab"))
        assertEquals("Username must be 3-32 letters, numbers, or underscores", AuthRules.usernameError("bad name"))
        assertEquals("Username is unavailable", AuthRules.usernameError("MHTalk"))
        assertEquals("Username is unavailable", AuthRules.usernameError("admin"))
    }

    @Test fun enforcesPasswordLength() {
        assertEquals("Password must be at least 10 characters", AuthRules.passwordError("short"))
        assertNull(AuthRules.passwordError("long-enough-password"))
        assertEquals("Password must be 128 characters or fewer", AuthRules.passwordError("x".repeat(129)))
    }
}
