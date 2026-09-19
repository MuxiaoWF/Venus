package com.muxiao.Venus;

import dagger.hilt.android.AndroidEntryPoint;

import static com.muxiao.Venus.common.Constants.Prefs.APP_INFO_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_IMAGE_URI;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.LAST_VERSION;
import static com.muxiao.Venus.common.Constants.Prefs.PREF_CAPTCHA_PENDING;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

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
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
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

    // 毛玻璃"透镜"三件套：底栏后方的模糊背景层、背景图视图、降采样模糊小样。
    // setupBackground（解码赋值）与 insets 回调（几何重对齐）跨方法共用，故提为字段。
    private ImageView backgroundImage;
    private ImageView bottomNavBlur;
    private Bitmap blurredGlassBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动闪屏
        SplashScreen.installSplashScreen(this);
        // 应用选定的主题（含换肤 overlay）。
        // 注意必须放在 installSplashScreen 之后：闪屏库内部 setTheme(postSplashScreenTheme)
        // 会重置主题、冲掉此前 applyStyle 的换肤 overlay，导致窗口背景回落到系统动态色。
        SettingsFragment.applyAppTheme(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 状态栏设置
        EdgeToEdge.enable(this);

        // 检查更新
        checkForUpdatesIfNeeded();
        checkAndUpdateConfig(this);

        // 初始化ViewPager2和底部导航
        viewPager = findViewById(R.id.viewPager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        navigationRailView = findViewById(R.id.navigation_rail);
        bottomNavBlur = findViewById(R.id.bottom_nav_blur);
        if (bottomNavBlur != null) {
            // 与 bg_bottom_nav_rounded 相同的大圆角裁剪，
            // 防止模糊层以方角从半透明玻璃的圆角空隙处露出
            float glassCorner = getResources().getDimension(R.dimen.shape_corner_lg);
            bottomNavBlur.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), glassCorner);
                }
            });
            bottomNavBlur.setClipToOutline(true);
        }

        // 设置背景图片（须在透镜视图初始化之后：其回调会引用 backgroundImage/bottomNavBlur）
        setupBackground();
        // 监听背景 prefs 变化：预设档/不透明度/图片更换即时重合成。
        // 此前只在 onResume 检测——ViewPager 切页不触发 Activity 生命周期，
        // 导致设置页里切换淡化风格「无感」。300ms 防抖合并滑条连续写值。
        getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(backgroundPrefListener);
        // 设置ViewPager2适配器
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 设置预加载相邻页面数量
        viewPager.setOffscreenPageLimit(3);

        // 页面切换动效：轻微淡入 + 缩放，增强高级感；尊重系统「减少动态效果」
        viewPager.setPageTransformer(this::applyPageTransform);

        // 设置背景图片时降低卡片不透明度
        adjustCardsForBackground();

        // 处理状态栏内边距，避免工具栏被状态栏遮挡。
        // 手机布局的底部避让由底栏监听器统一处理（导航栏 inset + 底栏高度），
        // 这里保留现有底部内边距即可；宽屏（NavigationRail）布局中 ViewPager
        // 直通屏幕底部，需自行让出系统导航栏高度。
        ViewCompat.setOnApplyWindowInsetsListener(viewPager, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottomPad = navigationRailView != null
                    ? insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    : v.getPaddingBottom();
            v.setPadding(v.getPaddingLeft(), statusBarHeight,
                    v.getPaddingRight(), bottomPad);
            return insets;
        });

        // EdgeToEdge 下系统导航栏（手势条/三键导航）会覆盖窗口底部内容，
        // 底部导航需在原有 16dp 外边距之上再叠加导航栏 inset，避免 Tab 被遮挡难以点按
        if (bottomNavigationView != null) {
            int baseBottomMargin = getResources().getDimensionPixelSize(R.dimen.space_4);
            ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView, (v, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                lp.bottomMargin = baseBottomMargin + navBarHeight;
                v.setLayoutParams(lp);
                // 内容已延伸到底栏之下（毛玻璃透感来源）：按「底栏高度 + 外边距 + 导航栏 inset」
                // 给 ViewPager 底部内边距，保证最后一个列表项能完整滚出玻璃区；
                // 底栏位置变化后同步重对齐模糊透镜的取景矩阵。
                v.post(() -> {
                    if (v.getHeight() == 0) return;
                    viewPager.setPadding(viewPager.getPaddingLeft(), viewPager.getPaddingTop(),
                            viewPager.getPaddingRight(),
                            v.getHeight() + baseBottomMargin + navBarHeight);
                    if (bottomNavBlur != null
                            && bottomNavBlur.getVisibility() == android.view.View.VISIBLE)
                        updateBlurStripGeometry();
                });
                return insets;
            });
        }
        if (navigationRailView != null) {
            // 宽屏侧边导航纵贯全高，底部让出导航栏 inset，防止最后一个 Tab 落入手势区
            ViewCompat.setOnApplyWindowInsetsListener(navigationRailView, (v, insets) -> {
                int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), navBarHeight);
                return insets;
            });
        }

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
        refreshBackgroundIfPrefsChanged();
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
     * 供 Fragment 程序化跳转到指定页（无用户引导：账号行/运行签到跳用户管理页）。
     * 页面切换回调会联动底部导航选中态，此处只需切 ViewPager。
     */
    public void navigateToPage(int position) {
        viewPager.setCurrentItem(position, true);
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
     * 加载用户设定的背景图：后台解码 → 预合成（按淡化风格预设降饱和提亮/压暗，
     * 再按不透明度滑条与主题底色一次性掺合）→ 主线程全不透明显示。
     * 关键：淡化只发生一次且全部烘进位图——此前先预处理再 setAlpha 叠在纯色底上，
     * 等于双重淡化（最终像素 = 预处理图 × alpha + 底色 × (1-alpha)），画面必然发蒙，
     * 且 alpha 把各预设档的差距压缩到一半以下，导致切换「无感」。
     * 预合成后：滑条直接控制图片权重，预设控制色彩质感，各自独立生效。
     * 权限不足或解码失败时回退隐藏并提示。
     * 同时在后台线程生成底栏毛玻璃用的模糊小样（连续降采样），主线程只做矩阵对齐。
     */
    private void setupBackground() {
        backgroundImage = findViewById(R.id.background_image);
        Uri backgroundImageUri = SettingsFragment.getBackgroundImageUri(this);
        float backgroundAlpha = SettingsFragment.getBackgroundAlpha(this);
        int backgroundPreset = SettingsFragment.getBackgroundPreset(this);
        appliedBackgroundPreset = backgroundPreset;
        appliedBackgroundAlpha = backgroundAlpha;

        // 无图：直接隐藏（透镜也一并隐藏，玻璃透出的就是滚动到栏下的页面内容）
        if (backgroundImageUri == null) {
            backgroundImage.setVisibility(android.view.View.GONE);
            return;
        }

        // 底色掺合色在主线程解析（后台线程访问主题属性不安全），只解析一次
        final int surfaceColor = com.google.android.material.color.MaterialColors
                .getColor(backgroundImage, com.google.android.material.R.attr.colorSurface);

        com.muxiao.Venus.common.AppExecutors.get().execute(() -> {
            String error = null;
            Bitmap source = null;
            try {
                source = getBitmapFromUri(backgroundImageUri);
            } catch (SecurityException e) {
                // 权限不足，清除背景设置
                getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(BACKGROUND_IMAGE_URI)
                        .apply();
                error = getString(R.string.err_no_background_permission,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            } catch (Exception e) {
                error = getString(R.string.err_background_setup_error,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            }
            // 预合成与模糊小样都在后台一次算好：纯位图运算，无版本兼容问题
            final Bitmap composited = source != null
                    ? createCompositedBitmap(source, backgroundPreset, backgroundAlpha, surfaceColor)
                    : null;
            final Bitmap blurred = composited != null ? createBlurredGlassBitmap(composited) : null;
            final Drawable result =
                    composited != null ? new BitmapDrawable(getResources(), composited) : null;
            final String errorMsg = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // 直接复用方法开头 findViewById 的结果：同一 Activity 实例内视图引用不变，
                // 且已用 isFinishing/isDestroyed 拦截过期回调（重复查找会触发 Lint DuplicateIdLookup）
                if (errorMsg != null) {
                    backgroundImage.setVisibility(android.view.View.GONE);
                    if (bottomNavBlur != null)
                        bottomNavBlur.setVisibility(android.view.View.GONE);
                    show_error_dialog(MainActivity.this, errorMsg);
                    return;
                }
                if (result != null) {
                    blurredGlassBitmap = blurred;
                    backgroundImage.setImageDrawable(result);
                    // 淡化已全部烘进位图：视图恒为全不透明，绝不再叠加 alpha
                    backgroundImage.setAlpha(1f);
                    backgroundImage.setVisibility(android.view.View.VISIBLE);
                    if (bottomNavBlur != null) {
                        bottomNavBlur.setVisibility(android.view.View.VISIBLE);
                        // 等布局完成（含 insets 让位）后再做矩阵对齐，取景才准确
                        bottomNavBlur.post(this::updateBlurStripGeometry);
                    }
                } else {
                    backgroundImage.setVisibility(android.view.View.GONE);
                    if (bottomNavBlur != null)
                        bottomNavBlur.setVisibility(android.view.View.GONE);
                }
            });
        });
    }

    /** 已应用到 UI 的背景预设档 / 不透明度，供 onResume 检测变化后重新预合成。 */
    private int appliedBackgroundPreset = -1;
    private float appliedBackgroundAlpha = -1f;

    /** 背景 prefs 变化 → 防抖后重新预合成（background_prefs 内全部键均为背景相关，无需逐键判断）。 */
    private final android.os.Handler backgroundReloadHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable backgroundReloadRunnable = this::setupBackground;
    private final SharedPreferences.OnSharedPreferenceChangeListener backgroundPrefListener =
            (prefs, key) -> {
                backgroundReloadHandler.removeCallbacks(backgroundReloadRunnable);
                backgroundReloadHandler.postDelayed(backgroundReloadRunnable, 300);
            };

    @Override
    protected void onDestroy() {
        getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(backgroundPrefListener);
        backgroundReloadHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * 设置页改了淡化风格/不透明度后无需重启：二者现在都烘在位图里，
     * 任一变化都走完整重合成（后台线程，开销一次性，可接受）。
     * 首次添加背景图（此前视图为 GONE）也在这里兜底触发。
     */
    private void refreshBackgroundIfPrefsChanged() {
        if (backgroundImage == null) return;
        int preset = SettingsFragment.getBackgroundPreset(this);
        float alpha = SettingsFragment.getBackgroundAlpha(this);
        boolean hadImage = backgroundImage.getVisibility() != android.view.View.GONE;
        if (!hadImage && SettingsFragment.getBackgroundImageUri(this) != null
                || preset != appliedBackgroundPreset || alpha != appliedBackgroundAlpha) {
            setupBackground();
        }
    }

    /**
     * 淡化风格预设：每档 {饱和度, 亮度偏移(0~1), 对比度}。亮度方向随昼夜翻转——
     * 浅色模式提亮（向主题浅底融合），深色模式压暗（向主题深底融合），
     * 避免深色模式下背景刺眼、浅色模式下背景发闷。
     * 淡化只过一遍位图（预合成），三档从「水彩」到「接近原图」拉开明显差距：
     * 轻盈大幅降饱和降对比，浓郁保持全饱和并轻微提升对比。
     */
    private static final float[][] BACKGROUND_PRESETS = {
            {0.25f, 0.15f, 0.85f},  // 0 轻盈：水彩感
            {0.60f, 0.06f, 0.95f},  // 1 标准（默认）：柔和
            {1.00f, 0.00f, 1.10f},  // 2 浓郁：接近原图
    };

    /**
     * 预合成：降饱和 + 亮度偏移（ColorMatrix）→ 与主题底色按图片权重一次性掺合。
     * 输出为全不透明位图（out = 预处理图 × w + 底色 × (1-w)），主线程直接铺满即可，
     * 不再依赖 ImageView alpha——那是此前「发蒙 + 预设无感」的根源。
     */
    private Bitmap createCompositedBitmap(Bitmap source, int presetIndex, float imageWeight,
            int surfaceColor) {
        float[] preset = BACKGROUND_PRESETS[
                Math.max(0, Math.min(presetIndex, BACKGROUND_PRESETS.length - 1))];
        boolean darkMode = (getResources().getConfiguration()
                .uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(preset[0]);
        float translate = (darkMode ? -1f : 1f) * preset[1] * 255f;
        ColorMatrix brightness = new ColorMatrix(new float[]{
                1, 0, 0, 0, translate,
                0, 1, 0, 0, translate,
                0, 0, 1, 0, translate,
                0, 0, 0, 1, 0
        });
        float contrast = preset[2];
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, (1 - contrast) * 128f,
                0, contrast, 0, 0, (1 - contrast) * 128f,
                0, 0, contrast, 0, (1 - contrast) * 128f,
                0, 0, 0, 1, 0
        });
        // 依序串联：降饱和 → 提亮/压暗 → 对比度（postConcat 语义为「后乘」，执行顺序按添加顺序）
        ColorMatrix effect = new ColorMatrix();
        effect.postConcat(saturation);
        effect.postConcat(brightness);
        effect.postConcat(contrastMatrix);

        // 先铺满主题底色，再按图片权重叠上预处理图：out = 预处理图 × w + 底色 × (1-w)。
        // 单次掺合、全不透明输出——不再有第二层 alpha 叠加。
        Bitmap src = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && source.getConfig() == Bitmap.Config.HARDWARE)
                ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
        if (src == null) return source;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return src;
        Canvas canvas = new Canvas(out);
        canvas.drawColor(surfaceColor);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(Math.round(Math.max(0f, Math.min(1f, imageWeight)) * 255f));
        paint.setColorFilter(new ColorMatrixColorFilter(effect));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    /**
     * 把模糊小样按 centerCrop 规则对齐到底栏区域：
     * 先按背景图视图的尺寸算出整图的缩放与居中偏移，再减去底栏相对背景图的偏移，
     * 使透镜里显示的正是玻璃背后那块像素——这就是"磨砂玻璃"的取景原理。
     * 底栏位置随 insets 变化（系统导航栏 inset 在首帧后才到位），几何变化后需重调。
     */
    private void updateBlurStripGeometry() {
        if (bottomNavBlur == null || blurredGlassBitmap == null
                || bottomNavBlur.getWidth() == 0 || backgroundImage.getWidth() == 0) return;
        int[] stripLoc = new int[2];
        int[] bgLoc = new int[2];
        bottomNavBlur.getLocationInWindow(stripLoc);
        backgroundImage.getLocationInWindow(bgLoc);
        int offsetX = stripLoc[0] - bgLoc[0];
        int offsetY = stripLoc[1] - bgLoc[1];

        int bgW = backgroundImage.getWidth();
        int bgH = backgroundImage.getHeight();
        int bmpW = blurredGlassBitmap.getWidth();
        int bmpH = blurredGlassBitmap.getHeight();
        float scale = Math.max(bgW / (float) bmpW, bgH / (float) bmpH);
        float dx = (bgW - bmpW * scale) / 2f;
        float dy = (bgH - bmpH * scale) / 2f;

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx - offsetX, dy - offsetY);
        bottomNavBlur.setImageMatrix(matrix);
    }

    /**
     * 生成毛玻璃用的模糊小样：连续 1/4 降采样到极小尺寸（浓缩像素即低通滤波），
     * 再双线性回升到原图约 1/4，得到平滑的磨砂质感。纯位图运算，全 API 级别可用。
     */
    private static Bitmap createBlurredGlassBitmap(Bitmap source) {
        Bitmap out = source;
        while (out.getWidth() > 64 && out.getHeight() > 64) {
            out = Bitmap.createScaledBitmap(out,
                    Math.max(1, out.getWidth() / 4), Math.max(1, out.getHeight() / 4), true);
        }
        int targetW = Math.max(1, source.getWidth() / 4);
        int targetH = Math.max(1, source.getHeight() / 4);
        if (out.getWidth() < targetW && out.getHeight() < targetH)
            out = Bitmap.createScaledBitmap(out, targetW, targetH, true);
        return out;
    }

    /**
     * 设置背景图片时，降低卡片背景不透明度以露出背景图
     */
    private void adjustCardsForBackground() {
        Uri bgUri = SettingsFragment.getBackgroundImageUri(this);
        viewPager.post(() -> {
            // 底部导航栏毛玻璃：颜色由 bg_bottom_nav_rounded 的 ?attr/colorSurfaceContainer
            // 提供（随 6 套 ThemeOverlay 与昼夜在运行时解析），此处只叠加"不透明度"，
            // 且 alpha 取自 @integer/bottom_nav_glass_alpha。
            // 颜色来源与不透明度各归一处；不再像过去那样 setTint 一个写死 alpha 的
            // 颜色去覆盖 drawable，也不再把颜色写死在 drawable 里（两者都会让换肤失效）。
            if (bottomNavigationView != null && bottomNavigationView.getBackground() != null) {
                bottomNavigationView.getBackground().mutate()
                        .setAlpha(getResources().getInteger(R.integer.bottom_nav_glass_alpha));
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
     * 按系统版本从 Uri 解码 Bitmap（Android P+ 用 ImageDecoder，自动处理 EXIF 旋转；
     * 否则 BitmapFactory）。失败时抛出异常，由调用方（后台线程）统一收集并回主线程提示。
     */
    private Bitmap getBitmapFromUri(Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9.0及以上版本使用ImageDecoder。
            // 必须强制软件内存分配：ImageDecoder 默认可能返回 HARDWARE 位图，
            // 而后续 createFadedBitmap 要在软件 Canvas 上做色彩滤镜重绘，会直接崩溃。
            return ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, source) ->
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            );
        }
        // Android 9.0以下版本使用BitmapFactory
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) return null;
        Bitmap bitmap;
        try (InputStream is = inputStream) {
            bitmap = BitmapFactory.decodeStream(is);
        }
        return bitmap;
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
