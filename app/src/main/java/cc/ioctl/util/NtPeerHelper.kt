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

package cc.ioctl.util

import android.content.Context
import android.view.View
import cc.hicore.QApp.QAppUtils
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.util.Log
import java.lang.reflect.Modifier

object NtPeerHelper {

    const val KEY_ENABLED = "fake_friend_add_date_nt.enabled"
    const val KEY_TARGET_UIN = "fake_friend_add_date_nt.target_uin"
    const val KEY_TARGET_PEER_ID = "fake_friend_add_date_nt.target_peer_id"
    const val KEY_DISPLAY_TEXT = "fake_friend_add_date_nt.display_text"
    const val KEY_ENABLE_PROFILE_PAGE = "fake_friend_add_date_nt.enable_profile_page"
    const val KEY_ENABLE_CHAT_SETTING_PAGE = "fake_friend_add_date_nt.enable_chat_setting_page"
    const val KEY_DEBUG_LOG = "fake_friend_add_date_nt.debug_log"

    private const val MAX_DEPTH = 2
    private const val MAX_FIELDS_PER_LAYER = 20
    private const val MAX_NESTED_PER_LAYER = 8
    private const val MATCH_CACHE_TTL_MS = 900L
    private const val TARGET_PEER_CACHE_TTL_MS = 1200L

    private val classKeywordHints = arrayOf(
        "Contact", "Profile", "ProfileCard", "Friend", "Relation", "Buddy", "AIO", "Session", "ViewModel", "Intimate"
    )

    private val fieldPriority = arrayOf(
        "peerId", "mPeerId", "uid", "mUid", "uin", "mUin", "friendUin", "mFriendUin", "targetUin",
        "contact", "mContact", "aioContact", "mAioContact", "session", "mSession",
        "profile", "mProfile", "relation", "mRelation"
    )

    private val getterPriority = arrayOf(
        "getPeerId", "getUid", "getUin", "getFriendUin", "getFriendUid",
        "getContact", "getAioContact", "getSession", "getProfile", "getRelation"
    )

    private val nestedNodeNames = setOf(
        "contact", "mContact", "aioContact", "mAioContact", "session", "mSession",
        "profile", "mProfile", "relation", "mRelation"
    )

    private data class ExtractResult(
        val peerId: String? = null,
        val uin: String? = null
    )

    private data class MatchCache(
        val className: String,
        val identityHash: Int,
        val targetSignature: String,
        val peerId: String?,
        val uin: String?,
        val matched: Boolean,
        val timestampMs: Long
    )

    private data class TargetPeerCache(
        val targetUin: String,
        val peerId: String?,
        val timestampMs: Long
    )

    @Volatile
    private var sLastMatchCache: MatchCache? = null

    @Volatile
    private var sTargetPeerCache: TargetPeerCache? = null

    fun resolveTargetPeerId(): String? {
        val directPeer = normalizePeerId(getConfigTargetPeerIdRaw())
        if (!directPeer.isNullOrEmpty()) {
            return directPeer
        }
        val targetUin = getConfigTargetUin() ?: return null
        val now = System.currentTimeMillis()
        val cache = sTargetPeerCache
        if (cache != null && cache.targetUin == targetUin && now - cache.timestampMs in 0..TARGET_PEER_CACHE_TTL_MS) {
            return cache.peerId
        }
        val resolved = runCatching { normalizePeerId(QAppUtils.UserUinToPeerID(targetUin)) }.getOrNull()
        sTargetPeerCache = TargetPeerCache(targetUin, resolved, now)
        return resolved
    }

    fun isNtTargetPeer(any: Any?): Boolean {
        if (any == null) return false
        val targetPeer = resolveTargetPeerId()
        val targetUin = if (!targetPeer.isNullOrEmpty()) null else getConfigTargetUin()
        if (targetPeer.isNullOrEmpty() && targetUin.isNullOrEmpty()) return false
        val targetSignature = buildTargetSignature(targetPeer, targetUin)
        val now = System.currentTimeMillis()
        val className = any.javaClass.name
        val identityHash = System.identityHashCode(any)
        val cache = sLastMatchCache
        if (cache != null &&
            cache.className == className &&
            cache.identityHash == identityHash &&
            cache.targetSignature == targetSignature &&
            now - cache.timestampMs in 0..MATCH_CACHE_TTL_MS
        ) {
            if (isDebugLogEnabled()) {
                logDebug("cache-hit class=${cache.className}, peer=${shortValue(cache.peerId)}, match=${cache.matched}")
            }
            return cache.matched
        }
        val peerId = extractPeerIdFromObject(any)
        val uin = extractUinFromObject(any)
        val matched = isTargetMatched(peerId, uin, any, targetPeer, targetUin)
        sLastMatchCache = MatchCache(className, identityHash, targetSignature, peerId, uin, matched, now)
        if (isDebugLogEnabled()) {
            logDebug("scan class=$className, peer=${shortValue(peerId)}, uin=${shortValue(uin)}, match=$matched")
        }
        return matched
    }

