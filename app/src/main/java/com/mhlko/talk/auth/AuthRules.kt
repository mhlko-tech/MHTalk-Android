package com.mhlko.talk.auth

object AuthRules {
    private val usernamePattern = Regex("^[A-Za-z0-9_]{3,32}$")
    private val reserved = setOf(
        "admin", "administrator", "api", "bot", "everyone", "help", "here",
        "mhlko", "mhtalk", "moderator", "official", "root", "security",
        "staff", "support", "system", "verified",
    )

    fun usernameError(value: String): String? = when {
        !usernamePattern.matches(value.trim()) -> "Username must be 3-32 letters, numbers, or underscores"
        value.trim().lowercase() in reserved -> "Username is unavailable"
        else -> null
    }

    fun passwordError(value: String): String? = when {
        value.length < 10 -> "Password must be at least 10 characters"
        value.length > 128 -> "Password must be 128 characters or fewer"
        else -> null
    }
}
