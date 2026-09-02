package com.mhlko.talk.call

import org.junit.Assert.assertEquals
import org.junit.Test

class RtcAdapterRegistryTest {
    @Test
    fun automaticRoutingAdvertisesOnlyLiveKitParityAdapters() {
        val parity = RtcMediaCapabilities(
            nativeMhtalkControls = true,
            independentScreenAudio = true,
            stableCommunicationAudioRoute = true,
            crossPlatformParity = true,
        )
        val mixedAudio = parity.copy(independentScreenAudio = false)
        val embedded = parity.copy(nativeMhtalkControls = false)
        val registry = RtcAdapterRegistry(
            listOf(
                RtcProviderAdapter("agora", parity) { error("not called") },
                RtcProviderAdapter("tencent", parity) { error("not called") },
                RtcProviderAdapter("stream", mixedAudio) { error("not called") },
                RtcProviderAdapter("jaas", embedded) { error("not called") },
                RtcProviderAdapter("livekit", parity) { error("not called") },
            ),
        )

        assertEquals(listOf("agora", "tencent", "livekit"), registry.routableProviders)
    }
}
