package com.mhlko.talk.call

import com.mhlko.talk.data.RoomCredentials

internal sealed interface RtcConnectionResult {
    val roomName: String

    data class Native(override val roomName: String) : RtcConnectionResult
    data class Embedded(
        override val roomName: String,
        val url: String,
    ) : RtcConnectionResult
}
internal fun interface RtcConnectHandler {
    suspend fun connect(credentials: RoomCredentials): RtcConnectionResult
}

/**
 * User-visible media behavior promised by an RTC adapter.
 *
 * MHTalk treats LiveKit's behavior as the contract: muting the microphone must
 * never mute device/screen audio, and provider-owned prebuilt controls must not
 * replace MHTalk's controls. Adapters that cannot satisfy that contract remain
 * compiled in for compatibility, but are not advertised to automatic routing.
 */
internal data class RtcMediaCapabilities(
    val nativeMhtalkControls: Boolean,
    val independentScreenAudio: Boolean,
    val stableCommunicationAudioRoute: Boolean,
    val crossPlatformParity: Boolean,
) {
    val hasLiveKitParity: Boolean
        get() = nativeMhtalkControls && independentScreenAudio && stableCommunicationAudioRoute && crossPlatformParity
}

internal data class RtcProviderAdapter(
    val providerId: String,
    val mediaCapabilities: RtcMediaCapabilities,
    val connectHandler: RtcConnectHandler,
)

/**
 * Contains only transports that are compiled into this APK. The same list is
 * sent to the routing service, preventing it from selecting an unavailable SDK.
 */
internal class RtcAdapterRegistry(adapters: List<RtcProviderAdapter>) {
    private val adaptersByProvider = adapters.associateBy(RtcProviderAdapter::providerId)

    init {
        require(adaptersByProvider.size == adapters.size) { "Duplicate RTC adapter" }
    }

    val routableProviders: List<String>
        get() = adaptersByProvider.values
            .filter { it.mediaCapabilities.hasLiveKitParity }
            .map(RtcProviderAdapter::providerId)

    suspend fun connect(credentials: RoomCredentials): RtcConnectionResult {
        val adapter = adaptersByProvider[credentials.provider]
            ?: error(
                "This app version cannot open the selected room connection",
            )
        require(adapter.mediaCapabilities.hasLiveKitParity) {
            "This room connection cannot provide the full MHTalk media experience on this device"
        }
        return adapter.connectHandler.connect(credentials)
    }
}
