package com.muxiao.Venus.common;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

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
     * 构建电池优化豁免的跳转 Intent，调用方用 ActivityResultLauncher 启动；
     * 机型不支持该页面（ActivityNotFoundException）时回退 {@link #openVendorBatterySettings}。
     *
     * <p>ColorOS（OPPO / realme / 一加）改跳「应用详情」页：该 ROM 的后台管控是自有机制
     * （应用详情 → 耗电管理 → 「允许完全后台行为」/「允许自启动」），与 AOSP 的
     * Doze 白名单不是同一套开关。ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 在该 ROM
     * 上只会打开系统的「电池优化」列表，勾了也不解除厂商管控（熄屏后仍会掐断后台网络），
     * 所以必须把用户送到应用详情里去改。
     *
     * <p>其余机型保持跳 AOSP「忽略电池优化」列表页：无需任何权限、无 Google Play
     * BatteryLife 政策风险，也不受国产 ROM 拦截变形影响（用户需手动切「所有应用」并允许本应用）。
     * 新增厂商适配时只需扩展 {@link #isVendorPowerManaged()} 的判定。
     */
    public static Intent buildBatteryOptimizationSettingsIntent(Context context) {
        if (isVendorPowerManaged())
            return buildAppDetailsIntent(context);
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    /**
     * 打开本应用的「应用详情」设置页，用户可在此手动设置后台行为、电池优化等
     * （ColorOS 等厂商的耗电管理/自启动入口都挂在这一页下面）
     */
    public static void openVendorBatterySettings(Context context) {
        context.startActivity(buildAppDetailsIntent(context));
    }

    /** 本应用的应用详情页 Intent（带包名，务必 NEW_TASK——调用方可能是非 Activity 上下文） */
    private static Intent buildAppDetailsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", context.getPackageName(), null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * 本机是否为「厂商自管省电」的 ROM：这些 ROM 上 AOSP 电池优化白名单不生效
     * （判定只用于选择跳转目标，判错最坏结果也只是多一次手动寻找，不会影响功能）。
     */
    private static boolean isVendorPowerManaged() {
        String brand = normalize(Build.BRAND);
        String manufacturer = normalize(Build.MANUFACTURER);
        return brand.contains("oppo") || brand.contains("oplus")
                || brand.contains("realme") || brand.contains("oneplus")
                || manufacturer.contains("oppo") || manufacturer.contains("oplus")
                || manufacturer.contains("realme") || manufacturer.contains("oneplus");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
