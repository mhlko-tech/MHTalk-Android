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
    data class AwaitingVerification(val email: String) : AuthState
    data object PasswordRecovery : AuthState
    data class SignedIn(val account: MHTalkAccount) : AuthState
    data class Failed(val message: String) : AuthState
}

class AuthRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("mhtalk.auth", Context.MODE_PRIVATE)
    private val secrets = SecureTokenStore(appContext)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    private val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")
    private val _state = MutableStateFlow<AuthState>(if (configured) AuthState.Checking else AuthState.Unavailable)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var refreshLoopStarted = false

    fun accessToken(): String? {
        val token = secrets.get("access_token") ?: return null
        val expiry = preferences.getLong("expires_at", 0L)
        return token.takeIf { expiry > System.currentTimeMillis() + 30_000 }
    }

    suspend fun initialize() {
        if (!configured) {
            _state.value = AuthState.Unavailable
            return
        }
        if (!refreshLoopStarted) {
            migrateLegacySecrets()
            refreshLoopStarted = true
            scope.launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    val expiresAt = preferences.getLong("expires_at", 0L)
                    if (secrets.get("refresh_token") != null && expiresAt < System.currentTimeMillis() + 10 * 60_000L) {
                        refreshSession()
                    }
                }
            }
        }
        _state.value = AuthState.Checking
        runCatching {
            if (accessToken() == null && secrets.get("refresh_token") != null) refreshSession()
            if (accessToken() != null) refreshProfile() else _state.value = AuthState.SignedOut
        }.onFailure {
            _state.value = AuthState.Failed(it.message ?: "Could not verify your account")
        }
    }

    suspend fun login(identifier: String, password: String) = withContext(Dispatchers.IO) {
        _state.value = AuthState.Authenticating
        runCatching {
            val body = gatewayPost("/auth/login", JSONObject().put("identifier", identifier.trim()).put("password", password))
            storeTokens(body.getString("access_token"), body.getString("refresh_token"), body.optLong("expires_in", 3600))
            refreshProfile()
        }.onFailure { _state.value = AuthState.Failed(it.message ?: "Sign-in failed") }
    }

    suspend fun usernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$origin/auth/username-available?username=${Uri.encode(username.trim())}").build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException(JSONObject(text.ifBlank { "{}" }).optString("error", "Could not check username"))
            JSONObject(text).optBoolean("available")
        }
    }

    suspend fun register(username: String, displayName: String, email: String, password: String) = withContext(Dispatchers.IO) {
        _state.value = AuthState.Authenticating
        runCatching {
            gatewayPost(
                "/auth/register",
                JSONObject().put("username", username.trim()).put("displayName", displayName.trim())
                    .put("email", email.trim()).put("password", password),
            )
            _state.value = AuthState.AwaitingVerification(email.trim())
        }.onFailure { _state.value = AuthState.Failed(it.message ?: "Could not create account") }
    }

    suspend fun requestPasswordReset(identifier: String) = withContext(Dispatchers.IO) {
        gatewayPost("/auth/forgot-password", JSONObject().put("identifier", identifier.trim()))
    }

    suspend fun resendVerification(email: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/resend?redirect_to=" + Uri.encode("mhtalk://auth/callback"))
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .post(JSONObject().put("type", "signup").put("email", email.trim()).toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Could not resend verification email")
        }
    }

    suspend fun completePasswordRecovery(password: String) = withContext(Dispatchers.IO) {
        val token = accessToken() ?: throw IllegalStateException("The recovery link is invalid or expired")
        val request = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/user")
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer $token")
            .put(JSONObject().put("password", password).toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException(JSONObject(text.ifBlank { "{}" }).optString("msg", "Could not update password"))
        }
        refreshProfile()
    }

    suspend fun cancelPasswordRecovery() { signOut() }

    private fun gatewayPost(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url("$origin$path").post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val value = JSONObject(text.ifBlank { "{}" })
            if (!response.isSuccessful) throw IllegalStateException(value.optString("error", "Account service request failed"))
            return value
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
        secrets.put("pkce_verifier", verifier)
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
        if (uri?.scheme != "mhtalk" || uri.host != "auth" || uri.path !in setOf("/callback", "/reset")) return false
        val recovery = uri.path == "/reset"
        val fragment = uri.fragment?.split('&')?.mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) Uri.decode(pieces[0]) to Uri.decode(pieces[1]) else null
        }?.toMap().orEmpty()
        val fragmentAccess = fragment["access_token"]
        val fragmentRefresh = fragment["refresh_token"]
        if (fragmentAccess != null && fragmentRefresh != null) {
            storeTokens(fragmentAccess, fragmentRefresh, fragment["expires_in"]?.toLongOrNull() ?: 3600)
            if (recovery) _state.value = AuthState.PasswordRecovery else refreshProfile()
            return true
        }
        val code = uri.getQueryParameter("code") ?: run {
            _state.value = AuthState.Failed(uri.getQueryParameter("error_description") ?: "Sign-in was cancelled")
            return true
        }
        val verifier = secrets.get("pkce_verifier") ?: run {
            _state.value = AuthState.Failed("The sign-in attempt expired. Please try again.")
            return true
        }
        _state.value = AuthState.Authenticating
        runCatching {
            tokenRequest("pkce", JSONObject().put("auth_code", code).put("code_verifier", verifier))
            secrets.remove("pkce_verifier")
            if (recovery) _state.value = AuthState.PasswordRecovery else refreshProfile()
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
        secrets.clear()
        _state.value = if (configured) AuthState.SignedOut else AuthState.Unavailable
    }

    private suspend fun refreshSession() {
        val refresh = secrets.get("refresh_token") ?: return
        runCatching { tokenRequest("refresh_token", JSONObject().put("refresh_token", refresh)) }
            .onFailure {
                preferences.edit().clear().apply()
                secrets.clear()
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
            storeTokens(body.getString("access_token"), body.getString("refresh_token"), expiresIn)
        }
    }

    private fun storeTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        secrets.put("access_token", accessToken)
        secrets.put("refresh_token", refreshToken)
        preferences.edit().putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000).apply()
    }

    private fun migrateLegacySecrets() {
        val access = preferences.getString("access_token", null)
        val refresh = preferences.getString("refresh_token", null)
        val verifier = preferences.getString("pkce_verifier", null)
        if (access != null) secrets.put("access_token", access)
        if (refresh != null) secrets.put("refresh_token", refresh)
        if (verifier != null) secrets.put("pkce_verifier", verifier)
        if (access != null || refresh != null || verifier != null) {
            preferences.edit().remove("access_token").remove("refresh_token").remove("pkce_verifier").apply()
        }
    }

    companion object {
        @Volatile private var instance: AuthRepository? = null
        fun get(context: Context): AuthRepository = instance ?: synchronized(this) {
            instance ?: AuthRepository(context).also { instance = it }
        }
    }
}
