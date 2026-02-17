package com.github.libretube.sender

import android.content.Context
import android.os.Build
import android.util.Log
import com.github.libretube.api.lounge.LoungeApiFactory
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class LoungeSender(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val api = LoungeApiFactory.create()
    private val json = Json { encodeDefaults = true }
    private val appName = "LibreTube"
    private val deviceName = Build.MODEL ?: "Android"
    private val random = Random()
    private val nextRequestId = AtomicInteger(1337)
    private val nextOffset = AtomicInteger(0)
    private val startAid = 0
    private val startOfs = startAid
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // never time out streaming RPC
        .build()
    private var session: LoungeSession? = null
    private val lastAid: AtomicInteger = AtomicInteger(loadLastAid())
    private val sendMutex = Mutex()

    suspend fun pair(pairingCode: String): Result<LoungeDevice> = runCatching {
        val normalizedCode = pairingCode.trim().replace("[^0-9]".toRegex(), "")
        Log.d(TAG, "Pairing with code=$normalizedCode")
        val response = api.getScreen(pairingCode = normalizedCode)
        val screen = response.screen
            ?: throw IllegalStateException("No screens returned for pairing code")
        val device = LoungeDevice(
            screenId = screen.screenId,
            loungeToken = screen.loungeToken,
            name = screen.name ?: screen.screenId
        )
        saveDevice(device)
        Log.d(TAG, "Paired with screen=${device.screenId}, name=${device.name}")
        device
    }.onFailure {
        if (it is retrofit2.HttpException) {
            val body = it.response()?.errorBody()?.string()
            Log.e(TAG, "Pairing failed http=${it.code()} body=${body ?: "<none>"}")
        } else {
            Log.e(TAG, "Pairing failed", it)
        }
    }

    fun pairedDevices(): List<LoungeDevice> = loadDevices()

    fun removeDevice(device: LoungeDevice) {
        val devices = loadDevices().toMutableList()
        val removed = devices.removeAll { it.loungeToken == device.loungeToken || it.screenId == device.screenId }
        if (removed) {
            saveDevices(devices)
            val activeId = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_ACTIVE_SCREEN_ID, "")
            if (session?.loungeToken == device.loungeToken || activeId == device.screenId) {
                clearActiveDevice()
            }
        }
    }

    fun currentDevice(): LoungeDevice? {
        val activeId = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_ACTIVE_SCREEN_ID, "")
        val devices = loadDevices()
        if (activeId.isNotBlank()) {
            devices.firstOrNull { it.screenId == activeId }?.let { return it }
        }
        return null
    }

    fun setActiveDevice(device: LoungeDevice) {
        val devices = loadDevices().toMutableList()
        val existingIndex = devices.indexOfFirst { it.loungeToken == device.loungeToken || it.screenId == device.screenId }
        if (existingIndex >= 0) devices.removeAt(existingIndex)
        devices.add(0, device)
        saveDevices(devices)
        PreferenceHelper.putString(PreferenceKeys.CAST_SENDER_ACTIVE_SCREEN_ID, device.screenId)
        session = null
        nextOffset.set(startOfs)
        lastAid.set(startAid)
        saveLastAid(startAid)
    }

    fun clearActiveDevice() {
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_ACTIVE_SCREEN_ID)
        session = null
        nextOffset.set(startOfs)
        lastAid.set(startAid)
        saveLastAid(startAid)
    }

    fun clearDevice() {
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_LOUNGE_TOKEN)
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_SCREEN_ID)
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_SCREEN_NAME)
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_DEVICES)
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_ACTIVE_SCREEN_ID)
        saveLastAid(startAid)
        session = null
        nextOffset.set(startOfs)
        lastAid.set(startAid)
    }

    fun clearAllDevices() {
        clearDevice()
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_DEVICE_ID)
    }

    suspend fun sendVideo(
        videoId: String,
        startPositionMs: Long,
        queue: List<String> = listOf(videoId),
        currentIndex: Int = 0,
        listId: String? = null,
        params: String? = null,
        playerParams: String? = null,
        audioOnly: Boolean = false
    ): Result<Unit> = runCatching {
        val payload = SetPlaylistAction(
            videoId = videoId,
            videoIds = queue,
            currentIndex = currentIndex,
            currentTime = (startPositionMs / 1000.0).coerceAtLeast(0.0),
            listId = listId,
            params = params,
            playerParams = playerParams,
            audioOnly = audioOnly
        )

        Log.d(TAG, "sendVideo videoId=$videoId positionMs=$startPositionMs queueSize=${queue.size}")

        // Send setPlaylist and play together to guarantee start on receivers that ignore state.
        sendActions(
            listOf(
                LoungeAction(
                    name = "setPlaylist",
                    payload = json.encodeToJsonElement(payload)
                ),
                LoungeAction(name = "play")
            )
        ).getOrThrow()
    }

    suspend fun play(): Result<Unit> = sendActions(listOf(LoungeAction("play")))

    suspend fun pause(): Result<Unit> = sendActions(listOf(LoungeAction("pause")))

    suspend fun seekTo(positionMs: Long): Result<Unit> = sendActions(
        listOf(
            LoungeAction(
                name = "seekTo",
                payload = json.encodeToJsonElement(
                    SeekToAction(newTime = positionMs / 1000.0, reason = 0, seekPlaybackRate = 1.0)
                )
            )
        )
    )

    suspend fun setCaptionTrack(trackId: String?): Result<Unit> = sendActions(
        listOf(
            LoungeAction(
                name = "setCaptionTrack",
                payload = json.encodeToJsonElement(
                    SetCaptionTrackAction(trackId = trackId?.takeIf { it.isNotBlank() } ?: "")
                )
            )
        )
    )

    suspend fun setAudioTrack(audioTrackId: String?): Result<Unit> = sendActions(
        listOf(
            LoungeAction(
                name = "setAudioTrack",
                payload = json.encodeToJsonElement(
                    SetAudioTrackAction(audioTrackId = audioTrackId?.takeIf { it.isNotBlank() } ?: "")
                )
            )
        )
    )

    suspend fun next(): Result<Unit> = sendActions(listOf(LoungeAction("next")))

    suspend fun previous(): Result<Unit> = sendActions(listOf(LoungeAction("previous")))

    suspend fun ping(device: LoungeDevice? = currentDevice()): Result<NowPlayingStatus?> = runCatching {
        val target = device ?: throw IllegalStateException("Device not paired")
        val body = sendActionsForPing(listOf(LoungeAction("getNowPlaying")), target).getOrThrow()
        parseNowPlaying(body)
    }

    suspend fun pollNowPlaying(): Result<NowPlayingStatus?> = runCatching {
        val device = currentDevice() ?: throw IllegalStateException("Device not paired")

        if (session?.loungeToken != null && session?.loungeToken != device.loungeToken) {
            session = null
            nextOffset.set(startOfs)
            lastAid.set(startAid)
            saveLastAid(startAid)
        }

        val remoteId = getOrCreateDeviceId().replace("-", "")

        suspend fun attemptPoll(): String? {
            val loungeSession = ensureSession(device, remoteId)
            val ofs = nextOffset.get()
            val rid = nextRequestId.incrementAndGet().toString()

            val response = try {
                api.sendAction(
                    clientName = "$appName ($deviceName)",
                    sessionId = loungeSession.sessionId,
                    gsessionId = loungeSession.gsessionId,
                    loungeIdToken = device.loungeToken,
                    count = 0,
                    actions = "[]",
                    requestId = rid,
                    sid = loungeSession.sid,
                    aid = null,
                    ofs = ofs,
                    cpn = loungeSession.cpn
                )
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Lounge poll timed out for screen=${device.screenId}, keeping session")
                return ""
            }

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "Lounge poll failed code=${response.code()} rid=$rid sid=${loungeSession.sid} ofs=$ofs body=${errorBody ?: "<none>"}")
                if (response.code() == 410 || response.code() == 404 ||
                    (response.code() == 400 && errorBody?.contains("Unknown SID", ignoreCase = true) == true)
                ) {
                    Log.w(TAG, "Poll session expired with ${response.code()} for screen=${device.screenId}, retrying handshake")
                    session = null
                    nextOffset.set(startOfs)
                    lastAid.set(startAid)
                    saveLastAid(startAid)
                    return null
                }

                Log.e(TAG, "Lounge poll failed with ${response.code()} for screen=${device.screenId} body=${errorBody ?: "<none>"}")
                throw IllegalStateException("Lounge poll failed with ${response.code()}")
            }

            val bodyText = response.body()?.string().orEmpty()
            val snippet = if (bodyText.length > 1200) bodyText.take(1200) + "..." else bodyText
            if (bodyText.isNotBlank()) {
                Log.d(TAG, "poll rid=$rid ofs=$ofs bodyLen=${bodyText.length} sid=${loungeSession.sid} body=$snippet")
            } else {
                Log.d(TAG, "poll rid=$rid ofs=$ofs empty body sid=${loungeSession.sid}")
            }
            return bodyText
        }

        val body = attemptPoll() ?: attemptPoll()
        if (body == null) throw IllegalStateException("Lounge poll failed after retry")
        parseNowPlaying(body)
    }

    private suspend fun sendActionsForPing(actions: List<LoungeAction>, device: LoungeDevice): Result<String> = runCatching {

        // If the paired device changed since the last session, drop the stale session.
        if (session?.loungeToken != null && session?.loungeToken != device.loungeToken) {
            session = null
            nextOffset.set(startOfs)
            lastAid.set(startAid)
            saveLastAid(startAid)
        }

        val remoteId = getOrCreateDeviceId().replace("-", "")

        return@runCatching sendMutex.withLock {
            var attempt = 0
            var backoffMs = 600L
            lateinit var result: String
            var hasResult = false
            while (!hasResult) {
                val loungeSession = ensureSession(device, remoteId)
                val rid = nextRequestId.incrementAndGet()
                val ofs = lastAid.get()

                val startMessageId = loungeSession.aid
                val actionsJson = buildActionsPayload(actions, startMessageId)
                val formFields = buildFormFields(actions)

                Log.d(
                    TAG,
                    "ping sendActions count=${actions.size} screen=${device.screenId} rid=$rid sid=${loungeSession.sid} aid=${loungeSession.aid} id=${loungeSession.sessionId}"
                )

                val response = try {
                    api.sendAction(
                        clientName = "$appName ($deviceName)",
                        sessionId = loungeSession.sessionId,
                        gsessionId = loungeSession.gsessionId,
                        loungeIdToken = device.loungeToken,
                        count = actions.size,
                        actions = actionsJson,
                        requestId = rid.toString(),
                        sid = loungeSession.sid,
                        aid = startMessageId,
                        ofs = ofs,
                        formFields = formFields,
                        cpn = loungeSession.cpn
                    )
                } catch (e: SocketTimeoutException) {
                    attempt++
                    if (attempt > 3) throw e
                    Log.w(TAG, "ping timeout retry=${attempt} rid=$rid")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                    continue
                } catch (e: UnknownHostException) {
                    // DNS hiccup – retry quickly before giving up.
                    attempt++
                    if (attempt > 3) throw e
                    Log.w(TAG, "ping dns retry=${attempt} rid=$rid host=${e.message}")
                    delay(backoffMs.coerceAtMost(2_000L))
                    backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                    continue
                }

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    val sessionExpired = response.code() == 410 || response.code() == 404 ||
                        (response.code() == 400 && errorBody?.contains("Unknown SID", ignoreCase = true) == true)
                    if (sessionExpired) {
                        Log.w(
                            TAG,
                            "Session expired during ping with ${response.code()} for screen=${device.screenId}, retrying handshake"
                        )
                        session = null
                        nextOffset.set(startOfs)
                        lastAid.set(startAid)
                        saveLastAid(startAid)
                        attempt++
                        if (attempt > 2) throw IllegalStateException("Session expired after retries")
                        continue
                    }

                    if (response.code() >= 500) {
                        attempt++
                        if (attempt > 3) {
                            throw IllegalStateException("Lounge request failed with ${response.code()}")
                        }
                        Log.w(TAG, "ping 5xx retry code=${response.code()} attempt=$attempt")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                        continue
                    }

                    Log.e(
                        TAG,
                        "Lounge ping failed with ${response.code()} for screen=${device.screenId} body=${errorBody ?: "<none>"}"
                    )
                    throw IllegalStateException("Lounge request failed with ${response.code()}")
                }

                val bodyText = response.body()?.string().orEmpty()

                val newAid = startMessageId + actions.size
                session = loungeSession.copy(aid = newAid)
                lastAid.set(newAid)
                nextOffset.set(newAid)
                saveLastAid(newAid)

                Log.d(
                    TAG,
                    "Ping sent ${actions.size} action(s) to screen=${device.screenId} body=${bodyText.ifBlank { "<empty>" }}"
                )
                result = bodyText
                hasResult = true
            }
            result
        }
    }

    private suspend fun sendActions(actions: List<LoungeAction>): Result<Unit> = runCatching {
        val device = currentDevice() ?: throw IllegalStateException("Device not paired")

        // If the paired device changed since the last session, drop the stale session.
        if (session?.loungeToken != null && session?.loungeToken != device.loungeToken) {
            session = null
            nextOffset.set(startOfs)
            lastAid.set(startAid)
            saveLastAid(startAid)
        }

        // Stable remote id to keep the receiver happy across requests.
        val remoteId = getOrCreateDeviceId().replace("-", "")

        sendMutex.withLock {
            var attempt = 0
            var backoffMs = 600L
            while (true) {
                val loungeSession = ensureSession(device, remoteId)
                val rid = nextRequestId.incrementAndGet()
                val ofs = lastAid.get()

                val startMessageId = loungeSession.aid
                val actionsJson = buildActionsPayload(actions, startMessageId)
                // Add reqN__sc plus mirrored fields for setPlaylist to help strict receivers (e.g., SmartTube).
                val formFields = buildFormFields(actions)

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Lounge payload actions=$actionsJson form=$formFields")
                }

                Log.d(
                    TAG,
                    "sendActions count=${actions.size} screen=${device.screenId} rid=$rid sid=${loungeSession.sid} aid=${loungeSession.aid} id=${loungeSession.sessionId}"
                )

                val response = try {
                    api.sendAction(
                        clientName = "$appName ($deviceName)",
                        sessionId = loungeSession.sessionId,
                        gsessionId = loungeSession.gsessionId,
                        loungeIdToken = device.loungeToken,
                        count = actions.size,
                        actions = actionsJson,
                        requestId = rid.toString(),
                        sid = loungeSession.sid,
                        aid = startMessageId,
                        ofs = ofs,
                        formFields = formFields,
                        cpn = loungeSession.cpn
                    )
                } catch (e: SocketTimeoutException) {
                    attempt++
                    if (attempt > 3) throw e
                    Log.w(TAG, "sendActions timeout retry=${attempt} rid=$rid")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                    continue
                } catch (e: UnknownHostException) {
                    attempt++
                    if (attempt > 3) throw e
                    Log.w(TAG, "sendActions dns retry=${attempt} rid=$rid host=${e.message}")
                    delay(backoffMs.coerceAtMost(2_000L))
                    backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                    continue
                }

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    // 404/410 or 400 Unknown SID -> drop session and retry.
                    val sessionExpired = response.code() == 410 || response.code() == 404 ||
                        (response.code() == 400 && errorBody?.contains("Unknown SID", ignoreCase = true) == true)
                    if (sessionExpired) {
                        Log.w(
                            TAG,
                            "Session expired with ${response.code()} for screen=${device.screenId}, retrying handshake"
                        )
                        session = null
                        nextOffset.set(startOfs)
                        lastAid.set(startAid)
                        saveLastAid(startAid)
                        attempt++
                        if (attempt > 2) throw IllegalStateException("Session expired after retries")
                        continue
                    }

                    if (response.code() >= 500) {
                        attempt++
                        if (attempt > 3) throw IllegalStateException("Lounge request failed with ${response.code()}")
                        Log.w(TAG, "sendActions 5xx retry code=${response.code()} attempt=$attempt")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                        continue
                    }

                    Log.e(
                        TAG,
                        "Lounge request failed with ${response.code()} for screen=${device.screenId} body=${errorBody ?: "<none>"}"
                    )
                    throw IllegalStateException("Lounge request failed with ${response.code()}")
                }

                val bodyText = response.body()?.string().orEmpty()

                // advance AID and offset by the number of actions just sent
                val newAid = startMessageId + actions.size
                session = loungeSession.copy(aid = newAid)
                lastAid.set(newAid)
                nextOffset.set(newAid)
                saveLastAid(newAid)

                Log.d(
                    TAG,
                    "Sent ${actions.size} action(s) to screen=${device.screenId} body=${bodyText.ifBlank { "<empty>" }}"
                )
                return@withLock
            }
        }
    }

    private suspend fun ensureSession(device: LoungeDevice, sessionId: String): LoungeSession {
        session?.let { return it }

        val rid = nextRequestId.incrementAndGet()
        val cpn = newCpn()
        val response = api.sendAction(
            clientName = "$appName ($deviceName)",
            sessionId = sessionId,
            loungeIdToken = device.loungeToken,
            count = 0,
            ofs = 0,
            actions = "[]",
            requestId = rid.toString(),
            cpn = cpn
        )

        if (!response.isSuccessful) {
            val body = response.errorBody()?.string()
            Log.e(TAG, "Handshake failed ${response.code()} body=${body ?: "<none>"}")
            throw IllegalStateException("Lounge handshake failed with ${response.code()}")
        }

        val bodyText = response.body()?.string().orEmpty()
        val hsSnippet = if (bodyText.length > 1200) bodyText.take(1200) + "..." else bodyText
        val sid = parseSid(bodyText)
            ?: throw IllegalStateException("Handshake missing SID")
        val gsessionId = parseGsession(bodyText)
        val maxSeen = parseMaxMessageId(bodyText)
        val initialAid = listOf(startAid, lastAid.get(), maxSeen + 1).maxOrNull() ?: startAid
        val loungeSession = LoungeSession(
            loungeToken = device.loungeToken,
            sessionId = sessionId,
            sid = sid,
            gsessionId = gsessionId,
            aid = initialAid,
            cpn = cpn
        )
        session = loungeSession
        nextOffset.set(initialAid)
        saveLastAid(initialAid)
        Log.d(
            TAG,
            "Handshake OK sid=$sid aidStart=${loungeSession.aid} ofsStart=${nextOffset.get()} id=${sessionId} gsession=$gsessionId body=$hsSnippet"
        )
        return loungeSession
    }

    /**
     * Long-lived RPC stream similar to SmartTube: keeps a single GET open and emits nowPlaying/state updates.
     */
    suspend fun streamNowPlaying(onUpdate: (NowPlayingStatus) -> Unit): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val device = currentDevice() ?: throw IllegalStateException("Device not paired")

            if (session?.loungeToken != null && session?.loungeToken != device.loungeToken) {
                session = null
                nextOffset.set(startOfs)
            }

            val remoteId = getOrCreateDeviceId().replace("-", "")
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                Log.w(TAG, "RPC stream completed", cause)
            }

            // Open (and reopen) the streaming bind. This returns only on error/EOF.
            var backoffMs = 1_000L
            while (isActive) {
                val loungeSession = ensureSession(device, remoteId)
                val rid = "rpc"
                var currentAid = maxOf(loungeSession.aid, lastAid.get())

                var hardReset = false

                val url = buildString {
                    append("https://www.youtube.com/api/lounge/bc/bind")
                    append("?device=REMOTE_CONTROL")
                    append("&app=android-remote")
                    append("&name=").append(URLEncoder.encode("$appName ($deviceName)", StandardCharsets.UTF_8.name()))
                    append("&id=").append(remoteId)
                    append("&gsessionid=").append(loungeSession.gsessionId ?: "")
                    append("&loungeIdToken=").append(device.loungeToken)
                    append("&VER=8&v=2&theme=cl&ui=1&capabilities=remote_queue")
                    append("&conn=longpoll&prop=yls&ctype=lb")
                    append("&RID=").append(rid)
                    append("&AID=").append(currentAid)
                    append("&CI=0&TYPE=xmlhttp&cpn=").append(loungeSession.cpn)
                    append("&SID=").append(loungeSession.sid)
                    append("&t=1&zx=").append(System.currentTimeMillis())
                }

                Log.d(TAG, "RPC stream start sid=${loungeSession.sid} gsession=${loungeSession.gsessionId} aid=$currentAid rid=$rid")

                val request = Request.Builder().url(url).get().build()
                try {
                    streamClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val body = response.body?.string()
                            val isHardReset = response.code == 410 || response.code == 404 ||
                                (response.code == 400 && body?.contains("Unknown SID", ignoreCase = true) == true)
                            if (isHardReset) {
                                Log.w(TAG, "RPC stream hard reset code=${response.code} sid=${loungeSession.sid} body=${body ?: "<none>"}")
                                session = null
                                nextOffset.set(startOfs)
                                lastAid.set(startAid)
                                saveLastAid(startAid)
                                delay(2_000)
                                hardReset = true
                                return@use
                            }
                            Log.w(TAG, "RPC stream failed code=${response.code} sid=${loungeSession.sid} body=${body ?: "<none>"}")
                            throw IllegalStateException("RPC stream failed with ${response.code}")
                        }

                    val source = response.body?.source() ?: return@use

                    while (isActive) {
                        // If session was cleared externally, stop reading.
                        if (session?.sid != loungeSession.sid) break

                        // Read length-prefixed chunk with buffering to handle partial reads
                        val lengthLine = source.readUtf8Line() ?: break
                        val trimmed = lengthLine.trim()
                        if (trimmed.isBlank()) continue
                        val length = trimmed.toIntOrNull() ?: continue
                        var chunk = source.readUtf8(length.toLong())
                        // If the source returned fewer chars than expected, keep reading the remainder.
                        while (chunk.length < length && !source.exhausted()) {
                            val missing = length - chunk.length
                            chunk += source.readUtf8(missing.toLong())
                        }

                        val parsed = runCatching { json.parseToJsonElement(chunk) as? JsonArray }.getOrNull()
                        if (parsed == null) {
                            Log.d(TAG, "RPC chunk parse failed sid=${loungeSession.sid} rid=$rid aid=$currentAid chunk=${chunk.take(200)}")
                            continue
                        }
                        if (parsed.isEmpty()) {
                            // Empty batch keepalive
                            continue
                        }

                        parsed.forEach { entry ->
                            val arr = entry as? JsonArray ?: return@forEach
                            val msgId = arr.getOrNull(0)?.jsonPrimitive?.content?.toIntOrNull()
                            if (msgId != null) {
                                    if (msgId <= currentAid) return@forEach
                                    currentAid = maxOf(currentAid, msgId)
                                    lastAid.set(currentAid)
                                    session = session?.copy(aid = currentAid)
                                    nextOffset.set(currentAid)
                            }

                            val cmd = arr.getOrNull(1) as? JsonArray ?: return@forEach
                            val name = cmd.getOrNull(0)?.jsonPrimitive?.content

                            when (name) {
                                "noop" -> return@forEach
                                "c" -> return@forEach
                                "S" -> return@forEach
                                "event", "nowPlaying", "onStateChange" -> {
                                    val status = runCatching {
                                        val bodyText = JsonArray(listOf(arr)).toString()
                                        parseNowPlaying(bodyText)
                                    }.getOrNull()
                                    status?.let { np ->
                                        withContext(Dispatchers.Main) { onUpdate(np) }
                                    }
                                }
                                else -> {
                                    // If payload still contains playback info, attempt parse
                                    val status = runCatching {
                                        val bodyText = JsonArray(listOf(arr)).toString()
                                        parseNowPlaying(bodyText)
                                    }.getOrNull()
                                    status?.let { np ->
                                        withContext(Dispatchers.Main) { onUpdate(np) }
                                    }
                                }
                            }
                        }
                    }
                }
                } catch (e: Exception) {
                    if (!isActive) throw e
                    Log.w(TAG, "RPC stream error sid=${session?.sid}", e)
                    // keep AID but drop session to force a clean handshake
                    session = null
                    // For network/DNS hiccups, retry quickly; otherwise exponential backoff.
                    backoffMs = when (e) {
                        is SocketTimeoutException, is UnknownHostException -> 2_000L
                        else -> (backoffMs * 2).coerceAtMost(15_000L)
                    }
                    delay(backoffMs)
                    continue
                }
                // If we reach here, the stream ended. Clear session to force re-handshake on next loop.
                // Soft reconnect: keep latest AID and SID, retry soon
                if (!hardReset) {
                    lastAid.set(currentAid)
                    session = session?.copy(aid = currentAid)
                    nextOffset.set(currentAid)
                    saveLastAid(currentAid)
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun parseSid(body: String): String? {
        // Looks like [[0,["c","<SID>","",8]], ...]
        val sidRegex = Regex("\\[\\s*\\d+,\\[\"c\",\"([A-Za-z0-9]+)\"")
        return sidRegex.find(body)?.groupValues?.getOrNull(1)
    }

    private fun parseMaxMessageId(body: String): Int {
        val idRegex = Regex("""\[\s*(\d+)\s*,\["""")
        return idRegex.findAll(body).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull() ?: -1
    }

    private fun parseGsession(body: String): String? {
        val gsRegex = Regex("""\[\s*\d+\s*,\["S","([^"]+)"""")
        return gsRegex.find(body)?.groupValues?.getOrNull(1)

    }

    private fun parseNowPlaying(body: String): NowPlayingStatus? {
        val jsonStart = body.indexOf('[')
        if (jsonStart == -1) {
            val snippet = body.take(400)
            Log.d(TAG, "parseNowPlaying missing '[' snippet=$snippet")
            return null
        }
        val sliced = body.substring(jsonStart)
        val root = runCatching { json.parseToJsonElement(sliced) as? JsonArray }.getOrNull()
        if (root == null) {
            val snippet = sliced.take(400)
            Log.d(TAG, "parseNowPlaying json parse failed snippet=$snippet")
            return null
        }

        root.forEach { entry ->
            val arr = entry as? JsonArray ?: return@forEach
            val cmd = arr.getOrNull(1) as? JsonArray ?: return@forEach
            val payload = cmd.getOrNull(1) as? JsonObject ?: return@forEach

            val status = buildNowPlayingStatus(payload)
            if (status != null) return status
        }
        val snippet = sliced.take(400)
        Log.d(TAG, "parseNowPlaying no status snippet=$snippet")
        return null
    }

    private fun buildNowPlayingStatus(payload: JsonObject): NowPlayingStatus? {
        fun num(key: String): Double? = payload[key]?.jsonPrimitive?.content?.toDoubleOrNull()

        val videoId = payload["videoId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val currentIndex = payload["currentIndex"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: payload["index"]?.jsonPrimitive?.content?.toIntOrNull()

        val videoIds: List<String>? = when (val raw = payload["videoIds"]) {
            is JsonArray -> raw.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            is JsonPrimitive -> raw.content
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            else -> null
        }

        val currentTimeSec = num("currentTime") ?: num("time")
        val durationSec = num("duration")
        val playerState = payload["playerState"]?.jsonPrimitive?.content?.toIntOrNull()
        val state = payload["state"]?.jsonPrimitive?.content?.toIntOrNull()

        val isPlaying = when {
            playerState != null -> playerState == 1
            state != null -> state == 1
            else -> null
        }

        if (currentTimeSec != null || durationSec != null || isPlaying != null || videoId != null || videoIds != null || currentIndex != null) {
            return NowPlayingStatus(
                currentTimeMs = currentTimeSec?.let { (it * 1000).toLong() },
                durationMs = durationSec?.let { (it * 1000).toLong() },
                isPlaying = isPlaying,
                videoId = videoId,
                currentIndex = currentIndex,
                videoIds = videoIds
            )
        }

        return null
    }

    private fun buildActionsPayload(actions: List<LoungeAction>, startMessageId: Int): String {
        val entries = actions.mapIndexed { idx, action ->
            val messageId = startMessageId + idx
            val command = if (action.payload != null) {
                JsonArray(listOf(JsonPrimitive(action.name), action.payload))
            } else {
                JsonArray(listOf(JsonPrimitive(action.name)))
            }
            JsonArray(listOf(JsonPrimitive(messageId), command))
        }
        return json.encodeToString(JsonArray(entries))
    }

    private fun newCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(16) {
            repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun buildFormFields(actions: List<LoungeAction>): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        actions.forEachIndexed { idx, action ->
            fields["req${idx}__sc"] = action.name

            if (action.name == "setPlaylist" && action.payload != null) {
                val payload = runCatching { json.decodeFromJsonElement<SetPlaylistAction>(action.payload) }.getOrNull()
                payload?.let {
                    fields["req${idx}_videoId"] = it.videoId
                    fields["req${idx}_videoIds"] = it.videoIds.joinToString(",")
                    fields["req${idx}_currentIndex"] = it.currentIndex.toString()
                    fields["req${idx}_currentTime"] = it.currentTime.toString()
                    fields["req${idx}_state"] = it.state.toString()
                    fields["req${idx}_source"] = it.source
                    fields["req${idx}_audioOnly"] = it.audioOnly.toString()
                    it.listId?.let { listId -> fields["req${idx}_listId"] = listId }
                    it.params?.let { params -> fields["req${idx}_params"] = params }
                    it.playerParams?.let { playerParams -> fields["req${idx}_playerParams"] = playerParams }
                }
            } else if (action.name == "seekTo" && action.payload != null) {
                val payload = runCatching { json.decodeFromJsonElement<SeekToAction>(action.payload) }.getOrNull()
                payload?.let {
                    fields["req${idx}_newTime"] = it.newTime.toString()
                    fields["req${idx}_reason"] = it.reason.toString()
                    fields["req${idx}_seekPlaybackRate"] = it.seekPlaybackRate.toString()
                }
            } else if (action.name == "setCaptionTrack" && action.payload != null) {
                val payload = runCatching { json.decodeFromJsonElement<SetCaptionTrackAction>(action.payload) }.getOrNull()
                payload?.let {
                    fields["req${idx}_trackId"] = it.trackId
                }
            } else if (action.name == "setAudioTrack" && action.payload != null) {
                val payload = runCatching { json.decodeFromJsonElement<SetAudioTrackAction>(action.payload) }.getOrNull()
                payload?.let {
                    fields["req${idx}_audioTrackId"] = it.audioTrackId
                }
            }
        }
        return fields
    }

    private fun saveDevice(device: LoungeDevice) {
        val devices = loadDevices().toMutableList()
        devices.removeAll { it.loungeToken == device.loungeToken || it.screenId == device.screenId }
        devices.add(0, device)
        while (devices.size > 20) {
            devices.removeLast()
        }
        saveDevices(devices)
    }

    private fun saveDevices(devices: List<LoungeDevice>) {
        val encoded = runCatching { json.encodeToString(devices) }.getOrNull()
        encoded?.let { PreferenceHelper.putString(PreferenceKeys.CAST_SENDER_DEVICES, it) }
    }

    private fun loadDevices(): MutableList<LoungeDevice> {
        val stored = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_DEVICES, "")
        if (stored.isNotBlank()) {
            return runCatching { json.decodeFromString<List<LoungeDevice>>(stored).toMutableList() }.getOrElse { mutableListOf() }
        }

        // Migrate legacy single-device storage if present.
        val loungeToken = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_LOUNGE_TOKEN, "")
        val screenId = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_SCREEN_ID, "")
        if (loungeToken.isNotBlank() && screenId.isNotBlank()) {
            val name = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_SCREEN_NAME, screenId)
            val legacy = LoungeDevice(screenId = screenId, loungeToken = loungeToken, name = name)
            saveDevices(listOf(legacy))
            clearLegacySingle()
            return mutableListOf(legacy)
        }

        return mutableListOf()
    }

    private fun clearLegacySingle() {
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_LOUNGE_TOKEN)
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_SCREEN_ID)
        PreferenceHelper.remove(PreferenceKeys.CAST_SENDER_SCREEN_NAME)
    }

    private fun getOrCreateDeviceId(): String {
        val existing = PreferenceHelper.getString(PreferenceKeys.CAST_SENDER_DEVICE_ID, "")
        if (existing.isNotBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        PreferenceHelper.putString(PreferenceKeys.CAST_SENDER_DEVICE_ID, fresh)
        return fresh
    }

    private fun saveLastAid(value: Int) {
        PreferenceHelper.putInt(PreferenceKeys.CAST_SENDER_LAST_AID, value)
    }

    private fun loadLastAid(): Int {
        return PreferenceHelper.getInt(PreferenceKeys.CAST_SENDER_LAST_AID, startAid)
    }

}

