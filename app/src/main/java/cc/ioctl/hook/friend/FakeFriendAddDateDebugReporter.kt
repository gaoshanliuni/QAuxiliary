/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2024 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cc.ioctl.hook.friend

import cc.ioctl.util.HostInfo
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FakeFriendAddDateDebugReporter {

    private const val MODULE_NAME = "FakeFriendAddDateNt"
    private const val LOG_ENDPOINT = "/qauxv/log"
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1200
    private const val EVENT_THROTTLE_MS = 1000L
    private const val MAX_QUEUE_SIZE = 100

    @Volatile
    private var mDebugEnabled = false

    @Volatile
    private var mServerEnabled = false

    @Volatile
    private var mBaseUrl = ""

    private val queueLock = Any()
    private val pendingLogs = ArrayDeque<JSONObject>(MAX_QUEUE_SIZE + 4)
    private val recentEventTimeMap = HashMap<String, Long>(64)
    private val flushScheduled = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QAuxv-FakeFriendAddDate-Reporter").apply {
            isDaemon = true
        }
    }

    fun configure(debugEnabled: Boolean, serverEnabled: Boolean, baseUrl: String) {
        mDebugEnabled = debugEnabled
        mServerEnabled = serverEnabled
        mBaseUrl = baseUrl.trim().removeSuffix("/")
        if (!isNetworkEnabled()) {
            synchronized(queueLock) {
                pendingLogs.clear()
            }
        }
    }

    fun report(
        level: String,
        event: String,
        message: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        if (!isNetworkEnabled()) return
        val normalizedEvent = event.trim().ifEmpty { "event" }
        val now = System.currentTimeMillis()
        val throttleKey = buildThrottleKey(normalizedEvent, extras)
        synchronized(queueLock) {
            val last = recentEventTimeMap[throttleKey] ?: 0L
            if (now - last in 0 until EVENT_THROTTLE_MS) {
                return
            }
            recentEventTimeMap[throttleKey] = now
            while (recentEventTimeMap.size > 256) {
                val firstKey = recentEventTimeMap.keys.firstOrNull() ?: break
                recentEventTimeMap.remove(firstKey)
            }
            while (pendingLogs.size >= MAX_QUEUE_SIZE) {
                if (pendingLogs.isNotEmpty()) {
                    pendingLogs.removeFirst()
                }
            }
            pendingLogs.addLast(buildPayload(level, normalizedEvent, message, now, extras))
            scheduleFlushLocked()
        }
    }

    private fun scheduleFlushLocked() {
        if (flushScheduled.compareAndSet(false, true)) {
            executor.execute {
                flushQueueLoop()
            }
        }
    }

    private fun flushQueueLoop() {
        while (true) {
            val payload = synchronized(queueLock) {
                if (pendingLogs.isEmpty()) null else pendingLogs.removeFirst()
            } ?: break
            postJson(LOG_ENDPOINT, payload)
        }
        flushScheduled.set(false)
        synchronized(queueLock) {
            if (pendingLogs.isNotEmpty()) {
                scheduleFlushLocked()
            }
        }
    }

    private fun buildPayload(
        level: String,
        event: String,
        message: String,
        timestamp: Long,
        extras: Map<String, Any?>
    ): JSONObject {
        val payload = JSONObject()
        payload.put("module", MODULE_NAME)
        payload.put("level", level.uppercase(Locale.ROOT))
        payload.put("event", event)
        payload.put("message", sanitizeMessage(message))
        payload.put("timestamp", timestamp)
        payload.put("qqVersion", runCatching { HostInfo.getVersionName() }.getOrDefault("-"))
        payload.put("processName", runCatching { SyncUtils.getProcessName() }.getOrDefault("-"))
        for ((key, value) in extras) {
            if (key.isBlank()) continue
            putSafeValue(payload, key, value)
        }
        return payload
    }

    private fun putSafeValue(payload: JSONObject, key: String, value: Any?) {
        when (value) {
            null -> payload.put(key, JSONObject.NULL)
            is Boolean, is Number -> payload.put(key, value)
            is String -> payload.put(key, sanitizeMessage(value))
            else -> payload.put(key, sanitizeMessage(value.toString()))
        }
    }

    private fun sanitizeMessage(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        return if (text.length <= 300) text else text.substring(0, 300)
    }

    private fun buildThrottleKey(event: String, extras: Map<String, Any?>): String {
        val hookPoint = extras["hookPoint"]?.toString().orEmpty()
        val page = extras["page"]?.toString().orEmpty()
        val strategy = extras["strategy"]?.toString().orEmpty()
        return "$event|$hookPoint|$page|$strategy"
    }

    private fun postJson(endpoint: String, payload: JSONObject) {
        val base = mBaseUrl
        if (base.isBlank()) return
        val target = base + endpoint
        runCatching {
            val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { os ->
                os.write(body)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                logLocal("report rejected: http=$code, event=${payload.optString("event")}")
            }
            conn.inputStream?.close()
            conn.errorStream?.close()
            conn.disconnect()
        }.onFailure {
            logLocal("report failed: ${it.javaClass.simpleName}")
        }
    }

    private fun isNetworkEnabled(): Boolean {
        return mDebugEnabled && mServerEnabled && mBaseUrl.startsWith("http", ignoreCase = true)
    }

    private fun logLocal(message: String) {
        if (mDebugEnabled) {
            Log.d("FakeFriendAddDateDebugReporter: $message")
        }
    }
}
