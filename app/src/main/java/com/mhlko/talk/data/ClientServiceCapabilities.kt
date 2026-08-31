package com.mhlko.talk.data

data class CompanionServiceRoute(
    val messagingProvider: String,
    val fileProvider: String,
)

object ClientServiceCapabilities {
    val messagingProviders = listOf(
        "stream-events",
        "agora-data",
        "tencent-data",
        "cloudflare-realtime",
        "supabase-realtime",
        "whereby-chat",
        "daily-chat",
        "livekit-data",
    )

    val fileProviders = listOf(
        "supabase-storage",
        "whereby-prebuilt",
        "daily-prebuilt",
        "livekit-stream",
    )

    fun routeFor(rtcProvider: String) = when (rtcProvider) {
        "stream" -> CompanionServiceRoute("stream-events", "supabase-storage")
        "agora" -> CompanionServiceRoute("agora-data", "supabase-storage")
        "tencent" -> CompanionServiceRoute("tencent-data", "supabase-storage")
        "cloudflare-realtime" -> CompanionServiceRoute("cloudflare-realtime", "supabase-storage")
        "100ms", "cometchat", "jaas", "mirotalk", "videosdk" ->
            CompanionServiceRoute("supabase-realtime", "supabase-storage")
        "whereby" -> CompanionServiceRoute("whereby-chat", "whereby-prebuilt")
        "daily" -> CompanionServiceRoute("daily-chat", "daily-prebuilt")
        "livekit" -> CompanionServiceRoute("livekit-data", "livekit-stream")
        else -> null
    }

    fun supportsCompleteRoute(rtcProvider: String): Boolean = routeFor(rtcProvider)?.let { route ->
        route.messagingProvider in messagingProviders && route.fileProvider in fileProviders
    } == true
}
