package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.Constants.Prefs.AUTO_UPDATE_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_ALPHA;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_IMAGE_URI;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_PREFS_NAME;
import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.BBS_VERSION_PREF;
import static com.muxiao.Venus.common.Constants.Prefs.CONFIG_PREFS_NAME;
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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.slider.Slider;
import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
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
public class SettingsFragment extends Fragment {

    {
        setEnterTransition(new android.transition.Fade(android.transition.Fade.IN).setDuration(300));
    }
    private SharedPreferences sharedPreferences;
    private SharedPreferences configPreferences;
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

        // 设置折叠按钮
        CollapsibleCardView bbsCard = view.findViewById(R.id.daily_card);
        CollapsibleCardView bbsGameCard = view.findViewById(R.id.game_daily_card);
        CollapsibleCardView serverCard = view.findViewById(R.id.server_card);
        CollapsibleCardView bbsUtilsCard = view.findViewById(R.id.config_card);
        CollapsibleCardView updateCard = view.findViewById(R.id.update_card);
        CollapsibleCardView cacheCard = view.findViewById(R.id.cache_card);
        CollapsibleCardView languageCard = view.findViewById(R.id.language_card);
        CollapsibleCardView themeCard = view.findViewById(R.id.theme_card);
        CollapsibleCardView backgroundCard = view.findViewById(R.id.background_card);
        CollapsibleCardView notificationCard = view.findViewById(R.id.notification_card);
        CollapsibleCardView aboutCard = view.findViewById(R.id.about_card);
        CollapsibleCardView sklandCard = view.findViewById(R.id.skland_card);

        bbsCard.setContent(R.layout.item_setting_bbs_daily);
        bbsGameCard.setContent(R.layout.item_setting_game_daily);
        serverCard.setContent(R.layout.item_setting_server);
        bbsUtilsCard.setContent(R.layout.item_setting_bbs_utils);
        updateCard.setContent(R.layout.item_setting_update);
        cacheCard.setContent(R.layout.item_setting_cache);
        languageCard.setContent(R.layout.item_setting_language);
        themeCard.setContent(R.layout.item_setting_theme);
        backgroundCard.setContent(R.layout.item_setting_background_picture);
        notificationCard.setContent(R.layout.item_setting_notification);
        aboutCard.setContent(R.layout.item_setting_about);
        sklandCard.setContent(R.layout.item_setting_skland);

        View dailyView = bbsCard.getContentLayout();
        View bbsGameView = bbsGameCard.getContentLayout();
        View serverView = serverCard.getContentLayout();
        View utilsView = bbsUtilsCard.getContentLayout();
        View updateView = updateCard.getContentLayout();
        View cacheView = cacheCard.getContentLayout();
        View languageView = languageCard.getContentLayout();
        View themeView = themeCard.getContentLayout();
        View backgroundView = backgroundCard.getContentLayout();
        View notificationView = notificationCard.getContentLayout();
        View aboutView = aboutCard.getContentLayout();
        View sklandView = sklandCard.getContentLayout();

        View bottomPaddingView = view.findViewById(R.id.bottom_padding_view);
        if (getActivity() instanceof MainActivity)
            ((MainActivity) getActivity()).applyBottomPadding(bottomPaddingView);

        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        configPreferences = requireActivity().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
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

        MaterialCheckBox dailyCheckboxGenshin = dailyView.findViewById(R.id.daily_checkbox_genshin);
        MaterialCheckBox dailyCheckboxZzz = dailyView.findViewById(R.id.daily_checkbox_zzz);
        MaterialCheckBox dailyCheckboxSrg = dailyView.findViewById(R.id.daily_checkbox_srg);
        MaterialCheckBox dailyCheckboxHr3 = dailyView.findViewById(R.id.daily_checkbox_hr3);
        MaterialCheckBox dailyCheckboxHr2 = dailyView.findViewById(R.id.daily_checkbox_hr2);
        MaterialCheckBox dailyCheckboxWeiding = dailyView.findViewById(R.id.daily_checkbox_weiding);
        MaterialCheckBox dailyCheckboxDabieye = dailyView.findViewById(R.id.daily_checkbox_dabieye);
        MaterialCheckBox dailyCheckboxHna = dailyView.findViewById(R.id.daily_checkbox_hna);

