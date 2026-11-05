package com.muxiao.Venus;

import com.muxiao.Venus.common.BaseActivity;
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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;

import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.LinkFragment;
import com.muxiao.Venus.User.UserManagementFragment;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.Setting.UpdateChecker;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.common.ui.compose.MainShellView;
import com.muxiao.Venus.common.data.ConfigRepository;

import java.io.InputStream;

/**
 * 主Activity：底部导航（主页/用户管理/抽卡链接/设置）、背景图片加载、
 * 语言/主题切换、深色模式跟随系统、后台人机验证入口。
 *
 * <p>界面外壳已迁到 Compose（{@link MainShellView}：背景层 + HorizontalPager +
 * 玻璃底栏，见 docs/module-ui.md）。本类只做业务编排：四个页面控制器
 * （原 Fragment 收敛为控制器）的创建与 show/hide、背景预合成管线、
 * 页面程序化跳转、人机验证触发链。</p>
 *
 * <p>控制器生命周期对齐原 FragmentStateAdapter 的 maxLifecycle 语义：
 * 仅当前选中页处于「已恢复」态——页面选中回调与 Activity onResume/onPause
 * 统一折算为控制器的 show()/hide()（见 {@link #setShownPage}）。</p>
 */
@AndroidEntryPoint
public class MainActivity extends BaseActivity {

    /** Compose 外壳（背景 + Pager + 底栏）。 */
    private MainShellView shell;

    /** 四个页面控制器，顺序即页面下标：0 主页 / 1 用户管理 / 2 抽卡链接 / 3 设置。 */
    private final HomeFragment homeController;
    private final UserManagementFragment usersController;
    private final LinkFragment linkController;
    private final SettingsFragment settingsController;

    /** 当前处于「已恢复」态的页面下标（-1 = 尚无）。 */
    private int shownPage = -1;

