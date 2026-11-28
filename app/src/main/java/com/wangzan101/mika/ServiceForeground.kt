package com.wangzan101.mika

import android.app.Notification
import android.app.Service
import android.os.Build
import android.content.pm.ServiceInfo

/**
 * 服务进前台,成功返回 true。安卓 [typeSince] 起带上前台服务类型 [type](特殊用途类从安卓 14 起要声明,麦克风类从安卓 11 起);
 * 更早的系统不带类型。系统不让进前台(后台启动被拒、缺权限)时返回 false 并交给 [onFailure],不让进程崩。
 * 通知在里面建:建通知出错也算进不了前台。
 */
internal inline fun Service.enterForeground(
    id: Int,
    type: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    typeSince: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    onFailure: (Throwable) -> Unit = {},
    notification: () -> Notification,
): Boolean = runCatching {
    val shown = notification()
    if (Build.VERSION.SDK_INT >= typeSince) startForeground(id, shown, type) else startForeground(id, shown)
}.onFailure(onFailure).isSuccess
