package com.wangzan101.mika.voice.ui

import android.Manifest
import android.content.Context
import androidx.core.app.ActivityCompat
import com.wangzan101.mika.browser.ui.findActivity

/**
 * 录音权限被拒过的记号,和「系统不再弹框」的判定。各入口记在自己的 prefs 文件里([InputBar] 按住说话、[Call] 通话),
 * 键都是 `mic_denied`;文件名和键已经存在用户手机上,不能改。
 */
internal class MicDenial private constructor(private val prefs: String) {
    fun denied(context: Context): Boolean =
        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun record(context: Context, denied: Boolean) =
        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putBoolean(KEY, denied).apply()

    /** 拒过、而且系统不再弹框也不该再解释:只能去 App 的权限页打开。 */
    fun blocked(context: Context): Boolean {
        val activity = context.findActivity()
        return denied(context) && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        private const val KEY = "mic_denied"
        val InputBar = MicDenial("voice_input")
        val Call = MicDenial("voice_call")
    }
}
