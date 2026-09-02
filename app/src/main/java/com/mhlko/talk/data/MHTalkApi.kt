package com.mhlko.talk.data

import com.mhlko.talk.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class RoomCredentials(
    val token: String,
    val attachmentAccessToken: String?,
    val usageAccessToken: String?,
    val identity: String?,
    val screenToken: String?,
    val screenIdentity: String?,
    val roomName: String,
    val provider: String,
    val serverUrl: String,
    val clientKey: String?,
    val subscriptionTier: SubscriptionTier,
    val messagingProvider: String,
    val fileProvider: String,
)
data class PrivateRoom(val roomName: String, val code: String)
data class AttachmentUploadTicket(
    val attachmentId: String,
    val uploadUrl: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
)
data class StoredAttachment(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val downloadUrl: String? = null,
)

class MHTalkApi(private val accessToken: () -> String? = { null }) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")

    suspend fun credentials(
        roomName: String,
        inviteCode: String?,
        supportedRtcProviders: List<String>,
        supportedMessagingProviders: List<String>,
        supportedFileProviders: List<String>,
    ): RoomCredentials = post(
        BuildConfig.TOKEN_ENDPOINT,
        JSONObject().put("roomName", roomName).apply {
            if (!inviteCode.isNullOrBlank()) put("inviteCode", inviteCode.trim().uppercase())
            put("clientPlatform", "android")
            put("clientVersion", BuildConfig.VERSION_NAME)
            put("capabilitiesVersion", 2)
            put(
                "supportedRtcProviders",
                org.json.JSONArray().apply { supportedRtcProviders.forEach(::put) },
            )
            put(
                "supportedMessagingProviders",
                org.json.JSONArray().apply { supportedMessagingProviders.forEach(::put) },
            )
            put(
                "supportedFileProviders",
                org.json.JSONArray().apply { supportedFileProviders.forEach(::put) },
            )
        },
    ).let { payload ->
        RoomCredentials(
            token = payload.requireString("token"),
            attachmentAccessToken = payload.optString("attachmentAccessToken").takeIf(String::isNotBlank),
            usageAccessToken = payload.optString("usageAccessToken").takeIf(String::isNotBlank),
            identity = payload.optString("identity").takeIf(String::isNotBlank),
            screenToken = payload.optString("screenToken").takeIf(String::isNotBlank),
            screenIdentity = payload.optString("screenIdentity").takeIf(String::isNotBlank),
            roomName = payload.requireString("roomName"),
            provider = payload.optJSONObject("routing")?.optJSONObject("rtc")?.optString("provider")
                ?.takeIf(String::isNotBlank) ?: payload.optString("provider", "livekit"),
            serverUrl = payload.optJSONObject("routing")?.optJSONObject("rtc")?.optString("serverUrl")
                ?.takeIf(String::isNotBlank) ?: payload.optString("serverUrl").takeIf(String::isNotBlank)
                ?: BuildConfig.LIVEKIT_URL,
            clientKey = payload.optJSONObject("routing")?.optJSONObject("rtc")
                ?.optString("clientKey")?.takeIf(String::isNotBlank),
            subscriptionTier = subscriptionTierFromWire(
                payload.optJSONObject("subscription")?.optString("tier"),
            ),
            messagingProvider = payload.optJSONObject("routing")?.optJSONObject("messaging")
                ?.optString("provider")?.takeIf(String::isNotBlank) ?: "livekit-data",
            fileProvider = payload.optJSONObject("routing")?.optJSONObject("files")
                ?.optString("provider")?.takeIf(String::isNotBlank) ?: "livekit-stream",
        ).also { credentials ->
            val expected = ClientServiceCapabilities.routeFor(credentials.provider)
                ?: error("The server selected an unsupported realtime provider")
            require(
                credentials.messagingProvider == expected.messagingProvider &&
                    credentials.fileProvider == expected.fileProvider,
            ) { "The server selected an incompatible room service route" }
        }
    }

    suspend fun createPrivateRoom(): PrivateRoom = post(
        "$origin/private-room",
        JSONObject(),
    ).let { payload ->
        PrivateRoom(
            roomName = payload.requireString("roomName"),
            code = payload.requireString("code"),
        )
    }

    suspend fun mainCount(): Int = post(
        "$origin/room-count",
        JSONObject().put("roomName", "Main"),
    ).optInt("count", 0)

    suspend fun membershipBadges(ids: List<String>): Map<String, SubscriptionTier> {
        if (ids.isEmpty()) return emptyMap()
        val payload = post(
            "$origin/social/badges",
            JSONObject().put("ids", org.json.JSONArray().apply { ids.distinct().take(50).forEach(::put) }),
        )
        val badges = payload.optJSONObject("badges") ?: return emptyMap()
        return badges.keys().asSequence().associateWith { id -> subscriptionTierFromWire(badges.optString(id)) }
    }

    suspend fun moderate(text: String): String = post(
        "$origin/moderate",
        JSONObject().put("roomName", "Main").put("text", text),
    ).optString("text", text)

    suspend fun report(roomName: String, reporterIdentity: String, targetIdentity: String, messageId: String?, content: String?) {
        post(
            "$origin/moderation/report",
            JSONObject()
                .put("roomName", roomName)
                .put("reporterIdentity", reporterIdentity)
                .put("targetIdentity", targetIdentity)
                .put("messageId", messageId ?: JSONObject.NULL)
                .put("content", content?.take(2_000) ?: JSONObject.NULL),
        )
    }

    suspend fun reportRtcUsage(
        usageAccessToken: String,
        reportId: String,
        measuredFrom: String,
        measuredTo: String,
        leaving: Boolean,
    ) {
        post(
            "$origin/rtc/usage",
            JSONObject()
                .put("usageAccessToken", usageAccessToken)
                .put("reportId", reportId)
                .put("measuredFrom", measuredFrom)
                .put("measuredTo", measuredTo)
                .put("leaving", leaving),
        )
    }

    suspend fun attachmentUploadTicket(
        roomAccessToken: String,
        fileName: String,
        mimeType: String,
        size: Long,
    ): AttachmentUploadTicket = post(
        "$origin/attachments/upload-ticket",
        JSONObject()
            .put("roomAccessToken", roomAccessToken)
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("size", size),
    ).let { payload ->
        AttachmentUploadTicket(
            attachmentId = payload.requireString("attachmentId"),
            uploadUrl = payload.requireString("uploadUrl"),
            fileName = payload.requireString("fileName"),
            mimeType = payload.requireString("mimeType"),
            size = payload.optLong("size"),
        )
    }

    suspend fun completeAttachment(roomAccessToken: String, attachmentId: String): StoredAttachment = post(
        "$origin/attachments/complete",
        JSONObject().put("roomAccessToken", roomAccessToken).put("attachmentId", attachmentId),
    ).toStoredAttachment()

    suspend fun attachmentDownloadTicket(roomAccessToken: String, attachmentId: String): StoredAttachment = post(
        "$origin/attachments/download-ticket",
        JSONObject().put("roomAccessToken", roomAccessToken).put("attachmentId", attachmentId),
    ).toStoredAttachment()

    suspend fun deleteAttachment(roomAccessToken: String, attachmentId: String) {
        post(
            "$origin/attachments/delete",
            JSONObject().put("roomAccessToken", roomAccessToken).put("attachmentId", attachmentId),
        )
    }

    suspend fun uploadSignedAttachment(
        uploadUrl: String,
        mimeType: String,
        size: Long,
        openStream: () -> InputStream,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val mediaType = mimeType.toMediaType()
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                openStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var uploaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        uploaded += count
                        require(uploaded <= size) { "Attachment changed while it was uploading" }
                        onProgress((uploaded.toFloat() / size.coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                    require(uploaded == size) { "Attachment size changed while it was uploading" }
                }
            }
        }
        val request = Request.Builder()
            .url(uploadUrl)
            .header("x-upsert", "false")
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Attachment storage rejected the upload")
        }
    }

    private suspend fun post(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { accessToken()?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Connection service unavailable")
            }
            JSONObject(text)
        }
    }

    private fun JSONObject.requireString(key: String): String =
        optString(key).takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Invalid server response")

    private fun JSONObject.toStoredAttachment() = StoredAttachment(
        attachmentId = requireString("attachmentId"),
        fileName = requireString("fileName"),
        mimeType = requireString("mimeType"),
        size = optLong("size"),
        downloadUrl = optString("downloadUrl").takeIf(String::isNotBlank),
    )
}
