package com.muxiao.Venus.common;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;

public class BatteryHelper {

    /**
     * 检查应用是否已在电池优化白名单中
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * 构建打开「忽略电池优化」系统设置列表页的 Intent（无需任何权限）。
     * 该页面展示全部应用的白名单列表，用户需手动切到「所有应用」并找到本应用允许——
     * 比直接弹授权框多两步，但不触碰 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，
     * 无 Google Play BatteryLife 政策风险，且不受国产 ROM 拦截/跳转变形的影响。
     * 用 ActivityResultLauncher 启动；机型不支持该 action 时
     * （ActivityNotFoundException）由调用方回退 {@link #openVendorBatterySettings}。
     */
    public static android.content.Intent buildBatteryOptimizationSettingsIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    /**
     * 打开应用详情设置页，用户可在此手动设置后台行为、电池优化等
     */
    public static void openVendorBatterySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
