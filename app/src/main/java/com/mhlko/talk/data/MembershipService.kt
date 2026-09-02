package com.mhlko.talk.data

import android.content.Context
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.auth.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MembershipService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class SyncResult(val status: String, val tier: SubscriptionTier, val pending: Boolean)

    suspend fun createLavaSession(context: Context, accessToken: String, planId: String = "plus"): String = withContext(Dispatchers.IO) {
        require(planId == "plus" || planId == "pro") { "Unsupported membership plan" }
        val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")
        val request = Request.Builder()
            .url("$origin/subscription/lava/start")
            .header("Authorization", "Bearer $accessToken")
            .post(JSONObject().put("planId", planId).toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = runCatching { JSONObject(response.body.string()) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                throw IllegalStateException(payload.optString("error", "LAVA membership is temporarily unavailable"))
            }
            val url = payload.optString("subscriptionUrl").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("LAVA membership returned an invalid link")
            val token = payload.optString("desktopToken").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("LAVA membership returned an invalid token")
            SecureTokenStore(context.applicationContext).put(TOKEN_KEY, token)
            url
        }
    }

    suspend fun createPatreonLink(context: Context, accessToken: String): String = withContext(Dispatchers.IO) {
        val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")
        val request = Request.Builder()
            .url("$origin/subscription/patreon/start")
            .header("Authorization", "Bearer $accessToken")
            .post("{}".toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = runCatching { JSONObject(response.body.string()) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(payload.optString("error", "Patreon linking is temporarily unavailable"))
            val url = payload.optString("linkUrl").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Patreon membership returned an invalid link")
            val token = payload.optString("desktopToken").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Patreon membership returned an invalid token")
            SecureTokenStore(context.applicationContext).put(TOKEN_KEY, token)
            url
        }
    }

    suspend fun sync(context: Context, accessToken: String): SyncResult? = withContext(Dispatchers.IO) {
        val store = SecureTokenStore(context.applicationContext)
        val token = store.get(TOKEN_KEY) ?: store.get(LEGACY_TOKEN_KEY) ?: return@withContext null
        val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")
        val request = Request.Builder()
            .url("$origin/subscription/membership/sync")
            .header("Authorization", "Bearer $accessToken")
            .post(JSONObject().put("membershipToken", token).toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = runCatching { JSONObject(response.body.string()) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(payload.optString("error", "Could not verify the LAVA membership"))
            SyncResult(
                status = payload.optString("status", "pending"),
                tier = subscriptionTierFromWire(payload.optString("tier")),
                pending = payload.optBoolean("pending"),
            )
        }
    }

    private const val TOKEN_KEY = "mhtalk.membership.token"
    private const val LEGACY_TOKEN_KEY = "mhtalk.membership.lava-token"
}
