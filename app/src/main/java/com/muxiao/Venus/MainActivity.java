package com.muxiao.Venus;

import dagger.hilt.android.AndroidEntryPoint;

import static com.muxiao.Venus.common.Constants.Prefs.APP_INFO_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.LAST_VERSION;
import static com.muxiao.Venus.common.Constants.Prefs.PREF_CAPTCHA_PENDING;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageView;

import com.google.android.material.card.MaterialCardView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;

import com.muxiao.Venus.common.Logger;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.LinkFragment;
import com.muxiao.Venus.User.UserManagementFragment;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.Setting.UpdateChecker;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.data.ConfigRepository;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import java.io.InputStream;

/**
 * 主Activity：底部导航（主页/抽卡链接/用户管理/设置）、背景图片加载、
 * 语言/主题切换、深色模式跟随系统、后台人机验证入口。
 */
@AndroidEntryPoint
public class MainActivity extends BaseActivity {
    private ViewPager2 viewPager;
    public BottomNavigationView bottomNavigationView;
    private NavigationRailView navigationRailView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动闪屏
        SplashScreen.installSplashScreen(this);
        // 应用选定的主题（含换肤 overlay）
        SettingsFragment.applyAppTheme(this);

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
        navigationRailView = findViewById(R.id.navigation_rail);
        // 设置ViewPager2适配器
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 设置预加载相邻页面数量
        viewPager.setOffscreenPageLimit(3);

