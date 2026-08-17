package com.muxiao.Venus;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.muxiao.Venus.common.LocaleHelper;

/**
 * 所有 Activity 的基类：在 {@link #attachBaseContext(Context)} 中统一应用用户语言设置，
 * 避免界面回退到设备语言。具体 Activity 继续标注 {@code @AndroidEntryPoint} 即可。
 */
public abstract class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }
}