private const val TAG = "LoungeSender"

@Serializable
data class LoungeDevice(
    val screenId: String,
    val loungeToken: String,
    val name: String
)

private data class LoungeSession(
    val loungeToken: String,
    val sessionId: String,
    val sid: String,
    val gsessionId: String?,
    val aid: Int,
    val cpn: String
)

private data class LoungeAction(
    val name: String,
    val payload: JsonElement? = null
)

data class NowPlayingStatus(
    val currentTimeMs: Long?,
    val durationMs: Long?,
    val isPlaying: Boolean?,
    val videoId: String? = null,
    val currentIndex: Int? = null,
    val videoIds: List<String>? = null
)

@Serializable
private data class SetPlaylistAction(
    val videoId: String,
    val videoIds: List<String>,
    val currentIndex: Int,
    val currentTime: Double,
    val state: Int = 1,
    val source: String = "REMOTE_CONTROL",
    val prioritizeMobileSenderPlaybackStateOnConnection: Boolean = true,
    val listId: String? = null,
    val params: String? = null,
    val playerParams: String? = null,
    val audioOnly: Boolean = false
)

@Serializable
private data class SeekToAction(
    val newTime: Double,
    val reason: Int = 0,
    val seekPlaybackRate: Double = 1.0
)

@Serializable
private data class SetCaptionTrackAction(
    val trackId: String
)

@Serializable
private data class SetAudioTrackAction(
    val audioTrackId: String
)
