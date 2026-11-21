package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_ALPHA;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_IMAGE_URI;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_REFRESH_AT;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PRESET;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BBS_VERSION_PREF;
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
import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_COOKIE;
import static com.muxiao.Venus.common.Constants.Prefs.THEME_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.UPDATE_TIME_LOCAL_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.UPDATE_TIME_PREF;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.core.content.ContextCompat;

import com.muxiao.Venus.BuildConfig;
import com.muxiao.Venus.Home.DailyTaskScheduler;
import com.muxiao.Venus.Home.CloudGameDaily;
import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Home.SklandDaily;
import com.muxiao.Venus.Home.TaskProfile;
import com.muxiao.Venus.Home.TaskProfileStore;
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
import com.muxiao.Venus.common.ui.compose.VenusColorSchemes;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import android.graphics.drawable.ColorDrawable;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.data.ConfigRepository;
import com.muxiao.Venus.common.ui.compose.BottomBarOverlay;
import com.muxiao.Venus.common.ui.compose.SettingsCard;
import com.muxiao.Venus.common.ui.compose.SettingsScreenView;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.User.UserManager;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

    /**
     * 设置页控制器：签到开关、森空岛/云游戏凭证、背景/主题/语言/服务器、缓存清理、更新检查。
     */
public class SettingsFragment implements PageController {

    private final MainActivity activity;

    private SharedPreferences sharedPreferences;
    // config_prefs 只经仓储访问（底层 DataStore）：直连 SP 会读到过期配置。
    // 否则 MiHoYoBBSConstants 写入 DataStore 后，本页仍读旧 xml，会显示过期配置值。
    private ConfigRepository configRepository;
    private static final int THEME_DEFAULT = 0;

    // 主题深浅色变体常量（0=跟随系统 1=浅色 2=深色）
    private static final int THEME_VARIANT_DEFAULT = 0;
    private static final int THEME_VARIANT_DARK = 2;

    // 折叠行右侧 mono 值摘要（命令台设计稿行值）所需的卡片引用
    /** 抽屉定位标识：供用户管理页「凭证状态」与任务列表「未配置」项跳转展开。 */
    public static final int PANE_CLOUD_GAME = 1;
    /** 抽屉定位标识：森空岛签到。 */
    public static final int PANE_SKLAND = 2;

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

    // 缓存抽屉内容为 Compose，Java 侧只回填占用文案。
    private CacheSettingPaneView cachePane;
    // 「配置信息」抽屉内容为 Compose。
    private BbsUtilsSettingPaneView utilsPane;
    private SklandSettingPaneView sklandPane;
    private CloudGameSettingPaneView cloudGamePane;
    /**
     * 凭证抽屉的「配置目标账号」；null = 跟随当前账号。仅本次打开有效（show() 清空）。
     */
    private String credentialUserId;
    /**
     * 正在编辑的任务配置（内存副本，改动立即落盘）。归属账号恒为<b>当前登录账号</b>——
     * 刻意不跟随 {@link #credentialUserId}：后者是「给某个账号填 token」的临时上下文，
     * 而任务配置决定「这个账号签哪些任务」，与首页/后台实际运行的账号必须一致。
     */
    private TaskProfile taskProfile;
    // 「通知 / 后台运行」抽屉内容为 Compose。
    private NotificationSettingPaneView notificationPane;
    // 「米游币签到」「游戏签到」抽屉内容为 Compose。
    private DailySettingPaneView dailyPane;
    private GameDailySettingPaneView gameDailyPane;

    private SharedPreferences backgroundPreferences;

    // ActivityResult 启动器（MainActivity 注册后注入）
    private ActivityResultLauncher<Intent> selectImageLauncher; // ACTION_OPEN_DOCUMENT (老设备)
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia; // Photo Picker (API33+)
    private ActivityResultLauncher<Intent> cropImageLauncher; // CropActivity 裁剪结果
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

    /** 云游戏 Token 抓取返回：抓取页可能顺手落了另一款，统一刷角标。 */
    public void onCloudTokenFetched() {
        if (cloudGamePane != null) refreshCloudGameTokens();
    }

    /** Photo Picker 选择结果（MainActivity 的 launcher 转发）：进入裁剪。 */
    public void onBackgroundImagePicked(Uri uri) {
        // 用户在系统照片选择器中取消/未选择时返回 null：静默忽略，
        // 不能把 null 传给裁剪页，否则裁剪页会以「图片裁剪失败: null」报错收场
        if (uri != null) startCropActivity(uri);
    }

