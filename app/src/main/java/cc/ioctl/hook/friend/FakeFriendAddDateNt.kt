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
import kotlin.math.max

@FunctionHookEntry
@UiItemAgentEntry
object FakeFriendAddDateNt : CommonConfigFunctionHook(
    hookKey = NtPeerHelper.KEY_ENABLED,
    defaultEnabled = false,
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
    override val valueState: MutableStateFlow<String?> = MutableStateFlow(if (isEnabled) "已开启" else "禁用")

    private data class RuntimeRule(
        val addDateMillis: Long,
        val addDateText: String,
        val days: Int
    )

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

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

    private val dateRegex = Regex("(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{1,2}月\\d{1,2}日)")
    private val dayRegex = Regex("((?:成为好友|已成为好友|已绑定)\\s*)(\\d+)(\\s*天)")

    override val onUiItemClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        showConfigDialog(activity)
    }

    override fun initOnce(): Boolean {
        if (!QAppUtils.isQQnt()) {
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
        return true
    }

    private fun installHeaderModelHook() {
        val method = resolveHookMethod(
            target = NT_Profile_AddFriendDate_Bind,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.activity.aio.intimate.header.f")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 1 &&
                p[0].name.contains("activity.aio.intimate.header.g")
        } ?: return
        hookBeforeIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isProfilePageEnabled()) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) return@runCatching
                val model = param.args.getOrNull(0) ?: return@runCatching
                val patchedModel = rebuildHeaderModel(model, rule.days) ?: return@runCatching
                param.args[0] = patchedModel
                debugLog("hook header model: class=${thisObj.javaClass.name}, days=${rule.days}")
            }.onFailure { traceError(it) }
        }
    }

    private fun installRelationDaysCalcHook() {
        val method = resolveHookMethod(
            target = NT_Profile_RelationInfo_Bind,
            fallbackClassNames = arrayOf("vx.o")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Int::class.javaPrimitiveType &&
                p.size == 2 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType
        } ?: return
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@runCatching
                val type = param.args.getOrNull(0) as? Int ?: return@runCatching
                if (type != 0) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                param.result = rule.days
                setIntFieldIfPresent(thisObj, arrayOf("mIntimateRealDays", "intimateRealDays"), rule.days)
                debugLog("hook relation days calc: class=${thisObj.javaClass.name}, days=${rule.days}")
            }.onFailure { traceError(it) }
        }
    }

    private fun installRelationRenderHook() {
        val method = resolveHookMethod(
            target = NT_Profile_RelationInfo_Bind,
            fallbackClassNames = arrayOf("vx.o")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == String::class.java
        } ?: return
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val dayView = getFieldValue(thisObj, arrayOf("mTvRelationshipDay"), TextView::class.java) ?: return@runCatching
                val oldText = dayView.text?.toString().orEmpty()
                val newText = replaceFriendTimeText(oldText, rule)
                if (newText != oldText) {
                    dayView.text = newText
                    debugLog("hook relation render: text=$newText")
                }
            }.onFailure { traceError(it) }
        }
    }

    private fun installUnbindCardHook() {
        val method = resolveHookMethod(
            target = NT_Profile_UnbindAddDate_Bind,
            fallbackClassNames = arrayOf("vx.w")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 3 &&
                p[1] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(p[2])
        } ?: return
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val dayView = getFieldValue(thisObj, arrayOf("mBecomeFriendDays"), TextView::class.java) ?: return@runCatching
                val oldText = dayView.text?.toString().orEmpty()
                val targetText = rule.days.toString()
                if (oldText != targetText) {
                    dayView.text = targetText
                    debugLog("hook unbind card days: $targetText")
                }
            }.onFailure { traceError(it) }
        }
    }

    private fun installMemoryDayHook() {
        val method = resolveHookMethod(
            target = NT_Profile_MemoryDay_Helper,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.activity.aio.intimate.k")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                p[0] == Context::class.java &&
                List::class.java.isAssignableFrom(p[1])
        } ?: return
        hookBeforeIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isProfilePageEnabled()) return@runCatching
                val thisObj = param.thisObject ?: return@runCatching
                val listArg = param.args.getOrNull(1) as? MutableList<Any> ?: return@runCatching
                if (!isTargetContext(thisObj, *param.args)) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                for (item in listArg) {
                    patchMemoryDayItem(item, rule)
                }
                debugLog("hook memory day list: size=${listArg.size}")
            }.onFailure { traceError(it) }
        }
    }

    private fun installDnaItemBindHook() {
        val method = resolveHookMethod(
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
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady() || !isProfilePageEnabled()) return@runCatching
                val adapterObj = param.thisObject ?: return@runCatching
                val holder = param.args.getOrNull(0) ?: return@runCatching
                if (!isTargetContext(adapterObj, holder, *param.args)) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                patchDnaViewHolder(holder, rule)
            }.onFailure { traceError(it) }
        }
    }

    private fun installFriendClueEntryHook() {
        val method = resolveHookMethod(
            target = null,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.relationx.friendclue.c")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                Activity::class.java.isAssignableFrom(p[0]) &&
                p[1] == String::class.java
        } ?: return
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
                if (NtPeerHelper.isDebugEnabled()) {
                    debugLog(
                        "friend clue entry: class=${param.thisObject?.javaClass?.name}, " +
                            "argIsUrl=${looksLikeHttpUrl(secondArg)}, host=${urlMeta?.host ?: "-"}, " +
                            "path=${urlMeta?.path ?: "-"}, keys=${urlMeta?.queryKeys ?: emptyList<String>()}, " +
                            "uin=${maskId(dbgUin)}, uid=${maskId(dbgUid)}, peer=${maskId(dbgPeer)}, " +
                            "match=$targetHit"
                    )
                }
                if (!targetHit) return@runCatching
                val rewrite = rewriteFriendClueUrl(sourceUrl, rule)
                if (!rewrite.changed) return@runCatching
                if (looksLikeHttpUrl(secondArg)) {
                    param.args[1] = rewrite.newUrl
                } else {
                    val encodedTail = buildFriendClueTailFromUrl(rewrite.newUrl, secondArg)
                    if (!encodedTail.isNullOrEmpty()) {
                        param.args[1] = encodedTail
                    }
                }
                debugLog("friend clue strategy=url-params, touched=${rewrite.touchedKeys}")
            }.onFailure { traceError(it) }
        }
    }

    private fun installFriendClueWebViewLoadHook() {
        hookFriendClueLoadUrlClass("com.tencent.smtt.sdk.WebView")
        hookFriendClueLoadUrlClass("android.webkit.WebView")
    }

    private fun hookFriendClueLoadUrlClass(className: String) {
        val clazz = runCatching { Initiator.loadClass(className) }.getOrNull() ?: return
        val hookedSignatures = HashSet<String>(4)
        for (method in clazz.declaredMethods) {
            val p = method.parameterTypes
            if (method.name != "loadUrl") continue
            if (p.isEmpty() || p[0] != String::class.java) continue
            if (p.size != 1 && !(p.size == 2 && Map::class.java.isAssignableFrom(p[1]))) continue
            val signature = "${clazz.name}#${method.name}${p.joinToString(prefix = "(", postfix = ")") { it.name }}"
            if (!hookedSignatures.add(signature)) continue
            hookBeforeIfEnabled(method) { param ->
                runCatching {
                    if (!isRuntimeReady()) return@runCatching
                    val oldUrl = param.args.getOrNull(0) as? String ?: return@runCatching
                    if (!isFriendClueUrl(oldUrl)) return@runCatching
                    val rule = resolveRuntimeRule() ?: return@runCatching
                    val webView = param.thisObject
                    val activity = resolveActivityFromWebView(webView)
                    if (!isTargetFriendForUrl(oldUrl, null, activity, webView, param.thisObject)) return@runCatching
                    val rewrite = rewriteFriendClueUrl(oldUrl, rule)
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
                        debugLog("friend clue strategy=webview-url, touched=${rewrite.touchedKeys}")
                    }
                }.onFailure { traceError(it) }
            }
        }
    }

    private fun installFriendClueJsBridgeHook() {
        val clazz = runCatching { Initiator.loadClass("com.tencent.mobileqq.webview.swift.JsBridgeListener") }.getOrNull() ?: return
        val jsonMethod = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            m.name == "d" && p.size == 1 && p[0] == JSONObject::class.java
        }
        if (jsonMethod != null) {
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
                    if (!isTargetFriendForUrl(url, null, activity, webView, listener)) return@runCatching
                    if (rewriteFriendClueJson(payload, rule, 0)) {
                        debugLog("friend clue strategy=jsbridge-json, method=d")
                    }
                }.onFailure { traceError(it) }
            }
        }
        val objMethod = clazz.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            m.name == "c" && p.size == 1
        }
        if (objMethod != null) {
            hookBeforeIfEnabled(objMethod) { param ->
                runCatching {
                    if (!isRuntimeReady()) return@runCatching
                    val listener = param.thisObject ?: return@runCatching
                    val webView = extractWebViewFromJsBridgeListener(listener) ?: return@runCatching
                    val url = getWebViewCurrentUrl(webView) ?: return@runCatching
                    if (!isFriendClueUrl(url)) return@runCatching
                    val rule = resolveRuntimeRule() ?: return@runCatching
                    val activity = resolveActivityFromWebView(webView)
                    if (!isTargetFriendForUrl(url, null, activity, webView, listener)) return@runCatching
                    val payload = param.args.getOrNull(0) ?: return@runCatching
                    when (payload) {
                        is JSONObject -> {
                            if (rewriteFriendClueJson(payload, rule, 0)) {
                                debugLog("friend clue strategy=jsbridge-json, method=c(JSONObject)")
                            }
                        }

                        is String -> {
                            val updated = rewriteFriendClueJsonString(payload, rule) ?: return@runCatching
                            if (updated != payload) {
                                param.args[0] = updated
                                debugLog("friend clue strategy=jsbridge-json, method=c(String)")
                            }
                        }
                    }
                }.onFailure { traceError(it) }
            }
        }
    }

    private fun installFriendCluePageFinishedHook() {
        val method = resolveHookMethod(
            target = null,
            fallbackClassNames = arrayOf("com.tencent.mobileqq.webview.swift.al", "com.tencent.mobileqq.activity.QQBrowserActivity")
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                m.name == "onPageFinished" &&
                p.size == 2 &&
                p[1] == String::class.java
        } ?: return
        hookAfterIfEnabled(method) { param ->
            runCatching {
                if (!isRuntimeReady()) return@runCatching
                val rule = resolveRuntimeRule() ?: return@runCatching
                val webView = param.args.getOrNull(0) ?: return@runCatching
                val url = (param.args.getOrNull(1) as? String)?.takeIf { it.isNotBlank() } ?: getWebViewCurrentUrl(webView) ?: return@runCatching
                if (!isFriendClueUrl(url)) return@runCatching
                val activity = resolveActivityFromWebView(webView) ?: (param.thisObject as? Activity)
                if (!isTargetFriendForUrl(url, null, activity, webView, param.thisObject)) return@runCatching
                if (NtPeerHelper.isDebugEnabled()) {
                    val meta = parseUrlMeta(url)
                    val (dbgUin, dbgUid, dbgPeer) = getUrlIdentity(url)
                    val extras = getIntentExtraKeys(activity)
                    debugLog(
                        "friend clue pageFinished: entry=${param.thisObject?.javaClass?.name}, " +
                            "activity=${activity?.javaClass?.name ?: "-"}, web=${webView.javaClass.name}, " +
                            "host=${meta?.host ?: "-"}, path=${meta?.path ?: "-"}, keys=${meta?.queryKeys ?: emptyList<String>()}, " +
                            "uin=${maskId(dbgUin)}, uid=${maskId(dbgUid)}, peer=${maskId(dbgPeer)}, extras=$extras"
                    )
                }
                if (!shouldInjectFriendClueDomPatch(webView, url)) return@runCatching
                injectFriendClueDomPatch(webView, rule)
                debugLog("friend clue strategy=dom-fallback, pageFinished injected")
            }.onFailure { traceError(it) }
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
            if (NtPeerHelper.isNtTargetPeer(candidate)) {
                return true
            }
        }
        return false
    }

    private fun isTargetByIdentity(uin: String?, uid: String?, peerId: String?): Boolean {
        if (!peerId.isNullOrBlank() && NtPeerHelper.isNtTargetPeer(peerId)) {
            return true
        }
        if (!uid.isNullOrBlank() && NtPeerHelper.isNtTargetPeer(uid)) {
            return true
        }
        val uinRaw = uin?.trim().orEmpty()
        if (uinRaw.isNotEmpty()) {
            if (NtPeerHelper.isNtTargetPeer(uinRaw)) {
                return true
            }
            val targetPeer = NtPeerHelper.resolveTargetPeerId()
            if (!targetPeer.isNullOrEmpty()) {
                val mappedPeer = runCatching {
                    NtPeerHelper.normalizePeerId(QAppUtils.UserUinToPeerID(uinRaw))
                }.getOrNull()
                if (!mappedPeer.isNullOrEmpty() && mappedPeer == targetPeer) {
                    return true
                }
            }
        }
        return false
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
        }.onFailure { traceError(it) }
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
        val targetUin = NtPeerHelper.getConfigTargetUin()
        val targetUid = NtPeerHelper.getConfigTargetUid()
        if (targetUin.isNullOrEmpty() && targetUid.isNullOrEmpty()) return null
        val fakeDateMillis = parseConfiguredDateMillis() ?: return null
        val fakeDateText = formatDate(fakeDateMillis)
        val days = calculateFriendDays(fakeDateMillis)
        return RuntimeRule(
            addDateMillis = fakeDateMillis,
            addDateText = fakeDateText,
            days = days
        )
    }

    private fun parseConfiguredDateMillis(): Long? {
        NtPeerHelper.getConfigFakeAddTimestamp()?.let {
            return floorToLocalDay(it)
        }
        val configured = NtPeerHelper.getConfigFakeAddDate() ?: return null
        val raw = dateRegex.find(configured)?.value ?: configured.trim()
        parseDateByFormats(raw)?.let { return floorToLocalDay(it) }
        return parseMonthDay(raw)
    }

    private fun parseDateByFormats(raw: String): Long? {
        val patterns = arrayOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyy年M月d日", "yyyy年MM月dd日")
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
        if (!QAppUtils.isQQnt()) return false
        if (!NtPeerHelper.isFeatureEnabled() && !isEnabled) return false
        if (resolveRuntimeRule() == null) return false
        return true
    }

    private fun isProfilePageEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, true)
    }

    private fun isChatSettingPageEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, true)
    }

    private fun isTargetContext(thisObj: Any?, vararg args: Any?): Boolean {
        if (NtPeerHelper.isNtTargetPeer(thisObj)) return true
        if (thisObj != null) {
            val friendUin = getStringField(thisObj, arrayOf("friendUin", "mFriendUin", "f163493f", "f163405c"))
            if (NtPeerHelper.isNtTargetPeer(friendUin)) return true
            val friendUid = getStringField(thisObj, arrayOf("friendUid", "mFriendUid", "uid", "mUid", "peerId", "mPeerId"))
            if (NtPeerHelper.isNtTargetPeer(friendUid)) return true
        }
        for (arg in args) {
            if (NtPeerHelper.isNtTargetPeer(arg)) return true
        }
        return false
    }

    private fun resolveHookMethod(
        target: DexKitTarget?,
        fallbackClassNames: Array<String>,
        checker: (Method) -> Boolean
    ): Method? {
        val classCandidates = LinkedHashSet<Class<*>>(4)
        if (target != null) {
            runCatching { DexKit.requireClassFromCache(target) }.onSuccess {
                classCandidates.add(it)
            }.onFailure {
                traceError(it)
            }
        }
        for (name in fallbackClassNames) {
            runCatching { Initiator.loadClass(name) }.onSuccess {
                classCandidates.add(it)
            }.onFailure {
                traceError(it)
            }
        }
        for (clazz in classCandidates) {
            val method = clazz.declaredMethods.firstOrNull(checker)
            if (method != null) {
                method.isAccessible = true
                return method
            }
        }
        traceError(NoSuchMethodException("No matching method in ${classCandidates.joinToString { it.name }}"))
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
            isChecked = NtPeerHelper.isFeatureEnabled() || isEnabled
        }
        val targetUinEdit = newEditText(ctx, "目标 QQ 号（uin）", NtPeerHelper.getConfigTargetUin().orEmpty())
        val targetUidEdit = newEditText(ctx, "目标 UID/peerId（可选）", NtPeerHelper.getConfigTargetUid().orEmpty())
        val dateEdit = newEditText(ctx, "伪造加好友日期（如 2020-05-20）", NtPeerHelper.getConfigFakeAddDate().orEmpty())
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

        val margin = LayoutHelper.dip2px(ctx, 8f)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = margin
        }
        root.addView(switchEnable, lp)
        root.addView(newLabel(ctx, "目标 QQ 号 (uin)"), lp)
        root.addView(targetUinEdit, lp)
        root.addView(newLabel(ctx, "目标 UID / peerId"), lp)
        root.addView(targetUidEdit, lp)
        root.addView(newLabel(ctx, "伪造加好友日期"), lp)
        root.addView(dateEdit, lp)
        root.addView(newLabel(ctx, "伪造时间戳（可选）"), lp)
        root.addView(timestampEdit, lp)
        root.addView(newLabel(ctx, "备注昵称（可选）"), lp)
        root.addView(nicknameEdit, lp)
        root.addView(profileSwitch, lp)
        root.addView(chatSettingSwitch, lp)
        root.addView(debugSwitch, lp)

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
                NtPeerHelper.setFakeAddDate(dateEdit.text?.toString().orEmpty())
                NtPeerHelper.setFakeAddTimestamp(timestampEdit.text?.toString().orEmpty())
                NtPeerHelper.setNickname(nicknameEdit.text?.toString().orEmpty())
                cfg.putBoolean(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, profileSwitch.isChecked)
                cfg.putBoolean(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, chatSettingSwitch.isChecked)
                NtPeerHelper.setDebugEnabled(debugSwitch.isChecked)
                NtPeerHelper.setFeatureEnabled(switchEnable.isChecked)
                isEnabled = switchEnable.isChecked
                if (isEnabled && !isInitialized) {
                    HookInstaller.initializeHookForeground(ctx, this@FakeFriendAddDateNt)
                }
                valueState.value = if (isEnabled) "已开启" else "禁用"
                Toasts.success(ctx, "配置已保存")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                resetConfig()
                valueState.value = "禁用"
                Toasts.info(ctx, "已重置配置")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun resetConfig() {
        NtPeerHelper.clearConfig()
        isEnabled = false
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
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "-"
        return if (value.length <= 8) value else value.take(3) + "***" + value.takeLast(3)
    }

    private fun debugLog(msg: String) {
        if (NtPeerHelper.isDebugEnabled()) {
            Log.d("FakeFriendAddDateNt: $msg")
        }
    }
}
