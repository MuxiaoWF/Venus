package com.muxiao.Venus;

import static com.muxiao.Venus.common.Constants.Prefs.APP_INFO_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.CONFIG_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.LANGUAGE_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.LAST_VERSION;
import static com.muxiao.Venus.common.Constants.Prefs.PREF_CAPTCHA_PENDING;
import static com.muxiao.Venus.common.Constants.Prefs.SELECTED_LANGUAGE;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageView;

import java.util.Locale;

import com.google.android.material.card.MaterialCardView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.LinkFragment;
import com.muxiao.Venus.User.UserManagementFragment;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.Setting.UpdateChecker;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import java.io.InputStream;

/**
 * 主Activity：底部导航（主页/抽卡链接/用户管理/设置）、背景图片加载、
 * 语言/主题切换、深色模式跟随系统、后台人机验证入口。
 */
public class MainActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    public BottomNavigationView bottomNavigationView;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(wrapLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动闪屏
        SplashScreen.installSplashScreen(this);
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 状态栏设置
        EdgeToEdge.enable(this);

        // 设置背景图片
        setupBackground();

        // 检查更新
        checkForUpdatesIfNeeded();
        checkAndUpdateConfig(this);

        // 初始化ViewPager2和底部导航
        viewPager = findViewById(R.id.viewPager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        // 设置ViewPager2适配器
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 设置预加载相邻页面数量
        viewPager.setOffscreenPageLimit(3);

        // 设置背景图片时降低卡片不透明度
        adjustCardsForBackground();

        // 处理状态栏内边距，避免工具栏被状态栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(viewPager, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), statusBarHeight,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        // 设置页面切换监听器，使滑动与底部导航联动
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                bottomNavigationView.setSelectedItemId(getMenuIdByPosition(position));
            }
        });

        // 设置底部导航选择监听器，与ViewPager2联动
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            int position = getPositionByMenuId(itemId);
            if (position != -1) {
                viewPager.setCurrentItem(position);
                return true;
            }
            return false;
        });

        // 默认加载首页Fragment
        if (savedInstanceState == null)
            viewPager.setCurrentItem(0);

        // 处理后台人机验证通知跳转
        handleCaptchaIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleCaptchaIntent(intent);
        triggerCaptchaIfPending();
    }

    @Override
    protected void onResume() {
        super.onResume();
        triggerCaptchaIfPending();
    }

    /**
     * 检查是否有待处理的后台人机验证，有则触发。
     */
    private void triggerCaptchaIfPending() {
        SharedPreferences configPrefs = getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        boolean pending = configPrefs.getBoolean(PREF_CAPTCHA_PENDING, false);
        android.util.Log.e("VenusCaptcha", "triggerCaptchaIfPending: pending=" + pending);
        if (!pending) return;

        // 清除标记，防止重复触发（apply 内存写入即时生效，同实例读取无问题）
        configPrefs.edit().putBoolean(PREF_CAPTCHA_PENDING, false).apply();
        viewPager.setCurrentItem(0, false);

        // 轮询等待 HomeFragment 就绪（ViewPager 创建 Fragment 需要时间）
        waitForHomeFragment(0);
    }

    private void waitForHomeFragment(int attempt) {
        // 打印所有 Fragment 用于调试
        if (attempt == 0) {
            java.util.List<androidx.fragment.app.Fragment> allFragments = getSupportFragmentManager().getFragments();
            android.util.Log.e("VenusCaptcha", "FragmentManager fragments count=" + allFragments.size());
            for (int i = 0; i < allFragments.size(); i++) {
                androidx.fragment.app.Fragment f = allFragments.get(i);
                android.util.Log.e("VenusCaptcha", "  fragment[" + i + "]=" + f.getClass().getSimpleName() + " tag=" + f.getTag() + " added=" + f.isAdded());
            }
        }
        HomeFragment homeFragment = getHomeFragment();
        android.util.Log.e("VenusCaptcha", "waitForHomeFragment attempt=" + attempt + ", fragment=" + homeFragment);
        if (homeFragment != null) {
            homeFragment.performBackgroundCaptchaVerification();
        } else if (attempt < 50) {
            viewPager.postDelayed(() -> waitForHomeFragment(attempt + 1), 100);
        }
    }

    private void handleCaptchaIntent(Intent intent) {
        android.util.Log.e("VenusCaptcha", "handleCaptchaIntent called, action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && Constants.ACTION_HANDLE_CAPTCHA.equals(intent.getAction())) {
            intent.setAction(null);
            getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_CAPTCHA_PENDING, true).apply();
            viewPager.setCurrentItem(0, false);
        }
    }

    private HomeFragment getHomeFragment() {
        // 遍历所有已添加的 Fragment 查找 HomeFragment（不依赖 tag 格式）
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof HomeFragment) {
                return (HomeFragment) f;
            }
        }
        // 如果找不到，尝试通过 ViewPager adapter 的内部状态获取
        androidx.fragment.app.Fragment primary = getSupportFragmentManager().getPrimaryNavigationFragment();
        if (primary instanceof HomeFragment) return (HomeFragment) primary;
        return null;
    }

    /**
     * 设置 ViewPager2 是否允许用户滑动切换
     */
    public void setViewPagerSwipeEnabled(boolean enabled) {
        viewPager.setUserInputEnabled(enabled);
    }

    /**
     * 为 Fragment 中的底部空白 View 设置高度，避免被底部导航栏遮挡。
     */
    public void applyBottomPadding(View bottomPaddingView) {
        if (bottomPaddingView == null) return;
        int bottomNavHeight = bottomNavigationView.getHeight();
        if (bottomNavHeight > 0) {
            ViewGroup.LayoutParams params = bottomPaddingView.getLayoutParams();
            params.height = bottomNavHeight + (int) (32 * getResources().getDisplayMetrics().density);
            bottomPaddingView.setLayoutParams(params);
        }
    }

    /**
     * 检查更新（如果需要）
     */
    private void checkForUpdatesIfNeeded() {
        // 检查是否启用了自动更新
        boolean autoUpdateEnabled = getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AUTO_UPDATE_ENABLED, true);

        if (autoUpdateEnabled) {
            UpdateChecker updateChecker = new UpdateChecker(this);
            updateChecker.checkForUpdatesIfNeeded();
        }
    }

    /**
     * 根据菜单ID获取位置
     *
     * @param menuId 菜单AndroidID
     * @return 位置
     */
    private int getPositionByMenuId(int menuId) {
        if (menuId == R.id.navigation_home) return 0;
        else if (menuId == R.id.navigation_users) return 1;
        else if (menuId == R.id.navigation_link) return 2;
        else if (menuId == R.id.navigation_settings) return 3;
        return -1;
    }

    /**
     * 根据位置获取菜单ID
     *
     * @param position 位置
     * @return 菜单AndroidID
     */
    private int getMenuIdByPosition(int position) {
        switch (position) {
            case 1:
                return R.id.navigation_users;
            case 2:
                return R.id.navigation_link;
            case 3:
                return R.id.navigation_settings;
            case 0:
            default:
                return R.id.navigation_home;
        }
    }

    /**
     * ViewPager2适配器
     */
    private static class ViewPagerAdapter extends FragmentStateAdapter {
        public ViewPagerAdapter(MainActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:
                    return new UserManagementFragment();
                case 2:
                    return new LinkFragment();
                case 3:
                    return new SettingsFragment();
                case 0:
                default:
                    return new HomeFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 4; // 四个Fragment
        }
    }

    /**
     * 设置背景图片
     */
    private void setupBackground() {
        ImageView backgroundImage = findViewById(R.id.background_image);
        Uri backgroundImageUri = SettingsFragment.getBackgroundImageUri(this);
        float backgroundAlpha = SettingsFragment.getBackgroundAlpha(this);

        if (backgroundImageUri != null) {
            try {
                Drawable drawable = getDrawableFromUri(backgroundImageUri);
                if (drawable != null) {
                    backgroundImage.setImageDrawable(drawable);
                    backgroundImage.setAlpha(backgroundAlpha);
                    backgroundImage.setVisibility(android.view.View.VISIBLE);
                } else {
                    backgroundImage.setVisibility(android.view.View.GONE);
                }
            } catch (SecurityException e) {
                // 权限不足，清除背景设置
                show_error_dialog(this, getString(R.string.err_no_background_permission) + e.getMessage());
                getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove("background_image_uri")
                        .apply();
                backgroundImage.setVisibility(android.view.View.GONE);
            } catch (Exception e) {
                // 出现其他异常时隐藏背景图片
                backgroundImage.setVisibility(android.view.View.GONE);
                show_error_dialog(this, getString(R.string.err_background_setup_error) + e.getMessage());
            }
        } else {
            backgroundImage.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * 设置背景图片时，降低卡片背景不透明度以露出背景图
     */
    private void adjustCardsForBackground() {
        Uri bgUri = SettingsFragment.getBackgroundImageUri(this);
        if (bgUri == null) return;
        viewPager.post(() -> {
            int semiTransparentColor = (200 << 24) | (0x00FFFFFF & com.google.android.material.color.MaterialColors.getColor(
                    viewPager, com.google.android.material.R.attr.colorSurfaceContainerLow, 0));
            applyCardAlpha(viewPager, semiTransparentColor);
            // 底部导航栏也适当降低不透明度，保留圆角
            int navColor = (200 << 24) | (0x00FFFFFF & com.google.android.material.color.MaterialColors.getColor(
                    bottomNavigationView, com.google.android.material.R.attr.colorSurfaceContainer, 0));
            android.graphics.drawable.Drawable bg = bottomNavigationView.getBackground().mutate();
            bg.setTint(navColor);
            bottomNavigationView.setBackground(bg);
        });
    }

    private void applyCardAlpha(View view, int color) {
        if (view instanceof MaterialCardView) {
            ((MaterialCardView) view).setCardBackgroundColor(color);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyCardAlpha(group.getChildAt(i), color);
            }
        }
    }

    /**
     * 从Uri获取Drawable
     */
    private Drawable getDrawableFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9.0及以上版本使用ImageDecoder
                return ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                        }
                );
            } else {
                // Android 9.0以下版本使用BitmapFactory
                InputStream inputStream = getContentResolver().openInputStream(uri);
                if (inputStream == null) return null;
                Bitmap bitmap;
                try (InputStream is = inputStream) {
                    bitmap = BitmapFactory.decodeStream(is);
                }
                return bitmap != null ? new android.graphics.drawable.BitmapDrawable(getResources(), bitmap) : null;
            }
        } catch (Exception e) {
            show_error_dialog(this, getString(R.string.err_get_drawable_error) + e.getMessage());
            return null;
        }
    }

    /**
     * 检查应用版本并在更新时自动从云端获取最新配置
     */
    public static void checkAndUpdateConfig(Context context) {
        SharedPreferences appPrefs = context.getSharedPreferences(APP_INFO_PREFS_NAME, Context.MODE_PRIVATE);
        int lastVersion = appPrefs.getInt(LAST_VERSION, 0);
        int currentVersion = BuildConfig.VERSION_CODE;
        if (currentVersion > lastVersion) {
            // 应用已更新，清除旧配置（回退到内置默认值）
            SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
            boolean captchaPending = prefs.getBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, false);
            prefs.edit().clear().apply();
            if (captchaPending) prefs.edit().putBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, true).apply();
            // 更新最后运行的版本号
            appPrefs.edit().putInt(LAST_VERSION, currentVersion).apply();
            // 后台自动从云端获取最新配置
            new Thread(() -> MiHoYoBBSConstants.update_config_from_web(context)).start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * 包装Context以应用语言设置（现代API，无deprecation）
     */
    public static Context wrapLocale(Context context) {
        SharedPreferences languagePrefs = context.getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedLanguage = languagePrefs.getInt(SELECTED_LANGUAGE, 0);

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