    /** 老设备（<API33）ACTION_OPEN_DOCUMENT 结果（MainActivity 转发）。 */
    public void onLegacyImagePicked(Uri uri) {
        if (uri != null) startCropActivity(uri);
    }

    /** 裁剪页结果（MainActivity 的 launcher 转发）：确认回传临时文件路径，取消静默忽略。 */
    public void onCropResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return; // 用户取消
        String path = data.getStringExtra(CropActivity.EXTRA_RESULT_PATH);
        if (path == null) {
            show_error_dialog(activity, activity.getString(R.string.err_crop_failed, "result path is null"));
            return;
        }
        // 将裁剪后的图片复制到应用私有目录并保存
        saveBackgroundImage(Uri.fromFile(new File(path)));
    }

    /** 首次组合时调用一次：生成 13 个窗格内容 → 塞进抽屉 → 维护折叠摘要。 */
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

        // 宿主已由 SettingsScreenView 构造并接线，这里只取引用回填状态。
        dailyPane = root.getDailyPane();
        gameDailyPane = root.getGameDailyPane();
        utilsPane = root.getConfigPane();
        cachePane = root.getCachePane();
        notificationPane = root.getNotificationPane();
        sklandPane = root.getSklandPane();
        cloudGamePane = root.getCloudGamePane();
        ServerSettingPaneView serverPane = root.getServerPane();
        UpdateSettingPaneView updatePane = root.getUpdatePane();
        LanguageSettingPaneView languagePane = root.getLanguagePane();
        ThemeSettingPaneView themePane = root.getThemePane();
        BackgroundSettingPaneView backgroundPane = root.getBackgroundPane();
        AboutSettingPaneView aboutPane = root.getAboutPane();

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

        // 任务配置按账号：先加载当前账号的配置，四类卡片的值统一由 renderTaskConfig 回填
        // （必须早于下面装监听，避免回填被当成用户改动而触发落盘）
        reloadTaskProfile();
        notificationPane.setBackgroundChecked(sharedPreferences.getBoolean(BACKGROUND_TASK_ENABLED, false));
        // 折叠行值摘要初始态（设计稿：行右侧 mono 摘要）
        updateNotificationCardValue(notificationPane.getBackgroundChecked());
        renderTaskConfig();

        dailyPane.setOnChipChangeListener((index, checked) -> {
            // 任务运行中弹窗，dismiss 时回滚勾选（与原 CompoundButton 版语义一致）
            if (blockIfTaskRunning(() -> dailyPane.setChipChecked(index, !checked))) return;
            if (blockIfNoTaskUser(() -> dailyPane.setChipChecked(index, !checked))) return;
            setProfileEntry(taskProfile.dailyForums, TaskProfileStore.DAILY_FORUM_ORDER[index], checked);
            persistTaskProfile();
        });

        gameDailyPane.setOnChipChangeListener((index, checked) -> {
            if (blockIfTaskRunning(() -> gameDailyPane.setChipChecked(index, !checked))) return;
            if (blockIfNoTaskUser(() -> gameDailyPane.setChipChecked(index, !checked))) return;
            setProfileEntry(taskProfile.gameDailyGames, TaskProfileStore.GAME_SIGN_ORDER[index], checked);
            persistTaskProfile();
            updateGameCardValue();
        });

        dailyPane.setOnSwitchChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> dailyPane.setSwitchChecked(!isChecked))) return;
            if (blockIfNoTaskUser(() -> dailyPane.setSwitchChecked(!isChecked))) return;
            taskProfile.daily = isChecked;
            persistTaskProfile();
            updateDailyCardValue(isChecked);
        });

        gameDailyPane.setOnSwitchChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> gameDailyPane.setSwitchChecked(!isChecked))) return;
            if (blockIfNoTaskUser(() -> gameDailyPane.setSwitchChecked(!isChecked))) return;
            taskProfile.gameDaily = isChecked;
            persistTaskProfile();
            updateGameCardValue();
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
                            this::openBatteryOptimizationSettings,
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
        // 未豁免时点击打开系统省电白名单页（用户手动勾选，无需权限），返回后刷新行值；
        // 机型不支持该设置页时退回应用详情页
        updateBatteryOptValue();
        notificationPane.setOnBatteryRowClickListener(() -> {
            if (BatteryHelper.isIgnoringBatteryOptimizations(context)) {
                tools.showCustomSnackbar(screenView, context.getApplicationContext(),
                        activity.getString(R.string.battery_already_exempted));
                return;
            }
            openBatteryOptimizationSettings();
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
            updateConfig();
        });
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
        cachePane.setOnClearClickListener(() -> clearCache(cachePane));

        //森空岛设置（Compose 版内容）：值已由 renderTaskConfig 按当前账号回填，此处只装监听
        sklandPane.setOnEnabledChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> sklandPane.setEnabledChecked(!isChecked))) return;
            if (blockIfNoTaskUser(() -> sklandPane.setEnabledChecked(!isChecked))) return;
            taskProfile.skland = isChecked;
            persistTaskProfile();
            // 两个子项的可点态由 pane 依 enabledChecked 自行派生，无需再 setEnabled
            updateSklandCardValue(isChecked);
        });
        sklandPane.setOnSubItemChangeListener((index, isChecked) -> {
            if (blockIfTaskRunning(() -> setSklandSubItemChecked(index, !isChecked))) return;
            if (blockIfNoTaskUser(() -> setSklandSubItemChecked(index, !isChecked))) return;
            if (index == SklandSettingPaneKt.SKLAND_INDEX_ARKNIGHTS) taskProfile.sklandArknights = isChecked;
            else taskProfile.sklandEndfield = isChecked;
            persistTaskProfile();
        });
        sklandPane.setOnLoginLinkClickListener(() -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.SKLAND_LOGIN_URL))));
        sklandPane.setOnCookieLinkClickListener(() -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.SKLAND_COOKIE_URL))));
        sklandPane.setOnSaveClickListener(() -> {
            String userId = credentialUser();
            if (userId == null || userId.isEmpty()) {
                showCustomSnackbar(screenView, activity, activity.getString(R.string.common_need_login));
                return;
            }
            // token 按用户存放（与米游社登录态、云游戏 token 同口径）：切换账号后自动切换
            String currentToken = tools.read(activity, userId, SKLAND_COOKIE);
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
                        tools.write(activity, userId, SKLAND_COOKIE, newToken.trim());
                        // 凭证补上/改掉会改变任务的「就绪」样式（列表划线），小组件须同步重渲染：
                        // token 存在 user_{id} 里，不受设置项监听覆盖，故此处显式刷新
                        TaskWidgetProvider.refreshAllWidgets(activity);
                        showCustomSnackbar(screenView, context, activity.getString(R.string.snack_skland_content_saved));
                    });
        });
        // 云游戏签到（Compose 版内容）：token 按当前用户存储，与米游社登录态无关
        setupCloudGameSettings();
        return root;
    }

    /**
     * 页面显示 / Activity 恢复：对账外部可变状态（后台开关、电池豁免、凭证账号）。
     * 系统设置项无变更广播，回前台是唯一对账时点。
     */
    @Override
    public void show() {
        // 目标账号只在「从用户管理页跳过来」的这一次有效：
        // 正常从底栏切到设置页时回到当前账号，避免残留成幽灵上下文
        credentialUserId = null;
        if (sharedPreferences != null && screenView != null) {
            // 账号可能在「用户管理」页被切换过：任务配置按账号存，必须整体重载重绘。
            // 漏这一步的后果是页面停在旧账号的勾选上，用户改一下就把改动写进了新账号的配置
            reloadTaskProfile();
            renderTaskConfig();
            notificationPane.setBackgroundChecked(sharedPreferences.getBoolean(BACKGROUND_TASK_ENABLED, false));
            updateNotificationCardValue(notificationPane.getBackgroundChecked());
            // 电池豁免是系统级开关，没有变更广播：用户无论是从本页跳系统设置、还是自己从桌面
            // 进设置改完再回来，都只能在这里对账。否则要等应用重启才刷新（行值会一直停在旧状态）
            updateBatteryOptValue();
        }
        // 用户可能在「用户管理」页切换过账号：云游戏 token 按用户存放，需重新判定已配置状态
        if (cloudGamePane != null) refreshCloudGameTokens();
    }

    /** 页面失去选中 / Activity 暂停（无逐帧资源，无操作）。 */
    @Override
    public void hide() {
    }

    /**
     * 展开指定抽屉并指定凭证目标账号（用户卡 chip、任务列表未配置项两个入口共用）。
     * 调用前视图须已创建、当前页已切到设置页。
     *
     * @param pane           PANE_CLOUD_GAME 或 PANE_SKLAND
     * @param credentialUser 目标账号；null = 跟随当前账号
     */
    public void expandPane(int pane, String credentialUser) {
        // 先定目标再展开：抽屉的账号归属与 token 进度都是展开时读的
        setCredentialUser(credentialUser);
        if (pane == PANE_CLOUD_GAME && cloudGameCard != null) cloudGameCard.expand();
        else if (pane == PANE_SKLAND && sklandCard != null) sklandCard.expand();
    }

    /** 销毁：解除底栏订阅，清空宿主引用（Activity 销毁时由 MainActivity 调用）。 */
    public void release() {
        BottomBarOverlay.removeListener(bottomBarListener);
        screenView = null;
    }

    /**
     * 启动裁剪页（Compose 实现的 CropActivity，比例锁定为屏幕宽高比，输出 JPEG 落临时文件）。
     */
    private void startCropActivity(@NonNull Uri uri) {
        Intent intent = new Intent(activity, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_SOURCE, uri.toString());
        // 授予裁剪页临时读取权限，否则读不到 content:// 来源（file:// 同进程无碍，保持旧防御）
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        cropImageLauncher.launch(intent);
    }

    /**
     * 保存背景图片到应用私有目录
     *
     * @param croppedImageUri 裁剪后的图片URI
     */
    private void saveBackgroundImage(Uri croppedImageUri) {
        try {
            File backgroundFile = new File(activity.getFilesDir(), "background.jpg");
            if (backgroundFile.exists() && !backgroundFile.delete()) {
                Logger.w("删除旧背景文件失败：" + backgroundFile.getAbsolutePath());
            }

            tools.copyFile(activity, croppedImageUri, backgroundFile);

            // 保存背景图片文件Uri到SharedPreferences。URI 固定指向 background.jpg，
            // 重裁剪时值不变、不触发 MainActivity 的 prefs 监听（表现为换图后仍显示旧背景），
            // 故额外写一个时间戳刷新键强制重合成（background_prefs 内全部键均为背景相关，见外壳监听注释）。
            Uri backgroundUri = Uri.fromFile(backgroundFile);
            backgroundPreferences.edit()
                    .putString(BACKGROUND_IMAGE_URI, backgroundUri.toString())
                    .putLong(BACKGROUND_REFRESH_AT, System.currentTimeMillis())
                    .apply();

            // 删除临时裁剪文件
            File temp = new File(activity.getCacheDir(), "temp_cropped_background.jpg");
            if (temp.exists() && !temp.delete()) {
                Logger.w("删除临时裁剪文件失败：" + temp.getAbsolutePath());
            }

            if (screenView != null)
                showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_background_set));
        } catch (Exception e) {
            show_error_dialog(activity, activity.getString(R.string.err_save_background_failed, tools.describeError(e)));
        }
    }

    /** 背景抽屉：淡化/不透明度只写 backgroundPreferences，MainActivity 防抖重合成，无需重启。 */
    private void setupBackgroundSelection(BackgroundSettingPaneView view) {
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

        view.setOnClearClickListener(() -> {
            // 清除之前先释放权限
            String uriString = backgroundPreferences.getString(BACKGROUND_IMAGE_URI, null);
            if (uriString == null) {
                // 从未设置过背景时点「清除」：如实提示，不再谎报「已清除」
                showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_no_background_to_clear));
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
            showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_background_cleared));
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
        view.setOnAlphaChangeFinishedListener(() -> showCustomSnackbar(screenView, activity,
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
            showCustomSnackbar(screenView, activity,
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
        // recreate() 重走 attachBaseContext，等价原 finish()+startActivity 但不销毁任务栈。
        activity.recreate();
    }

    /** 主题抽屉：圆点下标 = 主题序号（0 系统动态取色，1-6 蓝绿红黄青紫），选中态由 pane 维护。 */
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

        // 深浅分段：序号与 THEME_VARIANT_* 一一对应
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
        SharedPreferences themePreferences = activity.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        themePreferences.edit().putInt(SELECTED_THEME, themeId).apply();
        // 显示提示信息，告知用户需要重启应用以应用主题
        if (screenView != null)
            showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_theme_saved));
    }

    /**
     * 保存并应用主题深浅色变体。
     *
     * @param themeVariantId 主题深浅色变体ID
     */
    private void saveAndApplyThemeVariant(int themeVariantId) {
        if (themeVariantId < THEME_VARIANT_DEFAULT || themeVariantId > THEME_VARIANT_DARK) return;
        SharedPreferences themePreferences = activity.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        themePreferences.edit().putInt(SELECTED_THEME_VARIANT, themeVariantId).apply();

        // 立即应用深浅色模式
        String[] variantMessages = {
                activity.getString(R.string.snack_set_follow_system),
                activity.getString(R.string.snack_set_light_mode),
                activity.getString(R.string.snack_set_dark_mode)
        };
        if (screenView != null)
            showCustomSnackbar(screenView, activity, variantMessages[themeVariantId]);
        // 下一帧重建：让提示条先上屏，并重走 attachBaseContext 应用新的 uiMode
        activity.getWindow().getDecorView().post(activity::recreate);
    }

    /** 应用主题（须在 setContentView 前）：基础主题 + 运行时 ThemeOverlay。 */
    public static void applyAppTheme(Activity activity) {
        applyAppTheme(activity, R.style.Theme_Venus);
    }

    /**
     * 指定基础主题的重载：windowIsTranslucent 等窗口属性只能由主题提供，
     * 而 applyAppTheme(Activity) 固定 setTheme(Theme_Venus) 会抹掉 manifest 主题。
     * 语义色已统一到 VenusColorSchemes（XML 主题不再声明 per-theme 颜色），
     * 窗口背景跟随主题底色由代码注入（原 windowBackground=?attr/colorBackground 职责移交）。
     *
     * @param baseTheme 如 R.style.Theme_Venus_Fullscreen
     */
    public static void applyAppTheme(Activity activity, @StyleRes int baseTheme) {
        activity.setTheme(baseTheme);
        boolean darkNight = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int background = VenusColorSchemes
                .backgroundArgb(activity, getSelectedThemeIndex(activity), darkNight);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(background));
    }

    /** 从主题偏好读取当前选中的主题资源下标（默认 THEME_DEFAULT）；供 VenusTheme/取色方共用。 */
    public static int getSelectedThemeIndex(Context context) {
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

    /** 背景图 URI；未设置返回 null。 */
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

    /** 背景不透明度（0.0-0.8，默认 0.45）。 */
    public static float getBackgroundAlpha(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        return backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.45f);
    }

    /** 背景淡化预设（0 轻盈 / 1 标准 / 2 浓郁，默认 1）。 */
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
            // get() 标了 @Nullable：默认值非空也按契约判空
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
    private void updateConfig() {
        // ApplicationContext 在 UI 线程取出：后台线程不访问 Activity 引用
        final Context appContext = activity.getApplicationContext();
        AppExecutors.get().execute(() -> {
            boolean success = MiHoYoBBSConstants.update_config_from_web(appContext);
            activity.runOnUiThread(() -> {
                if (success) {
                    displayCurrentConfigValues();
                    showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_config_updated));
                } else {
                    showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_config_update_failed));
                }
            });
        });
    }

    /** 计算并回填缓存占用文案。 */
    private void calculateCacheSize(CacheSettingPaneView cachePane) {
            // 不可用 try-with-resources：close() = shutdown + awaitTermination，会在主线程同步等待
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
            // 同上：不可用 try-with-resources
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

    /** 任务运行中弹窗拦截；dismiss 时执行 revertAction 回滚 UI 状态。 */
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

    /**
     * 无账号守卫：任务配置按账号存，没有账号就没有可写入的位置。
     *
     * <p>不静默丢弃：回滚控件状态并提示，否则用户会以为「勾了却没生效」。
     * 与 {@link #blockIfTaskRunning} 同风格，返回 true 表示已拦截。
     */
    private boolean blockIfNoTaskUser(Runnable revertAction) {
        if (!taskConfigUser().isEmpty()) return false;
        revertAction.run();
        // screenView 在页面 release 后为 null：此时用户不可能正在勾选，判空只为兜住极端时序
        if (screenView != null)
            showCustomSnackbar(screenView, activity, activity.getString(R.string.home_no_user_hint));
        return true;
    }

    /**
     * 任务配置的归属账号：恒为当前登录账号。
     *
     * <p>刻意不复用 {@link #credentialUser()}：后者可能被「从用户管理页跳过来填 token」临时指到别的账号，
     * 而任务配置决定「这个账号签哪些任务」，必须与首页/后台实际运行的账号一致，
     * 否则会出现「在设置页改了 A 的任务，跑的却是 B」。
     */
    private String taskConfigUser() {
        return new UserManager(activity).getCurrentUser();
    }

    /** 从当前账号加载任务配置（无账号时为内存默认值，不落盘，见 {@link TaskProfileStore#load}）。 */
    private void reloadTaskProfile() {
        taskProfile = TaskProfileStore.load(activity, taskConfigUser());
    }

    /**
     * 任务卡片（可见性 / 已开启计数）用的服务器类型：跟随<b>正在编辑的任务配置</b>所属账号。
     * <p>
     * 不能读全局 {@code is_oversea}：任务配置自 P0-1 起按账号存，一个国际服账号在「全局=国服」时
     * 仍应看到国际服的 chip 集合（崩坏2/未定/云绝区零都不可用），否则勾了也跑不了。
     */
    private boolean overseaForTaskConfig() {
        return new UserManager(activity).isOversea(taskConfigUser());
    }

    /** 凭证（云游戏 token）用的服务器类型：跟随<b>凭证目标账号</b>{@link #credentialUser()}。 */
    private boolean overseaForCredential() {
        return new UserManager(activity).isOversea(credentialUser());
    }

    /** 落盘当前账号的任务配置；无账号时静默跳过（调用方已由 {@link #blockIfNoTaskUser} 拦截）。 */
    private void persistTaskProfile() {
        TaskProfileStore.save(activity, taskConfigUser(), taskProfile);
    }

    /**
     * 把 taskProfile 回填到四类任务卡片。
     *
     * <p>必须是唯一的回填入口：chip 下标与 {@link TaskProfileStore#DAILY_FORUM_ORDER} 等数组严格对应，
     * 分散回填时任何一处漏改都表现为「勾选跑到别的游戏上」。
     */
    private void renderTaskConfig() {
        if (taskProfile == null) return;

        dailyPane.setSwitchChecked(taskProfile.daily);
        for (int i = 0; i < TaskProfileStore.DAILY_FORUM_ORDER.length; i++)
            dailyPane.setChipChecked(i, taskProfile.dailyForums.contains(TaskProfileStore.DAILY_FORUM_ORDER[i]));
        updateDailyCardValue(taskProfile.daily);

        gameDailyPane.setSwitchChecked(taskProfile.gameDaily);
        for (int i = 0; i < TaskProfileStore.GAME_SIGN_ORDER.length; i++)
            gameDailyPane.setChipChecked(i, taskProfile.gameDailyGames.contains(TaskProfileStore.GAME_SIGN_ORDER[i]));
        updateGameCardValue();

        sklandPane.setEnabledChecked(taskProfile.skland);
        sklandPane.setArknightsChecked(taskProfile.sklandArknights);
        sklandPane.setEndfieldChecked(taskProfile.sklandEndfield);
        updateSklandCardValue(taskProfile.skland);

        cloudGamePane.setEnabledChecked(taskProfile.cloudGame);
        for (int i = 0; i < TaskProfileStore.CLOUD_GAME_ORDER.length; i++)
            cloudGamePane.setChipChecked(i, taskProfile.cloudGames.contains(TaskProfileStore.CLOUD_GAME_ORDER[i]));
        updateCloudGameCardValue();
    }

    /** 勾选集合的增删（{@link TaskProfile} 的列表是「已勾选项」语义，不是逐项布尔）。 */
    private static void setProfileEntry(java.util.List<String> entries, String value, boolean present) {
        if (!present) {
            entries.remove(value);
        } else if (!entries.contains(value)) {
            entries.add(value);
        }
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

    /** 游戏签到行值：已开启数 / 当前服务器可见的游戏数（设计稿：4/5 已开启）。 */
    private void updateGameCardValue() {
        if (gameDailyCard == null || taskProfile == null) return;
        boolean oversea = overseaForTaskConfig();
        int total = 0;
        int enabled = 0;
        for (String game : TaskProfileStore.GAME_SIGN_ORDER) {
            // 国际服没有崩坏2 / 未定事件簿的签到活动（chip 已隐藏）：分子分母都要排除，
            // 否则会显示「6/6 已开启」而界面上只有 4 个 chip
            if (oversea && (TaskProfileStore.GAME_HR2.equals(game)
                    || TaskProfileStore.GAME_WEIDING.equals(game))) continue;
            total++;
            if (taskProfile.gameDailyGames.contains(game)) enabled++;
        }
        setCardValue(gameDailyCard, activity.getString(R.string.game_enabled_fmt, enabled, total), enabled > 0);
    }

    private void updateNotificationCardValue(boolean enabled) {
        setCardValue(notificationCard, activity.getString(enabled ? R.string.value_on : R.string.value_off), enabled);
    }

    /**
     * 打开系统电池优化豁免页（行点击、定时签到引导共用出口）。
     * 必须走 ActivityResultLauncher：白名单无变更广播，result 回调是回前台唯一可靠时点；
     * 裸 startActivity 回来不刷新，须重启才更新。机型无此页时退回应用详情页。
     */
    private void openBatteryOptimizationSettings() {
        if (batteryOptLauncher != null) {
            try {
                batteryOptLauncher.launch(
                        BatteryHelper.buildBatteryOptimizationSettingsIntent(activity));
                return;
            } catch (Exception ignored) {
                // 部分 ROM 未导出「忽略电池优化」列表页：落到应用详情兜底
            }
        }
        BatteryHelper.openVendorBatterySettings(activity);
    }

    /** 刷新电池优化豁免行的值：已豁免=成功绿，未豁免=警告黄。 */
    public void updateBatteryOptValue() {
        // 启动器回调可能早于视图装配（或页面从未打开）：无窗格可刷时直接跳过
        if (notificationPane == null) return;
        boolean exempted = BatteryHelper.isIgnoringBatteryOptimizations(activity);
        String text = activity.getString(exempted
                ? R.string.battery_exempted_value : R.string.battery_not_exempted_value);
        notificationPane.setBatteryValue(text, ContextCompat.getColor(activity, exempted
                ? com.muxiao.Venus.R.color.status_success
                : com.muxiao.Venus.R.color.status_warning));
    }

    /** 刷新定时签到时间行（HH:mm，取自 settings_prefs）。 */
    private void updateScheduledTimeLabel() {
        int[] time = DailyTaskScheduler.getConfiguredTime(activity);
        notificationPane.setScheduledTimeText(activity.getString(R.string.scheduled_task_time_fmt,
                String.format(Locale.US, "%02d:%02d", time[0], time[1])));
    }

    private void updateSklandCardValue(boolean enabled) {
        setCardValue(sklandCard, activity.getString(enabled ? R.string.value_enabled : R.string.value_disabled), enabled);
    }

    /** 云游戏抽屉：总开关、两款勾选、token 入口与状态；token 按用户存 user_{id}，国际服隐藏绝区零。 */
    private void setupCloudGameSettings() {
        // 开关与勾选已由 renderTaskConfig 按当前账号回填；这里只做「国际服没有云绝区零」的可见性与凭证刷新
        cloudGamePane.setChipVisible(CloudGameSettingPaneKt.CLOUD_GAME_INDEX_ZZZ, !overseaForTaskConfig());
        refreshCloudGameTokens();
        cloudGamePane.setOnEnabledChangeListener(isChecked -> {
            if (blockIfTaskRunning(() -> cloudGamePane.setEnabledChecked(!isChecked))) return;
            if (blockIfNoTaskUser(() -> cloudGamePane.setEnabledChecked(!isChecked))) return;
            taskProfile.cloudGame = isChecked;
            persistTaskProfile();
            updateCloudGameCardValue();
        });
        // 顺序须与 Compose 侧 CLOUD_GAME_CHIP_LABEL_RES 一致（下标即 TaskProfileStore.CLOUD_GAME_ORDER 下标）
        cloudGamePane.setOnChipChangeListener((index, checked) -> {
            if (blockIfTaskRunning(() -> cloudGamePane.setChipChecked(index, !checked))) return;
            if (blockIfNoTaskUser(() -> cloudGamePane.setChipChecked(index, !checked))) return;
            setProfileEntry(taskProfile.cloudGames, TaskProfileStore.CLOUD_GAME_ORDER[index], checked);
            persistTaskProfile();
            updateCloudGameCardValue();
        });
        // 两款云游戏共用同一个获取入口：token 本身互不通用（按云游戏服务签发），
        // 但都源自同一份通行证登录态，一次 WebView 会话即可先后抓到两份，
        // 由抓取页按请求域名自动识别归属，无需先选游戏。
        cloudGamePane.setOnFetchClickListener(this::showCloudGameFetchPicker);
        cloudGamePane.setOnManualClickListener(this::showCloudGameManualPicker);
    }

    /**
     * 凭证读写目标账号：显式指定优先，否则当前登录账号。
     * 禁止绕过本方法直接 getCurrentUser()，否则 token 会写到当前账号名下；
     * 目标账号已删除/改名时回退当前账号。
     */
    private String credentialUser() {
        if (credentialUserId != null && !credentialUserId.isEmpty()) {
            if (new UserManager(activity).isUserExists(credentialUserId)) return credentialUserId;
            credentialUserId = null;
        }
        return new UserManager(activity).getCurrentUser();
    }

    /** 指定凭证目标账号；null = 跟随当前账号，仅本次有效（show() 清空）。 */
    public void setCredentialUser(String username) {
        credentialUserId = (username == null || username.isEmpty()) ? null : username;
        if (cloudGamePane != null) {
            refreshCloudGameTokens();
        } else {
            refreshCredentialAccountLabels();
        }
    }

    /** 刷新 token 进度（按目标账号读，未登录视为未配置）；国际服只有云原神，分母为 1。 */
    private void refreshCloudGameTokens() {
        String userId = credentialUser();
        boolean oversea = overseaForCredential();
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
        refreshCredentialAccountLabels();
    }

    /**
     * 刷新两个凭证抽屉的归属账号与森空岛状态。目标账号 ≠ 当前账号时文案必须明说，
     * 且每次页面显示都要重写（账号可能已换）。
     */
    private void refreshCredentialAccountLabels() {
        String userId = credentialUser();
        boolean hasUser = userId != null && !userId.isEmpty();
        // 目标账号可能是别的账号：文案要区分，否则会被当成「当前账号」的配置
        boolean isCurrent = hasUser && userId.equals(new UserManager(activity).getCurrentUser());
        if (cloudGamePane != null) cloudGamePane.setAccountLabel(hasUser ? userId : null, isCurrent);
        if (sklandPane != null) {
            sklandPane.setAccountLabel(hasUser ? userId : null, isCurrent);
            sklandPane.setTokenConfigured(hasUser
                    && SklandDaily.isValidToken(tools.read(activity, userId, SKLAND_COOKIE)));
        }
    }

    /** 是否已配置可用 token：按形态校验，占位值（undefined/null）视为未配置。 */
    private boolean hasCloudGameToken(String userId, String key) {
        if (userId == null || userId.isEmpty()) return false;
        return CloudGameDaily.isValidToken(tools.read(activity, userId, key));
    }

    /** 手动填写入口（抓取失败兜底）：两份 token 不通用，先选游戏；国际服只有云原神，直接进输入框。 */
    private void showCloudGameManualPicker() {
        String userId = credentialUser();
        if (userId == null || userId.isEmpty()) {
            showCustomSnackbar(screenView, activity, activity.getString(R.string.cloud_game_need_login));
            return;
        }
        if (overseaForCredential()) {
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
     * 云游戏 WebView 自动抓取（仅云原神有官方网页版 ys.mihoyo.com/cloud）。
     * 云绝区零无网页版，只能手动填写；抓取页按域名识别归属，顺带抓到另一款也一并落库。
     */
    private void showCloudGameFetchPicker() {
        String userId = credentialUser();
        if (userId == null || userId.isEmpty()) {
            showCustomSnackbar(screenView, activity, activity.getString(R.string.cloud_game_need_login));
            return;
        }
        launchCloudTokenFetch(CloudGameDaily.GAME_GENSHIN);
    }

    /** 打开指定云游戏的官方网页版并自动抓取 token。 */
    private void launchCloudTokenFetch(String gameName) {
        Intent intent = new Intent(activity, CloudGachaActivity.class);
        intent.putExtra(CloudGachaActivity.EXTRA_MODE, CloudGachaActivity.MODE_CLOUD_TOKEN);
        intent.putExtra(CloudGachaActivity.EXTRA_CLOUD_GAME, gameName);
        // token 按账号存放：把抽屉当前的目标账号带过去，
        // 否则从用户管理页给别的账号配 token 会落到当前账号名下
        String targetUser = credentialUser();
        if (targetUser != null && !targetUser.isEmpty()) {
            intent.putExtra(CloudGachaActivity.EXTRA_TARGET_USER, targetUser);
        }
        cloudTokenLauncher.launch(intent);
    }

    /** 手动填写云游戏 token；token 与米游社登录态无关，但按用户存放。 */
    private void showCloudGameTokenInput(String gameName, String userId) {
        // 键与服务器类型绑定（两服云原神 token 不通用）：按 token 归属的账号判定，而不是全局
        boolean oversea = new UserManager(activity).isOversea(userId);
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
            text -> text.trim().isEmpty()
                    ? activity.getString(R.string.cloud_game_token_required) : null,
                text -> {
                    tools.write(activity, userId, key, text.trim());
                    refreshCloudGameTokens();
                    // 就绪状态变化会改变小组件的划线样式（token 不在 settings_prefs，监听覆盖不到）
                    TaskWidgetProvider.refreshAllWidgets(activity);
                    showCustomSnackbar(screenView, activity,
                            activity.getString(R.string.cloud_game_token_saved, displayName));
                });
    }

    /**
     * 云游戏行值摘要：总开关关闭显示「已关闭」，开启显示「N/M 已开启」。
     * M 取当前服务器实际可用的云游戏数（国际服没有云绝区零）。
     */
    private void updateCloudGameCardValue() {
        if (cloudGameCard == null || taskProfile == null) return;
        if (!taskProfile.cloudGame) {
            setCardValue(cloudGameCard, activity.getString(R.string.value_off), false);
            return;
        }
        boolean oversea = overseaForTaskConfig();
        int total = oversea ? 1 : 2;
        int enabledCount = 0;
        for (String game : TaskProfileStore.CLOUD_GAME_ORDER) {
            if (oversea && CloudGameDaily.GAME_ZZZ.equals(game)) continue;
            if (taskProfile.cloudGames.contains(game)) enabledCount++;
        }
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
