package com.muxiao.Venus.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 小组件「刷新」按钮的专用接收器。
 *
 * <p>该 action（{@code com.muxiao.Venus.widget.REFRESH}）原先挂在 {@link TaskWidgetProvider} 的
 * intent-filter 里。AppWidgetProvider 必须 {@code exported="true"} 才收得到系统广播，同一个 filter 内的
 * 自定义 action 因此一并对外暴露——任意第三方应用发一条广播就能触发本应用刷新桌面小组件（读 DataStore +
 * 两次系统 IPC）。
 *
 * <p>拆成独立 receiver 并置 {@code exported="false"}：桌面按钮走显式组件 PendingIntent（由本应用身份投递）
 * 照常可达，外部应用无从触达；APK 也少掉一个「导出组件无权限保护」的检测项。
 */
public class TaskWidgetRefreshReceiver extends BroadcastReceiver {

    public static final String ACTION_REFRESH = "com.muxiao.Venus.widget.REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REFRESH.equals(intent.getAction())) return;
        TaskWidgetProvider.refreshAllWidgets(context);
    }
}