        MaterialCheckBox gameDailyCheckboxGenshin = bbsGameView.findViewById(R.id.game_daily_checkbox_genshin);
        MaterialCheckBox gameDailyCheckboxZzz = bbsGameView.findViewById(R.id.game_daily_checkbox_zzz);
        MaterialCheckBox gameDailyCheckboxSrg = bbsGameView.findViewById(R.id.game_daily_checkbox_srg);
        MaterialCheckBox gameDailyCheckboxHr3 = bbsGameView.findViewById(R.id.game_daily_checkbox_hr3);
        MaterialCheckBox gameDailyCheckboxHr2 = bbsGameView.findViewById(R.id.game_daily_checkbox_hr2);
        MaterialCheckBox gameDailyCheckboxWeiding = bbsGameView.findViewById(R.id.game_daily_checkbox_weiding);

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
            MaterialCheckBox cb = (MaterialCheckBox) b[0];
            String key = (String) b[1];
            boolean def = (boolean) b[2];
            cb.setChecked(sharedPreferences.getBoolean(key, def));
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (blockIfTaskRunning(btn, checked)) return;
                sharedPreferences.edit().putBoolean(key, checked).apply();
            });
        }
        for (Object[] b : gameDailyBindings) {
            MaterialCheckBox cb = (MaterialCheckBox) b[0];
            String key = (String) b[1];
            boolean def = (boolean) b[2];
            cb.setChecked(sharedPreferences.getBoolean(key, def));
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (blockIfTaskRunning(btn, checked)) return;
                sharedPreferences.edit().putBoolean(key, checked).apply();
            });
        }

        // 所有控件设置监听器以保存状态
        dailySwitchButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(DAILY, isChecked).apply();
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
        autoUpdateSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean(AUTO_UPDATE_ENABLED, isChecked).apply());
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

        // 看图按钮
        MaterialButton imageButton = view.findViewById(R.id.image_button);
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
        sklandSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (blockIfTaskRunning(buttonView, isChecked)) return;
            sharedPreferences.edit().putBoolean(SKLAND_ENABLED, isChecked).apply();
            sklandCheckboxArknights.setEnabled(isChecked);
            sklandCheckboxEndfield.setEnabled(isChecked);
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
     * 设置服务器选择功能
     */
    private void setupServerSelection(View view, CollapsibleCardView bbsCard, View... overseaHiddenViews) {
        SharedPreferences prefs = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        int serverType = prefs.getInt(SERVER_TYPE, 0);

        MaterialRadioButton serverCn = view.findViewById(R.id.server_cn);
        MaterialRadioButton serverOs = view.findViewById(R.id.server_os);

        // 国际服隐藏米游币签到（需要stoken，Cookie登录不提供）
        bbsCard.setVisibility(serverType == 0 ? View.VISIBLE : View.GONE);
        // 国际服隐藏不支持的游戏签到
        for (View v : overseaHiddenViews)
            v.setVisibility(serverType == 0 ? View.VISIBLE : View.GONE);

        if (serverType == 0) {
            serverCn.setChecked(true);
        } else {
            serverOs.setChecked(true);
        }

        serverCn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                prefs.edit().putInt(SERVER_TYPE, 0).apply();
                MiHoYoBBSConstants.clearOverseaCache();
                bbsCard.setVisibility(View.VISIBLE);
                for (View v : overseaHiddenViews)
                    v.setVisibility(View.VISIBLE);
                showCustomSnackbar(view, requireContext(), getString(R.string.snack_switched_to_cn));
            }
        });

        serverOs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                prefs.edit().putInt(SERVER_TYPE, 1).apply();
                MiHoYoBBSConstants.clearOverseaCache();
                bbsCard.setVisibility(View.GONE);
                for (View v : overseaHiddenViews)
                    v.setVisibility(View.GONE);
                showCustomSnackbar(view, requireContext(), getString(R.string.snack_switched_to_os));
            }
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

        if (selectedLanguage >= 0 && selectedLanguage < languages.length)
            languageDropdown.setText(languages[selectedLanguage], false);

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
        // 重启Activity以应用语言
        Intent intent = requireActivity().getIntent();
        requireActivity().finish();
        startActivity(intent);
    }

    /**
     * 设置主题选择功能
     */
    private void setupThemeSelection(View view) {
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedTheme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        int selectedThemeVariant = themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);

        AutoCompleteTextView themeDropdown = view.findViewById(R.id.theme_dropdown);

        String[] themes = {
                getString(R.string.theme_system),
                getString(R.string.theme_blue),
                getString(R.string.theme_green),
                getString(R.string.theme_red),
                getString(R.string.theme_yellow),
                getString(R.string.theme_light_name),
                getString(R.string.theme_dark_name)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, themes);
        themeDropdown.setAdapter(adapter);

        if (selectedTheme >= 0 && selectedTheme < themes.length)
            themeDropdown.setText(themes[selectedTheme], false);

        themeDropdown.setOnItemClickListener((parent, view1, position, id) ->
                saveAndApplyTheme(position));

        // 查找所有主题变体单选按钮
        MaterialRadioButton themeVariantDefault = view.findViewById(R.id.theme_variant_default);
        MaterialRadioButton themeVariantLight = view.findViewById(R.id.theme_variant_light);
        MaterialRadioButton themeVariantDark = view.findViewById(R.id.theme_variant_dark);

        // 根据保存的设置选中对应的深浅色模式
        MaterialRadioButton[] variantButtons = {themeVariantDefault, themeVariantLight, themeVariantDark};
        if (selectedThemeVariant >= 0 && selectedThemeVariant < variantButtons.length)
            variantButtons[selectedThemeVariant].setChecked(true);

        // 为每个主题变体单选按钮设置监听器
        themeVariantDefault.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyThemeVariant(THEME_VARIANT_DEFAULT);
        });
        themeVariantLight.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyThemeVariant(THEME_VARIANT_LIGHT);
        });
        themeVariantDark.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyThemeVariant(THEME_VARIANT_DARK);
        });
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
     * 获取应该应用的主题
     *
     * @param context 上下文
     * @return 主题资源ID
     */
    public static int getSelectedTheme(Context context) {
        SharedPreferences themePreferences = context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedTheme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);

        int[] themeStyles = {
                R.style.Theme_Venus,        // DEFAULT
                R.style.Theme_Venus_Blue,   // BLUE
                R.style.Theme_Venus_Green,  // GREEN
                R.style.Theme_Venus_Red,    // RED
                R.style.Theme_Venus_Yellow, // YELLOW
                R.style.Theme_Venus_Light,  // LIGHT
                R.style.Theme_Venus_Dark,   // DARK
        };
        if (selectedTheme >= 0 && selectedTheme < themeStyles.length)
            return themeStyles[selectedTheme];
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
        String salt6x = configPreferences.getString(SALT_6X_PREF, MiHoYoBBSConstants.SALT_6X_final);
        String salt4x = configPreferences.getString(SALT_4X_PREF, MiHoYoBBSConstants.SALT_4X_final);
        String lk2 = configPreferences.getString(LK2_PREF, MiHoYoBBSConstants.LK2_final);
        String k2 = configPreferences.getString(K2_PREF, MiHoYoBBSConstants.K2_final);
        String bbsVersion = configPreferences.getString(BBS_VERSION_PREF, MiHoYoBBSConstants.bbs_version_final);
        String update_time = configPreferences.getString(UPDATE_TIME_PREF, getString(R.string.config_not_fetched));
        String update_time_Local = configPreferences.getString(UPDATE_TIME_LOCAL_PREF, MiHoYoBBSConstants.update_time);

        salt6xValue.setText(getString(R.string.salt_6x_value_fmt, salt6x));
        salt4xValue.setText(getString(R.string.salt_4x_value_fmt, salt4x));
        lk2Value.setText(getString(R.string.lk2_value_fmt, lk2));
        k2Value.setText(getString(R.string.k2_value_fmt, k2));
        bbsVersionValue.setText(getString(R.string.miyoushe_version_fmt, bbsVersion));
        updateTimeLocal.setText(getString(R.string.local_config_update_time_fmt, update_time_Local));
        updateTime.setText(getString(R.string.cloud_config_update_time_fmt, update_time));
    }

    /**
     * 更新配置信息
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
                        activity.runOnUiThread(() -> cacheSizeText.setText(getString(R.string.current_cache_size_fmt, sizeText)));
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

}
