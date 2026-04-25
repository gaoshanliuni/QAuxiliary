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
import java.lang.reflect.Method
import java.text.SimpleDateFormat
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
                if (!NtPeerHelper.isDebugEnabled()) return@runCatching
                val uin = param.args.getOrNull(1)?.toString()
                val hit = NtPeerHelper.isNtTargetPeer(uin)
                debugLog("friend clue entry: uin=${maskId(uin)}, match=$hit")
            }.onFailure { traceError(it) }
        }
    }

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
        val today = floorToLocalDay(System.currentTimeMillis())
        val start = floorToLocalDay(addDateMillis)
        val diff = ((today - start) / DAY_MILLIS).toInt()
        return max(1, diff + 1)
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
