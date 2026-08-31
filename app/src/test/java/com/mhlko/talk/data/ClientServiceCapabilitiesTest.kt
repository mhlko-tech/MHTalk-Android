package com.mhlko.talk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientServiceCapabilitiesTest {
    @Test
    fun everyShippedRtcProviderHasACompleteCompanionRoute() {
        val providers = listOf(
            "stream",
            "agora",
            "tencent",
            "cloudflare-realtime",
            "100ms",
            "cometchat",
            "whereby",
            "jaas",
            "mirotalk",
            "videosdk",
            "daily",
            "livekit",
        )

        assertTrue(providers.all(ClientServiceCapabilities::supportsCompleteRoute))
        assertEquals("supabase-storage", ClientServiceCapabilities.routeFor("stream")?.fileProvider)
        assertEquals("livekit-data", ClientServiceCapabilities.routeFor("livekit")?.messagingProvider)
        assertEquals("supabase-realtime", ClientServiceCapabilities.routeFor("100ms")?.messagingProvider)
    }
}
