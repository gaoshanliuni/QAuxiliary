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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import cc.hicore.QApp.QAppUtils
import cc.ioctl.util.LayoutHelper
import cc.ioctl.util.NtPeerHelper
import cc.ioctl.util.hookAfterIfEnabled
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
import io.github.qauxv.util.dexkit.NT_Profile_AddFriendDate_Bind
import io.github.qauxv.util.dexkit.NT_Profile_RelationInfo_Bind
import io.github.qauxv.util.dexkit.NT_Profile_UnbindAddDate_Bind
import io.github.qauxv.util.xpcompat.XC_MethodHook
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Method

@FunctionHookEntry
@UiItemAgentEntry
object FakeFriendAddDateNt : CommonConfigFunctionHook(
    hookKey = NtPeerHelper.KEY_ENABLED,
    defaultEnabled = false,
    targets = arrayOf(
        NT_Profile_AddFriendDate_Bind,
        NT_Profile_RelationInfo_Bind,
        NT_Profile_UnbindAddDate_Bind
    )
) {

    override val name: String = "自定义好友添加日期"
    override val description: CharSequence = "仅修改指定好友的本地资料页添加日期显示，不影响真实数据"
    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.FRIEND_CATEGORY
    override val valueState: MutableStateFlow<String?> = MutableStateFlow(if (isEnabled) "已开启" else "禁用")

    private val keywordList = arrayOf("成为好友", "添加好友", "好友添加", "添加时间", "加为好友", "已成为好友", "已绑定", "绑定")
    private val dateRegex = Regex("(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{1,2}月\\d{1,2}日)")
    private val dayRegex = Regex("(成为好友\\s*\\d+\\s*天|已成为好友\\s*\\d+\\s*天|已绑定\\s*\\d+\\s*天|成为好友\\d+天|已成为好友\\d+天)")
    private val prefixRegex = Regex("^(\\s*(成为好友|添加好友|好友添加时间|好友添加|添加时间|加为好友|已成为好友|已绑定)\\s*[:：\\s]*)")

    override val onUiItemClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        showConfigDialog(activity)
    }

    override fun initOnce(): Boolean {
        if (!QAppUtils.isQQnt()) {
            return true
        }
        installHeaderCardHook()
        installRelationInfoHook()
        installUnbindCardHook()
        return true
    }

    private fun installHeaderCardHook() {
        val method = resolveHookMethod(
            target = NT_Profile_AddFriendDate_Bind,
            fallbackClassName = "com.tencent.mobileqq.activity.aio.intimate.header.f"
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 6 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == Int::class.javaPrimitiveType &&
                p[3] == Int::class.javaPrimitiveType &&
                p[4] == Int::class.javaPrimitiveType &&
                p[5] == String::class.java
        } ?: return
        hookAfterIfEnabled(method) { param ->
            if (!isRuntimeReady() || !isProfilePageEnabled()) return@hookAfterIfEnabled
            if (!isTargetInContext(param)) return@hookAfterIfEnabled
            val displayText = NtPeerHelper.getConfigDisplayText() ?: return@hookAfterIfEnabled
            val thisObj = param.thisObject ?: return@hookAfterIfEnabled
            val secondLineZero = getFieldValue(thisObj, "mSecondLineTextZeroDay", TextView::class.java) ?: return@hookAfterIfEnabled
            val firstLine = getFieldValue(thisObj, "mFirstLine", View::class.java)
            val secondLine = getFieldValue(thisObj, "mSecondLine", View::class.java)
            val fallbackText = "成为好友 " + (param.args.getOrNull(2)?.toString() ?: "")
            val oldText = secondLineZero.text?.toString().orEmpty().ifEmpty { fallbackText }
            val replaced = replaceFriendAddDateText(oldText, displayText)
            if (replaced == oldText) return@hookAfterIfEnabled
            secondLineZero.text = replaced
            secondLineZero.visibility = View.VISIBLE
            firstLine?.visibility = View.GONE
            secondLine?.visibility = View.GONE
            debugLog("header.f bind replaced")
        }
    }

    private fun installRelationInfoHook() {
        val method = resolveHookMethod(
            target = NT_Profile_RelationInfo_Bind,
            fallbackClassName = "vx.o"
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 2 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == String::class.java
        } ?: return
        hookAfterIfEnabled(method) { param ->
            if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@hookAfterIfEnabled
            if (!isTargetInContext(param)) return@hookAfterIfEnabled
            val displayText = NtPeerHelper.getConfigDisplayText() ?: return@hookAfterIfEnabled
            val thisObj = param.thisObject ?: return@hookAfterIfEnabled
            val targetView = getFieldValue(thisObj, "mTvRelationshipDay", TextView::class.java) ?: return@hookAfterIfEnabled
            val oldText = targetView.text?.toString()?.trim().orEmpty()
            if (oldText.isEmpty() || !isFriendAddDateLikeText(oldText)) return@hookAfterIfEnabled
            val replaced = replaceFriendAddDateText(oldText, displayText)
            if (replaced == oldText) return@hookAfterIfEnabled
            targetView.text = replaced
            debugLog("vx.o update replaced")
        }
    }

    private fun installUnbindCardHook() {
        val method = resolveHookMethod(
            target = NT_Profile_UnbindAddDate_Bind,
            fallbackClassName = "vx.w"
        ) { m ->
            val p = m.parameterTypes
            m.returnType == Void.TYPE &&
                p.size == 3 &&
                p[1] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(p[2]) &&
                (p[0].name.contains("IntimateBaseModel") || m.name == "onBindData")
        } ?: return
        hookAfterIfEnabled(method) { param ->
            if (!isRuntimeReady() || !isChatSettingPageEnabled()) return@hookAfterIfEnabled
            if (!isTargetInContext(param)) return@hookAfterIfEnabled
            val displayText = NtPeerHelper.getConfigDisplayText() ?: return@hookAfterIfEnabled
            val thisObj = param.thisObject ?: return@hookAfterIfEnabled
            val dayView = getFieldValue(thisObj, "mBecomeFriendDays", TextView::class.java) ?: return@hookAfterIfEnabled
            val rawText = dayView.text?.toString()?.trim().orEmpty()
            if (rawText.isEmpty()) return@hookAfterIfEnabled
            val candidate = if (isFriendAddDateLikeText(rawText)) rawText else "成为好友 $rawText 天"
            val replaced = replaceFriendAddDateText(candidate, displayText)
            if (replaced == rawText) return@hookAfterIfEnabled
            dayView.text = replaced
            debugLog("vx.w onBindData replaced")
        }
    }

    private fun resolveHookMethod(
        target: io.github.qauxv.util.dexkit.DexKitTarget,
        fallbackClassName: String,
        checker: (Method) -> Boolean
    ): Method? {
        val classCandidates = LinkedHashSet<Class<*>>(2)
        runCatching { DexKit.requireClassFromCache(target) }.onSuccess {
            classCandidates.add(it)
        }.onFailure {
            traceError(it)
        }
        runCatching { Initiator.loadClass(fallbackClassName) }.onSuccess {
            classCandidates.add(it)
        }.onFailure {
            traceError(it)
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

    private fun isRuntimeReady(): Boolean {
        if (!QAppUtils.isQQnt()) return false
        if (!isEnabled) return false
        if (NtPeerHelper.getConfigDisplayText().isNullOrEmpty()) return false
        val targetPeer = NtPeerHelper.resolveTargetPeerId()
        val targetUin = NtPeerHelper.getConfigTargetUin()
        return !targetPeer.isNullOrEmpty() || !targetUin.isNullOrEmpty()
    }

    private fun isProfilePageEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, true)
    }

    private fun isChatSettingPageEnabled(): Boolean {
        return ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, true)
    }

    private fun isTargetInContext(param: XC_MethodHook.MethodHookParam): Boolean {
        if (NtPeerHelper.isNtTargetPeer(param.thisObject)) return true
        for (arg in param.args) {
            if (NtPeerHelper.isNtTargetPeer(arg)) return true
        }
        return false
    }

    private fun <T> getFieldValue(instance: Any, fieldName: String, type: Class<T>): T? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null && clazz != Any::class.java) {
            val field = clazz.declaredFields.firstOrNull { it.name == fieldName && type.isAssignableFrom(it.type) }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    type.cast(field.get(instance))
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    fun replaceFriendAddDateText(oldText: String, displayText: String): String {
        val old = oldText.trim()
        val display = displayText.trim()
        if (old.isEmpty() || display.isEmpty()) return oldText
        if (old == display || old.contains(display)) return old
        if (containsKeyword(display)) {
            return display
        }
        if (dayRegex.containsMatchIn(old)) {
            return "成为好友：$display"
        }
        if (containsKeyword(old)) {
            val dateMatch = dateRegex.find(old)
            if (dateMatch != null) {
                return old.replaceRange(dateMatch.range, display)
            }
            val prefix = prefixRegex.find(old)?.groupValues?.getOrNull(1)?.orEmpty()
            if (prefix.isNotEmpty()) {
                return prefix + display
            }
            return display
        }
        val dateMatch = dateRegex.find(old)
        if (dateMatch != null) {
            return old.replaceRange(dateMatch.range, display)
        }
        return display
    }

    private fun containsKeyword(text: String): Boolean {
        return keywordList.any { text.contains(it) }
    }

    private fun isFriendAddDateLikeText(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        return containsKeyword(value) || dayRegex.containsMatchIn(value) || dateRegex.containsMatchIn(value)
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
            isChecked = isEnabled
        }
        val targetUinEdit = newEditText(ctx, "目标 QQ 号（可空）", cfg.getString(NtPeerHelper.KEY_TARGET_UIN).orEmpty())
        val targetPeerEdit = newEditText(ctx, "QQNT peerId / uid（可选，优先）", cfg.getString(NtPeerHelper.KEY_TARGET_PEER_ID).orEmpty())
        val displayEdit = newEditText(ctx, "自定义显示文本", cfg.getString(NtPeerHelper.KEY_DISPLAY_TEXT).orEmpty())
        val profileSwitch = SwitchCompat(ctx).apply {
            text = "在资料页生效"
            isChecked = cfg.getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, true)
        }
        val chatSettingSwitch = SwitchCompat(ctx).apply {
            text = "在聊天设置页生效"
            isChecked = cfg.getBooleanOrDefault(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, true)
        }
        val debugSwitch = SwitchCompat(ctx).apply {
            text = "调试日志"
            isChecked = cfg.getBooleanOrDefault(NtPeerHelper.KEY_DEBUG_LOG, false)
        }
        val margin = LayoutHelper.dip2px(ctx, 8f)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = margin
        }
        root.addView(switchEnable, lp)
        root.addView(newLabel(ctx, "目标 QQ 号"), lp)
        root.addView(targetUinEdit, lp)
        root.addView(newLabel(ctx, "目标 peerId / uid"), lp)
        root.addView(targetPeerEdit, lp)
        root.addView(newLabel(ctx, "显示文案"), lp)
        root.addView(displayEdit, lp)
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
                cfg.putString(NtPeerHelper.KEY_TARGET_UIN, targetUinEdit.text?.toString()?.trim().orEmpty())
                cfg.putString(NtPeerHelper.KEY_TARGET_PEER_ID, targetPeerEdit.text?.toString()?.trim().orEmpty())
                cfg.putString(NtPeerHelper.KEY_DISPLAY_TEXT, displayEdit.text?.toString()?.trim().orEmpty())
                cfg.putBoolean(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, profileSwitch.isChecked)
                cfg.putBoolean(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, chatSettingSwitch.isChecked)
                cfg.putBoolean(NtPeerHelper.KEY_DEBUG_LOG, debugSwitch.isChecked)
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
        val cfg = ConfigManager.getDefaultConfig()
        cfg.putString(NtPeerHelper.KEY_TARGET_UIN, "")
        cfg.putString(NtPeerHelper.KEY_TARGET_PEER_ID, "")
        cfg.putString(NtPeerHelper.KEY_DISPLAY_TEXT, "")
        cfg.putBoolean(NtPeerHelper.KEY_ENABLE_PROFILE_PAGE, true)
        cfg.putBoolean(NtPeerHelper.KEY_ENABLE_CHAT_SETTING_PAGE, true)
        cfg.putBoolean(NtPeerHelper.KEY_DEBUG_LOG, false)
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

    private fun debugLog(msg: String) {
        if (ConfigManager.getDefaultConfig().getBooleanOrDefault(NtPeerHelper.KEY_DEBUG_LOG, false)) {
            Log.d("FakeFriendAddDateNt: $msg")
        }
    }
}
