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

import android.os.Build
import io.github.qauxv.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object FakeFriendAddDateCommandClient {

    private const val MODULE_NAME = "FakeFriendAddDateNt"
    private const val COMMANDS_ENDPOINT = "/qauxv/commands"
    private const val RESULT_ENDPOINT = "/qauxv/command-result"
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1200

    data class CommandResult(
        val ok: Boolean,
        val result: JSONObject? = null,
        val error: String? = null
    )

    fun interface CommandHandler {
        fun handle(command: JSONObject): CommandResult
    }

    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QAuxv-FakeFriendAddDate-CommandClient").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var mEnabled = false

    @Volatile
    private var mBaseUrl = ""

    @Volatile
    private var mIntervalMs = 5000L

    @Volatile
    private var mHandler: CommandHandler? = null

    @Volatile
    private var mDebugLogEnabled = false

    private var mScheduledFuture: ScheduledFuture<*>? = null

    fun configure(
        enabled: Boolean,
        baseUrl: String,
        intervalMs: Long,
        debugLogEnabled: Boolean,
        handler: CommandHandler?
    ) {
        synchronized(lock) {
            mEnabled = enabled
            mBaseUrl = baseUrl.trim().removeSuffix("/")
            mIntervalMs = intervalMs.coerceIn(3000L, 60_000L)
            mDebugLogEnabled = debugLogEnabled
            mHandler = handler
            rescheduleLocked()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            mEnabled = false
            mScheduledFuture?.cancel(false)
            mScheduledFuture = null
        }
    }

    private fun rescheduleLocked() {
        mScheduledFuture?.cancel(false)
        mScheduledFuture = null
        val ready = mEnabled && mBaseUrl.startsWith("http", ignoreCase = true) && mHandler != null
        if (!ready) {
            return
        }
        mScheduledFuture = scheduler.scheduleWithFixedDelay(
            { safePoll() },
            mIntervalMs,
            mIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun safePoll() {
        runCatching {
            pollOnce()
        }.onFailure {
            logDebug("poll failed: ${it.javaClass.simpleName}")
        }
    }

    private fun pollOnce() {
        val handler = mHandler ?: return
        val baseUrl = mBaseUrl
        if (!mEnabled || baseUrl.isBlank()) return
        val commandUrl = buildString {
            append(baseUrl)
            append(COMMANDS_ENDPOINT)
            append("?module=")
            append(urlEncode(MODULE_NAME))
            append("&device=")
            append(urlEncode(getDeviceName()))
        }
        val body = httpGet(commandUrl) ?: return
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val commands = root.optJSONArray("commands") ?: return
        val limit = minOf(commands.length(), 16)
        for (index in 0 until limit) {
            val command = commands.optJSONObject(index) ?: continue
            val commandId = command.optString("id").ifBlank { "cmd-${System.currentTimeMillis()}-$index" }
            val result = runCatching { handler.handle(command) }
                .getOrElse { CommandResult(ok = false, error = "handler exception: ${it.javaClass.simpleName}") }
            postCommandResult(commandId, result)
        }
    }

    private fun postCommandResult(commandId: String, result: CommandResult) {
        val payload = JSONObject()
        payload.put("module", MODULE_NAME)
        payload.put("commandId", commandId)
        payload.put("ok", result.ok)
        if (result.result != null) {
            payload.put("result", result.result)
        }
        if (!result.error.isNullOrBlank()) {
            payload.put("error", result.error)
        }
        payload.put("timestamp", System.currentTimeMillis())
        val target = mBaseUrl + RESULT_ENDPOINT
        httpPost(target, payload.toString())
    }

    private fun httpGet(url: String): String? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) {
                logDebug("poll rejected: http=$code")
                conn.inputStream?.close()
                conn.errorStream?.close()
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.inputStream?.close()
            conn.errorStream?.close()
            conn.disconnect()
            text
        }.getOrElse {
            logDebug("poll network error: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun httpPost(url: String, body: String) {
        runCatching {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode
            conn.inputStream?.close()
            conn.errorStream?.close()
            conn.disconnect()
        }.onFailure {
            logDebug("result post failed: ${it.javaClass.simpleName}")
        }
    }

    private fun getDeviceName(): String {
        val brand = Build.BRAND?.trim().orEmpty().ifEmpty { "android" }
        val model = Build.MODEL?.trim().orEmpty().ifEmpty { "device" }
        return "$brand-$model"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun logDebug(message: String) {
        if (mDebugLogEnabled) {
            Log.d("FakeFriendAddDateCommandClient: $message")
        }
    }
}