        // 页面切换动效：轻微淡入 + 缩放，增强高级感；尊重系统「减少动态效果」
        viewPager.setPageTransformer(this::applyPageTransform);

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
                int menuId = getMenuIdByPosition(position);
                if (navigationRailView != null)
                    navigationRailView.setSelectedItemId(menuId);
                else if (bottomNavigationView != null)
                    bottomNavigationView.setSelectedItemId(menuId);
            }
        });

        // 导航选择监听器（BottomNav / NavigationRail 共用逻辑），与ViewPager2联动
        com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener navListener = item -> {
            int itemId = item.getItemId();
            int position = getPositionByMenuId(itemId);
            if (position != -1) {
                viewPager.setCurrentItem(position);
                return true;
            }
            return false;
        };
        if (navigationRailView != null)
            navigationRailView.setOnItemSelectedListener(navListener);
        else if (bottomNavigationView != null)
            bottomNavigationView.setOnItemSelectedListener(navListener);

        // 默认加载首页Fragment
        if (savedInstanceState == null)
            viewPager.setCurrentItem(0);

        // 注册 HomeFragment 就绪监听：替代原先 postDelayed 轮询等待 Fragment 创建，
        // 用于在其就绪后触发后台人机验证（见 tryTriggerCaptchaIfReady）。
        getSupportFragmentManager().setFragmentResultListener(
                HomeFragment.RESULT_HOME_READY, this,
                (requestKey, result) -> tryTriggerCaptchaIfReady());

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

    /** 待触发的后台人机验证标记（替代原先 postDelayed 轮询的状态机）。 */
    private boolean captchaPending = false;

    /**
     * 检查是否有待处理的后台人机验证，有则触发。
     * 不再轮询：HomeFragment 通过 FragmentResult({@link HomeFragment#RESULT_HOME_READY}) 通知就绪，
     * 由 {@link #tryTriggerCaptchaIfReady()} 触发。
     */
    private void triggerCaptchaIfPending() {
        ConfigRepository configRepo = new ConfigRepository(this);
        boolean pending = configRepo.getBoolean(PREF_CAPTCHA_PENDING, false);
        Logger.debug("VenusCaptcha", "triggerCaptchaIfPending: pending=" + pending);
        if (!pending) return;

        // 清除标记，防止重复触发（仓储写入为 Write-Through：缓存即时生效，后续读取无问题）
        configRepo.putBoolean(PREF_CAPTCHA_PENDING, false);
        captchaPending = true;
        viewPager.setCurrentItem(0, false);

        // HomeFragment 可能尚未就绪：先直接尝试一次；未就绪则等待其 RESULT_HOME_READY 回调。
        tryTriggerCaptchaIfReady();
    }

    /**
     * HomeFragment 已就绪（或刚就绪）时尝试触发后台人机验证。
     * 仅当 captchaPending 为真且能立即取到 HomeFragment 实例时触发，避免轮询。
     */
    private void tryTriggerCaptchaIfReady() {
        if (!captchaPending) return;
        HomeFragment homeFragment = getHomeFragment();
        if (homeFragment != null) {
            captchaPending = false;
            homeFragment.performBackgroundCaptchaVerification();
        }
    }

    // 解析来自后台人机验证通知的 Intent：置位 captchaPending 并切到首页等待触发
    private void handleCaptchaIntent(Intent intent) {
        Logger.debug("VenusCaptcha", "handleCaptchaIntent called, action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && Constants.ACTION_HANDLE_CAPTCHA.equals(intent.getAction())) {
            intent.setAction(null);
            new ConfigRepository(this).putBoolean(PREF_CAPTCHA_PENDING, true);
            viewPager.setCurrentItem(0, false);
        }
    }

    // 从 FragmentManager 中查找当前已挂载的 HomeFragment 实例（不依赖 tag）
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
     * ViewPager2 页面变换：当前页 100% 不透明/满尺寸，两侧页轻微淡出并缩小，
     * 形成柔和的纵深感。系统关闭动画时直接复位属性。
     */
    private void applyPageTransform(View page, float position) {
        if (com.muxiao.Venus.common.tools.isReducedMotionEnabled(this)) {
            page.setAlpha(1f);
            page.setScaleX(1f);
            page.setScaleY(1f);
            return;
        }
        float abs = Math.abs(position);
        page.setAlpha(1f - 0.12f * abs);
        page.setScaleX(1f - 0.03f * abs);
        page.setScaleY(1f - 0.03f * abs);
    }

    /**
     * 读取设置中的自动更新开关，开启时启动 UpdateChecker 检查新版本。
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
     * ViewPager2 适配器：按位置创建 Home / 用户管理 / 抽卡链接 / 设置 四个 Fragment。
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
     * 加载用户设定的背景图并按设置不透明度显示；权限不足或解码失败时回退隐藏并提示。
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
        viewPager.post(() -> {
            // 底部导航栏：始终为半透明毛玻璃（colorSurfaceContainer 的半透明，比 window 背景深一档），
            // 圆角由 bg_bottom_nav_rounded 提供。无背景图时也能浮在浅色背景上；
            // 有背景图时则透出图片形成真正的毛玻璃悬浮。不再依赖 backgroundTint，
            // 因为样式里已将其置 @null，此处直接给 drawable 上色。
            if (bottomNavigationView != null) {
                int navColor = (0x99 << 24) | (0x00FFFFFF & com.google.android.material.color.MaterialColors.getColor(
                        bottomNavigationView, com.google.android.material.R.attr.colorSurfaceContainer, 0));
                android.graphics.drawable.Drawable bg = bottomNavigationView.getBackground().mutate();
                bg.setTint(navColor);
                bottomNavigationView.setBackground(bg);
            }
            if (bgUri == null) return;
            int semiTransparentColor = (200 << 24) | (0x00FFFFFF & com.google.android.material.color.MaterialColors.getColor(
                    viewPager, com.google.android.material.R.attr.colorSurfaceContainerLow, 0));
            applyTopLevelCardAlpha(viewPager, semiTransparentColor);
        });
    }

    // 只为 ViewPager 内最外层分组卡片设置半透明背景色，以露出背景图；
    // 内层列表项/状态卡不再重复着色，避免多层半透明叠加显脏。
    // maxDepth=4 覆盖：ViewPager(0) → fragment root(1) → NestedScrollView(2)
    // → 内容容器(3) → 分组卡片(4)；再深的任务项/空态卡不再处理。
    private static final int CARD_ALPHA_MAX_DEPTH = 4;

    private void applyTopLevelCardAlpha(View view, int color) {
        applyTopLevelCardAlpha(view, color, 0);
    }

    private void applyTopLevelCardAlpha(View view, int color, int depth) {
        if (depth > CARD_ALPHA_MAX_DEPTH) return;
        if (view instanceof MaterialCardView) {
            ((MaterialCardView) view).setCardBackgroundColor(color);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTopLevelCardAlpha(group.getChildAt(i), color, depth + 1);
            }
        }
    }

    /**
     * 按系统版本从 Uri 解码背景 Drawable（Android P+ 用 ImageDecoder，否则 BitmapFactory）。
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
            ConfigRepository configRepo = new ConfigRepository(context);
            boolean captchaPending = configRepo.getBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, false);
            configRepo.clear();
            if (captchaPending) configRepo.putBoolean(Constants.Prefs.PREF_CAPTCHA_PENDING, true);
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

}
