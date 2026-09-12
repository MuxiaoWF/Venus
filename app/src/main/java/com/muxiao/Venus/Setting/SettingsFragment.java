package com.muxiao.Venus.Setting;

import dagger.hilt.android.AndroidEntryPoint;

import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_ALPHA;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_IMAGE_URI;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BBS_VERSION_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_DABIEYE;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_GENSHIN;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HNA;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR2;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_HR3;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_SRG;
import static com.muxiao.Venus.common.Constants.Prefs.DAILY_WEIDING;
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
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.slider.Slider;
import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.BuildConfig;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.data.ConfigRepository;
import com.muxiao.Venus.common.tools;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * 设置页Fragment：签到任务开关、森空岛content配置、背景图片/主题/语言切换、
 * 服务器类型切换（国服/国际服）、缓存清理、版本更新检查。
 */
@AndroidEntryPoint
public class SettingsFragment extends Fragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tools.setupFragmentTransitions(this);
    }

    private SharedPreferences sharedPreferences;
    // P0-1 收口：config_prefs 不再直接访问 SharedPreferences，统一走仓储（底层为 DataStore）。
    // 否则 MiHoYoBBSConstants 写入 DataStore 后，本页仍读旧 xml，会显示过期配置值。
    private ConfigRepository configRepository;
    private static final int THEME_DEFAULT = 0;

    // 添加主题深浅色常量
    private static final int THEME_VARIANT_DEFAULT = 0;
    private static final int THEME_VARIANT_LIGHT = 1;
    private static final int THEME_VARIANT_DARK = 2;

    // 配置显示文本视图
    private MaterialTextView salt6xValue;
    private MaterialTextView salt4xValue;
    private MaterialTextView lk2Value;
    private MaterialTextView k2Value;
    private MaterialTextView bbsVersionValue;
    private MaterialTextView updateTimeLocal;
    private MaterialTextView updateTime;

    // 折叠行右侧 mono 值摘要（命令台设计稿行值）所需的卡片引用
    private CollapsibleCardView dailyCard;
    private CollapsibleCardView gameDailyCard;
    private CollapsibleCardView serverCard;
    private CollapsibleCardView updateCard;
    private CollapsibleCardView cacheCard;
    private CollapsibleCardView languageCard;
    private CollapsibleCardView themeCard;
    private CollapsibleCardView backgroundCard;
    private CollapsibleCardView notificationCard;
    private CollapsibleCardView sklandCard;

    // 背景设置相关
    private SharedPreferences backgroundPreferences;

    // ActivityResult 启动器
    private ActivityResultLauncher<Intent> selectImageLauncher; // ACTION_OPEN_DOCUMENT (老设备)
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia; // Photo Picker (API33+)
    private ActivityResultLauncher<Intent> cropImageLauncher; // UCrop 结果

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // 加载布局
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 页眉版本 chip（设计稿：v2.4.0 形式）
        android.widget.TextView versionChip = view.findViewById(R.id.settings_version_chip);
        if (versionChip != null)
            versionChip.setText("v" + BuildConfig.VERSION_NAME);

        // 设置折叠按钮
        CollapsibleCardView bbsCard = view.findViewById(R.id.daily_card);
        CollapsibleCardView bbsGameCard = view.findViewById(R.id.game_daily_card);
        CollapsibleCardView serverCardView = view.findViewById(R.id.server_card);
        CollapsibleCardView bbsUtilsCard = view.findViewById(R.id.config_card);
        CollapsibleCardView updateCardView = view.findViewById(R.id.update_card);
        CollapsibleCardView cacheCardView = view.findViewById(R.id.cache_card);
        CollapsibleCardView languageCardView = view.findViewById(R.id.language_card);
        CollapsibleCardView themeCardView = view.findViewById(R.id.theme_card);
        CollapsibleCardView backgroundCardView = view.findViewById(R.id.background_card);
        CollapsibleCardView notificationCardView = view.findViewById(R.id.notification_card);
        CollapsibleCardView aboutCard = view.findViewById(R.id.about_card);
        CollapsibleCardView sklandCardView = view.findViewById(R.id.skland_card);
        dailyCard = bbsCard;
        gameDailyCard = bbsGameCard;
        serverCard = serverCardView;
        updateCard = updateCardView;
        cacheCard = cacheCardView;
        languageCard = languageCardView;
        themeCard = themeCardView;
        backgroundCard = backgroundCardView;
        notificationCard = notificationCardView;
        sklandCard = sklandCardView;

        bbsCard.setContent(R.layout.item_setting_bbs_daily);
        bbsGameCard.setContent(R.layout.item_setting_game_daily);
        serverCardView.setContent(R.layout.item_setting_server_segmented);
        bbsUtilsCard.setContent(R.layout.item_setting_bbs_utils);
        updateCardView.setContent(R.layout.item_setting_update);
        cacheCardView.setContent(R.layout.item_setting_cache);
        languageCardView.setContent(R.layout.item_setting_language);
        themeCardView.setContent(R.layout.item_setting_theme);
        backgroundCardView.setContent(R.layout.item_setting_background_picture);
        notificationCardView.setContent(R.layout.item_setting_notification);
        aboutCard.setContent(R.layout.item_setting_about);
        sklandCardView.setContent(R.layout.item_setting_skland);

        View dailyView = bbsCard.getContentLayout();
        View bbsGameView = bbsGameCard.getContentLayout();
        View serverView = serverCardView.getContentLayout();
        View utilsView = bbsUtilsCard.getContentLayout();
        View updateView = updateCardView.getContentLayout();
        View cacheView = cacheCardView.getContentLayout();
        View languageView = languageCardView.getContentLayout();
        View themeView = themeCardView.getContentLayout();
        View backgroundView = backgroundCardView.getContentLayout();
        View notificationView = notificationCardView.getContentLayout();
        View aboutView = aboutCard.getContentLayout();
        View sklandView = sklandCardView.getContentLayout();

        // 静态行值摘要（设计稿：配置信息=已配置、关于=开源许可 · 隐私政策、背景按设置）
        bbsUtilsCard.setValue(getString(R.string.config_configured));
        bbsUtilsCard.setValueColor(ContextCompat.getColor(requireContext(), com.muxiao.Venus.R.color.status_success));
        aboutCard.setValue(getString(R.string.about_value));
        backgroundCard.setValue(getString(SettingsFragment.getBackgroundImageUri(requireContext()) != null
                ? R.string.background_value_custom : R.string.background_value_default));

        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        configRepository = new ConfigRepository(requireContext());
        backgroundPreferences = requireActivity().getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化图片选择启动器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::startCropActivity);
        else
            selectImageLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Uri selectedImageUri = result.getData().getData();
                            if (selectedImageUri != null) // 启动裁剪
                                startCropActivity(selectedImageUri);
                        }
                    }
            );
        // UCrop 裁剪结果启动器
        cropImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // 获取裁剪后的图片URI
                        Uri croppedImageUri = UCrop.getOutput(result.getData());
                        if (croppedImageUri != null)
                            // 将裁剪后的图片复制到应用私有目录并保存
                            saveBackgroundImage(croppedImageUri);
                    } else if (result.getResultCode() == UCrop.RESULT_ERROR) {
                        // 处理裁剪错误
                        if (result.getData() != null) {
                            final Throwable cropError = UCrop.getError(result.getData());
                            show_error_dialog(requireContext(), getString(R.string.err_crop_failed, Objects.requireNonNull(cropError).getMessage()));
                        }
                    }
                }
        );

        // 查找所有Switch和CheckBox控件
        SwitchMaterial dailySwitchButton = dailyView.findViewById(R.id.daily_switch_button);
        SwitchMaterial gameDailySwitchButton = bbsGameView.findViewById(R.id.game_daily_switch_button);
        SwitchMaterial backgroundTaskSwitch = notificationView.findViewById(R.id.background_task_switch);

        Chip dailyCheckboxGenshin = dailyView.findViewById(R.id.daily_checkbox_genshin);
        Chip dailyCheckboxZzz = dailyView.findViewById(R.id.daily_checkbox_zzz);
        Chip dailyCheckboxSrg = dailyView.findViewById(R.id.daily_checkbox_srg);
        Chip dailyCheckboxHr3 = dailyView.findViewById(R.id.daily_checkbox_hr3);
        Chip dailyCheckboxHr2 = dailyView.findViewById(R.id.daily_checkbox_hr2);
        Chip dailyCheckboxWeiding = dailyView.findViewById(R.id.daily_checkbox_weiding);
        Chip dailyCheckboxDabieye = dailyView.findViewById(R.id.daily_checkbox_dabieye);
        Chip dailyCheckboxHna = dailyView.findViewById(R.id.daily_checkbox_hna);

        Chip gameDailyCheckboxGenshin = bbsGameView.findViewById(R.id.game_daily_checkbox_genshin);
        Chip gameDailyCheckboxZzz = bbsGameView.findViewById(R.id.game_daily_checkbox_zzz);
        Chip gameDailyCheckboxSrg = bbsGameView.findViewById(R.id.game_daily_checkbox_srg);
        Chip gameDailyCheckboxHr3 = bbsGameView.findViewById(R.id.game_daily_checkbox_hr3);
        Chip gameDailyCheckboxHr2 = bbsGameView.findViewById(R.id.game_daily_checkbox_hr2);
        Chip gameDailyCheckboxWeiding = bbsGameView.findViewById(R.id.game_daily_checkbox_weiding);

        // 查找配置显示文本视图
        salt6xValue = utilsView.findViewById(R.id.salt_6x_value);
        salt4xValue = utilsView.findViewById(R.id.salt_4x_value);
        lk2Value = utilsView.findViewById(R.id.lk2_value);
        k2Value = utilsView.findViewById(R.id.k2_value);
        bbsVersionValue = utilsView.findViewById(R.id.bbs_version_value);
        updateTimeLocal = utilsView.findViewById(R.id.update_time_local);
        updateTime = utilsView.findViewById(R.id.update_time);

        // 恢复保存的状态
        dailySwitchButton.setChecked(sharedPreferences.getBoolean(DAILY, true));
        gameDailySwitchButton.setChecked(sharedPreferences.getBoolean(GAME_DAILY, true));
        backgroundTaskSwitch.setChecked(sharedPreferences.getBoolean(BACKGROUND_TASK_ENABLED, false));
        // 折叠行值摘要初始态（设计稿：行右侧 mono 摘要）
        updateDailyCardValue(dailySwitchButton.isChecked());
        updateGameCardValue();
        updateNotificationCardValue(backgroundTaskSwitch.isChecked());

        // checkbox-pref 映射，统一恢复状态和设置监听器
        Object[][] dailyBindings = {
                {dailyCheckboxGenshin, DAILY_GENSHIN, false},
                {dailyCheckboxZzz, DAILY_ZZZ, false},
                {dailyCheckboxSrg, DAILY_SRG, false},
                {dailyCheckboxHr3, DAILY_HR3, false},
                {dailyCheckboxHr2, DAILY_HR2, false},
                {dailyCheckboxWeiding, DAILY_WEIDING, false},
                {dailyCheckboxDabieye, DAILY_DABIEYE, true},
                {dailyCheckboxHna, DAILY_HNA, false},
        };
        Object[][] gameDailyBindings = {
                {gameDailyCheckboxGenshin, GAME_DAILY_GENSHIN, false},
                {gameDailyCheckboxZzz, GAME_DAILY_ZZZ, false},
                {gameDailyCheckboxSrg, GAME_DAILY_SRG, false},
                {gameDailyCheckboxHr3, GAME_DAILY_HR3, false},
                {gameDailyCheckboxHr2, GAME_DAILY_HR2, false},
                {gameDailyCheckboxWeiding, GAME_DAILY_WEIDING, false},
        };
        for (Object[] b : dailyBindings) {
            Chip cb = (Chip) b[0];
            String key = (String) b[1];
            boolean def = (boolean) b[2];
            cb.setChecked(sharedPreferences.getBoolean(key, def));
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (blockIfTaskRunning(btn, checked)) return;
                sharedPreferences.edit().putBoolean(key, checked).apply();
            });
        }
        for (Object[] b : gameDailyBindings) {
            Chip cb = (Chip) b[0];
            String key = (String) b[1];
            boolean def = (boolean) b[2];
            cb.setChecked(sharedPreferences.getBoolean(key, def));
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (blockIfTaskRunning(btn, checked)) return;
                sharedPreferences.edit().putBoolean(key, checked).apply();
                updateGameCardValue();
            });
        }

        // 所有控件设置监听器以保存状态
        dailySwitchButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(DAILY, isChecked).apply();
            updateDailyCardValue(isChecked);
        });

        gameDailySwitchButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(GAME_DAILY, isChecked).apply();
        });

        backgroundTaskSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 检查系统通知权限
                Notification notificationUtil = new Notification(requireContext());
                if (notificationUtil.areNotificationsDisabled()) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.dialog_need_notification_permission))
                            .setMessage(getString(R.string.msg_need_notification_for_background))
                            .setPositiveButton(getString(R.string.btn_go_to_settings), (dialog, which) -> startActivity(notificationUtil.getNotificationSettingsIntent()))
                            .setNegativeButton(getString(R.string.btn_later), (dialog, which) -> backgroundTaskSwitch.setChecked(false))
                            .setOnCancelListener(dialog -> backgroundTaskSwitch.setChecked(false)).show();
                    return;
                }
            }
            sharedPreferences.edit().putBoolean(BACKGROUND_TASK_ENABLED, isChecked).apply();
            // 后台运行开启时同步开启通知，关闭时同步关闭
            sharedPreferences.edit().putBoolean(NOTIFICATION, isChecked).apply();
            updateNotificationCardValue(isChecked);
        });

        // 关于
        MaterialTextView githubLink = aboutView.findViewById(R.id.github_link);
        githubLink.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        githubLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_GITHUB_URL));
            startActivity(intent);
        });
        MaterialTextView blogLink = aboutView.findViewById(R.id.blog_link);
        blogLink.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        blogLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_BLOG_URL));
            startActivity(intent);
        });
        MaterialTextView appVersionText = aboutView.findViewById(R.id.app_version_text);
        appVersionText.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));

        // 配置更新
        MaterialButton updateConfigButton = utilsView.findViewById(R.id.update_config_button);
        updateConfigButton.setOnClickListener(v -> {
            if (HomeFragment.isTaskRunning() || ForegroundTaskService.isRunning()) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.dialog_task_running))
                        .setMessage(getString(R.string.msg_wait_task_finish_update))
                        .setPositiveButton(getString(R.string.btn_ok), null)
                        .show();
                return;
            }
            updateConfig(utilsView);
        });
        // 显示当前配置值
        displayCurrentConfigValues();

        // 服务器选择
        setupServerSelection(serverView, bbsCard, gameDailyCheckboxHr2, gameDailyCheckboxWeiding);
        // 语言选择
        setupLanguageSelection(languageView);
        // 主题选择
        setupThemeSelection(themeView);
        // 背景选择
        setupBackgroundSelection(backgroundView);
        // 自动更新设置
        SwitchMaterial autoUpdateSwitch = updateView.findViewById(R.id.auto_update_switch);
        boolean autoUpdateEnabled = sharedPreferences.getBoolean(AUTO_UPDATE_ENABLED, true);
        autoUpdateSwitch.setChecked(autoUpdateEnabled);
        updateUpdateCardValue(autoUpdateEnabled);
        autoUpdateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(AUTO_UPDATE_ENABLED, isChecked).apply();
            updateUpdateCardValue(isChecked);
        });
        // 手动检查更新按钮
        MaterialButton checkUpdateButton = updateView.findViewById(R.id.check_update_button_github);
        checkUpdateButton.setOnClickListener(v -> {
            UpdateChecker updateChecker = new UpdateChecker(requireContext());
            updateChecker.checkForUpdatesImmediately();
        });
        MaterialButton checkUpdateButton2 = updateView.findViewById(R.id.check_update_button_pan);
        checkUpdateButton2.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_pan_download))
                .setMessage(getString(R.string.msg_pan_download_hint))
                .setPositiveButton(getString(R.string.btn_go_to_download), (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_UPDATE_LANZOU_URL));
                    startActivity(intent);
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show());

        // 看图按钮（设置行形式，点击跳转）
        View imageButton = view.findViewById(R.id.image_button);
        imageButton.setOnClickListener(v -> {
            Intent intent = new Intent(requireActivity(), ImageActivity.class);
            startActivity(intent);
        });

        // 缓存管理
        MaterialTextView cacheSizeText = cacheView.findViewById(R.id.cache_size_text);
        MaterialButton clearCacheButton = cacheView.findViewById(R.id.clear_cache_button);
        // 计算并显示当前缓存大小
        calculateCacheSize(cacheSizeText);
        // 设置清理缓存按钮
        clearCacheButton.setOnClickListener(v -> clearCache(cacheSizeText));

        //森空岛设置
        SwitchMaterial sklandSwitch = sklandView.findViewById(R.id.skland_switch);
        MaterialCheckBox sklandCheckboxArknights = sklandView.findViewById(R.id.skland_checkbox_arknights);
        MaterialCheckBox sklandCheckboxEndfield = sklandView.findViewById(R.id.skland_checkbox_endfield);
        boolean sklandEnabled = sharedPreferences.getBoolean(SKLAND_ENABLED, false);
        sklandSwitch.setChecked(sklandEnabled);
        sklandCheckboxArknights.setChecked(sharedPreferences.getBoolean(SKLAND_ARKNIGHTS_ENABLED, false));
        sklandCheckboxEndfield.setChecked(sharedPreferences.getBoolean(SKLAND_ENDFIELD_ENABLED, false));
        sklandCheckboxArknights.setEnabled(sklandEnabled);
        sklandCheckboxEndfield.setEnabled(sklandEnabled);
        updateSklandCardValue(sklandEnabled);
        sklandSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(SKLAND_ENABLED, isChecked).apply();
            sklandCheckboxArknights.setEnabled(isChecked);
            sklandCheckboxEndfield.setEnabled(isChecked);
            updateSklandCardValue(isChecked);
        });
        sklandCheckboxArknights.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(SKLAND_ARKNIGHTS_ENABLED, isChecked).apply();
        });
        sklandCheckboxEndfield.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(SKLAND_ENDFIELD_ENABLED, isChecked).apply();
        });
        MaterialTextView skland_link = sklandView.findViewById(R.id.skland_link);
        skland_link.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        skland_link.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.SKLAND_LOGIN_URL));
            startActivity(intent);
        });
        MaterialTextView skland_cookie_link = sklandView.findViewById(R.id.skland_cookie_link);
        skland_cookie_link.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        skland_cookie_link.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.SKLAND_COOKIE_URL));
            startActivity(intent);
        });
        MaterialButton sklandButton = sklandView.findViewById(R.id.skland_button);
        sklandButton.setOnClickListener(v -> {
            // 获取当前保存的森空岛token值
            String currentToken = sharedPreferences.getString(SKLAND_COOKIE, "");
            // 创建一个EditText用于输入token
            TextInputEditText editText = new TextInputEditText(requireContext());
            editText.setText(currentToken);
            editText.setHint(getString(R.string.skland_content_tip));
            // 创建对话框
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_skland_content_settings))
                    .setMessage(getString(R.string.msg_skland_content_hint))
                    .setView(editText)
                    .setPositiveButton(getString(R.string.btn_save), (dialog, which) -> {
                        String newToken = Objects.requireNonNull(editText.getText()).toString().trim();
                        // 保存到SharedPreferences
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(SKLAND_COOKIE, newToken);
                        editor.apply();
                        showCustomSnackbar(view, requireContext(), getString(R.string.snack_skland_content_saved));
                    }).setNegativeButton(getString(R.string.btn_cancel), (dialog, which) -> {
                        // 用户选择取消，不做任何操作
                    }).show();
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 更新后台运行开关状态
        if (sharedPreferences != null && getView() != null) {
            SwitchMaterial backgroundTaskSwitch = getView().findViewById(R.id.background_task_switch);
            backgroundTaskSwitch.setChecked(sharedPreferences.getBoolean(BACKGROUND_TASK_ENABLED, false));
            updateNotificationCardValue(backgroundTaskSwitch.isChecked());
        }
    }

    /**
     * 启动裁剪Activity
     */
    private void startCropActivity(@NonNull Uri uri) {
        // 获取屏幕尺寸作为裁剪比例
        int screenWidth, screenHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = requireActivity().getSystemService(android.view.WindowManager.class).getCurrentWindowMetrics().getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            // 使用Resources获取屏幕尺寸，避免使用过时的Display方法
            DisplayMetrics displayMetrics = requireContext().getResources().getDisplayMetrics();
            screenWidth = displayMetrics.widthPixels;
            screenHeight = displayMetrics.heightPixels;
        }
        // 创建裁剪输出URI到临时文件
        Uri destinationUri = Uri.fromFile(new File(requireContext().getCacheDir(), "temp_cropped_background.jpg"));
        // 配置uCrop
        UCrop.Options options = new UCrop.Options();
        // 修改状态栏
        options.setStatusBarLight(true);
        options.setToolbarTitle(getString(R.string.toolbar_crop_image));
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
        Intent uCropIntent = uCrop.getIntent(requireContext());
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
            File backgroundFile = new File(requireContext().getFilesDir(), "background.jpg");
            if (backgroundFile.exists()) backgroundFile.delete();

            // 使用 tools.copyFile（确保实现基于 ContentResolver.openInputStream(...)）
            tools.copyFile(requireContext(), croppedImageUri, backgroundFile);

            // 保存背景图片文件Uri到SharedPreferences
            Uri backgroundUri = Uri.fromFile(backgroundFile);
            backgroundPreferences.edit().putString(BACKGROUND_IMAGE_URI, backgroundUri.toString()).apply();

            // 删除临时裁剪文件
            File temp = new File(requireContext().getCacheDir(), "temp_cropped_background.jpg");
            if (temp.exists())
                temp.delete();

            // 显示提示信息
            View view = getView();
            if (view != null)
                showCustomSnackbar(view, requireContext(), getString(R.string.snack_background_set));
        } catch (Exception e) {
            show_error_dialog(requireContext(), getString(R.string.err_save_background_failed, e.getMessage()));
        }
    }

    /**
     * 设置背景选择功能
     */
    private void setupBackgroundSelection(View view) {
        MaterialButton selectBackgroundButton = view.findViewById(R.id.select_background_button);
        MaterialButton clearBackgroundButton = view.findViewById(R.id.clear_background_button);
        Slider backgroundAlphaSlider = view.findViewById(R.id.background_alpha_slider);

        // 设置选择背景按钮
        selectBackgroundButton.setOnClickListener(v -> {
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

        // 设置清除背景按钮
        clearBackgroundButton.setOnClickListener(v -> {
            // 清除之前先释放权限
            String uriString = backgroundPreferences.getString(BACKGROUND_IMAGE_URI, null);
            if (uriString != null) {
                try {
                    Uri backgroundUri = Uri.parse(uriString);
                    if ("file".equals(backgroundUri.getScheme())) {
                        File backgroundFile = new File(Objects.requireNonNull(backgroundUri.getPath()));
                        if (backgroundFile.exists()) backgroundFile.delete();
                    } else {
                        requireContext().getContentResolver().delete(backgroundUri, null, null);
                    }
                } catch (Exception e) {
                    show_error_dialog(requireContext(), getString(R.string.err_delete_background_error, e.toString()));
                }
            }
            backgroundPreferences.edit().remove(BACKGROUND_IMAGE_URI).apply();
            showCustomSnackbar(view, requireContext(), getString(R.string.snack_background_cleared));
        });

        // 设置不透明度滑块
        float currentAlpha = backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.3f);
        backgroundAlphaSlider.setValue(currentAlpha * 100);
        backgroundAlphaSlider.setLabelFormatter(value -> (int) value + "%");
        backgroundAlphaSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                float alpha = value / 100.0f;
                backgroundPreferences.edit().putFloat(BACKGROUND_ALPHA, alpha).apply();
            }
        });
        backgroundAlphaSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                // 开始滑动时
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                // 滑动结束时
                showCustomSnackbar(view, requireContext(), getString(R.string.snack_opacity_set, (int) slider.getValue()));
            }
        });
    }

    /**
     * 设置服务器选择功能：使用 Material 3 分段按钮（SingleChoiceSegmentedButton），
     * 国服/国际服即时切换，无需展开卡片。
     */
    private void setupServerSelection(View view, View bbsCard, View... overseaHiddenViews) {
        ViewGroup settingsContainer = view.getRootView().findViewById(R.id.settings_content_container);
        SharedPreferences prefs = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        int serverType = prefs.getInt(SERVER_TYPE, 0);

        MaterialButtonToggleGroup segmented = view.findViewById(R.id.server_segmented);

        // 国际服隐藏米游币签到（需要stoken，Cookie登录不提供）
        if (!tools.isReducedMotionEnabled(requireContext()) && settingsContainer != null)
            TransitionManager.beginDelayedTransition(settingsContainer, new Fade());
        bbsCard.setVisibility(serverType == 0 ? View.VISIBLE : View.GONE);
        // 国际服隐藏不支持的游戏签到
        for (View v : overseaHiddenViews)
            v.setVisibility(serverType == 0 ? View.VISIBLE : View.GONE);

        // 先设初始选中（此时监听器尚未注册，不会触发回调/弹 Snackbar），
        // 再由下面的 addOnButtonCheckedListener 接管后续交互。
        segmented.setSelectionRequired(true);
        segmented.check(serverType == 0 ? R.id.server_cn : R.id.server_os);
        updateServerCardValue(serverType == 0);

        segmented.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return; // 仅处理被选中的一个
            int newType = (checkedId == R.id.server_cn) ? 0 : 1;
            prefs.edit().putInt(SERVER_TYPE, newType).apply();
            MiHoYoBBSConstants.clearOverseaCache();
            updateServerCardValue(newType == 0);
            if (!tools.isReducedMotionEnabled(requireContext()) && settingsContainer != null)
                TransitionManager.beginDelayedTransition(settingsContainer, new Fade());
            bbsCard.setVisibility(newType == 0 ? View.VISIBLE : View.GONE);
            for (View v : overseaHiddenViews)
                v.setVisibility(newType == 0 ? View.VISIBLE : View.GONE);
            showCustomSnackbar(view, requireContext(),
                    getString(newType == 0 ? R.string.snack_switched_to_cn : R.string.snack_switched_to_os));
        });
    }

    /**
     * 设置语言选择功能
     */
    private void setupLanguageSelection(View view) {
        SharedPreferences languagePreferences = requireActivity().getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedLanguage = languagePreferences.getInt(SELECTED_LANGUAGE, 0);

        AutoCompleteTextView languageDropdown = view.findViewById(R.id.language_dropdown);

        String[] languages = {
                getString(R.string.language_system),
                getString(R.string.language_zh),
                getString(R.string.language_zh_tw),
                getString(R.string.language_en)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, languages);
        languageDropdown.setAdapter(adapter);

        if (selectedLanguage >= 0 && selectedLanguage < languages.length) {
            languageDropdown.setText(languages[selectedLanguage], false);
            languageCard.setValue(languages[selectedLanguage]);
        }

        languageDropdown.setOnItemClickListener((parent, view1, position, id) -> saveAndApplyLanguage(position));
    }

    /**
     * 保存并应用语言
     *
     * @param languageId 语言ID
     */
    private void saveAndApplyLanguage(int languageId) {
        SharedPreferences languagePreferences = requireActivity().getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
        languagePreferences.edit().putInt(SELECTED_LANGUAGE, languageId).apply();
        // 重建 Activity 以应用语言：BaseActivity.attachBaseContext 已通过 LocaleHelper 读取最新语言，
        // recreate() 会重新走 attachBaseContext，等价于原 finish()+startActivity，但无需销毁整个任务栈。
        requireActivity().recreate();
    }

    /**
     * 设置主题选择功能
     */
    private void setupThemeSelection(View view) {
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedTheme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        int selectedThemeVariant = themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);

        // 七枚主题圆点：index 与 saveAndApplyTheme 的主题序号一致（0=系统动态取色，1-6=蓝绿红黄青紫）
        MaterialButton[] themeDots = {
                view.findViewById(R.id.theme_dot_system),
                view.findViewById(R.id.theme_dot_blue),
                view.findViewById(R.id.theme_dot_green),
                view.findViewById(R.id.theme_dot_red),
                view.findViewById(R.id.theme_dot_yellow),
                view.findViewById(R.id.theme_dot_cyan),
                view.findViewById(R.id.theme_dot_purple)
        };
        java.util.function.IntConsumer applyTheme = this::saveAndApplyTheme;
        updateThemeCardValue();
        for (int i = 0; i < themeDots.length; i++) {
            MaterialButton dot = themeDots[i];
            final int themeIndex = i;
            if (i == selectedTheme)
                markThemeDotSelected(dot, 0);
            dot.setOnClickListener(v -> {
                for (MaterialButton d : themeDots) d.setForeground(null);
                markThemeDotSelected(dot, 0);
                applyTheme.accept(themeIndex);
                updateThemeCardValue();
            });
        }

        // 深浅模式分段（沿用原单选语义：default/light/dark）
        MaterialButtonToggleGroup variantGroup = view.findViewById(R.id.theme_variant_group);
        int[] variantIds = {R.id.theme_variant_default, R.id.theme_variant_light, R.id.theme_variant_dark};
        if (selectedThemeVariant >= 0 && selectedThemeVariant < variantIds.length)
            variantGroup.check(variantIds[selectedThemeVariant]);
        variantGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.theme_variant_default) saveAndApplyThemeVariant(THEME_VARIANT_DEFAULT);
            else if (checkedId == R.id.theme_variant_light) saveAndApplyThemeVariant(THEME_VARIANT_LIGHT);
            else if (checkedId == R.id.theme_variant_dark) saveAndApplyThemeVariant(THEME_VARIANT_DARK);
            updateThemeCardValue();
        });
    }

    /** 主题圆点选中态：foreground 主色环（backgroundTint 会连带染色 stroke，故不用 stroke）。 */
    private void markThemeDotSelected(MaterialButton dot, int ringPx) {
        android.graphics.drawable.Drawable ring =
                androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_theme_dot_ring);
        if (ring != null)
            dot.setForeground(ring);
    }

    /**
     * 保存并应用主题
     *
     * @param themeId 主题ID
     */
    private void saveAndApplyTheme(int themeId) {
        // 保存选择的主题
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        themePreferences.edit().putInt(SELECTED_THEME, themeId).apply();
        // 显示提示信息，告知用户需要重启应用以应用主题
        View view = getView();
        if (view != null)
            showCustomSnackbar(view, requireContext(), getString(R.string.snack_theme_saved));
    }

    /**
     * 保存并应用主题深浅色变体
     *
     * @param themeVariantId 主题深浅色变体ID
     */
    private void saveAndApplyThemeVariant(int themeVariantId) {
        // 保存选择的主题深浅色变体
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        themePreferences.edit().putInt(SELECTED_THEME_VARIANT, themeVariantId).apply();

        // 立即应用深浅色模式
        int[] nightModes = {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES
        };
        String[] variantMessages = {
                getString(R.string.snack_set_follow_system),
                getString(R.string.snack_set_light_mode),
                getString(R.string.snack_set_dark_mode)
        };
        if (themeVariantId >= 0 && themeVariantId < nightModes.length) {
            AppCompatDelegate.setDefaultNightMode(nightModes[themeVariantId]);
            View view = getView();
            if (view != null)
                showCustomSnackbar(view, requireContext(), variantMessages[themeVariantId]);
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
     * @deprecated 替换为 {@link #applyAppTheme(Activity)}。保留以兼容旧调用，仅返回基础主题。
     */
    @Deprecated
    public static int getSelectedTheme() {
        return R.style.Theme_Venus;
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
     * @return 背景不透明度，范围0.0-0.8，默认为0.3
     */
    public static float getBackgroundAlpha(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        return backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.3f);
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
        String update_time = configRepository.get(UPDATE_TIME_PREF, getString(R.string.config_not_fetched));
        String update_time_Local = configRepository.get(UPDATE_TIME_LOCAL_PREF, MiHoYoBBSConstants.update_time);

        // KV 行布局：标签在布局中，此处只绑裸值（mono 右对齐）
        salt6xValue.setText(salt6x);
        salt4xValue.setText(salt4x);
        lk2Value.setText(lk2);
        k2Value.setText(k2);
        bbsVersionValue.setText(bbsVersion);
        updateTimeLocal.setText(update_time_Local);
        updateTime.setText(update_time);
    }

    /**
     * 后台从网络拉取最新 salt/版本等配置，成功则刷新显示并提示，失败提示错误。
     */
    private void updateConfig(View view) {
        new Thread(() -> {
            boolean success = MiHoYoBBSConstants.update_config_from_web(requireContext());
            requireActivity().runOnUiThread(() -> {
                if (success) {
                    displayCurrentConfigValues();
                    showCustomSnackbar(view, requireContext(), getString(R.string.snack_config_updated));
                } else {
                    showCustomSnackbar(view, requireContext(), getString(R.string.snack_config_update_failed));
                }
            });
        }).start();
    }

    /**
     * 计算并显示当前缓存大小
     *
     * @param cacheSizeText 显示缓存大小的TextView
     */
    private void calculateCacheSize(MaterialTextView cacheSizeText) {
        try (java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.execute(() -> {
                try {
                    long totalSize = 0;
                    File internalCache = requireContext().getCacheDir();
                    totalSize += getDirSizeSafe(internalCache);

                    File externalCache = requireContext().getExternalCacheDir();
                    if (externalCache != null)
                        totalSize += getDirSizeSafe(externalCache);
                    String sizeText = formatFileSize(totalSize);
                    android.app.Activity activity = getActivity();
                    if (activity != null)
                        activity.runOnUiThread(() -> {
                            cacheSizeText.setText(getString(R.string.current_cache_size_fmt, sizeText));
                            // 折叠行右侧值摘要同步（设计稿：缓存 128 MB）
                            if (cacheCard != null) cacheCard.setValue(sizeText);
                        });
                } catch (Exception e) {
                    android.app.Activity activity = getActivity();
                    if (activity != null)
                        activity.runOnUiThread(() -> cacheSizeText.setText(getString(R.string.current_cache_size_error)));
                }
            });
        }
    }

    /**
     * 清理缓存
     *
     * @param cacheSizeText 显示缓存大小的TextView
     */
    private void clearCache(MaterialTextView cacheSizeText) {
        try (java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.execute(() -> {
                boolean success = true;
                try {
                    success &= deleteDirSafe(requireContext().getCacheDir());
                    File externalCache = requireContext().getExternalCacheDir();
                    if (externalCache != null)
                        success &= deleteDirSafe(externalCache);
                } catch (Exception e) {
                    success = false;
                }
                boolean finalSuccess = success;
                android.app.Activity activity = getActivity();
                if (activity != null)
                    activity.runOnUiThread(() -> {
                        if (finalSuccess) {
                            cacheSizeText.setText(getString(R.string.current_cache_size_zero));
                            if (cacheCard != null) cacheCard.setValue("0 B");
                            showCustomSnackbar(getView(), requireContext(), getString(R.string.snack_cache_cleared));
                        } else {
                            showCustomSnackbar(getView(), requireContext(), getString(R.string.snack_cache_clear_partial_failed));
                        }
                    });
            });
        }
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
     * 检查是否有任务正在运行，如果有则弹窗提示并恢复控件状态。
     * @return true 表示已拦截（调用方应 return），false 表示可继续
     */
    private boolean blockIfTaskRunning(android.widget.CompoundButton buttonView, boolean isChecked) {
        if (HomeFragment.isTaskRunning() || ForegroundTaskService.isRunning()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_task_running))
                    .setMessage(getString(R.string.msg_wait_task_finish_settings))
                    .setPositiveButton(getString(R.string.btn_ok), null)
                    .setOnDismissListener(dialog -> buttonView.setChecked(!isChecked))
                    .show();
            return true;
        }
        return false;
    }

    /** 折叠行值摘要：positive 时用语义成功色，否则回退次要色。 */
    private void setCardValue(CollapsibleCardView card, CharSequence value, boolean positive) {
        if (card == null) return;
        card.setValue(value);
        card.setValueColor(positive
                ? ContextCompat.getColor(requireContext(), com.muxiao.Venus.R.color.status_success)
                : 0);
    }

    private void updateDailyCardValue(boolean enabled) {
        setCardValue(dailyCard, getString(enabled ? R.string.value_on : R.string.value_off), enabled);
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
        setCardValue(gameDailyCard, getString(R.string.game_enabled_fmt, enabled, defs.length), enabled > 0);
    }

    private void updateNotificationCardValue(boolean enabled) {
        setCardValue(notificationCard, getString(enabled ? R.string.value_on : R.string.value_off), enabled);
    }

    private void updateSklandCardValue(boolean enabled) {
        setCardValue(sklandCard, getString(enabled ? R.string.value_enabled : R.string.value_disabled), enabled);
    }

    private void updateUpdateCardValue(boolean auto) {
        setCardValue(updateCard, getString(auto ? R.string.update_value_auto : R.string.update_value_manual), false);
    }

    private void updateServerCardValue(boolean isCn) {
        setCardValue(serverCard, getString(isCn ? R.string.server_cn : R.string.server_os), false);
    }

    /** 主题行值：主题名 · 深浅名（设计稿：蓝色 · 浅色）。 */
    private void updateThemeCardValue() {
        if (themeCard == null) return;
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int theme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        int variant = themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);
        String[] names = {
                getString(R.string.theme_dynamic), getString(R.string.theme_blue),
                getString(R.string.theme_green), getString(R.string.theme_red), getString(R.string.theme_yellow),
                getString(R.string.theme_cyan), getString(R.string.theme_purple)
        };
        String[] variants = {
                getString(R.string.theme_follow), getString(R.string.theme_light), getString(R.string.theme_dark)
        };
        String name = names[theme >= 0 && theme < names.length ? theme : 0];
        String mode = variants[variant >= 0 && variant < variants.length ? variant : 0];
        setCardValue(themeCard, getString(R.string.theme_value_fmt, name, mode), false);
    }

}
