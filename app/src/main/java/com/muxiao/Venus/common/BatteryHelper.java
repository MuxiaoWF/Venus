package com.muxiao.Venus.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

public class BatteryHelper {

    private static final String TAG = "BatteryHelper";

    private enum Vendor {
        XIAOMI, HUAWEI, OPPO, VIVO, UNKNOWN
    }

    /**
     * 检查应用是否已在电池优化白名单中
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * 打开系统的电池优化设置页面（原生 Android）
     */
    public static void openBatterySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 打开厂商电池优化设置页。自动检测厂商并跳转到对应设置页，
     * 如果厂商 Intent 都失败则兜底到 Android 原生电池优化页。
     */
    public static void openVendorBatterySettings(Context context) {
        Vendor vendor = detectVendor();
        Intent intent = buildVendorIntent(context, vendor);
        if (intent != null) {
            try {
                context.startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "厂商设置页打开失败: " + vendor + ", 尝试下一个", e);
            }
        }
        // 兜底到原生电池优化页
        openBatterySettings(context);
    }

    private static Vendor detectVendor() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            return Vendor.XIAOMI;
        }
        if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            return Vendor.HUAWEI;
        }
        if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")) {
            return Vendor.OPPO;
        }
        if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            return Vendor.VIVO;
        }
        return Vendor.UNKNOWN;
    }

    private static Intent buildVendorIntent(Context context, Vendor vendor) {
        switch (vendor) {
            case XIAOMI:
                return buildXiaomiIntent(context);
            case HUAWEI:
                return buildHuaweiIntent(context);
            case OPPO:
                return buildOppoIntent(context);
            case VIVO:
                return buildVivoIntent(context);
            default:
                return null;
        }
    }

    // --- 小米/Redmi ---

    private static Intent buildXiaomiIntent(Context context) {
        // 优先: 小米安全中心电池页面
        try {
            Intent intent = new Intent("miui.intent.action.POWER_HIDE_ICON_APP");
            intent.putExtra("package_name", context.getPackageName());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: 小米电池优化详情
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: 小米安全中心主页面
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.securityscan.MainActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        return null;
    }

    // --- 华为/荣耀 ---

    private static Intent buildHuaweiIntent(Context context) {
        // 优先: 华为电池优化页面
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: 华为手机管家电池管理
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.battery.BatteryOptimizationActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: 华为手机管家主页
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.MainActActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        return null;
    }

    // --- OPPO/Realme/一加 ---

    private static Intent buildOppoIntent(Context context) {
        // 优先: OPPO 电池优化页面
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: OPPO 电池管理 (新版本)
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.oplus.battery",
                    "com.oplus.battery.OplusBatteryManagerActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: OPPO 应用详情页
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        return null;
    }

    // --- vivo/iQOO ---

    private static Intent buildVivoIntent(Context context) {
        // 优先: vivo 电池优化页面
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: vivo 省电管理
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.iqoo.powermanager",
                    "com.iqoo.powermanager.PowerManagerActivity"));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        // 备选: vivo 应用详情页
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception ignored) {}

        return null;
    }
}
