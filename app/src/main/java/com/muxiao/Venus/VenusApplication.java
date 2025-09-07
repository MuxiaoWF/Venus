package com.muxiao.Venus;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.muxiao.Venus.Setting.SettingsFragment;

public class VenusApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SettingsFragment.applyThemeVariant(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
