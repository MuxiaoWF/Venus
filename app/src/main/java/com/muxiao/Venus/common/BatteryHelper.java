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
     * 打开应用详情设置页，用户可在此手动设置后台行为、电池优化等
     */
    public static void openVendorBatterySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
