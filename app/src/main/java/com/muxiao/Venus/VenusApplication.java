package com.muxiao.Venus;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class VenusApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
