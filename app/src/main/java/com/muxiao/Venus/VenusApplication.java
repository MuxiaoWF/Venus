package com.muxiao.Venus;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.muxiao.Venus.Setting.SettingsFragment;

public class VenusApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 应用选定的主题深浅色
        SettingsFragment.applyThemeVariant(this);
        // 启用material动态颜色
        DynamicColors.applyToActivitiesIfAvailable(this);
        // 注册设备标识（gzu.liyujiang.android.cn.oaid库）
        // DeviceIdentifier.register(this);
    }
}
