package com.mhlko.talk.auth

import android.content.Context
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.data.SubscriptionTier
import com.mhlko.talk.data.subscriptionTierFromWire
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FriendProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val online: Boolean = false,
    val isFriend: Boolean = false,
    val subscriptionTier: SubscriptionTier = SubscriptionTier.Free,
)
data class IncomingFriendRequest(val requestId: String, val profile: FriendProfile)
data class RoomInvite(val id: String, val senderId: String, val roomName: String, val inviteCode: String?)
data class SocialState(
    val friends: List<FriendProfile> = emptyList(),
    val requests: List<IncomingFriendRequest> = emptyList(),
    val invite: RoomInvite? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class SocialRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val auth = AuthRepository.get(context)
    private val origin = BuildConfig.TOKEN_ENDPOINT.substringBefore("/livekit/token")
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = _state.asStateFlow()
    private var presence: WebSocket? = null
    private var reconnect = true

    init {
        scope.launch {
            auth.state.collect { accountState ->
                if (accountState is AuthState.SignedIn) refresh()
                else {
                    reconnect = false
                    presence?.close(1000, "Signed out")
                    presence = null
                    _state.value = SocialState()
                }
            }
        }
    }

    suspend fun refresh() {
        if (auth.accessToken() == null) return
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val friends = getArray("/social/friends").map(::profile)
            val requests = getArray("/social/requests").map {
                IncomingFriendRequest(it.getString("request_id"), profile(it))
            }
            val online = _state.value.friends.filter(FriendProfile::online).mapTo(mutableSetOf(), FriendProfile::id)
            _state.value = _state.value.copy(
                friends = friends.map { it.copy(online = it.id in online, isFriend = true) },
                requests = requests,
                loading = false,
                error = null,
            )
            connectPresence()
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                FirebaseMessaging.getInstance().register()
            }
        }.onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Could not load friends") }
    }

    suspend fun search(query: String): List<FriendProfile> =
        getArray("/social/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}").map { profile(it).copy(isFriend = it.optBoolean("is_friend")) }

    suspend fun sendFriendRequest(targetId: String) { post("/social/friend-request", JSONObject().put("targetId", targetId)) }
    suspend fun respond(requestId: String, accept: Boolean) {
        post("/social/friend-response", JSONObject().put("requestId", requestId).put("accept", accept)); refresh()
    }
    suspend fun removeFriend(friendId: String) {
        post("/social/friend-remove", JSONObject().put("friendId", friendId)); refresh()
    }
    suspend fun invite(friendId: String): RoomInvite {
        val body = post("/social/invite", JSONObject().put("targetId", friendId).put("private", true))
        return invite(body)
    }
    suspend fun registerDeviceToken(token: String) {
        if (auth.accessToken() != null) post("/social/device-token", JSONObject().put("token", token).put("platform", "android"))
    }
    suspend fun loadInvite(inviteId: String) {
        val value = getObject("/social/invite/${java.net.URLEncoder.encode(inviteId, "UTF-8")}")
        _state.value = _state.value.copy(invite = invite(value))
    }
    fun clearInvite() { _state.value = _state.value.copy(invite = null) }

    private suspend fun connectPresence() {
        if (presence != null || auth.accessToken() == null) return
        reconnect = true
        val ticket = post("/presence/ticket", JSONObject()).getString("ticket")
        val socketUrl = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/presence?ticket=$ticket"
        presence = client.newWebSocket(Request.Builder().url(socketUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = watch(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) { handlePresence(text) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnectLater(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { reconnectLater(webSocket) }
        })
    }
    private fun reconnectLater(socket: WebSocket) {
        if (presence !== socket) return
        presence = null
        if (reconnect && auth.accessToken() != null) scope.launch { delay(3_000); runCatching { connectPresence() } }
    }
    private fun watch(socket: WebSocket) {
        socket.send(JSONObject().put("type", "watch").put("friendIds", JSONArray(_state.value.friends.map(FriendProfile::id))).toString())
    }
    private fun handlePresence(text: String) {
        runCatching {
            val event = JSONObject(text)
            when (event.optString("type")) {
                "presence_snapshot" -> {
                    val online = event.getJSONArray("online").toStringList().toSet()
                    _state.value = _state.value.copy(friends = _state.value.friends.map { it.copy(online = it.id in online) })
                }
                "presence" -> {
                    val id = event.getString("userId")
                    val online = event.getBoolean("online")
                    _state.value = _state.value.copy(friends = _state.value.friends.map { if (it.id == id) it.copy(online = online) else it })
                }
                "invite" -> _state.value = _state.value.copy(invite = invite(event.getJSONObject("invite")))
            }
        }
    }

    private suspend fun getArray(path: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val array = JSONArray(execute(Request.Builder().url(origin + path).get().authorized().build()))
        (0 until array.length()).map(array::getJSONObject)
    }
    private suspend fun getObject(path: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(execute(Request.Builder().url(origin + path).get().authorized().build()))
    }
    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(execute(Request.Builder().url(origin + path).post(body.toString().toRequestBody(jsonType)).authorized().build()).ifBlank { "{}" })
    }
    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf(String::isNotBlank) ?: "Social service unavailable")
        text
    }
    private fun Request.Builder.authorized(): Request.Builder = apply {
        auth.accessToken()?.let { header("Authorization", "Bearer $it") } ?: throw IllegalStateException("Sign in is required")
        header("Content-Type", "application/json")
    }
    private fun profile(value: JSONObject) = FriendProfile(
        id = value.getString("id"), username = value.getString("username"), displayName = value.getString("display_name"),
        avatarUrl = value.optString("avatar_url").takeIf(String::isNotBlank), bio = value.optString("bio").takeIf(String::isNotBlank),
        subscriptionTier = subscriptionTierFromWire(value.optString("subscription_tier")),
    )
    private fun invite(value: JSONObject) = RoomInvite(
        id = value.getString("id"), senderId = value.getString("senderId"), roomName = value.getString("roomName"),
        inviteCode = value.optString("inviteCode").takeIf(String::isNotBlank),
    )
    private fun JSONArray.toStringList() = (0 until length()).map(::getString)

    companion object {
        @Volatile private var instance: SocialRepository? = null
        fun get(context: Context): SocialRepository = instance ?: synchronized(this) {
            instance ?: SocialRepository(context.applicationContext).also { instance = it }
        }
    }
}