    fun extractPeerIdFromObject(any: Any?): String? {
        val result = extractIds(any, 0, HashSet())
        return normalizePeerId(result.peerId)
    }

    fun extractUinFromObject(any: Any?): String? {
        val result = extractIds(any, 0, HashSet())
        return normalizeUin(result.uin)
    }

    fun looksLikeTargetContact(any: Any?): Boolean {
        if (any == null) return false
        if (any is CharSequence) return isNtTargetPeer(any.toString())
        val className = any.javaClass.name
        val hasHint = classKeywordHints.any { className.contains(it, ignoreCase = true) }
        if (!hasHint) return false
        val peerId = extractPeerIdFromObject(any)
        val uin = extractUinFromObject(any)
        return !peerId.isNullOrEmpty() || !uin.isNullOrEmpty()
    }

    fun normalizePeerId(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (raw.startsWith("U_", ignoreCase = true)) {
            "u_" + raw.substring(2)
        } else {
            raw
        }
    }

    fun getConfigTargetUin(): String? {
        return normalizeUin(ConfigManager.getDefaultConfig().getString(KEY_TARGET_UIN))
    }

    fun getConfigDisplayText(): String? {
        val text = ConfigManager.getDefaultConfig().getString(KEY_DISPLAY_TEXT)?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun getConfigTargetPeerIdRaw(): String? {
        val peer = ConfigManager.getDefaultConfig().getString(KEY_TARGET_PEER_ID)?.trim()
        return peer?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeUin(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!raw.all { it.isDigit() }) return null
        return raw
    }

    private fun extractIds(any: Any?, depth: Int, visited: MutableSet<Int>): ExtractResult {
        if (any == null) return ExtractResult()
        val direct = parseDirectValue(any)
        if (!direct.peerId.isNullOrEmpty() || !direct.uin.isNullOrEmpty()) return direct
        if (depth >= MAX_DEPTH) return ExtractResult()
        val clazz = any.javaClass
        if (shouldSkipClass(clazz)) return ExtractResult()
        val identity = System.identityHashCode(any)
        if (!visited.add(identity)) return ExtractResult()
        var peerId: String? = null
        var uin: String? = null
        val nested = ArrayList<Any>(MAX_NESTED_PER_LAYER)
        fun consume(value: Any?) {
            if (value == null) return
            val parsed = parseDirectValue(value)
            if (peerId == null && parsed.peerId != null) peerId = parsed.peerId
            if (uin == null && parsed.uin != null) uin = parsed.uin
            if (parsed.peerId == null && parsed.uin == null && nested.size < MAX_NESTED_PER_LAYER && isScannableObject(value)) {
                nested.add(value)
            }
        }
        for (fieldName in fieldPriority) {
            consume(readFieldValue(any, fieldName))
            if (peerId != null && uin != null) break
        }
        for (getterName in getterPriority) {
            consume(readGetterValue(any, getterName))
            if (peerId != null && uin != null) break
        }
        if ((peerId == null || uin == null) && hasClassHint(clazz.name)) {
            scanGenericFields(any, clazz, nested, ::consume)
        }
        if (depth + 1 <= MAX_DEPTH && (peerId == null || uin == null)) {
            for (child in nested) {
                val result = extractIds(child, depth + 1, visited)
                if (peerId == null && result.peerId != null) peerId = result.peerId
                if (uin == null && result.uin != null) uin = result.uin
                if (peerId != null && uin != null) break
            }
        }
        return ExtractResult(peerId = normalizePeerId(peerId), uin = normalizeUin(uin))
    }

    private fun parseDirectValue(value: Any): ExtractResult {
        return when (value) {
            is CharSequence -> parseStringValue(value.toString())
            is Number -> {
                val uin = normalizeUin(value.toLong().toString())
                ExtractResult(uin = uin)
            }
            else -> ExtractResult()
        }
    }

    private fun parseStringValue(rawValue: String): ExtractResult {
        val value = rawValue.trim()
        if (value.isEmpty()) return ExtractResult()
        val peer = normalizePeerId(value).takeIf { looksLikePeerId(it) }
        val uin = normalizeUin(value)
        return ExtractResult(peerId = peer, uin = uin)
    }

    private fun looksLikePeerId(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value.startsWith("u_", ignoreCase = true) && value.length in 8..40) return true
        return value.startsWith("uid_", ignoreCase = true)
    }

    private fun buildTargetSignature(targetPeer: String?, targetUin: String?): String {
        return "${targetPeer.orEmpty()}|${targetUin.orEmpty()}"
    }

