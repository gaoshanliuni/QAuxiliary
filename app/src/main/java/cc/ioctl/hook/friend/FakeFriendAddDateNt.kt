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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import cc.hicore.QApp.QAppUtils
import cc.ioctl.util.HostInfo
import cc.ioctl.util.LayoutHelper
import cc.ioctl.util.NtPeerHelper
import cc.ioctl.util.hookAfterIfEnabled
import cc.ioctl.util.hookBeforeIfEnabled
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.core.HookInstaller
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonConfigFunctionHook
import io.github.qauxv.ui.CommonContextWrapper
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.dexkit.DexKitTarget
import io.github.qauxv.util.dexkit.NT_Profile_AddFriendDate_Bind
import io.github.qauxv.util.dexkit.NT_Profile_MemoryDay_Helper
import io.github.qauxv.util.dexkit.NT_Profile_NewDna_Adapter
import io.github.qauxv.util.dexkit.NT_Profile_RelationInfo_Bind
import io.github.qauxv.util.dexkit.NT_Profile_UnbindAddDate_Bind
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

@FunctionHookEntry
@UiItemAgentEntry
object FakeFriendAddDateNt : CommonConfigFunctionHook(
    hookKey = NtPeerHelper.KEY_ENABLED,
    defaultEnabled = true,
    targets = arrayOf(
        NT_Profile_AddFriendDate_Bind,
        NT_Profile_RelationInfo_Bind,
        NT_Profile_UnbindAddDate_Bind,
        NT_Profile_MemoryDay_Helper,
        NT_Profile_NewDna_Adapter
    )
) {

    override val name: String = "自定义好友添加日期"
    override val description: CharSequence = "仅修改指定好友的本地资料页添加日期显示，不影响真实数据"
    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.FRIEND_CATEGORY
    override val valueState: MutableStateFlow<String?> = MutableStateFlow(if (isFeatureEnabledEffective()) "已开启" else "禁用")

    private data class RuntimeRule(
        val addDateMillis: Long,
        val addDateText: String,
        val days: Int
    )

    private data class EffectiveTarget(
        val hasUserConfig: Boolean,
        val targetUin: String?,
        val targetUidOrPeer: String?,
        val targetPeerFromUin: String?
    )

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val DEFAULT_TARGET_UIN = "3394944898"
    private const val DEFAULT_FAKE_ADD_DATE = "2023-01-02"
    private const val DEFAULT_DEBUG_SERVER_URL = "http://192.168.50.60:8848"

    private const val HOOK_INSTALLED_INTIMATE_HEADER = "intimateHeaderHookInstalled"
    private const val HOOK_INSTALLED_INTIMATE_DAYS_COMPUTE = "intimateDaysComputeHookInstalled"
    private const val HOOK_INSTALLED_RELATION_DAY_BIND = "relationshipDayBindHookInstalled"
    private const val HOOK_INSTALLED_BECOME_FRIEND_BIND = "becomeFriendDaysBindHookInstalled"
    private const val HOOK_INSTALLED_MEMORY_TIMELINE = "memoryTimelineHookInstalled"
    private const val HOOK_INSTALLED_DNA_BIND = "dnaBindHookInstalled"
    private const val HOOK_INSTALLED_FRIEND_CLUE_ENTRY = "friendClueEntryHookInstalled"
    private const val HOOK_INSTALLED_FRIEND_CLUE_WEBVIEW = "friendClueWebViewHookInstalled"
    private const val HOOK_INSTALLED_FRIEND_CLUE_JSBRIDGE = "friendClueJsBridgeHookInstalled"
    private const val HOOK_INSTALLED_FRIEND_CLUE_DOM = "friendClueDomHookInstalled"

    private const val HOOK_HIT_INTIMATE_HEADER = "intimateHeaderHitCount"
    private const val HOOK_HIT_INTIMATE_DAYS_COMPUTE = "daysComputeHitCount"
    private const val HOOK_HIT_RELATION_DAY_BIND = "relationshipBindHitCount"
    private const val HOOK_HIT_BECOME_FRIEND_BIND = "becomeFriendDaysBindHitCount"
    private const val HOOK_HIT_MEMORY_TIMELINE = "memoryTimelineHitCount"
    private const val HOOK_HIT_DNA_BIND = "dnaBindHitCount"
    private const val HOOK_HIT_FRIEND_CLUE_ENTRY = "friendClueEntryHitCount"
    private const val HOOK_HIT_FRIEND_CLUE_WEBVIEW = "friendClueWebViewHitCount"
    private const val HOOK_HIT_FRIEND_CLUE_JSBRIDGE = "friendClueJsBridgeHitCount"
    private const val HOOK_HIT_FRIEND_CLUE_DOM = "friendClueDomFallbackHitCount"

    private const val COUNTER_INIT_CALLED = "initCalled"
    private const val COUNTER_DEX_RESOLVE_SUCCESS = "dexTargetResolveSuccessCount"
    private const val COUNTER_DEX_RESOLVE_FAILED = "dexTargetResolveFailedCount"
    private const val COUNTER_HOOK_INSTALL_SUCCESS = "hookInstallSuccessCount"
    private const val COUNTER_HOOK_INSTALL_FAILED = "hookInstallFailedCount"
    private const val COUNTER_TARGET_MATCHED = "targetMatchedCount"
    private const val COUNTER_REWRITE_SUCCESS = "rewriteSuccessCount"
    private const val COUNTER_REWRITE_SKIPPED = "rewriteSkippedCount"

    private val dateKeywordList = arrayOf(
        "成为好友", "添加好友", "好友添加", "添加时间", "加好友", "加为好友", "已成为好友", "已绑定", "友谊时间线", "DNA"
    )

    private val friendClueUrlHints = arrayOf("ti.qq.com/friends/recall", "friendclue", "relationx/friend")
    private val friendClueIdentityQueryKeys = arrayOf("uin", "uid", "peerId", "peerid")
    private val friendClueTimeQueryKeys = arrayOf("addTime", "addFriendTime", "friendTime", "relationTime", "startTime", "time", "ts")
    private val friendClueDayQueryKeys = arrayOf("days", "becomeFriendDays", "relationDays", "friendDays")
    private val friendClueDateQueryKeys = arrayOf("friendDate", "addDate")

    @Volatile
    private var mLastFriendClueDomPatchSignature: String = ""

    @Volatile
    private var mLastFriendClueDomPatchTs: Long = 0L

    @Volatile
    private var mInitReadyForDebugChannel: Boolean = false

    @Volatile
    private var mLastError: String = "-"

    @Volatile
    private var mStartupDiagLogged = false

    private val mHookInstalledState = ConcurrentHashMap<String, Boolean>(16)
    private val mHookHitCounters = ConcurrentHashMap<String, AtomicLong>(32)

    private val dateRegex = Regex("(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{1,2}月\\d{1,2}日)")
    private val dayRegex = Regex("((?:成为好友|已成为好友|已绑定)\\s*)(\\d+)(\\s*天)")

    override val onUiItemClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        showConfigDialog(activity)
    }

    override fun initOnce(): Boolean {
        increaseCounter(COUNTER_INIT_CALLED)
        Log.d("FakeFriendAddDateNt initOnce called")
        refreshDebugTransportState()
        printStartupDiagnostics()
        if (!QAppUtils.isQQnt()) {
            mInitReadyForDebugChannel = false
            refreshDebugTransportState()
            reportHookEvent(
                event = "init_skipped_non_nt",
                hookPoint = "initOnce",
                page = "startup",
                strategy = "guard",
                targetMatched = false,
                message = "skip install because host is not QQNT"
            )
            return true
        }
        installHeaderModelHook()
        installRelationDaysCalcHook()
        installRelationRenderHook()
        installUnbindCardHook()
        installMemoryDayHook()
        installDnaItemBindHook()
        installFriendClueEntryHook()
        installFriendClueWebViewLoadHook()
        installFriendClueJsBridgeHook()
        installFriendCluePageFinishedHook()
        mInitReadyForDebugChannel = true
        refreshDebugTransportState()
        printStartupDiagnostics()
        return true
    }

    private fun installHeaderModelHook() {
        val method = resolveHookMethod(
            hookTag = "intimate_header_model",
            target = NT_Profile_AddFriendDate_Bind,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.activity.aio.intimate.header.f")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 1 &&
                p[0].name.contains("activity.aio.intimate.header.g")
        } ?: return
        markHookInstalled(HOOK_INSTALLED_INTIMATE_HEADER, method)
        hookBeforeIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isProfilePageEnabled()) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) {
                    logTargetMismatch("intimate_header", hookPointOf(method), thisObj, *param.args)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_INTIMATE_HEADER)
                val model = param.args.getOrNull(0) ?: return@runCatching
                val patchedModel = rebuildHeaderModel(model, rule.days) ?: return@runCatching
                param.args[0] = patchedModel
                reportHookEvent(
                    event = "intimate_header_model_rewrite",
                    hookPoint = hookPointOf(method),
                    page = "intimate_relation",
                    strategy = "data_layer",
                    targetMatched = true,
                    message = "hit intimate relation bind hook",
                    rule = rule,
                    extras = mapOf("calculatedDays" to rule.days),
                    rewriteChanged = true
                )
                debugLog("hook header model: class=${thisObj.javaClass.name}, days=${rule.days}")
            }.onFailure { onHookError("hook/intimate_header_model", it) }
        }
    }

    private fun installRelationDaysCalcHook() {
        val method = resolveHookMethod(
            hookTag = "intimate_days_compute",
            target = NT_Profile_RelationInfo_Bind,
            fallbackClassNames = arrayOf("vx.o")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Int::class.javaPrimitiveType &&
                p.size == 2 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType
        } ?: return
        markHookInstalled(HOOK_INSTALLED_INTIMATE_DAYS_COMPUTE, method)
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@runCatching
                val type = param.args.getOrNull(0) as? Int ?: return@runCatching
                if (type != 0) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) {
                    logTargetMismatch("intimate_relation", hookPointOf(method), thisObj, *param.args)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_INTIMATE_DAYS_COMPUTE)
                val rule = resolveRuntimeRule() ?: return@runCatching
                param.result = rule.days
                setIntFieldIfPresent(thisObj, arrayOf("mIntimateRealDays", "intimateRealDays"), rule.days)
                reportHookEvent(
                    event = "intimate_days_compute_override",
                    hookPoint = hookPointOf(method),
                    page = "intimate_relation",
                    strategy = "compute_layer",
                    targetMatched = true,
                    message = "override become-friend days result",
                    rule = rule,
                    extras = mapOf("calculatedDays" to rule.days),
                    rewriteChanged = true
                )
                debugLog("hook relation days calc: class=${thisObj.javaClass.name}, days=${rule.days}")
            }.onFailure { onHookError("hook/intimate_days_compute", it) }
        }
    }

    private fun installRelationRenderHook() {
        val method = resolveHookMethod(
            hookTag = "relationship_day_bind",
            target = NT_Profile_RelationInfo_Bind,
            fallbackClassNames = arrayOf("vx.o")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == String::class.java
        } ?: return
        markHookInstalled(HOOK_INSTALLED_RELATION_DAY_BIND, method)
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) {
                    logTargetMismatch("intimate_relation", hookPointOf(method), thisObj, *param.args)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_RELATION_DAY_BIND)
                val rule = resolveRuntimeRule() ?: return@runCatching
                val dayView = getFieldValue(thisObj, arrayOf("mTvRelationshipDay"), TextView::class.java) ?: return@runCatching
                val oldText = dayView.text?.toString().orEmpty()
                val newText = replaceFriendTimeText(oldText, rule)
                if (newText != oldText) {
                    dayView.text = newText
                    reportHookEvent(
                        event = "relationship_day_text_bind",
                        hookPoint = hookPointOf(method),
                        page = "intimate_relation",
                        strategy = "bind_layer",
                        targetMatched = true,
                        message = "rewrite relationship day text on bind",
                        rule = rule,
                        extras = mapOf(
                            "originalTextMasked" to maskTextForReport(oldText),
                            "newText" to newText,
                            "calculatedDays" to rule.days
                        ),
                        rewriteChanged = true
                    )
                    debugLog("hook relation render: text=$newText")
                } else {
                    markRewriteResult(false)
                }
            }.onFailure { onHookError("hook/relationship_day_bind", it) }
        }
    }

    private fun installUnbindCardHook() {
        val method = resolveHookMethod(
            hookTag = "become_friend_bind",
            target = NT_Profile_UnbindAddDate_Bind,
            fallbackClassNames = arrayOf("vx.w")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 3 &&
                p[1] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(p[2])
        } ?: return
        markHookInstalled(HOOK_INSTALLED_BECOME_FRIEND_BIND, method)
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) {
                    logTargetMismatch("chat_setting", hookPointOf(method), thisObj, *param.args)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_BECOME_FRIEND_BIND)
                val rule = resolveRuntimeRule() ?: return@runCatching
                val dayView = getFieldValue(thisObj, arrayOf("mBecomeFriendDays"), TextView::class.java) ?: return@runCatching
                val oldText = dayView.text?.toString().orEmpty()
                val targetText = rule.days.toString()
                if (oldText != targetText) {
                    dayView.text = targetText
                    reportHookEvent(
                        event = "become_friend_days_bind",
                        hookPoint = hookPointOf(method),
                        page = "chat_setting",
                        strategy = "bind_layer",
                        targetMatched = true,
                        message = "rewrite become-friend days card",
                        rule = rule,
                        extras = mapOf(
                            "originalTextMasked" to maskTextForReport(oldText),
                            "newText" to targetText,
                            "calculatedDays" to rule.days
                        ),
                        rewriteChanged = true
                    )
                    debugLog("hook unbind card days: $targetText")
                } else {
                    markRewriteResult(false)
                }
            }.onFailure { onHookError("hook/become_friend_bind", it) }
        }
    }

    private fun installMemoryDayHook() {
        val method = resolveHookMethod(
            hookTag = "memory_timeline_bind",
            target = NT_Profile_MemoryDay_Helper,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.activity.aio.intimate.k")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                p[0] == Context::class.java &&
                List::class.java.isAssignableFrom(p[1])
        } ?: return
        markHookInstalled(HOOK_INSTALLED_MEMORY_TIMELINE, method)
        hookBeforeIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isProfilePageEnabled()) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                val listArg = param.args.getOrNull(1) as? MutableList<Any> ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) {
                    logTargetMismatch("friend_timeline", hookPointOf(method), thisObj, *param.args)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_MEMORY_TIMELINE)
                val rule = resolveRuntimeRule() ?: return@runCatching
                for (item in listArg) {
                    patchMemoryDayItem(item, rule)
                }
                reportHookEvent(
                    event = "memory_timeline_rewrite",
                    hookPoint = hookPointOf(method),
                    page = "friend_timeline",
                    strategy = "data_layer",
                    targetMatched = true,
                    message = "rewrite memory timeline day models",
                    rule = rule,
                    extras = mapOf("itemCount" to listArg.size, "calculatedDays" to rule.days),
                    rewriteChanged = true
                )
                debugLog("hook memory day list: size=${listArg.size}")
            }.onFailure { onHookError("hook/memory_timeline_bind", it) }
        }
    }

    private fun installDnaItemBindHook() {
        val method = resolveHookMethod(
            hookTag = "dna_item_bind",
            target = NT_Profile_NewDna_Adapter,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.activity.aio.intimate.view.IntimateContentItemNewDnaView\$c")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                m.name == "onBindViewHolder" &&
                p.size == 2 &&
                RecyclerView.ViewHolder::class.java.isAssignableFrom(p[0]) &&
                p[1] == Int::class.javaPrimitiveType
        } ?: return
        markHookInstalled(HOOK_INSTALLED_DNA_BIND, method)
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isProfilePageEnabled()) return@runCatching
                val adapterObj = param.thisObject ?: return@runCatching
                val holder = param.args.getOrNull(0) ?: return@runCatching
                if (!isTargetContext(adapterObj, holder, *param.args)) {
                    logTargetMismatch("dna_timeline", hookPointOf(method), adapterObj, holder, *param.args)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_DNA_BIND)
                val rule = resolveRuntimeRule() ?: return@runCatching
                patchDnaViewHolder(holder, rule)
                reportHookEvent(
                    event = "dna_item_bind_rewrite",
                    hookPoint = hookPointOf(method),
                    page = "dna_timeline",
                    strategy = "bind_layer",
                    targetMatched = true,
                    message = "rewrite dna card bind text",
                    rule = rule,
                    rewriteChanged = true
                )
            }.onFailure { onHookError("hook/dna_item_bind", it) }
        }
    }

    private fun installFriendClueEntryHook() {
        val method = resolveHookMethod(
            hookTag = "friend_clue_entry",
            target = null,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.relationx.friendclue.c")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                Activity::class.java.isAssignableFrom(p[0]) &&
                p[1] == String::class.java
        } ?: return
        markHookInstalled(HOOK_INSTALLED_FRIEND_CLUE_ENTRY, method)
        hookBeforeIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady()) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val activity = param.args.getOrNull(0) as? Activity
                val secondArg = param.args.getOrNull(1)?.toString()?.trim().orEmpty()
                if (secondArg.isEmpty()) return@runCatching
                val sourceUrl = if (looksLikeHttpUrl(secondArg)) secondArg else buildFriendClueUrl(secondArg)
                val urlMeta = parseUrlMeta(sourceUrl)
                val (dbgUin, dbgUid, dbgPeer) = getUrlIdentity(sourceUrl)
                val targetHit = isTargetFriendForUrl(
                    sourceUrl,
                    if (looksLikeHttpUrl(secondArg)) null else secondArg,
                    activity,
                    param.thisObject
                )
                markHookHit(HOOK_HIT_FRIEND_CLUE_ENTRY)
                reportHookEvent(
                    event = "friend_clue_h5_entry",
                    hookPoint = hookPointOf(method),
                    page = "friend_clue_h5",
                    strategy = "h5_entry",
                    targetMatched = targetHit,
                    message = "friend clue h5 entry hook",
                    rule = rule,
                    extras = mapOf(
                        "host" to (urlMeta?.host ?: "-"),
                        "path" to (urlMeta?.path ?: "-"),
                        "queryKeys" to (urlMeta?.queryKeys ?: emptyList<String>()).joinToString(","),
                        "targetType" to currentTargetType(),
                        "targetMasked" to currentTargetMasked()
                    )
                )
                if (NtPeerHelper.isDebugEnabled()) {
                    debugLog(
                        "friend clue entry: class=${param.thisObject?.javaClass?.name}, " +
                            "argIsUrl=${looksLikeHttpUrl(secondArg)}, host=${urlMeta?.host ?: "-"}, " +
                            "path=${urlMeta?.path ?: "-"}, keys=${urlMeta?.queryKeys ?: emptyList<String>()}, " +
                            "targetUin=${maskId(getTargetUinOrDefault())}, " +
                            "uin=${maskId(dbgUin)}, uid=${maskId(dbgUid)}, peer=${maskId(dbgPeer)}, " +
                            "match=$targetHit"
                    )
                }
                if (!targetHit) {
                    logTargetMismatch("friend_clue_h5", hookPointOf(method), sourceUrl, activity, param.thisObject)
                    reportHookEvent(
                        event = "friend_clue_h5_entry",
                        hookPoint = hookPointOf(method),
                        page = "friend_clue_h5",
                        strategy = "h5_entry",
                        targetMatched = false,
                        message = "friend clue entry skipped: target not matched",
                        rule = rule
                    )
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                val rewrite = rewriteFriendClueUrl(sourceUrl, rule)
                if (!rewrite.changed) {
                    markRewriteResult(false)
                    return@runCatching
                }
                if (looksLikeHttpUrl(secondArg)) {
                    param.args[1] = rewrite.newUrl
                } else {
                    val encodedTail = buildFriendClueTailFromUrl(rewrite.newUrl, secondArg)
                    if (!encodedTail.isNullOrEmpty()) {
                        param.args[1] = encodedTail
                    }
                }
                reportHookEvent(
                    event = "friend_clue_h5_entry",
                    hookPoint = hookPointOf(method),
                    page = "friend_clue_h5",
                    strategy = "url_params",
                    targetMatched = true,
                    message = "rewrite friend clue url params",
                    rule = rule,
                    extras = mapOf(
                        "touchedKeys" to rewrite.touchedKeys.joinToString(",")
                    ),
                    rewriteChanged = true
                )
                debugLog("friend clue strategy=url-params, touched=${rewrite.touchedKeys}")
            }.onFailure { onHookError("hook/friend_clue_entry", it) }
        }
    }

    private fun installFriendClueWebViewLoadHook() {
        hookFriendClueLoadUrlClass("com.tencent.smtt.sdk.WebView")
        hookFriendClueLoadUrlClass("android.webkit.WebView")
    }

    private fun hookFriendClueLoadUrlClass(className: String) {
        val clazz = runCatching { Initiator.loadClass(className) }.getOrElse {
            increaseCounter(COUNTER_DEX_RESOLVE_FAILED)
            increaseCounter(COUNTER_HOOK_INSTALL_FAILED)
            onHookError("resolve/friend_clue_webview/$className", it)
            return
        }
        val hookedSignatures = HashSet<String>(4)
        var hasHooked = false
        for (method in clazz.declaredMethods) {
            val p = method.parameterTypes
            if (method.name != "loadUrl") continue
            if (p.isEmpty() || p[0] != String::class.java) continue
            if (p.size != 1 && !(p.size == 2 && Map::class.java.isAssignableFrom(p[1]))) continue
            val signature = "${clazz.name}#${method.name}${p.joinToString(prefix = "(", postfix = ")") { it.name }}"
            if (!hookedSignatures.add(signature)) continue
            hasHooked = true
            debugLog("resolve success hook=friend_clue_webview, method=$signature")
            hookBeforeIfEnabled(method) { param ->
                runCatching {
                    if (!isRuntimeReady()) return@runCatching
                    val oldUrl = param.args.getOrNull(0) as? String ?: return@runCatching
                    if (!isFriendClueUrl(oldUrl)) return@runCatching
                    val rule = resolveRuntimeRule() ?: return@runCatching
                    val webView = param.thisObject
                    val activity = resolveActivityFromWebView(webView)
                    if (!isTargetFriendForUrl(oldUrl, null, activity, webView, param.thisObject)) {
                        logTargetMismatch("friend_clue_h5", hookPointOf(method), oldUrl, activity, webView, param.thisObject)
                        return@runCatching
                    }
                    increaseCounter(COUNTER_TARGET_MATCHED)
                    val rewrite = rewriteFriendClueUrl(oldUrl, rule)
                    markHookHit(HOOK_HIT_FRIEND_CLUE_WEBVIEW)
                    if (NtPeerHelper.isDebugEnabled()) {
                        val meta = parseUrlMeta(oldUrl)
                        val (dbgUin, dbgUid, dbgPeer) = getUrlIdentity(oldUrl)
                        debugLog(
                            "friend clue webview.loadUrl: web=${webView?.javaClass?.name}, " +
                                "host=${meta?.host ?: "-"}, path=${meta?.path ?: "-"}, keys=${meta?.queryKeys ?: emptyList<String>()}, " +
                                "uin=${maskId(dbgUin)}, uid=${maskId(dbgUid)}, peer=${maskId(dbgPeer)}"
                        )
                    }
                    if (rewrite.changed) {
                        param.args[0] = rewrite.newUrl
                        reportHookEvent(
                            event = "friend_clue_webview_url_rewrite",
                            hookPoint = hookPointOf(method),
                            page = "friend_clue_h5",
                            strategy = "webview_url",
                            targetMatched = true,
                            message = "rewrite friend clue webview loadUrl",
                            rule = rule,
                            extras = mapOf("touchedKeys" to rewrite.touchedKeys.joinToString(",")),
                            rewriteChanged = true
                        )
                        debugLog("friend clue strategy=webview-url, touched=${rewrite.touchedKeys}")
                    } else {
                        markRewriteResult(false)
                    }
                }.onFailure { onHookError("hook/friend_clue_webview_loadurl", it) }
            }
        }
        if (hasHooked) {
            markHookInstalled(HOOK_INSTALLED_FRIEND_CLUE_WEBVIEW, clazz)
            increaseCounter(COUNTER_DEX_RESOLVE_SUCCESS)
            debugLog("resolve success hook=friend_clue_webview, class=${clazz.name}")
        } else {
            increaseCounter(COUNTER_DEX_RESOLVE_FAILED)
            increaseCounter(COUNTER_HOOK_INSTALL_FAILED)
            val ex = NoSuchMethodException("No loadUrl candidate in ${clazz.name}")
            onHookError("resolve/friend_clue_webview/no_method", ex)
        }
    }

    private fun installFriendClueJsBridgeHook() {
        val clazz = runCatching { Initiator.loadClass("com.tencent.mobileqq.webview.swift.JsBridgeListener") }.getOrElse {
            increaseCounter(COUNTER_DEX_RESOLVE_FAILED)
            increaseCounter(COUNTER_HOOK_INSTALL_FAILED)
            onHookError("resolve/friend_clue_jsbridge/class", it)
            return
        }
        var hasMethod = false
        val jsonMethod = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            m.name == "d" && p.size == 1 && p[0] == JSONObject::class.java
        }
        if (jsonMethod != null) {
            hasMethod = true
            increaseCounter(COUNTER_DEX_RESOLVE_SUCCESS)
            debugLog("resolve success hook=friend_clue_jsbridge, method=${hookPointOf(jsonMethod)}")
            markHookInstalled(HOOK_INSTALLED_FRIEND_CLUE_JSBRIDGE, jsonMethod)
            hookBeforeIfEnabled(jsonMethod) { param ->
                runCatching {
                    if (!isRuntimeReady()) return@runCatching
                    val payload = param.args.getOrNull(0) as? JSONObject ?: return@runCatching
                    val listener = param.thisObject ?: return@runCatching
                    val webView = extractWebViewFromJsBridgeListener(listener) ?: return@runCatching
                    val url = getWebViewCurrentUrl(webView) ?: return@runCatching
                    if (!isFriendClueUrl(url)) return@runCatching
                    val rule = resolveRuntimeRule() ?: return@runCatching
                    val activity = resolveActivityFromWebView(webView)
                    if (!isTargetFriendForUrl(url, null, activity, webView, listener)) {
                        logTargetMismatch("friend_clue_h5", hookPointOf(jsonMethod), url, activity, webView, listener)
                        return@runCatching
                    }
                    increaseCounter(COUNTER_TARGET_MATCHED)
                    markHookHit(HOOK_HIT_FRIEND_CLUE_JSBRIDGE)
                    if (rewriteFriendClueJson(payload, rule, 0)) {
                        reportHookEvent(
                            event = "friend_clue_jsbridge_rewrite",
                            hookPoint = hookPointOf(jsonMethod),
                            page = "friend_clue_h5",
                            strategy = "jsbridge_layer",
                            targetMatched = true,
                            message = "rewrite friend clue jsbridge json payload",
                            rule = rule,
                            rewriteChanged = true
                        )
                        debugLog("friend clue strategy=jsbridge-json, method=d")
                    } else {
                        markRewriteResult(false)
                    }
                }.onFailure { onHookError("hook/friend_clue_jsbridge_d", it) }
            }
        }
        val objMethod = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            m.name == "c" && p.size == 1
        }
        if (objMethod != null) {
            hasMethod = true
            increaseCounter(COUNTER_DEX_RESOLVE_SUCCESS)
            debugLog("resolve success hook=friend_clue_jsbridge, method=${hookPointOf(objMethod)}")
            markHookInstalled(HOOK_INSTALLED_FRIEND_CLUE_JSBRIDGE, objMethod)
            hookBeforeIfEnabled(objMethod) { param ->
                runCatching {
                    if (!isRuntimeReady()) return@runCatching
                    val listener = param.thisObject ?: return@runCatching
                    val webView = extractWebViewFromJsBridgeListener(listener) ?: return@runCatching
                    val url = getWebViewCurrentUrl(webView) ?: return@runCatching
                    if (!isFriendClueUrl(url)) return@runCatching
                    val rule = resolveRuntimeRule() ?: return@runCatching
                    val activity = resolveActivityFromWebView(webView)
                    if (!isTargetFriendForUrl(url, null, activity, webView, listener)) {
                        logTargetMismatch("friend_clue_h5", hookPointOf(objMethod), url, activity, webView, listener)
                        return@runCatching
                    }
                    increaseCounter(COUNTER_TARGET_MATCHED)
                    markHookHit(HOOK_HIT_FRIEND_CLUE_JSBRIDGE)
                    val payload = param.args.getOrNull(0) ?: return@runCatching
                    when (payload) {
                        is JSONObject -> {
                            if (rewriteFriendClueJson(payload, rule, 0)) {
                                reportHookEvent(
                                    event = "friend_clue_jsbridge_rewrite",
                                    hookPoint = hookPointOf(objMethod),
                                    page = "friend_clue_h5",
                                    strategy = "jsbridge_layer",
                                    targetMatched = true,
                                    message = "rewrite friend clue jsbridge JSONObject payload",
                                    rule = rule,
                                    rewriteChanged = true
                                )
                                debugLog("friend clue strategy=jsbridge-json, method=c(JSONObject)")
                            } else {
                                markRewriteResult(false)
                            }
                        }

                        is String -> {
                            val updated = rewriteFriendClueJsonString(payload, rule) ?: run {
                                markRewriteResult(false)
                                return@runCatching
                            }
                            if (updated != payload) {
                                param.args[0] = updated
                                reportHookEvent(
                                    event = "friend_clue_jsbridge_rewrite",
                                    hookPoint = hookPointOf(objMethod),
                                    page = "friend_clue_h5",
                                    strategy = "jsbridge_layer",
                                    targetMatched = true,
                                    message = "rewrite friend clue jsbridge String payload",
                                    rule = rule,
                                    rewriteChanged = true
                                )
                                debugLog("friend clue strategy=jsbridge-json, method=c(String)")
                            } else {
                                markRewriteResult(false)
                            }
                        }
                    }
                }.onFailure { onHookError("hook/friend_clue_jsbridge_c", it) }
            }
        }
        if (!hasMethod) {
            increaseCounter(COUNTER_DEX_RESOLVE_FAILED)
            increaseCounter(COUNTER_HOOK_INSTALL_FAILED)
            val ex = NoSuchMethodException("No jsbridge method d/c in ${clazz.name}")
            onHookError("resolve/friend_clue_jsbridge/no_method", ex)
        }
    }

    private fun installFriendCluePageFinishedHook() {
        val method = resolveHookMethod(
            hookTag = "friend_clue_dom_page_finished",
            target = null,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.webview.swift.al", "com.tencent.mobileqq.activity.QQBrowserActivity")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                m.name == "onPageFinished" &&
                p.size == 2 &&
                p[1] == String::class.java
        } ?: return
        markHookInstalled(HOOK_INSTALLED_FRIEND_CLUE_DOM, method)
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady()) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val webView = param.args.getOrNull(0) ?: return@runCatching
                val url = (param.args.getOrNull(1) as? String)?.takeIf { it.isNotBlank() } ?: getWebViewCurrentUrl(webView) ?: return@runCatching
                if (!isFriendClueUrl(url)) return@runCatching
                val activity = resolveActivityFromWebView(webView) ?: (param.thisObject as? Activity)
                if (!isTargetFriendForUrl(url, null, activity, webView, param.thisObject)) {
                    logTargetMismatch("friend_clue_h5", hookPointOf(method), url, activity, webView, param.thisObject)
                    return@runCatching
                }
                increaseCounter(COUNTER_TARGET_MATCHED)
                markHookHit(HOOK_HIT_FRIEND_CLUE_DOM)
                if (NtPeerHelper.isDebugEnabled()) {
                    val meta = parseUrlMeta(url)
                    val (dbgUin, dbgUid, dbgPeer) = getUrlIdentity(url)
                    val extras = getIntentExtraKeys(activity)
                    debugLog(
                            "friend clue pageFinished: entry=${param.thisObject?.javaClass?.name}, " +
                            "activity=${activity?.javaClass?.name ?: "-"}, web=${webView.javaClass.name}, " +
                            "host=${meta?.host ?: "-"}, path=${meta?.path ?: "-"}, keys=${meta?.queryKeys ?: emptyList<String>()}, " +
                            "targetUin=${maskId(getTargetUinOrDefault())}, " +
                            "uin=${maskId(dbgUin)}, uid=${maskId(dbgUid)}, peer=${maskId(dbgPeer)}, extras=$extras"
                    )
                }
                if (!shouldInjectFriendClueDomPatch(webView, url)) {
                    markRewriteResult(false)
                    return@runCatching
                }
                injectFriendClueDomPatch(webView, rule)
                reportHookEvent(
                    event = "friend_clue_dom_fallback",
                    hookPoint = hookPointOf(method),
                    page = "friend_clue_h5",
                    strategy = "dom_fallback",
                    targetMatched = true,
                    message = "inject friend clue dom fallback patch",
                    rule = rule,
                    rewriteChanged = true
                )
                debugLog("friend clue strategy=dom-fallback, pageFinished injected")
            }.onFailure { onHookError("hook/friend_clue_dom_page_finished", it) }
        }
    }

    private data class UrlMeta(
        val host: String,
        val path: String,
        val queryKeys: List<String>
    )

    private data class UrlRewriteResult(
        val newUrl: String,
        val changed: Boolean,
        val touchedKeys: List<String>
    )

    private fun rebuildHeaderModel(model: Any, fakeDays: Int): Any? {
        val clazz = model.javaClass
        val constructor = clazz.declaredConstructors.firstOrNull {
            val p = it.parameterTypes
            p.size == 8 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType &&
                p[4] == Long::class.javaPrimitiveType &&
                p[5] == Int::class.javaPrimitiveType &&
                p[6] == Long::class.javaPrimitiveType &&
                p[7] == String::class.java
        } ?: return null
        val type = invokeIntGetter(model, "getType") ?: return null
        val level = invokeIntGetter(model, "getLevel") ?: return null
        val lightUpTime = invokeLongGetter(model, "getLightUpTime") ?: 0L
        val scores = invokeIntGetter(model, "getScores") ?: 0
        val flag = invokeLongGetter(model, "getFlag") ?: 0L
        val extendName = invokeStringGetter(model, "getExtendName") ?: ""
        val animDays = max(0, fakeDays - 1)
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(type, level, animDays, fakeDays, lightUpTime, scores, flag, extendName)
        }.getOrNull()
    }

    private fun patchMemoryDayItem(item: Any, rule: RuntimeRule) {
        val wordingField = findField(item.javaClass, "wording") ?: return
        val oldWording = runCatching {
            wordingField.isAccessible = true
            wordingField.get(item) as? String
        }.getOrNull().orEmpty()
        if (oldWording.isNotEmpty()) {
            val newWording = replaceFriendTimeText(oldWording, rule)
            if (newWording != oldWording) {
                runCatching { wordingField.set(item, newWording) }
                debugLog("patch memory wording: ${newWording.take(48)}")
            }
        }
        val dateField = findField(item.javaClass, "date") ?: return
        runCatching {
            dateField.isAccessible = true
            when (dateField.type) {
                java.lang.Long.TYPE, java.lang.Long::class.java -> dateField.set(item, rule.addDateMillis)
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> dateField.set(item, (rule.addDateMillis / 1000L).toInt())
            }
        }
    }

    private fun patchDnaViewHolder(holder: Any, rule: RuntimeRule) {
        val titleView = getFieldValue(holder, arrayOf("f163512n"), TextView::class.java)
        if (titleView != null) {
            val old = titleView.text?.toString().orEmpty()
            val new = replaceFriendTimeText(old, rule)
            if (new != old) {
                titleView.text = new
                debugLog("patch dna title: ${new.take(40)}")
            }
        }
        val detailContainer = getFieldValue(holder, arrayOf("f163513o"), ViewGroup::class.java)
        if (detailContainer != null) {
            for (i in 0 until detailContainer.childCount) {
                val child = detailContainer.getChildAt(i)
                if (child is TextView) {
                    val old = child.text?.toString().orEmpty()
                    val new = replaceFriendTimeText(old, rule)
                    if (new != old) {
                        child.text = new
                    }
                }
            }
        }
    }

    private fun looksLikeHttpUrl(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    private fun buildFriendClueUrl(uin: String): String {
        return "https://ti.qq.com/friends/recall?uin=${Uri.encode(uin)}"
    }

    private fun parseUrlMeta(url: String?): UrlMeta? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching {
            val uri = Uri.parse(raw)
            val keys = uri.queryParameterNames.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
            UrlMeta(host = uri.host.orEmpty(), path = uri.path.orEmpty(), queryKeys = keys)
        }.getOrNull()
    }

    private fun getUrlIdentity(url: String?): Triple<String?, String?, String?> {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return Triple(null, null, null)
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return Triple(null, null, null)
        val uin = uri.getQueryParameter("uin") ?: uri.getQueryParameter("friendUin")
        val uid = uri.getQueryParameter("uid") ?: uri.getQueryParameter("friendUid")
        var peer: String? = null
        for (key in friendClueIdentityQueryKeys) {
            if (!key.equals("peerId", ignoreCase = true) && !key.equals("peerid", ignoreCase = true)) continue
            peer = uri.getQueryParameter(key)
            if (!peer.isNullOrEmpty()) break
        }
        return Triple(uin, uid, peer)
    }

    private fun isFriendClueUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val lower = raw.lowercase(Locale.ROOT)
        return friendClueUrlHints.any { lower.contains(it) }
    }

    private fun isFriendClueTimeQueryKey(key: String): Boolean {
        return friendClueTimeQueryKeys.any { it.equals(key, ignoreCase = true) }
    }

    private fun isFriendClueDayQueryKey(key: String): Boolean {
        return friendClueDayQueryKeys.any { it.equals(key, ignoreCase = true) }
    }

    private fun isFriendClueDateQueryKey(key: String): Boolean {
        if (friendClueDateQueryKeys.any { it.equals(key, ignoreCase = true) }) return true
        return key.contains("date", ignoreCase = true) && key.contains("friend", ignoreCase = true)
    }

    private fun buildFriendClueTailFromUrl(url: String, fallbackUin: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val baseUin = uri.getQueryParameter("uin").orEmpty().ifEmpty { fallbackUin }
        if (baseUin.isEmpty()) return null
        val sb = StringBuilder(baseUin)
        for (key in uri.queryParameterNames) {
            if (key.equals("uin", ignoreCase = true)) continue
            val values = uri.getQueryParameters(key)
            if (values.isEmpty()) {
                sb.append('&').append(Uri.encode(key)).append('=')
                continue
            }
            for (value in values) {
                sb.append('&')
                    .append(Uri.encode(key))
                    .append('=')
                    .append(Uri.encode(value))
            }
        }
        return sb.toString()
    }

    private fun isTargetFriendForUrl(
        url: String?,
        fallbackUin: String? = null,
        activity: Activity? = null,
        vararg candidates: Any?
    ): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            if (uri != null) {
                val uin = uri.getQueryParameter("uin") ?: uri.getQueryParameter("friendUin") ?: fallbackUin
                val uid = uri.getQueryParameter("uid")
                var peer: String? = null
                for (key in friendClueIdentityQueryKeys) {
                    if (!key.equals("peerId", ignoreCase = true) && !key.equals("peerid", ignoreCase = true)) continue
                    peer = uri.getQueryParameter(key)
                    if (!peer.isNullOrEmpty()) break
                }
                if (isTargetByIdentity(uin, uid, peer)) {
                    return true
                }
            }
        }
        if (isTargetByIdentity(fallbackUin, null, null)) {
            return true
        }
        val extras = activity?.intent?.extras
        if (extras != null) {
            val uin = runCatching { extras.getString("uin") ?: extras.getString("friend_uin") ?: extras.getString("friendUin") }.getOrNull()
            val uid = runCatching { extras.getString("uid") ?: extras.getString("friendUid") }.getOrNull()
            val peer = runCatching { extras.getString("peerId") ?: extras.getString("peerid") }.getOrNull()
            if (isTargetByIdentity(uin, uid, peer)) {
                return true
            }
        }
        for (candidate in candidates) {
            if (isTargetPeerWithDefaults(candidate)) {
                return true
            }
        }
        return false
    }

    private fun isTargetByIdentity(uin: String?, uid: String?, peerId: String?): Boolean {
        return matchesTargetIdentityWithDefaults(peerId) ||
            matchesTargetIdentityWithDefaults(uid) ||
            matchesTargetIdentityWithDefaults(uin)
    }

    private fun rewriteFriendClueUrl(url: String, rule: RuntimeRule): UrlRewriteResult {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return UrlRewriteResult(url, false, emptyList())
        val keys = uri.queryParameterNames
        if (keys.isEmpty()) return UrlRewriteResult(url, false, emptyList())
        val hasCandidate = keys.any { key ->
            isFriendClueTimeQueryKey(key) || isFriendClueDayQueryKey(key) || isFriendClueDateQueryKey(key)
        }
        if (!hasCandidate) return UrlRewriteResult(url, false, emptyList())
        val builder = uri.buildUpon().clearQuery()
        var changed = false
        val touched = LinkedHashSet<String>()
        for (key in keys) {
            val values = uri.getQueryParameters(key)
            if (values.isEmpty()) {
                builder.appendQueryParameter(key, "")
                continue
            }
            for (value in values) {
                val replacement = rewriteFriendClueQueryValue(key, value, rule)
                if (replacement != value) {
                    changed = true
                    touched.add(key)
                }
                builder.appendQueryParameter(key, replacement)
            }
        }
        val newUrl = builder.build().toString()
        return UrlRewriteResult(newUrl, changed, touched.toList())
    }

    private fun rewriteFriendClueQueryValue(key: String, value: String?, rule: RuntimeRule): String {
        if (isFriendClueDayQueryKey(key)) {
            return rule.days.toString()
        }
        if (isFriendClueDateQueryKey(key)) {
            return rule.addDateText
        }
        if (!isFriendClueTimeQueryKey(key)) {
            return value.orEmpty()
        }
        val old = value?.trim().orEmpty()
        if (old.isEmpty()) return rule.addDateText
        val numeric = old.toLongOrNull()
        if (numeric != null) {
            return if (old.length >= 13) rule.addDateMillis.toString() else (rule.addDateMillis / 1000L).toString()
        }
        if (dateRegex.containsMatchIn(old)) {
            return rule.addDateText
        }
        return rule.addDateText
    }

    private fun rewriteFriendClueJsonString(raw: String, rule: RuntimeRule): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!(text.startsWith("{") || text.startsWith("["))) return null
        return runCatching {
            if (text.startsWith("{")) {
                val obj = JSONObject(text)
                if (rewriteFriendClueJson(obj, rule, 0)) obj.toString() else null
            } else {
                val array = JSONArray(text)
                if (rewriteFriendClueJsonArray(array, rule, 0)) array.toString() else null
            }
        }.getOrNull()
    }

    private fun rewriteFriendClueJson(obj: JSONObject, rule: RuntimeRule, depth: Int): Boolean {
        if (depth > 3) return false
        var changed = false
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = runCatching { obj.get(key) }.getOrNull() ?: continue
            when (value) {
                is JSONObject -> {
                    if (rewriteFriendClueJson(value, rule, depth + 1)) {
                        changed = true
                    }
                }

                is JSONArray -> {
                    if (rewriteFriendClueJsonArray(value, rule, depth + 1)) {
                        changed = true
                    }
                }

                is Number, is String -> {
                    val replacement = when {
                        isFriendClueDayQueryKey(key) -> rule.days
                        isFriendClueDateQueryKey(key) -> rule.addDateText
                        isFriendClueTimeQueryKey(key) -> {
                            val asText = value.toString().trim()
                            if (value is Number || asText.all { it.isDigit() }) {
                                if (asText.length >= 13) rule.addDateMillis else (rule.addDateMillis / 1000L)
                            } else {
                                rule.addDateText
                            }
                        }

                        else -> null
                    }
                    if (replacement != null && replacement != value) {
                        runCatching { obj.put(key, replacement) }
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private fun rewriteFriendClueJsonArray(array: JSONArray, rule: RuntimeRule, depth: Int): Boolean {
        if (depth > 3) return false
        var changed = false
        val max = minOf(array.length(), 64)
        for (i in 0 until max) {
            val value = runCatching { array.get(i) }.getOrNull() ?: continue
            when (value) {
                is JSONObject -> if (rewriteFriendClueJson(value, rule, depth + 1)) changed = true
                is JSONArray -> if (rewriteFriendClueJsonArray(value, rule, depth + 1)) changed = true
            }
        }
        return changed
    }

    private fun shouldInjectFriendClueDomPatch(webView: Any, url: String): Boolean {
        val now = System.currentTimeMillis()
        val signature = "${System.identityHashCode(webView)}|${url.hashCode()}|${parseConfiguredDateMillis() ?: 0L}"
        val lastSig = mLastFriendClueDomPatchSignature
        val lastTs = mLastFriendClueDomPatchTs
        if (signature == lastSig && now - lastTs in 0..1500L) {
            return false
        }
        mLastFriendClueDomPatchSignature = signature
        mLastFriendClueDomPatchTs = now
        return true
    }

    private fun injectFriendClueDomPatch(webView: Any, rule: RuntimeRule) {
        val script = buildFriendClueDomPatchScript(rule)
        evaluateJavascriptOnWebView(webView, script)
    }

    private fun buildFriendClueDomPatchScript(rule: RuntimeRule): String {
        val fakeDate = JSONObject.quote(rule.addDateText)
        return """
            (function() {
              var FAKE_DATE = $fakeDate;
              var FAKE_DAYS = ${rule.days};
              var DATE_RE = /(\d{4}[-./]\d{1,2}[-./]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日|\d{1,2}月\d{1,2}日)/;
              var LABELS_DATE = ["他添加我时的日期", "加好友时间"];
              var LABELS_DAY = ["成为好友"];
              function patchText(oldText) {
                if (!oldText) return oldText;
                var text = oldText;
                var hasDateLabel = false;
                for (var i = 0; i < LABELS_DATE.length; i++) {
                  if (text.indexOf(LABELS_DATE[i]) >= 0) {
                    hasDateLabel = true;
                    break;
                  }
                }
                if (hasDateLabel) {
                  if (DATE_RE.test(text)) {
                    text = text.replace(new RegExp(DATE_RE.source, "g"), FAKE_DATE);
                  } else if (text.indexOf("：") >= 0 || text.indexOf(":") >= 0) {
                    text = text.replace(/([:：]\s*).*/, "$1" + FAKE_DATE);
                  }
                }
                var hasDayLabel = false;
                for (var j = 0; j < LABELS_DAY.length; j++) {
                  if (text.indexOf(LABELS_DAY[j]) >= 0) {
                    hasDayLabel = true;
                    break;
                  }
                }
                if (hasDayLabel && text.indexOf("天") >= 0) {
                  text = text.replace(/(成为好友\s*)(\d+)(\s*天)/, "$1" + FAKE_DAYS + "$3");
                }
                return text;
              }
              function patchNode(node) {
                if (!node) return false;
                if (node.nodeType === Node.TEXT_NODE) {
                  var oldText = node.nodeValue || "";
                  if (!oldText || oldText.length > 120) return false;
                  var newText = patchText(oldText);
                  if (newText !== oldText) {
                    node.nodeValue = newText;
                    return true;
                  }
                  return false;
                }
                if (node.nodeType === Node.ELEMENT_NODE && node.childElementCount === 0) {
                  var oldElText = node.textContent || "";
                  if (!oldElText || oldElText.length > 120) return false;
                  var newElText = patchText(oldElText);
                  if (newElText !== oldElText) {
                    node.textContent = newElText;
                    return true;
                  }
                }
                return false;
              }
              function patchAroundLabels() {
                var root = document.body || document.documentElement;
                if (!root) return;
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                var node;
                while ((node = walker.nextNode())) {
                  var t = node.nodeValue || "";
                  if (t.indexOf("他添加我时的日期") < 0 && t.indexOf("加好友时间") < 0 && t.indexOf("成为好友") < 0) {
                    continue;
                  }
                  patchNode(node);
                  var p = node.parentElement;
                  if (!p) continue;
                  patchNode(p);
                  var prev = p.previousElementSibling;
                  var next = p.nextElementSibling;
                  if (prev) {
                    patchNode(prev);
                    for (var i = 0; i < prev.childNodes.length; i++) {
                      patchNode(prev.childNodes[i]);
                    }
                  }
                  if (next) {
                    patchNode(next);
                    for (var j = 0; j < next.childNodes.length; j++) {
                      patchNode(next.childNodes[j]);
                    }
                  }
                }
              }
              function runPatch() {
                try { patchAroundLabels(); } catch (e) {}
              }
              runPatch();
              setTimeout(runPatch, 180);
              setTimeout(runPatch, 600);
              if (window.__qauxv_friendclue_observer) {
                try { window.__qauxv_friendclue_observer.disconnect(); } catch (e2) {}
              }
              var timer = null;
              var observer = new MutationObserver(function() {
                if (timer) clearTimeout(timer);
                timer = setTimeout(runPatch, 90);
              });
              observer.observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true });
              window.__qauxv_friendclue_observer = observer;
            })();
        """.trimIndent()
    }

    private fun evaluateJavascriptOnWebView(webView: Any, script: String) {
        runCatching {
            val evalMethod = webView.javaClass.methods.firstOrNull { m ->
                val p = m.parameterTypes
                m.name == "evaluateJavascript" && p.size == 2 && p[0] == String::class.java
            }
            if (evalMethod != null) {
                evalMethod.invoke(webView, script, null)
                return@runCatching
            }
            val loadUrlMethod = webView.javaClass.methods.firstOrNull { m ->
                val p = m.parameterTypes
                m.name == "loadUrl" && p.size == 1 && p[0] == String::class.java
            } ?: return@runCatching
            val compactScript = script
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")
            loadUrlMethod.invoke(webView, "javascript:$compactScript")
        }.onFailure { onHookError("hook/friend_clue_dom_eval", it) }
    }

    private fun extractWebViewFromJsBridgeListener(listener: Any): Any? {
        return runCatching {
            val weakRefField = listener.javaClass.declaredFields.firstOrNull {
                java.lang.ref.WeakReference::class.java.isAssignableFrom(it.type)
            } ?: return@runCatching null
            weakRefField.isAccessible = true
            val ref = weakRefField.get(listener) as? java.lang.ref.WeakReference<*>
            ref?.get()
        }.getOrNull()
    }

    private fun getWebViewCurrentUrl(webView: Any?): String? {
        if (webView == null) return null
        return runCatching {
            val method = webView.javaClass.methods.firstOrNull { it.name == "getUrl" && it.parameterTypes.isEmpty() } ?: return@runCatching null
            method.invoke(webView) as? String
        }.getOrNull()
    }

    private fun resolveActivityFromWebView(webView: Any?): Activity? {
        val context = runCatching {
            val method = webView?.javaClass?.methods?.firstOrNull { it.name == "getContext" && it.parameterTypes.isEmpty() } ?: return@runCatching null
            method.invoke(webView) as? Context
        }.getOrNull()
        return resolveActivityFromContext(context)
    }

    private fun resolveActivityFromContext(context: Context?): Activity? {
        var current = context
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Activity) return current
            current = if (current is ContextWrapper) current.baseContext else null
            depth++
        }
        return null
    }

    private fun getIntentExtraKeys(activity: Activity?): List<String> {
        val extras = activity?.intent?.extras ?: return emptyList()
        return runCatching {
            extras.keySet().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        }.getOrElse { emptyList() }
    }

    private fun replaceFriendTimeText(oldText: String, rule: RuntimeRule): String {
        val old = oldText.trim()
        if (old.isEmpty()) return oldText
        var changed = false
        var result = old
        if (dayRegex.containsMatchIn(result)) {
            result = dayRegex.replace(result) { mr ->
                changed = true
                "${mr.groupValues[1]}${rule.days}${mr.groupValues[3]}"
            }
        }
        val dateMatch = dateRegex.find(result)
        if (dateMatch != null && isFriendTimeLikeText(result)) {
            result = result.replaceRange(dateMatch.range, rule.addDateText)
            changed = true
        }
        if (!changed && isFriendTimeLikeText(result)) {
            result = when {
                result.contains("成为好友") && result.contains("天") -> "成为好友${rule.days}天"
                result.contains("已绑定") && result.contains("天") -> "已绑定${rule.days}天"
                result.contains("添加") || result.contains("时间") || result.contains("日期") -> "加好友时间：${rule.addDateText}"
                else -> result
            }
            changed = result != old
        }
        return if (changed) result else oldText
    }

    private fun isFriendTimeLikeText(text: String): Boolean {
        if (text.isBlank()) return false
        return dateKeywordList.any { text.contains(it) } || dateRegex.containsMatchIn(text)
    }

    private fun resolveRuntimeRule(): RuntimeRule? {
        val target = getEffectiveTarget()
        if (target.targetUin.isNullOrEmpty() && target.targetUidOrPeer.isNullOrEmpty() && target.targetPeerFromUin.isNullOrEmpty()) {
            return null
        }
        val fakeDateMillis = parseConfiguredDateMillis() ?: return null
        val fakeDateText = getFakeAddDateOrDefault()
        val days = calculateFriendDays(fakeDateMillis)
        return RuntimeRule(
            addDateMillis = fakeDateMillis,
            addDateText = fakeDateText,
            days = days
        )
    }

    private fun parseConfiguredDateMillis(): Long? {
        // fake_add_timestamp is normalized to milliseconds in NtPeerHelper.getConfigFakeAddTimestamp().
        NtPeerHelper.getConfigFakeAddTimestamp()?.let {
            return floorToLocalDay(it)
        }
        val raw = getRawFakeAddDateConfig()
        if (raw.isNullOrEmpty()) {
            return parseDateByFormats(DEFAULT_FAKE_ADD_DATE)?.let { floorToLocalDay(it) }
        }
        val candidate = dateRegex.find(raw)?.value ?: raw.trim()
        parseDateByFormats(candidate)?.let { return floorToLocalDay(it) }
        parseMonthDay(candidate)?.let { return floorToLocalDay(it) }
        debugLog("invalid fake_add_date=${candidate.take(32)}, fallback=$DEFAULT_FAKE_ADD_DATE")
        return parseDateByFormats(DEFAULT_FAKE_ADD_DATE)?.let { floorToLocalDay(it) }
    }

    private fun getRawFakeAddDateConfig(): String? {
        return NtPeerHelper.getConfigFakeAddDate()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun getFakeAddDateOrDefault(): String {
        val raw = getRawFakeAddDateConfig()
        if (raw.isNullOrEmpty()) return DEFAULT_FAKE_ADD_DATE
        parseDateByFormats(raw)?.let { return formatDate(it) }
        parseMonthDay(raw)?.let { return formatDate(it) }
        debugLog("invalid fake_add_date=${raw.take(32)}, fallback=$DEFAULT_FAKE_ADD_DATE")
        return DEFAULT_FAKE_ADD_DATE
    }

    private fun getEffectiveTarget(): EffectiveTarget {
        val configUin = NtPeerHelper.getConfigTargetUin()?.trim()?.takeIf { it.isNotEmpty() }
        val configUidOrPeer = NtPeerHelper.getConfigTargetUid()?.trim()?.takeIf { it.isNotEmpty() }
        val hasUserConfig = !configUin.isNullOrEmpty() || !configUidOrPeer.isNullOrEmpty()
        val targetUin = if (hasUserConfig) configUin else DEFAULT_TARGET_UIN
        val peerFromUin = resolvePeerIdFromUin(targetUin)
        return EffectiveTarget(
            hasUserConfig = hasUserConfig,
            targetUin = targetUin,
            targetUidOrPeer = configUidOrPeer,
            targetPeerFromUin = peerFromUin
        )
    }

    private fun getTargetUinOrDefault(): String {
        return getEffectiveTarget().targetUin ?: DEFAULT_TARGET_UIN
    }

    private fun resolvePeerIdFromUin(uin: String?): String? {
        val value = uin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!value.all { it.isDigit() }) return null
        return runCatching { NtPeerHelper.normalizePeerId(QAppUtils.UserUinToPeerID(value)) }.getOrNull()
    }

    private fun matchesPeerIdentity(candidate: String?, targetPeer: String?): Boolean {
        if (candidate.isNullOrBlank() || targetPeer.isNullOrBlank()) return false
        val normalized = NtPeerHelper.normalizePeerId(candidate) ?: candidate.trim()
        return normalized.equals(targetPeer, ignoreCase = true)
    }

    private fun matchesUinIdentity(candidate: String?, targetUin: String?): Boolean {
        if (candidate.isNullOrBlank() || targetUin.isNullOrBlank()) return false
        val raw = candidate.trim()
        if (raw == targetUin) return true
        return raw.all { it.isDigit() } && raw == targetUin
    }

    private fun matchesTargetIdentityWithDefaults(candidate: String?): Boolean {
        val raw = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val target = getEffectiveTarget()
        if (!target.targetUidOrPeer.isNullOrEmpty()) {
            if (matchesPeerIdentity(raw, target.targetUidOrPeer)) return true
            if (raw == target.targetUidOrPeer) return true
        }
        if (matchesUinIdentity(raw, target.targetUin)) return true
        if (matchesPeerIdentity(raw, target.targetPeerFromUin)) return true
        if (raw.all { it.isDigit() }) {
            val peerFromCandidateUin = resolvePeerIdFromUin(raw)
            if (matchesPeerIdentity(peerFromCandidateUin, target.targetUidOrPeer)) return true
            if (matchesPeerIdentity(peerFromCandidateUin, target.targetPeerFromUin)) return true
        }
        return false
    }

    private fun isTargetPeerWithDefaults(any: Any?): Boolean {
        if (any == null) return false
        if (NtPeerHelper.isNtTargetPeer(any)) return true
        when (any) {
            is CharSequence -> if (matchesTargetIdentityWithDefaults(any.toString())) return true
            is Number -> if (matchesTargetIdentityWithDefaults(any.toLong().toString())) return true
        }
        val peer = NtPeerHelper.extractPeerIdFromObject(any)
        if (matchesTargetIdentityWithDefaults(peer)) return true
        val uin = NtPeerHelper.extractUinFromObject(any)
        if (matchesTargetIdentityWithDefaults(uin)) return true
        return false
    }

    private fun parseDateByFormats(raw: String): Long? {
        val patterns = arrayOf(
            "yyyy-MM-dd",
            "yyyy-M-d",
            "yyyy/MM/dd",
            "yyyy/M/d",
            "yyyy.MM.dd",
            "yyyy.M.d",
            "yyyy年M月d日",
            "yyyy年MM月dd日"
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.getDefault())
            format.isLenient = false
            val date = runCatching { format.parse(raw) }.getOrNull() ?: continue
            return date.time
        }
        return null
    }

    private fun parseMonthDay(raw: String): Long? {
        val mdRegex = Regex("^(\\d{1,2})月(\\d{1,2})日$")
        val match = mdRegex.matchEntire(raw) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun calculateFriendDays(addDateMillis: Long): Int {
        return runCatching {
            val zone = ZoneId.systemDefault()
            val startDate = Instant.ofEpochMilli(addDateMillis).atZone(zone).toLocalDate()
            val today = LocalDate.now(zone)
            val diff = ChronoUnit.DAYS.between(startDate, today).toInt()
            max(1, diff + 1)
        }.getOrElse {
            val today = floorToLocalDay(System.currentTimeMillis())
            val start = floorToLocalDay(addDateMillis)
            val diff = ((today - start) / DAY_MILLIS).toInt()
            max(1, diff + 1)
        }
    }

    private fun floorToLocalDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatDate(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(timeMillis)
    }

    private fun isRuntimeReady(): Boolean {
        if (!QAppUtils.isQQnt()) {
            debugLog("runtime guard: skip, not QQNT")
            return false
        }
        if (!isFeatureEnabledEffective()) {
            debugLog("runtime guard: skip, feature disabled")
            return false
        }
        if (resolveRuntimeRule() == null) {
            debugLog("runtime guard: skip, runtime rule unavailable")
            return false
        }
        return true
    }

    private fun isFeatureEnabledEffective(): Boolean {
        val cfg = ConfigManager.getDefaultConfig()
        val hasNew = cfg.containsKey(NtPeerHelper.KEY_ENABLED)
        val hasLegacy = cfg.containsKey(NtPeerHelper.KEY_ENABLED_LEGACY)
        if (hasNew) {
            return cfg.getBooleanOrDefault(NtPeerHelper.KEY_ENABLED, true)
        }
        if (hasLegacy) {
            return cfg.getBooleanOrDefault(NtPeerHelper.KEY_ENABLED_LEGACY, true)
        }
        return true
    }

    private fun isProfilePageEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, true)
    }

    private fun isChatSettingPageEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, true)
    }

    private fun isTargetContext(thisObj: Any?, vararg args: Any?): Boolean {
        if (isTargetPeerWithDefaults(thisObj)) return true
        if (thisObj != null) {
            val friendUin = getStringField(thisObj, arrayOf("friendUin", "mFriendUin", "f163493f", "f163405c"))
            if (matchesTargetIdentityWithDefaults(friendUin)) return true
            val friendUid = getStringField(thisObj, arrayOf("friendUid", "mFriendUid", "uid", "mUid", "peerId", "mPeerId"))
            if (matchesTargetIdentityWithDefaults(friendUid)) return true
        }
        for (arg in args) {
            if (isTargetPeerWithDefaults(arg)) return true
        }
        return false
    }

    private fun resolveHookMethod(
        hookTag: String,
        target: DexKitTarget?,
        fallbackClassNames: Array<String>,
        checker: (Method) -> Boolean
    ): Method? {
        debugLog(
            "resolving target hook=$hookTag, dexTarget=${target?.javaClass?.simpleName ?: "-"}, " +
                "fallback=${fallbackClassNames.joinToString()}"
        )
        val classCandidates = LinkedHashSet<Class<*>>(4)
        if (target != null) {
            runCatching { DexKit.requireClassFromCache(target) }.onSuccess {
                classCandidates.add(it)
            }.onFailure {
                onHookError("resolve/$hookTag/dexkit", it)
            }
        }
        for (name in fallbackClassNames) {
            runCatching { Initiator.loadClass(name) }.onSuccess {
                classCandidates.add(it)
            }.onFailure {
                onHookError("resolve/$hookTag/fallback:$name", it)
            }
        }
        for (clazz in classCandidates) {
            val method = clazz.declaredMethods.firstOrNull(checker)
            if (method != null) {
                method.isAccessible = true
                increaseCounter(COUNTER_DEX_RESOLVE_SUCCESS)
                debugLog(
                    "resolve success hook=$hookTag, class=${clazz.name}, " +
                        "method=${hookPointOf(method)}"
                )
                return method
            }
        }
        increaseCounter(COUNTER_DEX_RESOLVE_FAILED)
        increaseCounter(COUNTER_HOOK_INSTALL_FAILED)
        val classList = classCandidates.joinToString { it.name }
        val ex = NoSuchMethodException("No matching method hook=$hookTag in $classList")
        onHookError("resolve/$hookTag/no_match", ex)
        return null
    }

    private fun <T> getFieldValue(instance: Any, names: Array<String>, type: Class<T>): T? {
        for (name in names) {
            val field = findField(instance.javaClass, name) ?: continue
            val value = runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull() ?: continue
            if (type.isInstance(value)) {
                return type.cast(value)
            }
        }
        return null
    }

    private fun getStringField(instance: Any, names: Array<String>): String? {
        return getFieldValue(instance, names, String::class.java)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun setIntFieldIfPresent(instance: Any, names: Array<String>, value: Int) {
        for (name in names) {
            val field = findField(instance.javaClass, name) ?: continue
            runCatching {
                field.isAccessible = true
                when (field.type) {
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> field.set(instance, value)
                }
            }
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull { it.name == fieldName }
            if (field != null) return field
            current = current.superclass
        }
        return null
    }

    private fun invokeIntGetter(instance: Any, name: String): Int? {
        return runCatching {
            val method = instance.javaClass.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(instance) as? Int
        }.getOrNull()
    }

    private fun invokeLongGetter(instance: Any, name: String): Long? {
        return runCatching {
            val method = instance.javaClass.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(instance) as? Long
        }.getOrNull()
    }

    private fun invokeStringGetter(instance: Any, name: String): String? {
        return runCatching {
            val method = instance.javaClass.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(instance) as? String
        }.getOrNull()
    }

    private fun increaseCounter(key: String, delta: Long = 1L): Long {
        return mHookHitCounters.getOrPut(key) { AtomicLong(0L) }.addAndGet(delta)
    }

    private fun onHookError(stage: String, error: Throwable) {
        mLastError = "$stage:${error.javaClass.simpleName}"
        traceError(error)
        reportHookEvent(
            event = "hook_error",
            hookPoint = stage,
            page = "runtime",
            strategy = "error",
            targetMatched = false,
            message = "hook error at $stage",
            extras = mapOf("error" to "${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}"),
            level = "ERROR"
        )
    }

    private fun markRewriteResult(changed: Boolean) {
        if (changed) {
            increaseCounter(COUNTER_REWRITE_SUCCESS)
        } else {
            increaseCounter(COUNTER_REWRITE_SKIPPED)
        }
    }

    private fun markHookInstalled(key: String, anchor: Any?) {
        val firstInstalled = mHookInstalledState.put(key, true) != true
        val anchorName = when (anchor) {
            is Method -> hookPointOf(anchor)
            is Class<*> -> anchor.name
            else -> anchor?.javaClass?.name ?: "-"
        }
        if (firstInstalled) {
            increaseCounter(COUNTER_HOOK_INSTALL_SUCCESS)
            reportHookEvent(
                event = "hook_install_success",
                hookPoint = anchorName,
                page = "startup",
                strategy = "install",
                targetMatched = false,
                message = "hook install success",
                extras = mapOf("hookKey" to key)
            )
        }
        if (NtPeerHelper.isDebugEnabled()) {
            debugLog("hook installed: $key <- $anchorName")
        }
    }

    private fun markHookHit(key: String): Long {
        return mHookHitCounters.getOrPut(key) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun clearDebugCounters() {
        mHookHitCounters.clear()
        mLastFriendClueDomPatchSignature = ""
        mLastFriendClueDomPatchTs = 0L
        mLastError = "-"
    }

    private fun hookPointOf(method: Method): String {
        return method.declaringClass.name + "#" + method.name
    }

    private fun currentTargetType(): String {
        val targetUid = NtPeerHelper.getConfigTargetUid()
        return if (!targetUid.isNullOrBlank()) "uid" else "uin"
    }

    private fun currentTargetMasked(): String {
        val target = getEffectiveTarget()
        val raw = target.targetUidOrPeer ?: target.targetUin
        return NtPeerHelper.maskIdentifier(raw)
    }

    private fun logTargetMismatch(page: String, hookPoint: String, vararg candidates: Any?) {
        increaseCounter(COUNTER_REWRITE_SKIPPED)
        val snapshot = capturePeerSnapshot(*candidates)
        val currentType = snapshot.first
        val currentMasked = snapshot.second
        val target = getEffectiveTarget()
        val targetUinMasked = NtPeerHelper.maskUin(target.targetUin)
        val targetUidMasked = NtPeerHelper.maskUid(target.targetUidOrPeer)
        val note = if (currentType == "uid" && target.targetUidOrPeer.isNullOrBlank()) {
            "current page only exposes uid, target_uin cannot match directly"
        } else {
            ""
        }
        debugLog(
            "target mismatch page=$page, hook=$hookPoint, currentPeerType=$currentType, " +
                "currentPeerMasked=$currentMasked, targetUinMasked=$targetUinMasked, " +
                "targetUidMasked=$targetUidMasked, targetMatched=false, note=$note"
        )
        reportHookEvent(
            event = "target_mismatch",
            hookPoint = hookPoint,
            page = page,
            strategy = "target_match",
            targetMatched = false,
            message = "target not matched on hook",
            extras = mapOf(
                "currentPeerType" to currentType,
                "currentPeerMasked" to currentMasked,
                "targetUinMasked" to targetUinMasked,
                "targetUidMasked" to targetUidMasked,
                "note" to note
            )
        )
    }

    private fun capturePeerSnapshot(vararg candidates: Any?): Pair<String, String> {
        for (candidate in candidates) {
            if (candidate == null) continue
            when (candidate) {
                is CharSequence -> {
                    val raw = candidate.toString().trim()
                    if (raw.isEmpty()) continue
                    if (raw.all { it.isDigit() }) return "uin" to NtPeerHelper.maskIdentifier(raw)
                    if (raw.startsWith("u_", ignoreCase = true) || raw.startsWith("uid_", ignoreCase = true)) {
                        return "uid" to NtPeerHelper.maskIdentifier(raw)
                    }
                }

                is Number -> {
                    val raw = candidate.toLong().toString()
                    if (raw.isNotEmpty()) return "uin" to NtPeerHelper.maskIdentifier(raw)
                }
            }
            val peer = NtPeerHelper.extractPeerIdFromObject(candidate)
            if (!peer.isNullOrBlank()) {
                val type = if (peer.startsWith("uid_", ignoreCase = true) || peer.startsWith("u_", ignoreCase = true)) "uid" else "peerId"
                return type to NtPeerHelper.maskIdentifier(peer)
            }
            val uin = NtPeerHelper.extractUinFromObject(candidate)
            if (!uin.isNullOrBlank()) {
                return "uin" to NtPeerHelper.maskIdentifier(uin)
            }
        }
        return "-" to "-"
    }

    private fun maskTextForReport(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val noDigits = text.replace(Regex("\\d{4,}"), "***")
        return if (noDigits.length <= 80) noDigits else noDigits.substring(0, 80)
    }

    private fun reportHookEvent(
        event: String,
        hookPoint: String,
        page: String,
        strategy: String,
        targetMatched: Boolean,
        message: String,
        rule: RuntimeRule? = null,
        extras: Map<String, Any?> = emptyMap(),
        rewriteChanged: Boolean? = null,
        level: String = "INFO"
    ) {
        when (rewriteChanged) {
            true -> increaseCounter(COUNTER_REWRITE_SUCCESS)
            false -> increaseCounter(COUNTER_REWRITE_SKIPPED)
        }
        if (!NtPeerHelper.isDebugEnabled()) return
        val payload = LinkedHashMap<String, Any?>(16)
        payload["hookPoint"] = hookPoint
        payload["page"] = page
        payload["targetMatched"] = targetMatched
        payload["targetType"] = currentTargetType()
        payload["targetMasked"] = currentTargetMasked()
        payload["strategy"] = strategy
        rule?.let {
            payload["fakeAddDate"] = it.addDateText
            payload["calculatedDays"] = it.days
        }
        for ((k, v) in extras) {
            payload[k] = v
        }
        FakeFriendAddDateDebugReporter.report(level, event, message, payload)
    }

    private fun printStartupDiagnostics() {
        if (mStartupDiagLogged) return
        mStartupDiagLogged = true
        val debugEnabled = NtPeerHelper.isDebugEnabled()
        val target = getEffectiveTarget()
        val targetUinRaw = NtPeerHelper.getConfigTargetUin()
        val targetUidRaw = NtPeerHelper.getConfigTargetUid()
        val fakeDateRaw = NtPeerHelper.getConfigFakeAddDate()
        val fakeTsRaw = NtPeerHelper.getConfigFakeAddTimestamp()
        val cfg = ConfigManager.getDefaultConfig()
        val enabledRawExists = cfg.containsKey(NtPeerHelper.KEY_ENABLED) || cfg.containsKey(NtPeerHelper.KEY_ENABLED_LEGACY)
        val enabledFinal = isFeatureEnabledEffective()
        val processName = runCatching { SyncUtils.getProcessName() }.getOrDefault("-")
        val packageName = runCatching { HostInfo.getPackageName() }.getOrDefault("-")
        val processAccepted = processName == packageName
        val hostVersionName = runCatching { HostInfo.getVersionName() }.getOrDefault("-")
        val hostVersionCode = runCatching { HostInfo.getVersionCode() }.getOrDefault(0)
        val startupMeta = linkedMapOf<String, Any?>(
            "moduleLoaded" to true,
            "processName" to processName,
            "processAccepted" to processAccepted,
            "hostPackageName" to packageName,
            "hostVersionName" to hostVersionName,
            "hostVersionCode" to hostVersionCode,
            "enabledRawExists" to enabledRawExists,
            "enabledFinal" to enabledFinal,
            "targetUinFinal" to NtPeerHelper.maskUin(target.targetUin),
            "targetUidFinal" to NtPeerHelper.maskUid(target.targetUidOrPeer),
            "targetPeerFromUin" to NtPeerHelper.maskUid(target.targetPeerFromUin),
            "targetUinRaw" to NtPeerHelper.maskUin(targetUinRaw),
            "targetUidRaw" to NtPeerHelper.maskUid(targetUidRaw),
            "fakeAddDateRaw" to (fakeDateRaw ?: ""),
            "fakeAddDateFinal" to getFakeAddDateOrDefault(),
            "fakeAddTimestampFinal" to (parseConfiguredDateMillis() ?: 0L),
            "fakeAddTimestampRaw" to (fakeTsRaw ?: 0L),
            "debugLogFinal" to debugEnabled,
            "debugServerEnabledFinal" to NtPeerHelper.isDebugServerEnabled(),
            "debugServerUrlFinal" to NtPeerHelper.getDebugServerUrl(DEFAULT_DEBUG_SERVER_URL)
        )
        Log.d(
            "FakeFriendAddDateNt init diagnostics: " +
                "process=$processName, pkg=$packageName, version=$hostVersionName($hostVersionCode), " +
                "processAccepted=$processAccepted, " +
                "enabledRawExists=$enabledRawExists, enabledFinal=$enabledFinal, " +
                "targetUin=${NtPeerHelper.maskUin(target.targetUin)}, targetUid=${NtPeerHelper.maskUid(target.targetUidOrPeer)}, " +
                "fakeAddDate=${getFakeAddDateOrDefault()}, debugLog=$debugEnabled, " +
                "debugServer=${NtPeerHelper.isDebugServerEnabled()}, " +
                "debugServerUrl=${NtPeerHelper.getDebugServerUrl(DEFAULT_DEBUG_SERVER_URL)}"
        )
        if (debugEnabled) {
            debugLog("startup diagnostics: $startupMeta")
        }
        FakeFriendAddDateDebugReporter.report(
            level = "INFO",
            event = "startup_diagnostics",
            message = "module loaded diagnostics",
            extras = startupMeta
        )
    }

    private fun refreshDebugTransportState() {
        val debugEnabled = NtPeerHelper.isDebugEnabled()
        val serverEnabled = NtPeerHelper.isDebugServerEnabled()
        val debugServerUrl = NtPeerHelper.getDebugServerUrl(DEFAULT_DEBUG_SERVER_URL)
        FakeFriendAddDateDebugReporter.configure(
            debugEnabled = debugEnabled,
            serverEnabled = serverEnabled,
            baseUrl = debugServerUrl
        )
        val enablePolling = debugEnabled &&
            serverEnabled &&
            NtPeerHelper.isDebugCommandPollingEnabled() &&
            mInitReadyForDebugChannel
        val pollingInterval = NtPeerHelper.getDebugCommandPollingIntervalMs(5000L)
        FakeFriendAddDateCommandClient.configure(
            enabled = enablePolling,
            baseUrl = debugServerUrl,
            intervalMs = pollingInterval,
            debugLogEnabled = debugEnabled,
            handler = if (enablePolling) FakeFriendAddDateCommandClient.CommandHandler { command ->
                handleDebugCommand(command)
            } else null
        )
    }

    private fun handleDebugCommand(command: JSONObject): FakeFriendAddDateCommandClient.CommandResult {
        val type = command.optString("type").trim()
        if (type.isEmpty()) {
            return FakeFriendAddDateCommandClient.CommandResult(false, error = "missing command type")
        }
        return runCatching {
            when (type) {
                "ping" -> {
                    val result = JSONObject()
                    result.put("pong", "pong")
                    result.put("timestamp", System.currentTimeMillis())
                    FakeFriendAddDateCommandClient.CommandResult(true, result = result)
                }

                "dump_config" -> {
                    FakeFriendAddDateCommandClient.CommandResult(true, result = buildDumpConfigJson())
                }

                "dump_hook_state" -> {
                    FakeFriendAddDateCommandClient.CommandResult(true, result = buildDumpHookStateJson())
                }

                "set_debug_log" -> {
                    val enabled = getCommandBoolean(command, "enabled")
                        ?: return@runCatching FakeFriendAddDateCommandClient.CommandResult(false, error = "missing enabled")
                    NtPeerHelper.setDebugEnabled(enabled)
                    refreshDebugTransportState()
                    val result = JSONObject()
                    result.put("debugLog", NtPeerHelper.isDebugEnabled())
                    FakeFriendAddDateCommandClient.CommandResult(true, result = result)
                }

                "set_debug_server_enabled" -> {
                    val enabled = getCommandBoolean(command, "enabled")
                        ?: return@runCatching FakeFriendAddDateCommandClient.CommandResult(false, error = "missing enabled")
                    NtPeerHelper.setDebugServerEnabled(enabled)
                    refreshDebugTransportState()
                    val result = JSONObject()
                    result.put("debugServerEnabled", NtPeerHelper.isDebugServerEnabled())
                    FakeFriendAddDateCommandClient.CommandResult(true, result = result)
                }

                "set_target" -> {
                    val targetUin = getCommandString(command, "target_uin")
                    val targetUid = getCommandString(command, "target_uid")
                    if (targetUin.isNullOrBlank() && targetUid.isNullOrBlank()) {
                        return@runCatching FakeFriendAddDateCommandClient.CommandResult(false, error = "target_uin/target_uid required")
                    }
                    if (!targetUin.isNullOrBlank()) {
                        NtPeerHelper.setTargetUin(targetUin)
                    }
                    if (!targetUid.isNullOrBlank()) {
                        NtPeerHelper.setTargetUid(targetUid)
                    }
                    val result = JSONObject()
                    result.put("targetUin", NtPeerHelper.maskUin(getEffectiveTarget().targetUin))
                    result.put("targetUid", NtPeerHelper.maskUid(getEffectiveTarget().targetUidOrPeer))
                    FakeFriendAddDateCommandClient.CommandResult(true, result = result)
                }

                "set_fake_add_date" -> {
                    val raw = getCommandString(command, "fake_add_date")
                    val normalized = normalizeDateInputForCommand(raw)
                        ?: return@runCatching FakeFriendAddDateCommandClient.CommandResult(false, error = "invalid fake_add_date")
                    NtPeerHelper.setFakeAddDate(normalized)
                    val result = JSONObject()
                    result.put("fakeAddDate", normalized)
                    FakeFriendAddDateCommandClient.CommandResult(true, result = result)
                }

                "clear_debug_counters" -> {
                    clearDebugCounters()
                    val result = JSONObject()
                    result.put("cleared", true)
                    FakeFriendAddDateCommandClient.CommandResult(true, result = result)
                }

                else -> {
                    FakeFriendAddDateCommandClient.CommandResult(false, error = "unknown command type")
                }
            }
        }.getOrElse {
            FakeFriendAddDateCommandClient.CommandResult(false, error = "command exception: ${it.javaClass.simpleName}")
        }
    }

    private fun getCommandString(command: JSONObject, key: String): String? {
        val direct = command.optString(key).trim().takeIf { it.isNotEmpty() }
        if (direct != null) return direct
        val params = command.optJSONObject("params") ?: return null
        return params.optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun getCommandBoolean(command: JSONObject, key: String): Boolean? {
        val directObj = command.opt(key)
        if (directObj != null && directObj != JSONObject.NULL) {
            return toBooleanSafe(directObj)
        }
        val params = command.optJSONObject("params") ?: return null
        val paramObj = params.opt(key)
        if (paramObj == null || paramObj == JSONObject.NULL) return null
        return toBooleanSafe(paramObj)
    }

    private fun toBooleanSafe(any: Any?): Boolean? {
        return when (any) {
            is Boolean -> any
            is Number -> any.toInt() != 0
            is String -> {
                when (any.trim().lowercase(Locale.ROOT)) {
                    "1", "true", "on", "yes", "enabled" -> true
                    "0", "false", "off", "no", "disabled" -> false
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun normalizeDateInputForCommand(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        parseDateByFormats(text)?.let { return formatDate(it) }
        parseMonthDay(text)?.let { return formatDate(it) }
        return null
    }

    private fun buildDumpConfigJson(): JSONObject {
        val cfg = JSONObject()
        val config = ConfigManager.getDefaultConfig()
        val enabledRawExists = config.containsKey(NtPeerHelper.KEY_ENABLED) || config.containsKey(NtPeerHelper.KEY_ENABLED_LEGACY)
        cfg.put("enabledRawExists", enabledRawExists)
        cfg.put("enabledFinal", isFeatureEnabledEffective())
        cfg.put("enabled", isFeatureEnabledEffective())
        cfg.put("debugLog", NtPeerHelper.isDebugEnabled())
        cfg.put("debugServerEnabled", NtPeerHelper.isDebugServerEnabled())
        cfg.put("debugServerUrl", NtPeerHelper.getDebugServerUrl(DEFAULT_DEBUG_SERVER_URL))
        cfg.put("debugCommandPollingEnabled", NtPeerHelper.isDebugCommandPollingEnabled())
        cfg.put("debugCommandPollingIntervalMs", NtPeerHelper.getDebugCommandPollingIntervalMs(5000L))
        val target = getEffectiveTarget()
        cfg.put("targetUin", NtPeerHelper.maskUin(target.targetUin))
        cfg.put("targetUid", NtPeerHelper.maskUid(target.targetUidOrPeer))
        cfg.put("targetPeerFromUin", NtPeerHelper.maskUid(target.targetPeerFromUin))
        cfg.put("targetUinRawMasked", NtPeerHelper.maskUin(NtPeerHelper.getConfigTargetUin()))
        cfg.put("targetUidRawMasked", NtPeerHelper.maskUid(NtPeerHelper.getConfigTargetUid()))
        cfg.put("fakeAddDate", getFakeAddDateOrDefault())
        cfg.put("fakeAddDateRaw", NtPeerHelper.getConfigFakeAddDate().orEmpty())
        cfg.put("fakeAddTimestampRaw", NtPeerHelper.getConfigFakeAddTimestamp() ?: 0L)
        cfg.put("fakeAddTimestampFinal", parseConfiguredDateMillis() ?: 0L)
        cfg.put("hasUserConfig", target.hasUserConfig)
        cfg.put("profilePageEnabled", isProfilePageEnabled())
        cfg.put("chatSettingEnabled", isChatSettingPageEnabled())
        cfg.put("processName", runCatching { SyncUtils.getProcessName() }.getOrDefault("-"))
        cfg.put("hostPackageName", runCatching { HostInfo.getPackageName() }.getOrDefault("-"))
        cfg.put("hostVersionName", runCatching { HostInfo.getVersionName() }.getOrDefault("-"))
        cfg.put("hostVersionCode", runCatching { HostInfo.getVersionCode() }.getOrDefault(0))
        return cfg
    }

    private fun buildDumpHookStateJson(): JSONObject {
        val result = JSONObject()
        val installedKeys = arrayOf(
            HOOK_INSTALLED_INTIMATE_HEADER,
            HOOK_INSTALLED_INTIMATE_DAYS_COMPUTE,
            HOOK_INSTALLED_RELATION_DAY_BIND,
            HOOK_INSTALLED_BECOME_FRIEND_BIND,
            HOOK_INSTALLED_MEMORY_TIMELINE,
            HOOK_INSTALLED_DNA_BIND,
            HOOK_INSTALLED_FRIEND_CLUE_ENTRY,
            HOOK_INSTALLED_FRIEND_CLUE_WEBVIEW,
            HOOK_INSTALLED_FRIEND_CLUE_JSBRIDGE,
            HOOK_INSTALLED_FRIEND_CLUE_DOM
        )
        for (key in installedKeys) {
            result.put(key, mHookInstalledState[key] ?: false)
        }
        val hitCounterKeys = arrayOf(
            HOOK_HIT_INTIMATE_HEADER,
            HOOK_HIT_INTIMATE_DAYS_COMPUTE,
            HOOK_HIT_RELATION_DAY_BIND,
            HOOK_HIT_BECOME_FRIEND_BIND,
            HOOK_HIT_MEMORY_TIMELINE,
            HOOK_HIT_DNA_BIND,
            HOOK_HIT_FRIEND_CLUE_ENTRY,
            HOOK_HIT_FRIEND_CLUE_WEBVIEW,
            HOOK_HIT_FRIEND_CLUE_JSBRIDGE,
            HOOK_HIT_FRIEND_CLUE_DOM
        )
        for (key in hitCounterKeys) {
            val value = mHookHitCounters[key]?.get() ?: 0L
            result.put(key, value)
        }
        val requiredCounters = arrayOf(
            COUNTER_INIT_CALLED,
            COUNTER_DEX_RESOLVE_SUCCESS,
            COUNTER_DEX_RESOLVE_FAILED,
            COUNTER_HOOK_INSTALL_SUCCESS,
            COUNTER_HOOK_INSTALL_FAILED,
            HOOK_HIT_INTIMATE_HEADER,
            HOOK_HIT_INTIMATE_DAYS_COMPUTE,
            HOOK_HIT_RELATION_DAY_BIND,
            HOOK_HIT_MEMORY_TIMELINE,
            HOOK_HIT_DNA_BIND,
            HOOK_HIT_FRIEND_CLUE_ENTRY,
            COUNTER_TARGET_MATCHED,
            COUNTER_REWRITE_SUCCESS,
            COUNTER_REWRITE_SKIPPED
        )
        for (key in requiredCounters) {
            val value = mHookHitCounters[key]?.get() ?: 0L
            result.put(key, value)
        }
        result.put("lastError", mLastError)
        result.put("initReadyForDebugChannel", mInitReadyForDebugChannel)
        return result
    }

    private fun parsePollingIntervalMs(rawInput: String): Long {
        val raw = rawInput.trim()
        val parsed = raw.toLongOrNull()
        return (parsed ?: 5000L).coerceIn(3000L, 60_000L)
    }

    private fun showConfigDialog(activity: Activity) {
        val ctx = CommonContextWrapper.createAppCompatContext(activity)
        val cfg = ConfigManager.getDefaultConfig()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dip2px(ctx, 12f)
            setPadding(pad, pad, pad, pad)
        }
        val switchEnable = SwitchCompat(ctx).apply {
            text = "启用功能"
            isChecked = isFeatureEnabledEffective()
        }
        val targetUinEdit = newEditText(ctx, "目标 QQ 号（uin，留空默认 $DEFAULT_TARGET_UIN）", NtPeerHelper.getConfigTargetUin().orEmpty())
        val targetUidEdit = newEditText(ctx, "目标 UID/peerId（可选）", NtPeerHelper.getConfigTargetUid().orEmpty())
        val dateEdit = newEditText(ctx, "伪造加好友日期（留空默认 $DEFAULT_FAKE_ADD_DATE）", getRawFakeAddDateConfig().orEmpty())
        val timestampEdit = newEditText(ctx, "伪造时间戳（可空，秒或毫秒）", NtPeerHelper.getConfigFakeAddTimestamp()?.toString().orEmpty())
        val nicknameEdit = newEditText(ctx, "备注昵称（仅模块显示）", NtPeerHelper.getConfigNickname().orEmpty())
        val profileSwitch = SwitchCompat(ctx).apply {
            text = "在资料页生效"
            isChecked = cfg.getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, true)
        }
        val chatSettingSwitch = SwitchCompat(ctx).apply {
            text = "在聊天设置/亲密关系生效"
            isChecked = cfg.getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, true)
        }
        val debugSwitch = SwitchCompat(ctx).apply {
            text = "调试日志"
            isChecked = NtPeerHelper.isDebugEnabled()
        }
        val debugServerSwitch = SwitchCompat(ctx).apply {
            text = "启用局域网 Debug 服务器"
            isChecked = NtPeerHelper.isDebugServerEnabled()
        }
        val debugServerUrlEdit = newEditText(
            ctx,
            "Debug 服务器地址（默认 $DEFAULT_DEBUG_SERVER_URL）",
            NtPeerHelper.getDebugServerUrl(DEFAULT_DEBUG_SERVER_URL)
        )
        val commandPollingSwitch = SwitchCompat(ctx).apply {
            text = "启用命令轮询"
            isChecked = NtPeerHelper.isDebugCommandPollingEnabled()
        }
        val commandPollingIntervalEdit = newEditText(
            ctx,
            "命令轮询间隔毫秒（3000-60000，默认 5000）",
            NtPeerHelper.getDebugCommandPollingIntervalMs(5000L).toString()
        )
        commandPollingSwitch.isEnabled = debugServerSwitch.isChecked
        commandPollingIntervalEdit.isEnabled = debugServerSwitch.isChecked && commandPollingSwitch.isChecked
        debugServerSwitch.setOnCheckedChangeListener { _, enabled ->
            commandPollingSwitch.isEnabled = enabled
            if (!enabled) {
                commandPollingSwitch.isChecked = false
            }
            commandPollingIntervalEdit.isEnabled = enabled && commandPollingSwitch.isChecked
        }
        commandPollingSwitch.setOnCheckedChangeListener { _, enabled ->
            commandPollingIntervalEdit.isEnabled = enabled && debugServerSwitch.isChecked
        }

        val margin = LayoutHelper.dip2px(ctx, 8f)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = margin
        }
        root.addView(switchEnable, lp)
        root.addView(newLabel(ctx, "目标 QQ 号 (uin，未填默认 $DEFAULT_TARGET_UIN)"), lp)
        root.addView(targetUinEdit, lp)
        root.addView(newLabel(ctx, "目标 UID / peerId"), lp)
        root.addView(targetUidEdit, lp)
        root.addView(newLabel(ctx, "伪造加好友日期（未填默认 $DEFAULT_FAKE_ADD_DATE）"), lp)
        root.addView(dateEdit, lp)
        root.addView(newLabel(ctx, "伪造时间戳（可选）"), lp)
        root.addView(timestampEdit, lp)
        root.addView(newLabel(ctx, "备注昵称（可选）"), lp)
        root.addView(nicknameEdit, lp)
        root.addView(profileSwitch, lp)
        root.addView(chatSettingSwitch, lp)
        root.addView(debugSwitch, lp)
        root.addView(debugServerSwitch, lp)
        root.addView(newLabel(ctx, "Debug 服务器地址（仅局域网）"), lp)
        root.addView(debugServerUrlEdit, lp)
        root.addView(commandPollingSwitch, lp)
        root.addView(newLabel(ctx, "命令轮询间隔（毫秒）"), lp)
        root.addView(commandPollingIntervalEdit, lp)

        val scrollView = ScrollView(ctx).apply {
            addView(root)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(name)
            .setView(scrollView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("重置", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                NtPeerHelper.setTargetUin(targetUinEdit.text?.toString().orEmpty())
                NtPeerHelper.setTargetUid(targetUidEdit.text?.toString().orEmpty())
                val rawDateInput = dateEdit.text?.toString().orEmpty()
                val normalizedDate = normalizeDateInputForConfig(rawDateInput)
                if (rawDateInput.isNotBlank() && normalizedDate.isEmpty()) {
                    Toasts.info(ctx, "日期格式无效，已回退默认 $DEFAULT_FAKE_ADD_DATE")
                }
                NtPeerHelper.setFakeAddDate(normalizedDate)
                NtPeerHelper.setFakeAddTimestamp(timestampEdit.text?.toString().orEmpty())
                NtPeerHelper.setNickname(nicknameEdit.text?.toString().orEmpty())
                cfg.putBoolean(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, profileSwitch.isChecked)
                cfg.putBoolean(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, chatSettingSwitch.isChecked)
                NtPeerHelper.setDebugEnabled(debugSwitch.isChecked)
                NtPeerHelper.setDebugServerEnabled(debugServerSwitch.isChecked)
                NtPeerHelper.setDebugServerUrl(debugServerUrlEdit.text?.toString().orEmpty())
                NtPeerHelper.setDebugCommandPollingEnabled(commandPollingSwitch.isChecked)
                NtPeerHelper.setDebugCommandPollingIntervalMs(
                    parsePollingIntervalMs(commandPollingIntervalEdit.text?.toString().orEmpty())
                )
                NtPeerHelper.setFeatureEnabled(switchEnable.isChecked)
                isEnabled = switchEnable.isChecked
                if (isEnabled && !isInitialized) {
                    HookInstaller.initializeHookForeground(ctx, this@FakeFriendAddDateNt)
                }
                refreshDebugTransportState()
                valueState.value = if (isEnabled) "已开启" else "禁用"
                Toasts.success(ctx, "配置已保存")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                resetConfig()
                valueState.value = "禁用"
                refreshDebugTransportState()
                Toasts.info(ctx, "已重置配置")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun resetConfig() {
        NtPeerHelper.clearConfig()
        isEnabled = false
        clearDebugCounters()
    }

    private fun normalizeDateInputForConfig(rawInput: String): String {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return ""
        parseDateByFormats(raw)?.let { return formatDate(it) }
        parseMonthDay(raw)?.let { return formatDate(it) }
        debugLog("invalid fake_add_date input=${raw.take(32)}, keep empty for default fallback")
        return ""
    }

    private fun newLabel(context: Context, text: String): TextView {
        return AppCompatTextView(context).apply {
            this.text = text
            textSize = 12f
        }
    }

    private fun newEditText(context: Context, hint: String, value: String): EditText {
        return AppCompatEditText(context).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
        }
    }

    private fun maskId(raw: String?): String {
        return NtPeerHelper.maskIdentifier(raw)
    }

    private fun debugLog(msg: String) {
        if (NtPeerHelper.isDebugEnabled()) {
            Log.d("FakeFriendAddDateNt: $msg")
        }
    }
}
