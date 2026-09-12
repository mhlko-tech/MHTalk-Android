package com.mhlko.talk.call

import com.mhlko.talk.data.MHTalkApiException
import com.mhlko.talk.data.RoomCredentials
import com.mhlko.talk.data.SubscriptionTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcFailoverTest {
    @Test fun repeatedRuntimeOutagesStopAfterTwoRecoveryCycles() {
        val budget = RtcRecoveryBudget()
        budget.reset(1_000)
        assertTrue(budget.consume(1_001))
        assertTrue(budget.consume(1_002))
        assertFalse(budget.consume(1_003))
        assertFalse(budget.consume(180_999))
        assertTrue(budget.consume(181_000))
    }

    @Test fun explicitRejoinResetsTheRecoveryBudget() {
        val budget = RtcRecoveryBudget()
        budget.consume(1)
        budget.consume(2)
        assertFalse(budget.consume(3))
        budget.reset(4)
        assertTrue(budget.consume(4))
    }

    private fun allocation(provider: String) = RoomCredentials(
        token = "test-token", attachmentAccessToken = null, usageAccessToken = "usage-$provider",
        identity = "test-user", screenToken = null, screenIdentity = null, roomName = "test-room",
        provider = provider, serverUrl = "wss://test.invalid", clientKey = "test-key",
        subscriptionTier = SubscriptionTier.Free, messagingProvider = "livekit-data", fileProvider = "livekit-stream",
    )

    @Test fun gatewayFailureReleasesBeforeRequestingAnotherProvider() = runBlocking {
        val events = mutableListOf<String>()
        val result = connectWithRtcFailover(
            providers = listOf("agora", "livekit"),
            credentials = { excluded ->
                events += "request:${excluded.joinToString()}"
                allocation(if (excluded.isEmpty()) "agora" else "livekit")
            },
            connect = {
                events += "connect:${it.provider}"
                if (it.provider == "agora") error("CAN_NOT_GET_GATEWAY_SERVER")
                RtcConnectionResult.Native(it.roomName)
            },
            release = { events += "release:${it.provider}" },
        )
        assertEquals("livekit", result.first.provider)
        assertEquals(listOf("request:", "connect:agora", "release:agora", "request:agora", "connect:livekit"), events)
    }

    @Test fun permissionAndAuthenticationFailuresDoNotSwitchProviders() = runBlocking {
        for (failure in listOf(SecurityException("Microphone permission denied"), IllegalStateException("Invalid token"))) {
            var requests = 0
            var releases = 0
            val result = runCatching {
                connectWithRtcFailover(
                    listOf("agora", "livekit"),
                    credentials = { requests++; allocation("agora") },
                    connect = { throw failure },
                    release = { releases++ },
                )
            }
            assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(failure.message.orEmpty()))
            assertEquals(1, requests)
            assertEquals(1, releases)
        }
    }

    @Test fun cancellationReleasesThePendingAllocationAndDoesNotRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var requests = 0
        var releases = 0
        val job = launch {
            connectWithRtcFailover(
                listOf("agora", "livekit"),
                credentials = { requests++; allocation("agora") },
                connect = { entered.complete(Unit); delay(Long.MAX_VALUE); RtcConnectionResult.Native(it.roomName) },
                release = { delay(1); releases++ },
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, requests)
        assertEquals(1, releases)
    }

    @Test fun hangingConnectTimesOutAndTriesTheAlternative() = runBlocking {
        val result = connectWithRtcFailover(
            listOf("agora", "livekit"), connectTimeoutMillis = 20,
            credentials = { allocation(if (it.isEmpty()) "agora" else "livekit") },
            connect = { if (it.provider == "agora") delay(Long.MAX_VALUE); RtcConnectionResult.Native(it.roomName) },
            release = {},
        )
        assertEquals("livekit", result.first.provider)
    }

    @Test fun unsupportedOrRepeatedProviderIsReleasedAndRejected() = runBlocking {
        var releases = 0
        var connects = 0
        val result = runCatching {
            connectWithRtcFailover(
                listOf("agora", "livekit"), initiallyExcluded = listOf("agora"),
                credentials = { allocation("agora") },
                connect = { connects++; RtcConnectionResult.Native(it.roomName) },
                release = { releases++ },
            )
        }
        assertTrue(result.isFailure)
        assertEquals(0, connects)
        assertEquals(1, releases)
    }

    @Test fun brokerRejectsTerminalAuthenticationWithoutTryingAnAdapter() = runBlocking {
        val rejection = MHTalkApiException(401, "UNAUTHORIZED", "Sign in again")
        val result = runCatching {
            connectWithRtcFailover(
                listOf("agora", "livekit"),
                credentials = { throw rejection },
                connect = { error("Must not connect") },
                release = { error("No allocation was issued") },
            )
        }
        assertEquals(rejection, result.exceptionOrNull())
    }

    @Test fun attemptsAreBoundedEvenWhenManyAdaptersExist() = runBlocking {
        val providers = listOf("agora", "tencent", "livekit", "fourth")
        var requests = 0
        val result = runCatching {
            connectWithRtcFailover(
                providers,
                credentials = { excluded -> requests++; allocation(providers.first { it !in excluded }) },
                connect = { error("Network unavailable") }, release = {},
            )
        }
        assertTrue(result.isFailure)
        assertEquals(3, requests)
    }

    @Test fun lockedRoomWaitsForOtherParticipantsInsteadOfSplitting() = runBlocking {
        var requests = 0
        val result = waitForRtcRoomHandoff(retryDelayMillis = 1) {
            if (++requests < 3) throw MHTalkApiException(409, "RTC_ROOM_PROVIDER_LOCKED", "Room occupied")
            "joined"
        }
        assertEquals("joined", result)
        assertEquals(3, requests)
    }

    @Test fun lockedRoomWaitHasAHardDeadline() = runBlocking {
        val result = runCatching {
            waitForRtcRoomHandoff(timeoutMillis = 25, retryDelayMillis = 5) {
                throw MHTalkApiException(409, "RTC_ROOM_PROVIDER_LOCKED", "Room occupied")
            }
        }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test fun failureClassificationDoesNotTreatUnknownErrorsAsOutages() {
        assertFalse(canRetryRtcConnection(IllegalStateException("Unsupported media configuration")))
        assertFalse(canRetryRtcConnection(IllegalStateException("Network authentication failed")))
        assertTrue(canRetryRtcConnection(IllegalStateException("CAN_NOT_GET_GATEWAY_SERVER")))
        assertTrue(canRetryRtcConnection(IllegalStateException("ICE failed")))
    }
}
