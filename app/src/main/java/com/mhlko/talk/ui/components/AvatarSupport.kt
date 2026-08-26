package com.mhlko.talk.ui

internal fun isImageAvatar(value: String): Boolean {
    val normalized = value.lowercase()
    return normalized.startsWith("data:image/") ||
        normalized.startsWith("content://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("http://") ||
        normalized.startsWith("https://")
}
