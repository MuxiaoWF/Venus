package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_ALPHA;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_IMAGE_URI;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PRESET;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BBS_VERSION_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.CLOUD_GAME_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.CLOUD_GAME_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.CLOUD_GAME_ZZZ;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_DABIEYE;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HNA;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR2;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR3;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_SRG;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_WEIDING;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_XINGBUGUDI;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_ZZZ;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_HR2;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_HR3;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_SRG;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_WEIDING;
import static com.muxiao.Venus.common.Constants.Prefs.GAME_DAILY_ZZZ;
import static com.muxiao.Venus.common.Constants.Prefs.K2_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.LANGUAGE_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.LK2_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.NOTIFICATION;
import static com.muxiao.Venus.common.Constants.Prefs.SALT_4X_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.SALT_6X_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.SELECTED_LANGUAGE;
import static com.muxiao.Venus.common.Constants.Prefs.SELECTED_THEME;
import static com.muxiao.Venus.common.Constants.Prefs.SELECTED_THEME_VARIANT;
import static com.muxiao.Venus.common.Constants.Prefs.SERVER_TYPE;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.SCHEDULED_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SCHEDULED_TASK_TIME;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ARKNIGHTS_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_COOKIE;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_ENDFIELD_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.THEME_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.UPDATE_TIME_LOCAL_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.UPDATE_TIME_PREF;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.muxiao.Venus.BuildConfig;
import com.muxiao.Venus.Home.DailyTaskScheduler;
import com.muxiao.Venus.Home.CloudGameDaily;
import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.CloudGachaActivity;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.common.ui.compose.VenusDialogs;
import com.muxiao.Venus.common.ui.compose.AboutSettingPaneView;
import com.muxiao.Venus.common.ui.compose.BackgroundSettingPaneKt;
import com.muxiao.Venus.common.ui.compose.BackgroundSettingPaneView;
import com.muxiao.Venus.common.ui.compose.BbsUtilsSettingPaneView;
import com.muxiao.Venus.common.ui.compose.CacheSettingPaneView;
import com.muxiao.Venus.common.ui.compose.CloudGameSettingPaneKt;
import com.muxiao.Venus.common.ui.compose.CloudGameSettingPaneView;
import com.muxiao.Venus.common.ui.compose.DailySettingPaneView;
import com.muxiao.Venus.common.ui.compose.GameDailySettingPaneView;
import com.muxiao.Venus.common.ui.compose.GameDailySettingPaneKt;
import com.muxiao.Venus.common.ui.compose.LanguageSettingPaneView;
import com.muxiao.Venus.common.ui.compose.SklandSettingPaneKt;
import com.muxiao.Venus.common.ui.compose.SklandSettingPaneView;
import com.muxiao.Venus.common.ui.compose.NotificationSettingPaneView;
import com.muxiao.Venus.common.ui.compose.ServerSettingPaneView;
import com.muxiao.Venus.common.ui.compose.ThemeSettingPaneView;
import com.muxiao.Venus.common.ui.compose.UpdateSettingPaneView;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.data.ConfigRepository;
import com.muxiao.Venus.common.ui.compose.BottomBarOverlay;
import com.muxiao.Venus.common.ui.compose.SettingsCard;
import com.muxiao.Venus.common.ui.compose.SettingsScreenView;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.User.UserManager;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * 设置页控制器（原 Fragment 收敛为控制器）：签到任务开关、森空岛content配置、
 * 背景图片/主题/语言切换、服务器类型切换（国服/国际服）、缓存清理、版本更新检查。
 */
public class SettingsFragment implements PageController {

    private final MainActivity activity;

    private SharedPreferences sharedPreferences;
    // 收口：config_prefs 不再直接访问 SharedPreferences，统一走仓储（底层为 DataStore）。
    // 否则 MiHoYoBBSConstants 写入 DataStore 后，本页仍读旧 xml，会显示过期配置值。
    private ConfigRepository configRepository;
    private static final int THEME_DEFAULT = 0;

    // 主题深浅色变体常量（0=跟随系统 1=浅色 2=深色）
    private static final int THEME_VARIANT_DEFAULT = 0;
    private static final int THEME_VARIANT_DARK = 2;

    // 折叠行右侧 mono 值摘要（命令台设计稿行值）所需的卡片引用
    private SettingsCard dailyCard;
    private SettingsCard gameDailyCard;
    private SettingsCard serverCard;
    private SettingsCard updateCard;
    private SettingsCard cacheCard;
    private SettingsCard languageCard;
    private SettingsCard themeCard;
    private SettingsCard notificationCard;
    private SettingsCard sklandCard;
    private SettingsCard cloudGameCard;
    /** Compose 宿主（本页唯一 UI 入口）。 */
    private SettingsScreenView screenView;
    /** 底栏覆盖高度订阅（弱引用持有，必须由本类强持有并在 release 解除）。 */
    private final Runnable bottomBarListener = () -> {
        if (screenView != null) screenView.setBottomClearance(BottomBarOverlay.get());
    };

    // 「缓存管理」抽屉内容为 Compose，由 CacheSettingPaneView 承载并在 Java 侧回填缓存占用文案。
    private CacheSettingPaneView cachePane;
    // 「配置信息」抽屉内容为 Compose。
    private BbsUtilsSettingPaneView utilsPane;
    private SklandSettingPaneView sklandPane;
    private CloudGameSettingPaneView cloudGamePane;
    // 「通知 / 后台运行」抽屉内容为 Compose。
    private NotificationSettingPaneView notificationPane;
    // 「米游币签到」「游戏签到」抽屉内容为 Compose。
    private DailySettingPaneView dailyPane;
    private GameDailySettingPaneView gameDailyPane;

    // 背景设置相关
    private SharedPreferences backgroundPreferences;

    // ActivityResult 启动器（MainActivity 注册后注入）
    private ActivityResultLauncher<Intent> selectImageLauncher; // ACTION_OPEN_DOCUMENT (老设备)
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia; // Photo Picker (API33+)
    private ActivityResultLauncher<Intent> cropImageLauncher; // UCrop 结果
    private ActivityResultLauncher<Intent> batteryOptLauncher; // 电池优化豁免授权
    private ActivityResultLauncher<Intent> cloudTokenLauncher; // 云游戏 Token 抓取页

    public SettingsFragment(MainActivity activity) {
        this.activity = activity;
    }

    /** 注入 MainActivity 注册的启动器（图片选择/裁剪/电池优化豁免/云游戏 Token 抓取）。 */
    public void setLaunchers(ActivityResultLauncher<PickVisualMediaRequest> pickMedia,
                             ActivityResultLauncher<Intent> selectImage,
                             ActivityResultLauncher<Intent> cropImage,
                             ActivityResultLauncher<Intent> batteryOpt,
                             ActivityResultLauncher<Intent> cloudToken) {
        this.pickMedia = pickMedia;
        this.selectImageLauncher = selectImage;
        this.cropImageLauncher = cropImage;
        this.batteryOptLauncher = batteryOpt;
        this.cloudTokenLauncher = cloudToken;
    }

    /**
     * 云游戏 Token 抓取页返回：无论抓到的是不是目标游戏都刷新角标
     * （抓取页会把顺手抓到的另一款也落库）。
     */
    public void onCloudTokenFetched() {
        if (cloudGamePane != null) refreshCloudGameTokens();
    }

    /** Photo Picker 选择结果（MainActivity 的 launcher 转发）：进入裁剪。 */
    public void onBackgroundImagePicked(Uri uri) {
        // 用户在系统照片选择器中取消/未选择时返回 null：静默忽略，
        // 不能把 null 传给裁剪页，否则 UCrop 会以「图片裁剪失败: null」报错收场
        if (uri != null) startCropActivity(uri);
    }

