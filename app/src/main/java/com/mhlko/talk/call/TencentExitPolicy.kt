package com.mhlko.talk.call

import java.io.IOException

/** Native onExitRoom: 0 voluntary, 1 removed, 2 dismissed, 3 server failure. */
internal class TencentExitPolicy {
    var terminalFailure: SecurityException? = null
        private set

    val permitsRecovery: Boolean get() = terminalFailure == null

    fun reset() {
        terminalFailure = null
    }

    fun onExit(reason: Int): Throwable {
        if (reason == 3 && permitsRecovery) return IOException("Tencent server connection failed")
        return terminalFailure ?: SecurityException("Tencent ended the room connection (reason $reason)")
            .also { terminalFailure = it }
    }
}
