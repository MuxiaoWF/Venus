package com.muxiao.Venus.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

import com.muxiao.Venus.common.Constants.Prefs;

/**
 * 集中封装「按照设置中选择的语音」重建 Context 的逻辑。
 * 原先该逻辑只存在于 {@code MainActivity.wrapLocale()}，导致其它 Activity / Service /
 * 广播 / 桌面小组件仍使用设备语言。现抽取到此处统一调用，确保任意界面都遵循
 * 设置中的语言（简体中文 / 繁體中文 / English / 跟随系统）。
 * 由 {@code BaseActivity}、各 Service 及广播/小组件统一调用。
 */
public final class LocaleHelper {
    private LocaleHelper() {
    }

    /**
     * 返回应用了用户语言设置后的 Context。
     * 当设置为「跟随系统」时直接返回原 Context（由系统语言决定）。
     */
    public static Context wrap(Context context) {
        SharedPreferences languagePrefs = context.getSharedPreferences(Prefs.LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedLanguage = languagePrefs.getInt(Prefs.SELECTED_LANGUAGE, 0);

        Locale locale;
        switch (selectedLanguage) {
            case 1: // 简体中文
                locale = Locale.SIMPLIFIED_CHINESE;
                break;
            case 2: // 繁體中文
                locale = Locale.TRADITIONAL_CHINESE;
                break;
            case 3: // English
                locale = Locale.ENGLISH;
                break;
            default: // 跟随系统
                return context;
        }

        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
