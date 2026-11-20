package com.muxiao.Venus.common;

import android.content.Context;

import androidx.activity.ComponentActivity;


/**
 * 所有 Activity 的基类：在 {@link #attachBaseContext(Context)} 中统一应用用户语言与深浅色设置。
 * 用 {@link ComponentActivity}：界面已全量 Compose，无需 AppCompat 的主题宿主与夜间模式开关。
 */
public abstract class BaseActivity extends ComponentActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }
}
