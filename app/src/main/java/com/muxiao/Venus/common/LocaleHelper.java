package com.muxiao.Venus.common;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

import com.muxiao.Venus.common.Constants.Prefs;

/**
 * 按设置重建 Context，供 {@code BaseActivity}、各 Service 与广播/小组件统一调用：
 * 语言（简体中文 / 繁體中文 / English / 跟随系统）与深浅色（跟随系统 / 浅色 / 深色）。
 *
 * <p>深浅色靠覆写 {@link Configuration#uiMode} 实现（替代已移除的 AppCompatDelegate）。
 */
public final class LocaleHelper {
    private LocaleHelper() {
    }

    /** 主题深浅色变体：跟随系统，不覆写 uiMode。 */
    private static final int THEME_VARIANT_FOLLOW_SYSTEM = 0;
    /** 主题深浅色变体：强制深色。 */
    private static final int THEME_VARIANT_DARK = 2;

    /** 返回应用语言与深浅色设置后的 Context；两项都「跟随系统」时原样返回。 */
    public static Context wrap(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        boolean changed = false;

        int selectedLanguage = context.getSharedPreferences(Prefs.LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(Prefs.SELECTED_LANGUAGE, 0);
        switch (selectedLanguage) {
            case 1: // 简体中文
                config.setLocale(Locale.SIMPLIFIED_CHINESE);
                changed = true;
                break;
            case 2: // 繁體中文
                config.setLocale(Locale.TRADITIONAL_CHINESE);
                changed = true;
                break;
            case 3: // English
                config.setLocale(Locale.ENGLISH);
                changed = true;
                break;
            default: // 跟随系统
                break;
        }

        int themeVariant = context.getSharedPreferences(Prefs.THEME_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(Prefs.SELECTED_THEME_VARIANT, THEME_VARIANT_FOLLOW_SYSTEM);
        if (themeVariant != THEME_VARIANT_FOLLOW_SYSTEM) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (themeVariant == THEME_VARIANT_DARK
                    ? Configuration.UI_MODE_NIGHT_YES
                    : Configuration.UI_MODE_NIGHT_NO);
            changed = true;
        }

        return changed ? context.createConfigurationContext(config) : context;
    }
}