    public MainActivity() {
        homeController = new HomeFragment(this);
        usersController = new UserManagementFragment(this);
        linkController = new LinkFragment(this);
        settingsController = new SettingsFragment(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动闪屏
        SplashScreen.installSplashScreen(this);
        // 应用选定的主题（含换肤 overlay）。
        // 注意必须放在 installSplashScreen 之后：闪屏库内部 setTheme(postSplashScreenTheme)
        // 会重置主题、冲掉此前 applyStyle 的换肤 overlay，导致窗口背景回落到系统动态色。
        SettingsFragment.applyAppTheme(this);

        super.onCreate(savedInstanceState);

        // 状态栏/导航栏全出血（inset 由外壳与各页在 Compose 侧自行消费）
        EdgeToEdge.enable(this);

        // 检查更新
        checkForUpdatesIfNeeded();
        checkAndUpdateConfig(this);

        // ActivityResult 启动器：必须在本方法内（STARTED 之前）注册
        registerLaunchers();

        // Compose 外壳：页面视图由各控制器惰性创建（外壳首次组合时逐页调用一次）
        shell = new MainShellView(this);
        shell.setPageFactory((index, context) -> {
            switch (index) {
                case 1: return usersController.createView(context);
                case 2: return linkController.createView(context);
                case 3: return settingsController.createView(context);
                case 0:
                default: return homeController.createView(context);
            }
        });
        // 页面选中 → 控制器 show/hide（对齐 FragmentStateAdapter 的 maxLifecycle 语义）
        shell.setOnPageSelectedListener(this::setShownPage);

        // 设置背景图片（发布给外壳背景层；玻璃采样源为 Compose GraphicsLayer，见 MainShellView）
        setupBackground();
        // 监听背景 prefs 变化：预设档/不透明度/图片更换即时重合成。
        // 此前只在 onResume 检测——切页不触发 Activity 生命周期，导致设置页里
        // 切换淡化风格「无感」。300ms 防抖合并滑条连续写值。
        getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(backgroundPrefListener);

        setContentView(shell);

        // 主页控制器就绪 → 触发后台人机验证（替代原先的 FragmentResult + postDelayed 轮询）
        homeController.setHomeReadyCallback(this::tryTriggerCaptchaIfReady);

        // 处理后台人机验证通知跳转
        handleCaptchaIntent(getIntent());
    }

    /** 注册全部 ActivityResult 启动器并分发到对应控制器（控制器惰性创建视图，注册先于视图）。 */
    private void registerLaunchers() {
        // 抽卡链接页：VPN 抓包授权 + 电池优化豁免
        ActivityResultLauncher<Intent> linkVpnLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> linkController.onVpnPermissionResult(
                result.getResultCode() == RESULT_OK));
        ActivityResultLauncher<Intent> linkBatteryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> linkController.startCaptureAfterChecks());
        // 用户管理页：头像照片选择器
        ActivityResultLauncher<PickVisualMediaRequest> userAvatarPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            usersController::onAvatarPicked);
        // 设置页：背景图选择（Photo Picker / 老设备回退）+ UCrop 裁剪 + 电池优化豁免
        ActivityResultLauncher<PickVisualMediaRequest> settingsPickMedia = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            settingsController::onBackgroundImagePicked);
        ActivityResultLauncher<Intent> settingsSelectImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.onLegacyImagePicked(
                result.getResultCode() == RESULT_OK
                    && result.getData() != null ? result.getData().getData() : null));
        ActivityResultLauncher<Intent> settingsCropLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.onCropResult(result.getResultCode(), result.getData()));
        ActivityResultLauncher<Intent> settingsBatteryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> settingsController.updateBatteryOptValue());
        // 分发（控制器在视图创建前只持有引用，attach 后使用）
        linkController.setLaunchers(linkVpnLauncher, linkBatteryLauncher);
        usersController.setAvatarPicker(userAvatarPicker);
        settingsController.setLaunchers(settingsPickMedia, settingsSelectImageLauncher,
            settingsCropLauncher, settingsBatteryLauncher);
    }

    // ===== 页面控制器生命周期（对齐 FragmentStateAdapter maxLifecycle） =====

    /**
     * 页面选中回调（含外壳首次组合的初始页）：上一个选中页 hide、当前页 show。
     * 已暂停状态下不会收到页面回调（Pager 不可交互），故无需额外的暂停分支。
     */
    private void setShownPage(int index) {
        if (shownPage == index) return;
        int previous = shownPage;
        shownPage = index;
        if (previous >= 0) controllerAt(previous).hide();
        controllerAt(index).show();
    }

    private PageController controllerAt(int index) {
        switch (index) {
            case 1: return usersController;
            case 2: return linkController;
            case 3: return settingsController;
            case 0:
            default: return homeController;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        triggerCaptchaIfPending();
        refreshBackgroundIfPrefsChanged();
        // 与 Fragment 时代同口径：Activity 恢复时当前页控制器收到 show（回到前台刷新）
        if (shownPage >= 0) controllerAt(shownPage).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shownPage >= 0) controllerAt(shownPage).hide();
    }

    @Override
    protected void onDestroy() {
        getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(backgroundPrefListener);
        backgroundReloadHandler.removeCallbacksAndMessages(null);
        // 控制器资源回收（广播接收器/极验按钮等；Activity 销毁即全量释放）
        homeController.release();
        usersController.release();
        linkController.release();
        settingsController.release();
        super.onDestroy();
    }

    // ===== 后台人机验证触发链（原 ViewPager 时代逻辑平移） =====

    /** 待触发的后台人机验证标记（替代原先 postDelayed 轮询的状态机）。 */
    private boolean captchaPending = false;

    /**
     * 检查是否有待处理的后台人机验证，有则触发。
     * 主页控制器通过 {@link HomeFragment#setHomeReadyCallback} 通知就绪，
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
        shell.setCurrentPage(0, false);

        // 主页控制器可能尚未就绪（视图未创建）：直接尝试一次；未就绪则等就绪回调。
        tryTriggerCaptchaIfReady();
    }

    /**
     * 主页控制器已就绪（或刚就绪）时尝试触发后台人机验证。
     * 仅当 captchaPending 为真且能立即取到主页控制器实例时触发，避免轮询。
     */
    private void tryTriggerCaptchaIfReady() {
        if (!captchaPending) return;
        if (homeController.isReady()) {
            captchaPending = false;
            homeController.performBackgroundCaptchaVerification();
        }
    }

    // 解析来自后台人机验证通知的 Intent：置位 captchaPending 并切到首页等待触发
    private void handleCaptchaIntent(Intent intent) {
        Logger.debug("VenusCaptcha", "handleCaptchaIntent called, action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && Constants.ACTION_HANDLE_CAPTCHA.equals(intent.getAction())) {
            intent.setAction(null);
            new ConfigRepository(this).putBoolean(PREF_CAPTCHA_PENDING, true);
            shell.setCurrentPage(0, false);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleCaptchaIntent(intent);
        triggerCaptchaIfPending();
    }

    /**
     * 供控制器程序化跳转到指定页（无用户引导：账号行/运行签到跳用户管理页）。
     * 页面选中回调会联动控制器 show/hide，此处只需切 Pager。
     */
    public void navigateToPage(int position) {
        shell.setCurrentPage(position, true);
    }

    // ===== 背景预合成管线（原 ImageView 版逻辑平移到 Compose 背景） =====

    /**
     * 加载用户设定的背景图：后台解码 → 预合成（按淡化风格预设降饱和提亮/压暗，
     * 再按不透明度滑条与主题底色一次性掺合）→ 主线程发布给外壳背景层。
     * 关键：淡化只发生一次且全部烘进位图——先预处理再叠 alpha 等于双重淡化，
     * 画面发蒙且预设档差距被压缩。预合成后滑条直接控制图片权重，预设控制质感。
     * 权限不足或解码失败时回退纯色基底（底栏玻璃保留磨砂）并提示。
     * 同时在后台线程生成底栏毛玻璃用的模糊小样（连续降采样），主线程只做矩阵对齐。
     */
    private void setupBackground() {
        Uri backgroundImageUri = SettingsFragment.getBackgroundImageUri(this);
        float backgroundAlpha = SettingsFragment.getBackgroundAlpha(this);
        int backgroundPreset = SettingsFragment.getBackgroundPreset(this);
        appliedBackgroundPreset = backgroundPreset;
        appliedBackgroundAlpha = backgroundAlpha;

        // 无图：不发背景层——玻璃采样源子树自带主题底色基底（见 MainShellView.Content），
        // 底栏透镜继续磨砂折射滚到玻璃下的真实内容
        if (backgroundImageUri == null) {
            shell.publishBackground(null);
            return;
        }

        // 底色掺合色在主线程解析（后台线程访问主题属性不安全），只解析一次
        final int surfaceColor = com.google.android.material.color.MaterialColors
                .getColor(shell, com.google.android.material.R.attr.colorSurface);

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
            // 预合成在后台一次算好：纯位图运算，无版本兼容问题
            final Bitmap composited = source != null
                    ? createCompositedBitmap(source, backgroundPreset, backgroundAlpha, surfaceColor)
                    : null;
            final String errorMsg = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (errorMsg != null) {
                    shell.publishBackground(null);
                    show_error_dialog(MainActivity.this, errorMsg);
                    return;
                }
                // 发布背景位图：玻璃采样源子树的背景 Image 同帧入画（GraphicsLayer 录制）
                // 淡化已全部烘进位图：背景层恒为全不透明，绝不再叠加 alpha
                shell.publishBackground(composited);
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

    /**
     * 设置页改了淡化风格/不透明度后无需重启：二者现在都烘在位图里，
     * 任一变化都走完整重合成（后台线程，开销一次性，可接受）。
     * 首次添加背景图（此前为纯色基底）也在这里兜底触发。
     */
    private void refreshBackgroundIfPrefsChanged() {
        int preset = SettingsFragment.getBackgroundPreset(this);
        float alpha = SettingsFragment.getBackgroundAlpha(this);
        // 背景图的增删改本身走 background_prefs 监听（防抖触发），这里只兜底
        // 淡化风格/不透明度变化（onResume 对账，覆盖监听未覆盖的时序）
        if (preset != appliedBackgroundPreset || alpha != appliedBackgroundAlpha) {
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
     * 输出为全不透明位图（out = 预处理图 × w + 底色 × (1-w)），背景层直接铺满即可，
     * 不再依赖视图 alpha——那是此前「发蒙 + 预设无感」的根源。
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
     * 按系统版本从 Uri 解码 Bitmap（Android P+ 用 ImageDecoder，自动处理 EXIF 旋转；
     * 否则 BitmapFactory）。失败时抛出异常，由调用方（后台线程）统一收集并回主线程提示。
     */
    private Bitmap getBitmapFromUri(Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9.0及以上版本使用ImageDecoder。
            // 必须强制软件内存分配：ImageDecoder 默认可能返回 HARDWARE 位图，
            // 而后续预合成要在软件 Canvas 上做色彩滤镜重绘，会直接崩溃。
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
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