    private fun isTargetMatched(
        peerId: String?,
        uin: String?,
        rawValue: Any?,
        targetPeerId: String?,
        targetUin: String?
    ): Boolean {
        val normalizedPeer = normalizePeerId(peerId)
        val normalizedTargetPeer = normalizePeerId(targetPeerId)
        if (!normalizedPeer.isNullOrEmpty() && !normalizedTargetPeer.isNullOrEmpty() && normalizedPeer == normalizedTargetPeer) {
            return true
        }
        val normalizedUin = normalizeUin(uin)
        if (!normalizedUin.isNullOrEmpty() && !targetUin.isNullOrEmpty() && normalizedUin == targetUin) {
            return true
        }
        if (!normalizedUin.isNullOrEmpty() && !normalizedTargetPeer.isNullOrEmpty()) {
            val converted = normalizePeerId(runCatching { QAppUtils.UserUinToPeerID(normalizedUin) }.getOrNull())
            if (!converted.isNullOrEmpty() && converted == normalizedTargetPeer) {
                return true
            }
        }
        if (rawValue is String) {
            val directValue = rawValue.trim()
            if (!targetUin.isNullOrEmpty() && directValue == targetUin) return true
            val directPeer = normalizePeerId(directValue)
            if (!directPeer.isNullOrEmpty() && !normalizedTargetPeer.isNullOrEmpty() && directPeer == normalizedTargetPeer) {
                return true
            }
        }
        return false
    }

    private fun hasClassHint(className: String): Boolean {
        return classKeywordHints.any { className.contains(it, ignoreCase = true) }
    }

    private fun shouldSkipClass(clazz: Class<*>): Boolean {
        if (clazz.isPrimitive || clazz.isArray || clazz.isEnum || clazz.isAnnotation || clazz.isInterface) return true
        if (View::class.java.isAssignableFrom(clazz)) return true
        if (Context::class.java.isAssignableFrom(clazz)) return true
        if (Map::class.java.isAssignableFrom(clazz) || Collection::class.java.isAssignableFrom(clazz)) return true
        val name = clazz.name
        if (name.startsWith("androidx.")) return true
        if (name.startsWith("android.")) return true
        if (name.startsWith("java.")) return true
        if (name.startsWith("javax.")) return true
        if (name.startsWith("kotlin.")) return true
        return false
    }

    private fun isScannableObject(value: Any): Boolean {
        if (value is CharSequence || value is Number || value is Boolean) return false
        val clazz = value.javaClass
        if (clazz.isArray) return false
        if (View::class.java.isAssignableFrom(clazz)) return false
        if (Context::class.java.isAssignableFrom(clazz)) return false
        if (Map::class.java.isAssignableFrom(clazz) || Collection::class.java.isAssignableFrom(clazz)) return false
        return hasClassHint(clazz.name) || nestedNodeNames.any { clazz.name.contains(it, ignoreCase = true) }
    }

    private fun scanGenericFields(
        obj: Any,
        clazz: Class<*>,
        nested: MutableList<Any>,
        consume: (Any?) -> Unit
    ) {
        var count = 0
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java && count < MAX_FIELDS_PER_LAYER) {
            for (field in current.declaredFields) {
                if (count >= MAX_FIELDS_PER_LAYER) break
                if (Modifier.isStatic(field.modifiers)) continue
                val fieldName = field.name
                if (fieldName.startsWith("\$") || fieldName.startsWith("this$")) continue
                if (field.type.isPrimitive) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(obj)
                }.getOrNull()
                consume(value)
                if (value != null && nested.size < MAX_NESTED_PER_LAYER && isScannableObject(value)) {
                    nested.add(value)
                }
                count++
            }
            current = current.superclass
        }
    }

    private fun readFieldValue(obj: Any, targetName: String): Any? {
        var current: Class<*>? = obj.javaClass
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
            if (field == null) {
                current = current.superclass
                continue
            }
            val value = runCatching {
                field.isAccessible = true
                field.get(obj)
            }.getOrNull()
            if (value != null) return value
            current = current.superclass
        }
        return null
    }

    private fun readGetterValue(obj: Any, targetName: String): Any? {
        var current: Class<*>? = obj.javaClass
        while (current != null && current != Any::class.java) {
            val method = current.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                    it.returnType != Void.TYPE &&
                    it.name.equals(targetName, ignoreCase = true)
            }
            if (method == null) {
                current = current.superclass
                continue
            }
            return runCatching {
                method.isAccessible = true
                method.invoke(obj)
            }.getOrNull()
        }
        return null
    }

    private fun isDebugLogEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(KEY_DEBUG_LOG, false)
    }

    private fun shortValue(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        return if (value.length <= 8) value else value.take(4) + "***" + value.takeLast(3)
    }

    private fun logDebug(message: String) {
        Log.d("NtPeerHelper: $message")
    }
}
