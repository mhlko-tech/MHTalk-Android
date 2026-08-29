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

internal data class RtcProviderAdapter(
    val providerId: String,
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

    val supportedProviders: List<String>
        get() = adaptersByProvider.keys.toList()

    suspend fun connect(credentials: RoomCredentials): RtcConnectionResult {
        val adapter = adaptersByProvider[credentials.provider]
            ?: error(
                "The selected call provider (${credentials.provider}) " +
                    "is not supported by this app version",
            )
        return adapter.connectHandler.connect(credentials)
    }
}