    /** 老设备（<API33）ACTION_OPEN_DOCUMENT 选择结果（MainActivity 的 launcher 转发）。 */
    public void onLegacyImagePicked(Uri uri) {
        if (uri != null) startCropActivity(uri);
    }

    /** UCrop 裁剪结果（MainActivity 的 launcher 转发）。 */
    public void onCropResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            // 获取裁剪后的图片URI
            Uri croppedImageUri = UCrop.getOutput(data);
            if (croppedImageUri != null)
                // 将裁剪后的图片复制到应用私有目录并保存
                saveBackgroundImage(croppedImageUri);
        } else if (resultCode == UCrop.RESULT_ERROR) {
            // 处理裁剪错误
            if (data != null) {
                final Throwable cropError = UCrop.getError(data);
                show_error_dialog(activity, activity.getString(R.string.err_crop_failed,
                        Objects.requireNonNull(cropError).getMessage()));
            }
        }
    }

    /**
     * 构建页面 UI（外壳首次组合时调用一次）：12 个抽屉由宿主持有，
     * 本方法负责「生成窗格内容 → 塞进抽屉 → 维护折叠态摘要」。
     */
    public View createView(Context context) {
        if (screenView != null) return screenView; // 幂等：外壳重组不会重复装配
        SettingsScreenView root = new SettingsScreenView(context);
        screenView = root;

        // 页眉版本 chip（设计稿：v2.4.0 形式）
        root.setVersionChip(activity.getString(R.string.app_version_fmt, BuildConfig.VERSION_NAME));

        // 内容不再容器级避让：正文底部留出底栏覆盖高度，末项可完整滚出玻璃区
        BottomBarOverlay.addListener(bottomBarListener);
        root.setBottomClearance(BottomBarOverlay.get());

        SettingsCard bbsCard = root.getDailyCard();
        SettingsCard bbsGameCard = root.getGameDailyCard();
        SettingsCard serverCardView = root.getServerCard();
        SettingsCard bbsUtilsCard = root.getConfigCard();
        SettingsCard updateCardView = root.getUpdateCard();
        SettingsCard cacheCardView = root.getCacheCard();
        SettingsCard languageCardView = root.getLanguageCard();
        SettingsCard themeCardView = root.getThemeCard();
        SettingsCard backgroundCardView = root.getBackgroundCard();
        SettingsCard notificationCardView = root.getNotificationCard();
        SettingsCard aboutCard = root.getAboutCard();
        SettingsCard sklandCardView = root.getSklandCard();
        SettingsCard cloudGameCardView = root.getCloudGameCard();
        dailyCard = bbsCard;
        gameDailyCard = bbsGameCard;
        serverCard = serverCardView;
        updateCard = updateCardView;
        cacheCard = cacheCardView;
        languageCard = languageCardView;
        themeCard = themeCardView;
        notificationCard = notificationCardView;
        sklandCard = sklandCardView;
        cloudGameCard = cloudGameCardView;

        dailyPane = new DailySettingPaneView(context);
        bbsCard.setContent(dailyPane);
        gameDailyPane = new GameDailySettingPaneView(context);
        bbsGameCard.setContent(gameDailyPane);
        ServerSettingPaneView serverPane = new ServerSettingPaneView(context);
        serverCardView.setContent(serverPane);
        utilsPane = new BbsUtilsSettingPaneView(context);
        bbsUtilsCard.setContent(utilsPane);
        UpdateSettingPaneView updatePane = new UpdateSettingPaneView(context);
        updateCardView.setContent(updatePane);
        cachePane = new CacheSettingPaneView(context);
        cacheCardView.setContent(cachePane);
        LanguageSettingPaneView languagePane = new LanguageSettingPaneView(context);
        languageCardView.setContent(languagePane);
        ThemeSettingPaneView themePane = new ThemeSettingPaneView(context);
        themeCardView.setContent(themePane);
        BackgroundSettingPaneView backgroundPane = new BackgroundSettingPaneView(context);
        backgroundCardView.setContent(backgroundPane);
        notificationPane = new NotificationSettingPaneView(context);
        notificationCardView.setContent(notificationPane);
        AboutSettingPaneView aboutPane = new AboutSettingPaneView(context);
        aboutCard.setContent(aboutPane);
        sklandPane = new SklandSettingPaneView(context);
        sklandCardView.setContent(sklandPane);
        cloudGamePane = new CloudGameSettingPaneView(context);
        cloudGameCardView.setContent(cloudGamePane);


        // 静态行值摘要（设计稿：配置信息=已配置、关于=开源许可 · 隐私政策、背景按设置）
        bbsUtilsCard.setValue(activity.getString(R.string.config_configured));
        bbsUtilsCard.setValueColor(ContextCompat.getColor(context, com.muxiao.Venus.R.color.status_success));
        aboutCard.setValue(activity.getString(R.string.about_value));
        backgroundCardView.setValue(activity.getString(SettingsFragment.getBackgroundImageUri(context) != null
                ? R.string.background_value_custom : R.string.background_value_default));

        // 初始化SharedPreferences
        sharedPreferences = activity.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        configRepository = new ConfigRepository(context);
        backgroundPreferences = activity.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);

        // 恢复保存的状态
        dailyPane.setSwitchChecked(sharedPreferences.getBoolean(DAILY, true));
        gameDailyPane.setSwitchChecked(sharedPreferences.getBoolean(GAME_DAILY, true));
        notificationPane.setBackgroundChecked(sharedPreferences.getBoolean(BACKGROUND_TASK_ENABLED, false));
        // 折叠行值摘要初始态（设计稿：行右侧 mono 摘要）
        updateDailyCardValue(dailyPane.getSwitchChecked());
        updateGameCardValue();
        updateNotificationCardValue(notificationPane.getBackgroundChecked());

        // chip-pref 映射：下标顺序与 Compose 侧 DAILY_CHIP_LABEL_RES / GAME_CHIP_LABEL_RES 一致
        final String[] dailyKeys = {
                DAILY_GENSHIN, DAILY_ZZZ, DAILY_SRG, DAILY_HR3,
                DAILY_HR2, DAILY_WEIDING, DAILY_DABIEYE, DAILY_HNA,
                DAILY_XINGBUGUDI,
        };
        final boolean[] dailyDefaults = {false, false, false, false, false, false, true, false, false};
        for (int i = 0; i < dailyKeys.length; i++)
            dailyPane.setChipChecked(i, sharedPreferences.getBoolean(dailyKeys[i], dailyDefaults[i]));
        dailyPane.setOnChipChangeListener((index, checked) -> {
            // 任务运行中弹窗，dismiss 时回滚勾选（与原 CompoundButton 版语义一致）
            if (blockIfTaskRunning(() -> dailyPane.setChipChecked(index, !checked))) return;
            sharedPreferences.edit().putBoolean(dailyKeys[index], checked).apply();
        });

        final String[] gameKeys = {
                GAME_DAILY_GENSHIN, GAME_DAILY_ZZZ, GAME_DAILY_SRG,
                GAME_DAILY_HR3, GAME_DAILY_HR2, GAME_DAILY_WEIDING,
        };
        for (int i = 0; i < gameKeys.length; i++)
            gameDailyPane.setChipChecked(i, sharedPreferences.getBoolean(gameKeys[i], false));
        gameDailyPane.setOnChipChangeListener((index, checked) -> {
            if (blockIfTaskRunning(() -> gameDailyPane.setChipChecked(index, !checked))) return;
            sharedPreferences.edit().putBoolean(gameKeys[index], checked).apply();
            updateGameCardValue();
        });

        // 所有控件设置监听器以保存状态
        dailyPane.setOnSwitchChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> dailyPane.setSwitchChecked(!isChecked))) return;
            sharedPreferences.edit().putBoolean(DAILY, isChecked).apply();
            updateDailyCardValue(isChecked);
        });

        gameDailyPane.setOnSwitchChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> gameDailyPane.setSwitchChecked(!isChecked))) return;
            sharedPreferences.edit().putBoolean(GAME_DAILY, isChecked).apply();
        });

        notificationPane.setOnBackgroundChangeListener(isChecked -> {
            if (isChecked) {
                // 检查系统通知权限
                Notification notificationUtil = new Notification(context);
                if (notificationUtil.areNotificationsDisabled()) {
                    VenusDialogs.confirm(
                            activity,
                            activity.getString(R.string.dialog_need_notification_permission),
                            activity.getString(R.string.msg_need_notification_for_background),
                            activity.getString(R.string.btn_go_to_settings),
                            () -> activity.startActivity(notificationUtil.getNotificationSettingsIntent()),
                            activity.getString(R.string.btn_later),
                            null,
                            () -> notificationPane.setBackgroundChecked(false));
                    return;
                }
            }
            sharedPreferences.edit().putBoolean(BACKGROUND_TASK_ENABLED, isChecked).apply();
            // 后台运行开启时同步开启通知，关闭时同步关闭
            sharedPreferences.edit().putBoolean(NOTIFICATION, isChecked).apply();
            updateNotificationCardValue(isChecked);
        });

        // 定时签到：开关排程/取消 + 时间行弹出选择器（WorkManager 自链式每日调度）
        boolean scheduledEnabled = sharedPreferences.getBoolean(SCHEDULED_TASK_ENABLED, false);
        notificationPane.setScheduledChecked(scheduledEnabled);
        notificationPane.setScheduledTimeVisible(scheduledEnabled);
        updateScheduledTimeLabel();
        notificationPane.setOnScheduledChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> notificationPane.setScheduledChecked(!isChecked))) return;
            sharedPreferences.edit().putBoolean(SCHEDULED_TASK_ENABLED, isChecked).apply();
            // 与后台运行开关同口径：验证码提示通知受通知开关控制，开启定时必须连带开启通知，
            // 否则定时运行遇到人机验证时用户将收不到任何可点击的入口
            sharedPreferences.edit().putBoolean(NOTIFICATION, isChecked).apply();
            notificationPane.setScheduledTimeVisible(isChecked);
            if (isChecked) {
                DailyTaskScheduler.applySchedule(context);
                // 厂商省电是定时任务失准的头号原因：未豁免电池优化时引导一次（不阻断开启）
                if (!BatteryHelper.isIgnoringBatteryOptimizations(context)) {
                    VenusDialogs.confirm(
                            activity,
                            activity.getString(R.string.dialog_need_battery_optimization),
                            activity.getString(R.string.msg_scheduled_battery_hint),
                            activity.getString(R.string.btn_go_to_settings),
                            () -> BatteryHelper.openVendorBatterySettings(context),
                            activity.getString(R.string.btn_ignore));
                }
            } else {
                DailyTaskScheduler.cancel(context);
            }
        });
        notificationPane.setOnScheduledTimeClickListener(() -> {
            int[] time = DailyTaskScheduler.getConfiguredTime(context);
            VenusDialogs.timePicker(
                    activity,
                    android.text.format.DateFormat.is24HourFormat(context),
                    time[0], time[1],
                    activity.getString(R.string.scheduled_time_picker_title),
                    (hour, minute) -> {
                        String timeStr = String.format(Locale.US, "%02d:%02d", hour, minute);
                        sharedPreferences.edit().putString(SCHEDULED_TASK_TIME, timeStr).apply();
                        updateScheduledTimeLabel();
                        // 已开启状态下改时间：立即重排到新时刻
                        if (sharedPreferences.getBoolean(SCHEDULED_TASK_ENABLED, false))
                            DailyTaskScheduler.applySchedule(context);
                    });
        });

        // 电池优化豁免行：厂商省电策略是定时任务失准与 VPN 抓包挂机断连（游戏闪退）的根源。
        // 未豁免时点击打开系统省电白名单列表页（用户手动勾选，无需权限），返回后刷新行值；
        // 机型不支持该设置页时退回应用详情页
        updateBatteryOptValue();
        notificationPane.setOnBatteryRowClickListener(() -> {
            if (BatteryHelper.isIgnoringBatteryOptimizations(context)) {
                tools.showCustomSnackbar(notificationPane, context.getApplicationContext(),
                        activity.getString(R.string.battery_already_exempted));
                return;
            }
            try {
                batteryOptLauncher.launch(
                        BatteryHelper.buildBatteryOptimizationSettingsIntent(context));
            } catch (Exception e) {
                BatteryHelper.openVendorBatterySettings(context);
            }
        });

        // 关于（Compose 版内容）
        aboutPane.setOnGithubClickListener(() -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_GITHUB_URL));
            activity.startActivity(intent);
        });
        aboutPane.setOnBlogClickListener(() -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_BLOG_URL));
            activity.startActivity(intent);
        });
        aboutPane.setAppVersion(activity.getString(R.string.about_version, BuildConfig.VERSION_NAME));

        // 配置更新
        utilsPane.setOnUpdateClickListener(() -> {
            if (HomeFragment.isTaskRunning() || ForegroundTaskService.isRunning()) {
                VenusDialogs.info(
                        activity,
                        activity.getString(R.string.dialog_task_running),
                        activity.getString(R.string.msg_wait_task_finish_update),
                        activity.getString(R.string.btn_ok));
                return;
            }
            updateConfig(utilsPane);
        });
        // 显示当前配置值
        displayCurrentConfigValues();

        // 服务器选择
        setupServerSelection(serverPane, bbsCard, gameDailyPane);
        // 语言选择
        setupLanguageSelection(languagePane);
        // 主题选择
        setupThemeSelection(themePane);
        // 背景选择
        setupBackgroundSelection(backgroundPane);
        // 自动更新设置（Compose 版内容）
        boolean autoUpdateEnabled = sharedPreferences.getBoolean(AUTO_UPDATE_ENABLED, true);
        updatePane.setAutoUpdateChecked(autoUpdateEnabled);
        updateUpdateCardValue(autoUpdateEnabled);
        updatePane.setOnAutoUpdateChangeListener(isChecked -> {
            sharedPreferences.edit().putBoolean(AUTO_UPDATE_ENABLED, isChecked).apply();
            updateUpdateCardValue(isChecked);
        });
        // 手动检查更新按钮
        updatePane.setOnCheckGithubClickListener(() -> {
            UpdateChecker updateChecker = new UpdateChecker(context);
            updateChecker.checkForUpdatesImmediately();
        });
        updatePane.setOnCheckPanClickListener(() -> VenusDialogs.confirm(
                activity,
                activity.getString(R.string.dialog_pan_download),
                activity.getString(R.string.msg_pan_download_hint),
                activity.getString(R.string.btn_go_to_download),
                () -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_UPDATE_LANZOU_URL));
                    activity.startActivity(intent);
                },
                activity.getString(R.string.btn_cancel)));

        // 看图按钮（设置行形式，点击跳转）
        root.setOnImageClickListener(() -> {
            Intent intent = new Intent(activity, ImageActivity.class);
            activity.startActivity(intent);
        });

        // 缓存管理（Compose 版内容：计算并显示当前缓存大小）
        calculateCacheSize(cachePane);
        // 设置清理缓存按钮
        cachePane.setOnClearClickListener(() -> clearCache(cachePane));

        //森空岛设置（Compose 版内容）
        boolean sklandEnabled = sharedPreferences.getBoolean(SKLAND_ENABLED, false);
        sklandPane.setEnabledChecked(sklandEnabled);
        sklandPane.setArknightsChecked(sharedPreferences.getBoolean(SKLAND_ARKNIGHTS_ENABLED, false));
        sklandPane.setEndfieldChecked(sharedPreferences.getBoolean(SKLAND_ENDFIELD_ENABLED, false));
        updateSklandCardValue(sklandEnabled);
        sklandPane.setOnEnabledChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> sklandPane.setEnabledChecked(!isChecked))) return;
            sharedPreferences.edit().putBoolean(SKLAND_ENABLED, isChecked).apply();
            // 两个子项的可点态由 pane 依 enabledChecked 自行派生，无需再 setEnabled
            updateSklandCardValue(isChecked);
        });
        sklandPane.setOnSubItemChangeListener((index, isChecked) -> {
            if (blockIfTaskRunning(() -> setSklandSubItemChecked(index, !isChecked))) return;
            sharedPreferences.edit().putBoolean(
                    index == SklandSettingPaneKt.SKLAND_INDEX_ARKNIGHTS ? SKLAND_ARKNIGHTS_ENABLED : SKLAND_ENDFIELD_ENABLED,
                    isChecked).apply();
        });
        sklandPane.setOnLoginLinkClickListener(() -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.SKLAND_LOGIN_URL))));
        sklandPane.setOnCookieLinkClickListener(() -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.SKLAND_COOKIE_URL))));
        sklandPane.setOnSaveClickListener(() -> {
            // 获取当前保存的森空岛token值
            String currentToken = sharedPreferences.getString(SKLAND_COOKIE, "");
            // Token 输入对话框（Compose 版输入框）
            VenusDialogs.input(
                    activity,
                    activity.getString(R.string.dialog_skland_content_settings),
                    activity.getString(R.string.msg_skland_content_hint),
                    currentToken,
                    activity.getString(R.string.skland_content_tip),
                    activity.getString(R.string.btn_save),
                    activity.getString(R.string.btn_cancel),
                    true,
                    null,
                    newToken -> {
                        // 保存到SharedPreferences
                        sharedPreferences.edit().putString(SKLAND_COOKIE, newToken).apply();
                        showCustomSnackbar(screenView, context, activity.getString(R.string.snack_skland_content_saved));
                    });
        });
        // 云游戏签到（Compose 版内容）：token 按当前用户存储，与米游社登录态无关
        setupCloudGameSettings(context);
        return root;
    }

    /** 页面成为当前选中页 / Activity 恢复：同步后台运行开关状态。 */
    @Override
    public void show() {
        // 更新后台运行开关状态
        if (sharedPreferences != null && screenView != null) {
            notificationPane.setBackgroundChecked(sharedPreferences.getBoolean(BACKGROUND_TASK_ENABLED, false));
            updateNotificationCardValue(notificationPane.getBackgroundChecked());
        }
        // 用户可能在「用户管理」页切换过账号：云游戏 token 按用户存放，需重新判定已配置状态
        if (cloudGamePane != null) refreshCloudGameTokens();
    }

    /** 页面失去选中 / Activity 暂停（无逐帧资源，无操作）。 */
    @Override
    public void hide() {
    }

    /** 销毁：解除底栏订阅，清空宿主引用（Activity 销毁时由 MainActivity 调用）。 */
    public void release() {
        BottomBarOverlay.removeListener(bottomBarListener);
        screenView = null;
    }

    /**
     * 启动裁剪Activity
     */
    private void startCropActivity(@NonNull Uri uri) {
        // 获取屏幕尺寸作为裁剪比例
        int screenWidth, screenHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = activity.getSystemService(android.view.WindowManager.class).getCurrentWindowMetrics().getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            // 使用Resources获取屏幕尺寸，避免使用过时的Display方法
            DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
            screenWidth = displayMetrics.widthPixels;
            screenHeight = displayMetrics.heightPixels;
        }
        // 创建裁剪输出URI到临时文件
        Uri destinationUri = Uri.fromFile(new File(activity.getCacheDir(), "temp_cropped_background.jpg"));
        // 配置uCrop
        UCrop.Options options = new UCrop.Options();
        // 修改状态栏
        options.setStatusBarLight(true);
        options.setToolbarTitle(activity.getString(R.string.toolbar_crop_image));
        // 隐藏底部工具
        options.setHideBottomControls(true);
        // 图片格式
        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
        // 设置图片压缩质量
        options.setCompressionQuality(100);
        // 不显示网格线
        options.setShowCropGrid(true);
        UCrop uCrop = UCrop.of(uri, destinationUri)
                .withAspectRatio(screenWidth, screenHeight) // 固定屏幕比例
                .withMaxResultSize(screenWidth, screenHeight) // 设置最大尺寸为屏幕尺寸
                .withOptions(options);
        // 启动裁剪Activity，使用cropImageLauncher处理结果
        Intent uCropIntent = uCrop.getIntent(activity);
        // 授予裁剪 Activity 临时读写权限，确保能读取 source Uri 并写入 destination Uri
        uCropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        cropImageLauncher.launch(uCropIntent);
    }

    /**
     * 保存背景图片到应用私有目录
     *
     * @param croppedImageUri 裁剪后的图片URI
     */
    private void saveBackgroundImage(Uri croppedImageUri) {
        try {
            // 创建背景图片文件
            File backgroundFile = new File(activity.getFilesDir(), "background.jpg");
            if (backgroundFile.exists() && !backgroundFile.delete()) {
                Logger.w("删除旧背景文件失败：" + backgroundFile.getAbsolutePath());
            }

            // 使用 tools.copyFile（确保实现基于 ContentResolver.openInputStream(...)）
            tools.copyFile(activity, croppedImageUri, backgroundFile);

            // 保存背景图片文件Uri到SharedPreferences
            Uri backgroundUri = Uri.fromFile(backgroundFile);
            backgroundPreferences.edit().putString(BACKGROUND_IMAGE_URI, backgroundUri.toString()).apply();

            // 删除临时裁剪文件
            File temp = new File(activity.getCacheDir(), "temp_cropped_background.jpg");
            if (temp.exists() && !temp.delete()) {
                Logger.w("删除临时裁剪文件失败：" + temp.getAbsolutePath());
            }

            // 显示提示信息
            if (screenView != null)
                showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_background_set));
        } catch (Exception e) {
            show_error_dialog(activity, activity.getString(R.string.err_save_background_failed, tools.describeError(e)));
        }
    }

    /**
     * 设置背景选择功能（Compose 版内容）。
     * <p>
     * 淡化风格与不透明度只写 backgroundPreferences，MainActivity 侧监听（300ms 防抖）
     * 即时重新预合成，无需重启；两枚按钮的动作由 Java 侧注入（涉及 ActivityResult launcher）。
     */
    private void setupBackgroundSelection(BackgroundSettingPaneView view) {
        // 选择背景
        view.setOnSelectClickListener(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pickMedia.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            } else {
                // 回退到 ACTION_OPEN_DOCUMENT
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                String[] mimetypes = {"image/jpeg", "image/png", "image/jpg", "image/gif"};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                // 仅请求临时读取权限（立即复制文件）
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                selectImageLauncher.launch(intent);
            }
        });

        // 清除背景
        view.setOnClearClickListener(() -> {
            // 清除之前先释放权限
            String uriString = backgroundPreferences.getString(BACKGROUND_IMAGE_URI, null);
            if (uriString == null) {
                // 从未设置过背景时点「清除」：如实提示，不再谎报「已清除」
                showCustomSnackbar(view, activity, activity.getString(R.string.snack_no_background_to_clear));
                return;
            }
            try {
                Uri backgroundUri = Uri.parse(uriString);
                if ("file".equals(backgroundUri.getScheme())) {
                    File backgroundFile = new File(Objects.requireNonNull(backgroundUri.getPath()));
                    if (backgroundFile.exists() && !backgroundFile.delete()) {
                        Logger.w("删除背景文件失败：" + backgroundFile.getAbsolutePath());
                    }
                } else {
                    activity.getContentResolver().delete(backgroundUri, null, null);
                }
            } catch (Exception e) {
                show_error_dialog(activity, activity.getString(R.string.err_delete_background_error, e.toString()));
            }
            backgroundPreferences.edit().remove(BACKGROUND_IMAGE_URI).apply();
            showCustomSnackbar(view, activity, activity.getString(R.string.snack_background_cleared));
        });

        // 淡化风格预设档（0=轻盈 1=标准 2=浓郁）：控制解码时的图像预处理强度，
        // 与不透明度滑条正交——风格管质感，滑条管浓度。
        view.setPresetIndex(backgroundPreferences.getInt(BACKGROUND_PRESET, BackgroundSettingPaneKt.BG_PRESET_INDEX_STANDARD));
        view.setOnPresetSelectListener(preset ->
                backgroundPreferences.edit().putInt(BACKGROUND_PRESET, preset).apply());

        // 不透明度滑块（0-90，步长 5）
        float currentAlpha = backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.45f);
        view.setAlphaPercent(currentAlpha * 100);
        view.setOnAlphaChangeListener(percent ->
                backgroundPreferences.edit().putFloat(BACKGROUND_ALPHA, percent / 100.0f).apply());
        view.setOnAlphaChangeFinishedListener(() -> showCustomSnackbar(view, activity,
                activity.getString(R.string.snack_opacity_set, (int) view.getAlphaPercent())));
    }

    /**
     * 设置服务器选择功能：国服/国际服即时切换，无需展开卡片。
     */
    private void setupServerSelection(ServerSettingPaneView view, SettingsCard bbsCard, GameDailySettingPaneView gamePane) {
        SharedPreferences prefs = activity.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        int serverType = prefs.getInt(SERVER_TYPE, 0);

        // 国际服隐藏米游币签到（需要stoken，Cookie登录不提供）
        bbsCard.setVisibility(serverType == 0 ? View.VISIBLE : View.GONE);
        // 国际服隐藏不支持的游戏签到（崩坏2 / 未定事件簿）
        setOverseaChipsVisible(gamePane, serverType == 0);
        // 国际服无云绝区零钱包接口；且两服的云原神 token 互不通用，需按服刷新已配置状态
        cloudGamePane.setChipVisible(CloudGameSettingPaneKt.CLOUD_GAME_INDEX_ZZZ, serverType == 0);
        refreshCloudGameTokens();
        updateCloudGameCardValue();

        // 先设初始选中（此时监听器尚未注册，不会触发回调/弹 Snackbar），
        // 再由下面的监听器接管后续交互。
        view.setSelectedIndex(serverType == 0 ? 0 : 1);
        updateServerCardValue(serverType == 0);

        view.setOnSelectListener(newType -> {
            // 重复点选中态不再触发一次切换
            if (newType == prefs.getInt(SERVER_TYPE, 0)) return;
            prefs.edit().putInt(SERVER_TYPE, newType).apply();
            MiHoYoBBSConstants.clearOverseaCache();
            updateServerCardValue(newType == 0);
            bbsCard.setVisibility(newType == 0 ? View.VISIBLE : View.GONE);
            setOverseaChipsVisible(gamePane, newType == 0);
            cloudGamePane.setChipVisible(CloudGameSettingPaneKt.CLOUD_GAME_INDEX_ZZZ, newType == 0);
            refreshCloudGameTokens();
            updateCloudGameCardValue();
            showCustomSnackbar(view, activity,
                    activity.getString(newType == 0 ? R.string.snack_switched_to_cn : R.string.snack_switched_to_os));
        });
    }

    /**
     * 设置语言选择功能
     */
    private void setupLanguageSelection(LanguageSettingPaneView view) {
        SharedPreferences languagePreferences = activity.getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedLanguage = languagePreferences.getInt(SELECTED_LANGUAGE, 0);

        String[] languages = {
                activity.getString(R.string.language_system),
                activity.getString(R.string.language_zh),
                activity.getString(R.string.language_zh_tw),
                activity.getString(R.string.language_en)
        };
        view.setOptions(Arrays.asList(languages));

        if (selectedLanguage >= 0 && selectedLanguage < languages.length) {
            view.setSelectedIndex(selectedLanguage);
            languageCard.setValue(languages[selectedLanguage]);
        }

        view.setOnSelectListener(this::saveAndApplyLanguage);
    }

    /**
     * 保存并应用语言
     *
     * @param languageId 语言ID
     */
    private void saveAndApplyLanguage(int languageId) {
        SharedPreferences languagePreferences = activity.getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
        languagePreferences.edit().putInt(SELECTED_LANGUAGE, languageId).apply();
        // 重建 Activity 以应用语言：BaseActivity.attachBaseContext 已通过 LocaleHelper 读取最新语言，
        // recreate() 会重新走 attachBaseContext，等价于原 finish()+startActivity，但无需销毁整个任务栈。
        activity.recreate();
    }

    /**
     * 设置主题选择功能（Compose 版内容）。
     * <p>
     * 七枚圆点的下标即 `saveAndApplyTheme` 的主题序号（0=系统动态取色，1-6=蓝绿红黄青紫）；
     * 选中态由 pane 内部维护，Java 只负责落盘与刷新折叠行摘要。
     */
    private void setupThemeSelection(ThemeSettingPaneView view) {
        SharedPreferences themePreferences = activity.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedTheme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        int selectedThemeVariant = themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);

        view.setSelectedTheme(selectedTheme);
        updateThemeCardValue();
        view.setOnThemeSelectListener(themeIndex -> {
            saveAndApplyTheme(themeIndex);
            updateThemeCardValue();
        });

        // 深浅模式分段（沿用原单选语义：default/light/dark，序号与 THEME_VARIANT_* 一一对应）
        if (selectedThemeVariant >= THEME_VARIANT_DEFAULT && selectedThemeVariant <= THEME_VARIANT_DARK)
            view.setSelectedVariant(selectedThemeVariant);
        view.setOnVariantSelectListener(variantIndex -> {
            saveAndApplyThemeVariant(variantIndex);
            updateThemeCardValue();
        });
    }

    /**
     * 保存并应用主题
     *
     * @param themeId 主题ID
     */
    private void saveAndApplyTheme(int themeId) {
        // 保存选择的主题
        SharedPreferences themePreferences = activity.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        themePreferences.edit().putInt(SELECTED_THEME, themeId).apply();
        // 显示提示信息，告知用户需要重启应用以应用主题
        if (screenView != null)
            showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_theme_saved));
    }

    /**
     * 保存并应用主题深浅色变体
     *
     * @param themeVariantId 主题深浅色变体ID
     */
    private void saveAndApplyThemeVariant(int themeVariantId) {
        // 保存选择的主题深浅色变体
        SharedPreferences themePreferences = activity.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        themePreferences.edit().putInt(SELECTED_THEME_VARIANT, themeVariantId).apply();

        // 立即应用深浅色模式
        int[] nightModes = {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES
        };
        String[] variantMessages = {
                activity.getString(R.string.snack_set_follow_system),
                activity.getString(R.string.snack_set_light_mode),
                activity.getString(R.string.snack_set_dark_mode)
        };
        if (themeVariantId >= 0 && themeVariantId < nightModes.length) {
            AppCompatDelegate.setDefaultNightMode(nightModes[themeVariantId]);
            if (screenView != null)
                showCustomSnackbar(screenView, activity, variantMessages[themeVariantId]);
        }
    }

    /**
     * 应用主题：基础主题 + 运行时节色叠加（ThemeOverlay.applyStyle）。
     * 必须在 setContentView 之前调用。
     */
    public static void applyAppTheme(Activity activity) {
        activity.setTheme(R.style.Theme_Venus);
        int index = getSelectedThemeIndex(activity);
        if (index >= 0 && index < COLOR_OVERLAYS.length) {
            int overlay = COLOR_OVERLAYS[index];
            if (overlay != 0) activity.getTheme().applyStyle(overlay, true);
        }
    }

    private static final int[] COLOR_OVERLAYS = {
            0,                                  // DEFAULT（动态取色）
            R.style.ThemeOverlay_Venus_Blue,   // BLUE
            R.style.ThemeOverlay_Venus_Green,  // GREEN
            R.style.ThemeOverlay_Venus_Red,    // RED
            R.style.ThemeOverlay_Venus_Yellow, // YELLOW
            R.style.ThemeOverlay_Venus_Cyan,   // CYAN
            R.style.ThemeOverlay_Venus_Purple, // PURPLE
            0,                                  // LIGHT（浅/深由 AppCompatDelegate 变体控制）
            0,                                  // DARK
    };

    /** 从主题偏好读取当前选中的主题资源下标（默认 THEME_DEFAULT）。 */
    private static int getSelectedThemeIndex(Context context) {
        SharedPreferences themePreferences = context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        return themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
    }

    /**
     * 获取应该应用的主题深浅色变体
     *
     * @param context 上下文
     * @return 主题深浅色变体
     */
    public static int getSelectedThemeVariant(Context context) {
        SharedPreferences themePreferences = context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        return themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);
    }

    /**
     * 应用主题深浅色变体
     *
     * @param context 上下文
     */
    public static void applyThemeVariant(Context context) {
        int themeVariant = getSelectedThemeVariant(context);
        int[] nightModes = {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES
        };
        if (themeVariant >= 0 && themeVariant < nightModes.length)
            AppCompatDelegate.setDefaultNightMode(nightModes[themeVariant]);
    }

    /**
     * 获取背景图片URI
     *
     * @param context 上下文
     * @return 背景图片URI，如果没有设置则返回null
     */
    public static Uri getBackgroundImageUri(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = backgroundPreferences.getString(BACKGROUND_IMAGE_URI, null);
        if (uriString != null) {
            try {
                Uri backgroundUri = Uri.parse(uriString);
                if ("file".equals(backgroundUri.getScheme())) {
                    File backgroundFile = new File(Objects.requireNonNull(backgroundUri.getPath()));
                    if (backgroundFile.exists()) return backgroundUri;
                }
            } catch (Exception e) {
                // 如果解析Uri失败，直接使用文件路径
                File backgroundFile = new File(uriString);
                if (backgroundFile.exists()) return Uri.fromFile(backgroundFile);
            }
        }
        return null;
    }

    /**
     * 获取背景不透明度
     *
     * @param context 上下文
     * @return 背景不透明度，范围0.0-0.8，默认为0.45
     */
    public static float getBackgroundAlpha(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        return backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.45f);
    }

    /**
     * 获取背景淡化风格预设档
     *
     * @param context 上下文
     * @return 预设档索引（0=轻盈 1=标准 2=浓郁），默认为1（标准）
     */
    public static int getBackgroundPreset(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        return backgroundPreferences.getInt(BACKGROUND_PRESET, 1);
    }

    /**
     * 显示当前配置值
     */
    private void displayCurrentConfigValues() {
        String salt6x = configRepository.get(SALT_6X_PREF, MiHoYoBBSConstants.SALT_6X_final);
        String salt4x = configRepository.get(SALT_4X_PREF, MiHoYoBBSConstants.SALT_4X_final);
        String lk2 = configRepository.get(LK2_PREF, MiHoYoBBSConstants.LK2_final);
        String k2 = configRepository.get(K2_PREF, MiHoYoBBSConstants.K2_final);
        String bbsVersion = configRepository.get(BBS_VERSION_PREF, MiHoYoBBSConstants.bbs_version_final);
        String update_time = configRepository.get(UPDATE_TIME_PREF, activity.getString(R.string.config_not_fetched));
        String update_time_Local = configRepository.get(UPDATE_TIME_LOCAL_PREF, MiHoYoBBSConstants.update_time);

        // KV 行布局：标签在布局中，此处只绑裸值（mono 右对齐）
        // 下标顺序与 Compose 侧 CONFIG_KEY_LABEL_RES 一致
        // ConfigRepository.get 标注 @Nullable（defValue 理论可为 null），此处默认值均非空，仍按契约判空兜底
        utilsPane.setValue(0, update_time_Local != null ? update_time_Local : activity.getString(R.string.config_not_fetched));
        utilsPane.setValue(1, update_time != null ? update_time : activity.getString(R.string.config_not_fetched));
        utilsPane.setValue(2, bbsVersion != null ? bbsVersion : MiHoYoBBSConstants.bbs_version_final);
        utilsPane.setValue(3, salt6x != null ? salt6x : MiHoYoBBSConstants.SALT_6X_final);
        utilsPane.setValue(4, salt4x != null ? salt4x : MiHoYoBBSConstants.SALT_4X_final);
        utilsPane.setValue(5, lk2 != null ? lk2 : MiHoYoBBSConstants.LK2_final);
        utilsPane.setValue(6, k2 != null ? k2 : MiHoYoBBSConstants.K2_final);
    }

    /**
     * 后台从网络拉取最新 salt/版本等配置，成功则刷新显示并提示，失败提示错误。
     */
    private void updateConfig(View view) {
        // ApplicationContext 在 UI 线程取出：后台线程不访问 Activity 引用
        final Context appContext = activity.getApplicationContext();
        AppExecutors.get().execute(() -> {
            boolean success = MiHoYoBBSConstants.update_config_from_web(appContext);
            activity.runOnUiThread(() -> {
                if (success) {
                    displayCurrentConfigValues();
                    showCustomSnackbar(view, activity, activity.getString(R.string.snack_config_updated));
                } else {
                    showCustomSnackbar(view, activity, activity.getString(R.string.snack_config_update_failed));
                }
            });
        });
    }

    /**
     * 计算并显示当前缓存大小
     *
     * @param cachePane Compose 版缓存抽屉内容，回填缓存占用文案
     */
    private void calculateCacheSize(CacheSettingPaneView cachePane) {
        // 注意：不能写成 try (ExecutorService e = ...) —— try-with-resources 会在 try 出口调用
        // close()，而 ExecutorService.close() 语义是 shutdown + awaitTermination，
        // 等于在主线程同步等待整个目录遍历结束（大缓存时直接 ANR）。
        // 这里改用进程级共享 IO 线程池，并预先在 UI 线程取出 ApplicationContext 引用。
        final Context appContext = activity.getApplicationContext();
        AppExecutors.get().execute(() -> {
            try {
                long totalSize = getDirSizeSafe(appContext.getCacheDir());
                File externalCache = appContext.getExternalCacheDir();
                if (externalCache != null)
                    totalSize += getDirSizeSafe(externalCache);
                String sizeText = formatFileSize(totalSize);
                activity.runOnUiThread(() -> {
                    cachePane.setCacheSize(activity.getString(R.string.current_cache_size_fmt, sizeText));
                    // 折叠行右侧值摘要同步（设计稿：缓存 128 MB）
                    if (cacheCard != null) cacheCard.setValue(sizeText);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() ->
                        cachePane.setCacheSize(activity.getString(R.string.current_cache_size_error)));
            }
        });
    }

    /**
     * 清理缓存
     *
     * @param cachePane Compose 版缓存抽屉内容，清理后回填缓存占用文案
     */
    private void clearCache(CacheSettingPaneView cachePane) {
        // 同 calculateCacheSize：不可用 try-with-resources（close() 会在主线程同步等待任务结束）。
        final Context appContext = activity.getApplicationContext();
        AppExecutors.get().execute(() -> {
            boolean success = true;
            try {
                success &= deleteDirSafe(appContext.getCacheDir());
                File externalCache = appContext.getExternalCacheDir();
                if (externalCache != null)
                    success &= deleteDirSafe(externalCache);
            } catch (Exception e) {
                success = false;
            }
            boolean finalSuccess = success;
            activity.runOnUiThread(() -> {
                if (finalSuccess) {
                    cachePane.setCacheSize(activity.getString(R.string.current_cache_size_zero));
                    if (cacheCard != null) cacheCard.setValue("0 B");
                    showCustomSnackbar(activity, activity.getString(R.string.snack_cache_cleared));
                } else {
                    showCustomSnackbar(activity, activity.getString(R.string.snack_cache_clear_partial_failed));
                }
            });
        });
    }

    /**
     * 获取目录大小
     *
     * @param dir 目录
     * @return 目录大小（字节）
     */
    private long getDirSizeSafe(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null)
            for (File file : files)
                size += getDirSizeSafe(file);
        return size;
    }

    /**
     * 格式化文件大小
     *
     * @param size 文件大小（字节）
     * @return 格式化后的文件大小字符串
     */
    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    /**
     * 删除目录及其内容
     *
     * @param dir 目录
     * @return 是否删除成功
     */
    private boolean deleteDirSafe(File dir) {
        if (dir == null || !dir.exists()) return true;
        if (dir.isFile()) return dir.delete();
        File[] files = dir.listFiles();
        if (files != null)
            for (File file : files)
                if (!deleteDirSafe(file))
                    return false;
        return dir.delete();
    }

    /**
     * Compose 版拦截：任务运行中弹窗提示，对话框 dismiss 时执行 [revertAction] 回滚 UI 状态。
     * 回滚动作由调用方以 Runnable 形式给出（Compose 侧没有 View 可 setChecked）。
     */
    private boolean blockIfTaskRunning(Runnable revertAction) {
        if (HomeFragment.isTaskRunning() || ForegroundTaskService.isRunning()) {
            VenusDialogs.confirm(
                    activity,
                    activity.getString(R.string.dialog_task_running),
                    activity.getString(R.string.msg_wait_task_finish_settings),
                    activity.getString(R.string.btn_ok),
                    null,
                    null,
                    null,
                    revertAction);
            return true;
        }
        return false;
    }

    /** 森空岛子项勾选态回写（任务运行中弹窗 dismiss 时的回滚入口）。 */
    private void setSklandSubItemChecked(int index, boolean checked) {
        if (index == SklandSettingPaneKt.SKLAND_INDEX_ARKNIGHTS) sklandPane.setArknightsChecked(checked);
        else if (index == SklandSettingPaneKt.SKLAND_INDEX_ENDFIELD) sklandPane.setEndfieldChecked(checked);
    }

    /** 国际服隐藏游戏签到里不支持的游戏（崩坏2 / 未定事件簿）。 */
    private void setOverseaChipsVisible(GameDailySettingPaneView gamePane, boolean visible) {
        gamePane.setChipVisible(GameDailySettingPaneKt.GAME_CHIP_INDEX_HR2, visible);
        gamePane.setChipVisible(GameDailySettingPaneKt.GAME_CHIP_INDEX_WEIDING, visible);
    }

    /** 折叠行值摘要：positive 时用语义成功色，否则回退次要色。 */
    private void setCardValue(SettingsCard card, CharSequence value, boolean positive) {
        if (card == null) return;
        card.setValue(value);
        card.setValueColor(positive
                ? ContextCompat.getColor(activity, com.muxiao.Venus.R.color.status_success)
                : 0);
    }

    private void updateDailyCardValue(boolean enabled) {
        setCardValue(dailyCard, activity.getString(enabled ? R.string.value_on : R.string.value_off), enabled);
    }

    /** 游戏签到行值：已开启游戏数 / 总数（设计稿：4/5 已开启）。 */
    private void updateGameCardValue() {
        if (gameDailyCard == null) return;
        String[][] defs = {
                {GAME_DAILY_GENSHIN, "false"}, {GAME_DAILY_ZZZ, "false"}, {GAME_DAILY_SRG, "false"},
                {GAME_DAILY_HR3, "false"}, {GAME_DAILY_HR2, "false"}, {GAME_DAILY_WEIDING, "false"}
        };
        int enabled = 0;
        for (String[] d : defs)
            if (sharedPreferences.getBoolean(d[0], Boolean.parseBoolean(d[1]))) enabled++;
        setCardValue(gameDailyCard, activity.getString(R.string.game_enabled_fmt, enabled, defs.length), enabled > 0);
    }

    private void updateNotificationCardValue(boolean enabled) {
        setCardValue(notificationCard, activity.getString(enabled ? R.string.value_on : R.string.value_off), enabled);
    }

    /** 刷新电池优化豁免行的值：已豁免=成功绿，未豁免=警告黄。 */
    public void updateBatteryOptValue() {
        boolean exempted = BatteryHelper.isIgnoringBatteryOptimizations(activity);
        String text = activity.getString(exempted
                ? R.string.battery_exempted_value : R.string.battery_not_exempted_value);
        notificationPane.setBatteryValue(text, ContextCompat.getColor(activity, exempted
                ? com.muxiao.Venus.R.color.status_success
                : com.muxiao.Venus.R.color.status_warning));
    }

    /** 刷新定时签到时间行的时间值（每天 HH:mm，值取自 settings_prefs；行内含图标/标签/箭头）。 */
    private void updateScheduledTimeLabel() {
        int[] time = DailyTaskScheduler.getConfiguredTime(activity);
        notificationPane.setScheduledTimeText(activity.getString(R.string.scheduled_task_time_fmt,
                String.format(Locale.US, "%02d:%02d", time[0], time[1])));
    }

    private void updateSklandCardValue(boolean enabled) {
        setCardValue(sklandCard, activity.getString(enabled ? R.string.value_enabled : R.string.value_disabled), enabled);
    }

    // ===== 云游戏签到 =====

    /**
     * 装配「云游戏签到」抽屉：总开关、两款游戏的勾选（决定任务是否进入列表）、
     * token 配置入口与已配置状态。token 按当前选中用户存放（{@code user_{id}}），
     * 国际服隐藏云绝区零。
     */
    private void setupCloudGameSettings(Context context) {
        boolean cloudEnabled = sharedPreferences.getBoolean(CLOUD_GAME_ENABLED, false);
        cloudGamePane.setEnabledChecked(cloudEnabled);
        cloudGamePane.setChipVisible(CloudGameSettingPaneKt.CLOUD_GAME_INDEX_ZZZ,
                !MiHoYoBBSConstants.is_oversea(context));
        refreshCloudGameTokens();
        updateCloudGameCardValue();
        cloudGamePane.setOnEnabledChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> cloudGamePane.setEnabledChecked(!isChecked))) return;
            sharedPreferences.edit().putBoolean(CLOUD_GAME_ENABLED, isChecked).apply();
            updateCloudGameCardValue();
        });
        // chip-pref 映射：下标顺序与 Compose 侧 CLOUD_GAME_CHIP_LABEL_RES 一致
        final String[] cloudGameKeys = {CLOUD_GAME_GENSHIN, CLOUD_GAME_ZZZ};
        for (int i = 0; i < cloudGameKeys.length; i++)
            cloudGamePane.setChipChecked(i, sharedPreferences.getBoolean(cloudGameKeys[i], false));
        cloudGamePane.setOnChipChangeListener((index, checked) -> {
            if (blockIfTaskRunning(() -> cloudGamePane.setChipChecked(index, !checked))) return;
            sharedPreferences.edit().putBoolean(cloudGameKeys[index], checked).apply();
            updateCloudGameCardValue();
        });
        // 两款云游戏共用同一个获取入口：token 本身互不通用（按云游戏服务签发），
        // 但都源自同一份通行证登录态，一次 WebView 会话即可先后抓到两份，
        // 由抓取页按请求域名自动识别归属，无需先选游戏。
        cloudGamePane.setOnFetchClickListener(this::launchCloudTokenFetch);
        cloudGamePane.setOnManualClickListener(this::showCloudGameManualPicker);
    }

    /**
     * 刷新 token 配置进度（按当前选中用户读取，未登录时均视为未配置）。
     *
     * <p>国际服只有云原神一个可用项，分母随之为 1——否则「1/2 已配置」会让人误以为漏配。
     */
    private void refreshCloudGameTokens() {
        String userId = new UserManager(activity).getCurrentUser();
        boolean oversea = MiHoYoBBSConstants.is_oversea(activity);
        String[] games = oversea
                ? new String[]{CloudGameDaily.GAME_GENSHIN}
                : new String[]{CloudGameDaily.GAME_GENSHIN, CloudGameDaily.GAME_ZZZ};
        int configured = 0;
        StringBuilder missing = new StringBuilder();
        for (String game : games) {
            if (hasCloudGameToken(userId, CloudGameDaily.tokenKey(game, oversea))) {
                configured++;
            } else {
                if (missing.length() > 0) missing.append(", ");
                missing.append(CloudGameDaily.displayName(activity, game));
            }
        }
        cloudGamePane.setTokenStatus(configured, games.length,
                missing.length() > 0 ? missing.toString() : null);
    }

    /**
     * 是否已配置可用 token。用形态校验而非判空：历史上被占位值（{@code undefined}/{@code null}）
     * 写脏的条目要显示为「未配置」，否则用户会以为配好了、任务却一直在报 token 失效。
     */
    private boolean hasCloudGameToken(String userId, String key) {
        if (userId == null || userId.isEmpty()) return false;
        return CloudGameDaily.isValidToken(tools.read(activity, userId, key));
    }

    /**
     * 手动填写入口（自动抓取失败时的兜底）：token 两份互不通用，需先选游戏。
     * 国际服只有云原神一项，跳过选择直接进输入框。
     */
    private void showCloudGameManualPicker() {
        String userId = new UserManager(activity).getCurrentUser();
        if (userId == null || userId.isEmpty()) {
            showCustomSnackbar(screenView, activity, activity.getString(R.string.cloud_game_need_login));
            return;
        }
        if (MiHoYoBBSConstants.is_oversea(activity)) {
            showCloudGameTokenInput(CloudGameDaily.GAME_GENSHIN, userId);
            return;
        }
        VenusDialogs.items(
                activity,
                activity.getString(R.string.cloud_game_select_game),
                java.util.Arrays.asList(
                        CloudGameDaily.displayName(activity, CloudGameDaily.GAME_GENSHIN),
                        CloudGameDaily.displayName(activity, CloudGameDaily.GAME_ZZZ)),
                index -> showCloudGameTokenInput(
                        index == CloudGameSettingPaneKt.CLOUD_GAME_INDEX_GENSHIN
                                ? CloudGameDaily.GAME_GENSHIN : CloudGameDaily.GAME_ZZZ,
                        userId),
                activity.getString(R.string.btn_cancel));
    }

    /**
     * 打开云游戏 WebView 页自动抓取 token（复用抽卡链接页的页面外壳）。
     * 不指定目标游戏：抓取页按请求域名自动识别是云原神还是云绝区零，抓到哪个存哪个。
     */
    private void launchCloudTokenFetch() {
        String userId = new UserManager(activity).getCurrentUser();
        if (userId == null || userId.isEmpty()) {
            showCustomSnackbar(screenView, activity, activity.getString(R.string.cloud_game_need_login));
            return;
        }
        Intent intent = new Intent(activity, CloudGachaActivity.class);
        intent.putExtra(CloudGachaActivity.EXTRA_MODE, CloudGachaActivity.MODE_CLOUD_TOKEN);
        cloudTokenLauncher.launch(intent);
    }

    /**
     * 手动填写云游戏 token。token（{@code X-Rpc-Combo_token}）与米游社登录态无关，
     * 但按用户存放，故 userId 由入口处取好传入。
     */
    private void showCloudGameTokenInput(String gameName, String userId) {
        boolean oversea = MiHoYoBBSConstants.is_oversea(activity);
        String key = CloudGameDaily.tokenKey(gameName, oversea);
        String displayName = CloudGameDaily.displayName(activity, gameName);
        VenusDialogs.input(
                activity,
                activity.getString(R.string.cloud_game_token_dialog_title, displayName),
                null,
                tools.read(activity, userId, key),
                activity.getString(R.string.cloud_game_token_hint),
                activity.getString(R.string.btn_save),
                activity.getString(R.string.btn_cancel),
                true,
                text -> text == null || text.trim().isEmpty()
                        ? activity.getString(R.string.cloud_game_token_required) : null,
                text -> {
                    tools.write(activity, userId, key, text.trim());
                    refreshCloudGameTokens();
                    showCustomSnackbar(screenView, activity,
                            activity.getString(R.string.cloud_game_token_saved, displayName));
                });
    }

    /**
     * 云游戏行值摘要：总开关关闭显示「已关闭」，开启显示「N/M 已开启」。
     * M 取当前服务器实际可用的云游戏数（国际服没有云绝区零）。
     */
    private void updateCloudGameCardValue() {
        if (cloudGameCard == null || sharedPreferences == null) return;
        if (!sharedPreferences.getBoolean(CLOUD_GAME_ENABLED, false)) {
            setCardValue(cloudGameCard, activity.getString(R.string.value_off), false);
            return;
        }
        boolean oversea = MiHoYoBBSConstants.is_oversea(activity);
        int total = oversea ? 1 : 2;
        int enabledCount = sharedPreferences.getBoolean(CLOUD_GAME_GENSHIN, false) ? 1 : 0;
        if (!oversea && sharedPreferences.getBoolean(CLOUD_GAME_ZZZ, false)) enabledCount++;
        setCardValue(cloudGameCard,
                activity.getString(R.string.game_enabled_fmt, enabledCount, total), enabledCount > 0);
    }

    private void updateUpdateCardValue(boolean auto) {
        setCardValue(updateCard, activity.getString(auto ? R.string.update_value_auto : R.string.update_value_manual), false);
    }

    private void updateServerCardValue(boolean isCn) {
        setCardValue(serverCard, activity.getString(isCn ? R.string.server_cn : R.string.server_os), false);
    }

    /** 主题行值：主题名 · 深浅名（设计稿：蓝色 · 浅色）。 */
    private void updateThemeCardValue() {
        if (themeCard == null) return;
        SharedPreferences themePreferences = activity.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int theme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        int variant = themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);
        String[] names = {
                activity.getString(R.string.theme_dynamic), activity.getString(R.string.theme_blue),
                activity.getString(R.string.theme_green), activity.getString(R.string.theme_red), activity.getString(R.string.theme_yellow),
                activity.getString(R.string.theme_cyan), activity.getString(R.string.theme_purple)
        };
        String[] variants = {
                activity.getString(R.string.theme_follow), activity.getString(R.string.theme_light), activity.getString(R.string.theme_dark)
        };
        String name = names[theme >= 0 && theme < names.length ? theme : 0];
        String mode = variants[variant >= 0 && variant < variants.length ? variant : 0];
        setCardValue(themeCard, activity.getString(R.string.theme_value_fmt, name, mode), false);
    }
}
