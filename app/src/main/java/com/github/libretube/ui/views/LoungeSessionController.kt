package com.github.libretube.ui.views

import androidx.lifecycle.LifecycleCoroutineScope
import com.github.libretube.sender.LoungeSender
import com.github.libretube.sender.NowPlayingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

/**
 * Lightweight controller for lounge reachability polling.
 * Keeps coroutines and failure counting out of the view layer.
 */
class LoungeSessionController(
    private val loungeSender: LoungeSender
) {
    private var reachabilityJob: Job? = null
    private var heartbeatJob: Job? = null
    private var pingJob: Job? = null

    fun startReachability(
        scope: LifecycleCoroutineScope,
        onReachabilityUpdate: (reachable: Boolean, status: NowPlayingStatus?) -> Unit,
        onDeviceCleared: () -> Unit
    ) {
        if (reachabilityJob?.isActive == true) return

        reachabilityJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val device = loungeSender.currentDevice()
                if (device == null) {
                    consecutiveFailures = 0
                    onReachabilityUpdate(false, null)
                } else {
                    val pingResult = withContext(Dispatchers.IO) { loungeSender.ping() }
                    val status = pingResult.getOrNull()
                    onReachabilityUpdate(pingResult.isSuccess, status)

                    if (pingResult.isSuccess) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            loungeSender.clearActiveDevice()
                            onDeviceCleared()
                            consecutiveFailures = 0
                        }
                    }
                }

                delay(15_000)
            }
        }
    }

    fun stopReachability() {
        reachabilityJob?.cancel()
        reachabilityJob = null
    }

    fun startHeartbeat(
        scope: LifecycleCoroutineScope,
        isPaused: () -> Boolean,
        lastRealtimeMs: () -> Long,
        onNowPlaying: (NowPlayingStatus) -> Unit,
        onPingResult: (reachable: Boolean, status: NowPlayingStatus?) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        if (heartbeatJob?.isActive == true && pingJob?.isActive == true) return
        stopHeartbeat()

        heartbeatJob = scope.launch {
            val streamResult = loungeSender.streamNowPlaying { status ->
                onNowPlaying(status)
            }

            if (streamResult.isFailure) {
                onConnectionLost()
            }
        }

        pingJob = scope.launch {
            var backoffMs = LOUNGE_ACTIVE_PING_INTERVAL_MS
            var consecutiveFailures = 0
            var lastSuccessMs = 0L
            while (isActive) {
                delay(backoffMs)

                val now = SystemClock.elapsedRealtime()
                val staleMs = now - lastRealtimeMs()
                val paused = isPaused()
                val shouldPing = if (paused) {
                    now - lastSuccessMs >= LOUNGE_PAUSED_PING_INTERVAL_MS
                } else {
                    staleMs > LOUNGE_ACTIVE_STALE_MS
                }

                if (!shouldPing) {
                    backoffMs = if (paused) LOUNGE_PAUSED_PING_INTERVAL_MS else LOUNGE_ACTIVE_PING_INTERVAL_MS
                    if (!paused) consecutiveFailures = 0
                    continue
                }

                val pingResult = withContext(Dispatchers.IO) { loungeSender.ping() }
                val status = pingResult.getOrNull()
                if (pingResult.isSuccess) {
                    consecutiveFailures = 0
                    lastSuccessMs = SystemClock.elapsedRealtime()
                    backoffMs = if (paused) LOUNGE_PAUSED_PING_INTERVAL_MS else LOUNGE_ACTIVE_PING_INTERVAL_MS
                    onPingResult(true, status)
                } else {
                    consecutiveFailures++
                    backoffMs = (backoffMs * 2).coerceAtMost(LOUNGE_MAX_PING_INTERVAL_MS)
                    onPingResult(false, null)
                    if (consecutiveFailures >= LOUNGE_PING_FAILURE_LIMIT) {
                        onConnectionLost()
                        break
                    }
                }
            }
        }
    }

    fun stopHeartbeat() {
        pingJob?.cancel()
        pingJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    companion object {
        private const val LOUNGE_ACTIVE_STALE_MS = 7_000L
        private const val LOUNGE_ACTIVE_PING_INTERVAL_MS = 7_000L
        private const val LOUNGE_PAUSED_PING_INTERVAL_MS = 18_000L
        private const val LOUNGE_MAX_PING_INTERVAL_MS = 14_000L
        private const val LOUNGE_PING_FAILURE_LIMIT = 5
    }
}
