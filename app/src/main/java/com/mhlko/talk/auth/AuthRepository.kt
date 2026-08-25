package com.mhlko.talk.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.mhlko.talk.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class MHTalkAccount(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
)

sealed interface AuthState {
    data object Unavailable : AuthState
    data object Checking : AuthState
    data object SignedOut : AuthState
    data object Authenticating : AuthState
    data class SignedIn(val account: MHTalkAccount) : AuthState
    data class Failed(val message: String) : AuthState
}

class AuthRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("mhtalk.auth", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    private val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")
    private val _state = MutableStateFlow<AuthState>(if (configured) AuthState.Checking else AuthState.Unavailable)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var refreshLoopStarted = false

    fun accessToken(): String? {
        val token = preferences.getString("access_token", null) ?: return null
        val expiry = preferences.getLong("expires_at", 0L)
        return token.takeIf { expiry > System.currentTimeMillis() + 30_000 }
    }

    suspend fun initialize() {
        if (!configured) {
            _state.value = AuthState.Unavailable
            return
        }
        if (!refreshLoopStarted) {
            refreshLoopStarted = true
            scope.launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    val expiresAt = preferences.getLong("expires_at", 0L)
                    if (preferences.getString("refresh_token", null) != null && expiresAt < System.currentTimeMillis() + 10 * 60_000L) {
                        refreshSession()
                    }
                }
            }
        }
        _state.value = AuthState.Checking
        runCatching {
            if (accessToken() == null && preferences.getString("refresh_token", null) != null) refreshSession()
            if (accessToken() != null) refreshProfile() else _state.value = AuthState.SignedOut
        }.onFailure {
            _state.value = AuthState.Failed(it.message ?: "Could not verify your account")
        }
    }

    fun beginSignIn(provider: String = "google") {
        if (!configured) {
            _state.value = AuthState.Unavailable
            return
        }
        val verifierBytes = ByteArray(48).also(SecureRandom()::nextBytes)
        val verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        preferences.edit().putString("pkce_verifier", verifier).apply()
        _state.value = AuthState.Authenticating
        val url = Uri.parse(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", provider)
            .appendQueryParameter("redirect_to", "mhtalk://auth/callback")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()
        appContext.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun handleDeepLink(uri: Uri?): Boolean {
        if (uri?.scheme != "mhtalk" || uri.host != "auth" || uri.path != "/callback") return false
        val code = uri.getQueryParameter("code") ?: run {
            _state.value = AuthState.Failed(uri.getQueryParameter("error_description") ?: "Sign-in was cancelled")
            return true
        }
        val verifier = preferences.getString("pkce_verifier", null) ?: run {
            _state.value = AuthState.Failed("The sign-in attempt expired. Please try again.")
            return true
        }
        _state.value = AuthState.Authenticating
        runCatching {
            tokenRequest("pkce", JSONObject().put("auth_code", code).put("code_verifier", verifier))
            preferences.edit().remove("pkce_verifier").apply()
            refreshProfile()
        }.onFailure { _state.value = AuthState.Failed(it.message ?: "Sign-in failed") }
        return true
    }

    suspend fun refreshProfile() = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext
        val request = Request.Builder().url("$origin/social/me").header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException(JSONObject(text).optString("error", "Profile unavailable"))
            val profile = JSONObject(text)
            val account = MHTalkAccount(
                id = profile.getString("id"), username = profile.getString("username"),
                displayName = profile.getString("display_name"), avatarUrl = profile.optString("avatar_url").takeIf(String::isNotBlank),
                bio = profile.optString("bio").takeIf(String::isNotBlank),
            )
            preferences.edit().putString("account.id", account.id).putString("account.username", account.username)
                .putString("account.name", account.displayName).putString("account.avatar", account.avatarUrl)
                .putString("account.bio", account.bio).apply()
            _state.value = AuthState.SignedIn(account)
        }
    }

    suspend fun updateProfile(displayName: String, bio: String, avatar: String? = null) = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext
        val account = (_state.value as? AuthState.SignedIn)?.account ?: return@withContext
        val avatarUrl = when {
            avatar == null -> null
            avatar.startsWith("data:image/") -> uploadAvatar(account.id, avatar, token)
            else -> avatar
        }
        val body = JSONObject().put("display_name", displayName.trim().take(60).ifBlank { account.displayName })
            .put("bio", bio.trim().take(160))
        if (avatarUrl != null) body.put("avatar_url", avatarUrl)
        val request = Request.Builder().url("$origin/social/profile").header("Authorization", "Bearer $token")
            .patch(body.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf(String::isNotBlank) ?: "Could not update profile")
        }
        refreshProfile()
    }

    private fun uploadAvatar(userId: String, dataUrl: String, token: String): String {
        val metadataEnd = dataUrl.indexOf(',')
        require(metadataEnd > 5) { "Invalid profile image" }
        val mimeType = dataUrl.substringAfter("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val bytes = Base64.decode(dataUrl.substring(metadataEnd + 1), Base64.DEFAULT)
        require(bytes.size <= 5 * 1024 * 1024) { "Profile image must be 5 MB or smaller" }
        val extension = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        val path = "$userId/avatar.$extension"
        val request = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/profile-avatars/$path")
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer $token")
            .header("x-upsert", "true").put(bytes.toRequestBody(mimeType.toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Could not upload profile image")
        }
        return BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/profile-avatars/$path?v=${System.currentTimeMillis()}"
    }

    suspend fun signOut() {
        val token = accessToken()
        if (token != null) withContext(Dispatchers.IO) {
            val request = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/logout")
                .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null)).build()
            runCatching { client.newCall(request).execute().close() }
        }
        preferences.edit().clear().apply()
        _state.value = if (configured) AuthState.SignedOut else AuthState.Unavailable
    }

    private suspend fun refreshSession() {
        val refresh = preferences.getString("refresh_token", null) ?: return
        runCatching { tokenRequest("refresh_token", JSONObject().put("refresh_token", refresh)) }
            .onFailure {
                preferences.edit().clear().apply()
                _state.value = AuthState.SignedOut
            }
    }

    private suspend fun tokenRequest(grantType: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=$grantType")
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .post(payload.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val body = JSONObject(text.ifBlank { "{}" })
            if (!response.isSuccessful) throw IllegalStateException(body.optString("error_description", body.optString("msg", "Sign-in failed")))
            val expiresIn = body.optLong("expires_in", 3600)
            preferences.edit().putString("access_token", body.getString("access_token"))
                .putString("refresh_token", body.getString("refresh_token"))
                .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000).apply()
        }
    }

    companion object {
        @Volatile private var instance: AuthRepository? = null
        fun get(context: Context): AuthRepository = instance ?: synchronized(this) {
            instance ?: AuthRepository(context).also { instance = it }
        }
    }
}
