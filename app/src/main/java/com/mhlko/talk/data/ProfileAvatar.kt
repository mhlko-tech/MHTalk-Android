package com.mhlko.talk.data

private const val MAX_ROOM_DATA_AVATAR_BYTES = 11_000
private const val MAX_REMOTE_AVATAR_URL_LENGTH = 1_000

internal fun normalizeRoomAvatar(value: String?): String {
    val avatar = value?.trim().orEmpty()
    if (avatar.isBlank()) return ""

    if (avatar.startsWith("https://", ignoreCase = true) && avatar.length <= MAX_REMOTE_AVATAR_URL_LENGTH) {
        return avatar
    }
    if (avatar.startsWith("data:image/", ignoreCase = true) && avatar.toByteArray().size <= MAX_ROOM_DATA_AVATAR_BYTES) {
        return avatar
    }
    return avatar.takeIf { it.matches(Regex("[\\p{L}\\p{N}]{1,3}")) }.orEmpty()
}

internal fun isImageAvatar(value: String): Boolean {
    val normalized = value.trim().lowercase()
    return normalized.startsWith("data:image/") ||
        normalized.startsWith("content://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("https://")
}

