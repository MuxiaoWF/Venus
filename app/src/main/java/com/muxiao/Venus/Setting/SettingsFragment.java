package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.copyToClipboard;
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
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.android.material.slider.Slider;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class SettingsFragment extends Fragment {
    private SharedPreferences sharedPreferences;
    private SharedPreferences configPreferences;
    private static final String PREFS_NAME = "settings_prefs";
    private static final String CONFIG_PREFS_NAME = "config_prefs";
    private static final String UPDATE_PREFS_NAME = "update_prefs";
    private static final String AUTO_UPDATE_ENABLED = "auto_update_enabled";
    private static final String THEME_PREFS_NAME = "theme_prefs";
    private static final String BACKGROUND_PREFS_NAME = "background_prefs";
    private static final int THEME_DEFAULT = 0;
    private static final int THEME_BLUE = 1;
    private static final int THEME_GREEN = 2;
    private static final int THEME_RED = 3;
    private static final int THEME_YELLOW = 4;
    private static final int THEME_LIGHT = 5;
    private static final int THEME_DARK = 6;
    private static final String SELECTED_THEME = "selected_theme";

    // 添加主题深浅色常量
    private static final int THEME_VARIANT_DEFAULT = 0;
    private static final int THEME_VARIANT_LIGHT = 1;
    private static final int THEME_VARIANT_DARK = 2;
    private static final String SELECTED_THEME_VARIANT = "selected_theme_variant";

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
    private static final String BACKGROUND_IMAGE_URI = "background_image_uri";
    private static final String BACKGROUND_ALPHA = "background_alpha";
    // ActivityResult 启动器
    private ActivityResultLauncher<Intent> selectImageLauncher; // ACTION_OPEN_DOCUMENT (老设备)
    private ActivityResultLauncher<PickVisualMediaRequest>  pickMedia; // Photo Picker (API33+)
    private ActivityResultLauncher<Intent> cropImageLauncher; // UCrop 结果

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // 加载布局
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 设置折叠按钮
        CollapsibleCardView bbsCard = view.findViewById(R.id.daily_card);
        CollapsibleCardView bbsGameCard = view.findViewById(R.id.game_daily_card);
        CollapsibleCardView bbsUtilsCard = view.findViewById(R.id.config_card);
        CollapsibleCardView updateCard = view.findViewById(R.id.update_card);
        CollapsibleCardView cacheCard = view.findViewById(R.id.cache_card);
        CollapsibleCardView themeCard = view.findViewById(R.id.theme_card);
        CollapsibleCardView backgroundCard = view.findViewById(R.id.background_card);
        CollapsibleCardView notificationCard = view.findViewById(R.id.notification_card);
        CollapsibleCardView aboutCard = view.findViewById(R.id.about_card);

        ViewGroup dailyView = bbsCard.getContentLayout();
        ViewGroup bbsGameView = bbsGameCard.getContentLayout();
        ViewGroup utilsView = bbsUtilsCard.getContentLayout();
        ViewGroup updateView = updateCard.getContentLayout();
        ViewGroup cacheView = cacheCard.getContentLayout();
        ViewGroup themeView = themeCard.getContentLayout();
        ViewGroup backgroundView = backgroundCard.getContentLayout();
        ViewGroup notificationView = notificationCard.getContentLayout();
        ViewGroup aboutView = aboutCard.getContentLayout();

        bbsCard.setContent(inflater.inflate(R.layout.item_setting_bbs_daily, dailyView, false));
        bbsGameCard.setContent(inflater.inflate(R.layout.item_setting_game_daily, bbsGameView, false));
        bbsUtilsCard.setContent(inflater.inflate(R.layout.item_setting_bbs_utils, utilsView, false));
        updateCard.setContent(inflater.inflate(R.layout.item_setting_update, updateView, false));
        cacheCard.setContent(inflater.inflate(R.layout.item_setting_cache, cacheView, false));
        themeCard.setContent(inflater.inflate(R.layout.item_setting_theme, themeView, false));
        backgroundCard.setContent(inflater.inflate(R.layout.item_setting_background_picture, backgroundView, false));
        notificationCard.setContent(inflater.inflate(R.layout.item_setting_notification, notificationView, false));
        aboutCard.setContent(inflater.inflate(R.layout.item_setting_about, aboutView, false));

        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        configPreferences = requireActivity().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        backgroundPreferences = requireActivity().getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化Activity结果启动器
        initActivityLauncher();

        // 查找所有Switch和CheckBox控件
        SwitchMaterial dailySwitchButton = dailyView.findViewById(R.id.daily_switch_button);
        SwitchMaterial gameDailySwitchButton = bbsGameView.findViewById(R.id.game_daily_switch_button);
        SwitchMaterial notificationSwitch = notificationCard.getContentLayout().findViewById(R.id.notification_switch);

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
        dailySwitchButton.setChecked(sharedPreferences.getBoolean("daily_switch_button", true));
        gameDailySwitchButton.setChecked(sharedPreferences.getBoolean("game_daily_switch_button", true));
        notificationSwitch.setChecked(sharedPreferences.getBoolean("notification_switch", false));

        dailyCheckboxGenshin.setChecked(sharedPreferences.getBoolean("daily_checkbox_genshin", false));
        dailyCheckboxZzz.setChecked(sharedPreferences.getBoolean("daily_checkbox_zzz", false));
        dailyCheckboxSrg.setChecked(sharedPreferences.getBoolean("daily_checkbox_srg", false));
        dailyCheckboxHr3.setChecked(sharedPreferences.getBoolean("daily_checkbox_hr3", false));
        dailyCheckboxHr2.setChecked(sharedPreferences.getBoolean("daily_checkbox_hr2", false));
        dailyCheckboxWeiding.setChecked(sharedPreferences.getBoolean("daily_checkbox_weiding", false));
        dailyCheckboxDabieye.setChecked(sharedPreferences.getBoolean("daily_checkbox_dabieye", true));
        dailyCheckboxHna.setChecked(sharedPreferences.getBoolean("daily_checkbox_hna", false));

        gameDailyCheckboxGenshin.setChecked(sharedPreferences.getBoolean("game_daily_checkbox_genshin", false));
        gameDailyCheckboxZzz.setChecked(sharedPreferences.getBoolean("game_daily_checkbox_zzz", false));
        gameDailyCheckboxSrg.setChecked(sharedPreferences.getBoolean("game_daily_checkbox_srg", false));
        gameDailyCheckboxHr3.setChecked(sharedPreferences.getBoolean("game_daily_checkbox_hr3", false));
        gameDailyCheckboxHr2.setChecked(sharedPreferences.getBoolean("game_daily_checkbox_hr2", false));
        gameDailyCheckboxWeiding.setChecked(sharedPreferences.getBoolean("game_daily_checkbox_weiding", false));

        // 所有控件设置监听器以保存状态
        dailySwitchButton.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_switch_button", isChecked).apply());

        gameDailySwitchButton.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_switch_button", isChecked).apply());

        notificationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 用户开启了通知开关，检查实际的通知权限
                Notification notificationUtil = new Notification(requireContext());
                if (!notificationUtil.areNotificationsEnabled()) {
                    // 实际权限未开启，提示用户去设置
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("需要通知权限")
                            .setMessage("您已开启通知功能，但系统通知权限尚未开启。是否前往系统设置页面开启权限？")
                            .setPositiveButton("去设置", (dialog, which) -> notificationUtil.goToNotificationSettings())
                            .setNegativeButton("稍后再说", (dialog, which) -> {
                                // 用户选择稍后处理，将开关状态设为关闭
                                notificationSwitch.setChecked(false);
                            }).setOnCancelListener(dialog -> {
                                // 用户取消对话框，也将开关状态设为关闭
                                notificationSwitch.setChecked(false);
                            })
                            .show();
                    // 不保存设置，保持开关关闭状态
                    return;
                }
            }
            sharedPreferences.edit().putBoolean("notification_switch", isChecked).apply();
        });

        dailyCheckboxGenshin.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_genshin", isChecked).apply());
        dailyCheckboxZzz.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_zzz", isChecked).apply());
        dailyCheckboxSrg.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_srg", isChecked).apply());
        dailyCheckboxHr3.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_hr3", isChecked).apply());
        dailyCheckboxHr2.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_hr2", isChecked).apply());
        dailyCheckboxWeiding.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_weiding", isChecked).apply());
        dailyCheckboxDabieye.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_dabieye", isChecked).apply());
        dailyCheckboxHna.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("daily_checkbox_hna", isChecked).apply());

        gameDailyCheckboxGenshin.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_checkbox_genshin", isChecked).apply());
        gameDailyCheckboxZzz.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_checkbox_zzz", isChecked).apply());
        gameDailyCheckboxSrg.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_checkbox_srg", isChecked).apply());
        gameDailyCheckboxHr3.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_checkbox_hr3", isChecked).apply());
        gameDailyCheckboxHr2.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_checkbox_hr2", isChecked).apply());
        gameDailyCheckboxWeiding.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean("game_daily_checkbox_weiding", isChecked).apply());

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
        updateConfigButton.setOnClickListener(v -> updateConfig(utilsView));
        // 显示当前配置值
        displayCurrentConfigValues();

        // 主题选择
        setupThemeSelection(themeView);
        // 背景选择
        setupBackgroundSelection(backgroundView);
        // 自动更新设置
        SwitchMaterial autoUpdateSwitch = updateView.findViewById(R.id.auto_update_switch);
        SharedPreferences updatePreferences = requireActivity().getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoUpdateEnabled = updatePreferences.getBoolean(AUTO_UPDATE_ENABLED, true);
        autoUpdateSwitch.setChecked(autoUpdateEnabled);
        autoUpdateSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                updatePreferences.edit().putBoolean(AUTO_UPDATE_ENABLED, isChecked).apply());

        // 手动检查更新按钮
        MaterialButton checkUpdateButton = updateView.findViewById(R.id.check_update_button_github);
        checkUpdateButton.setOnClickListener(v -> {
            UpdateChecker updateChecker = new UpdateChecker(requireContext());
            updateChecker.checkForUpdatesImmediately();
        });
        MaterialButton checkUpdateButton2 = updateView.findViewById(R.id.check_update_button_pan);
        checkUpdateButton2.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.Urls.MUXIAO_MINE_UPDATE_LANZOU_URL));
            startActivity(intent);
            copyToClipboard(view,requireContext(),"mxwf"); // 提取码
        });

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
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 更新通知开关状态
        if (sharedPreferences != null && getView() != null) {
            SwitchMaterial notificationSwitch = getView().findViewById(R.id.notification_switch);
            boolean notificationEnabled = sharedPreferences.getBoolean("notification_switch", false);
            notificationSwitch.setChecked(notificationEnabled);
        }
    }

    /**
     * 初始化Activity结果启动器
     */
    private void initActivityLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::startCropActivity);
        } else {
            selectImageLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Uri selectedImageUri = result.getData().getData();
                            if (selectedImageUri != null) {
                                // 启动裁剪
                                startCropActivity(selectedImageUri);
                            }
                        }
                    }
            );
        }
        // UCrop 裁剪结果启动器
        cropImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // 获取裁剪后的图片URI
                        Uri croppedImageUri = UCrop.getOutput(result.getData());
                        if (croppedImageUri != null) {
                            // 将裁剪后的图片复制到应用私有目录并保存
                            saveBackgroundImage(croppedImageUri);
                        }
                    } else if (result.getResultCode() == UCrop.RESULT_ERROR) {
                        // 处理裁剪错误
                        if (result.getData() != null) {
                            final Throwable cropError = UCrop.getError(result.getData());
                            show_error_dialog(requireContext(), "图片裁剪失败: " + Objects.requireNonNull(cropError).getMessage());
                        }
                    }
                }
        );
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
        options.setToolbarTitle("裁剪图片");
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
            if (temp.exists()) {
                try {
                    temp.delete();
                } catch (Exception ignored) {}
            }

            // 显示提示信息
            View view = getView();
            if (view != null) showCustomSnackbar(view, requireContext(), "背景图片已设置，将在下次启动时生效");
        } catch (Exception e) {
            show_error_dialog(requireContext(), "保存背景图片失败: " + e.getMessage());
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
                        try { requireContext().getContentResolver().delete(backgroundUri, null, null); } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    show_error_dialog(requireContext(),"删除背景图片出错："+ e);
                }
            }
            backgroundPreferences.edit().remove(BACKGROUND_IMAGE_URI).apply();
            showCustomSnackbar(view, requireContext(), "背景图片已清除，将在下次启动时生效");
        });

        // 设置不透明度滑块
        float currentAlpha = backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.3f);
        backgroundAlphaSlider.setValue(currentAlpha * 100);
        backgroundAlphaSlider.addOnChangeListener((slider, value, fromUser) -> {
            float alpha = value / 100.0f;
            backgroundPreferences.edit().putFloat(BACKGROUND_ALPHA, alpha).apply();
            showCustomSnackbar(view, requireContext(), "不透明度已设置为 " + (int) value + "%，将在下次启动时生效");
        });
    }

    /**
     * 设置主题选择功能
     */
    private void setupThemeSelection(View view) {
        // 获取主题SharedPreferences
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedTheme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        int selectedThemeVariant = themePreferences.getInt(SELECTED_THEME_VARIANT, THEME_VARIANT_DEFAULT);

        // 查找所有主题单选按钮
        MaterialRadioButton themeDefault = view.findViewById(R.id.theme_default);
        MaterialRadioButton themeBlue = view.findViewById(R.id.theme_blue);
        MaterialRadioButton themeGreen = view.findViewById(R.id.theme_green);
        MaterialRadioButton themeRed = view.findViewById(R.id.theme_red);
        MaterialRadioButton themeYellow = view.findViewById(R.id.theme_yellow);
        MaterialRadioButton themeLight = view.findViewById(R.id.theme_light);
        MaterialRadioButton themeDark = view.findViewById(R.id.theme_dark);

        // 查找所有主题变体单选按钮
        MaterialRadioButton themeVariantDefault = view.findViewById(R.id.theme_variant_default);
        MaterialRadioButton themeVariantLight = view.findViewById(R.id.theme_variant_light);
        MaterialRadioButton themeVariantDark = view.findViewById(R.id.theme_variant_dark);

        // 根据保存的设置选中对应的主题
        switch (selectedTheme) {
            case THEME_DEFAULT:
                themeDefault.setChecked(true);
                break;
            case THEME_BLUE:
                themeBlue.setChecked(true);
                break;
            case THEME_GREEN:
                themeGreen.setChecked(true);
                break;
            case THEME_RED:
                themeRed.setChecked(true);
                break;
            case THEME_YELLOW:
                themeYellow.setChecked(true);
                break;
            case THEME_LIGHT:
                themeLight.setChecked(true);
                break;
            case THEME_DARK:
                themeDark.setChecked(true);
                break;
        }

        // 根据保存的设置选中对应的深浅色模式
        switch (selectedThemeVariant) {
            case THEME_VARIANT_DEFAULT:
                themeVariantDefault.setChecked(true);
                break;
            case THEME_VARIANT_LIGHT:
                themeVariantLight.setChecked(true);
                break;
            case THEME_VARIANT_DARK:
                themeVariantDark.setChecked(true);
                break;
        }

        // 为每个单选按钮设置监听器
        themeDefault.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_DEFAULT);
        });
        themeBlue.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_BLUE);
        });
        themeGreen.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_GREEN);
        });
        themeRed.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_RED);
        });
        themeYellow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_YELLOW);
        });
        themeLight.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_LIGHT);
        });
        themeDark.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) saveAndApplyTheme(THEME_DARK);
        });

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
            showCustomSnackbar(view, requireContext(), "主题已保存，请重启应用以应用新主题");
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
        switch (themeVariantId) {
            case THEME_VARIANT_DEFAULT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case THEME_VARIANT_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_VARIANT_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }

        // 显示提示信息
        View view = getView();
        if (view != null) {
            String message = "";
            switch (themeVariantId) {
                case THEME_VARIANT_DEFAULT:
                    message = "已设置为跟随系统深浅色模式";
                    break;
                case THEME_VARIANT_LIGHT:
                    message = "已设置为浅色模式";
                    break;
                case THEME_VARIANT_DARK:
                    message = "已设置为深色模式";
                    break;
            }
            showCustomSnackbar(view, requireContext(), message);
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

        switch (selectedTheme) {
            case THEME_BLUE:
                return R.style.Theme_Venus_Blue;
            case THEME_GREEN:
                return R.style.Theme_Venus_Green;
            case THEME_RED:
                return R.style.Theme_Venus_Red;
            case THEME_YELLOW:
                return R.style.Theme_Venus_Yellow;
            case THEME_LIGHT:
                return R.style.Theme_Venus_Light;
            case THEME_DARK:
                return R.style.Theme_Venus_Dark;
            case THEME_DEFAULT:
            default:
                return R.style.Theme_Venus;
        }
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
        switch (themeVariant) {
            case THEME_VARIANT_DEFAULT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case THEME_VARIANT_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_VARIANT_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
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
                    if (backgroundFile.exists()) {
                        return backgroundUri;
                    }
                }
            } catch (Exception e) {
                // 如果解析Uri失败，直接使用文件路径
                File backgroundFile = new File(uriString);
                if (backgroundFile.exists()) {
                    return Uri.fromFile(backgroundFile);
                }
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
        String salt6x = configPreferences.getString("SALT_6X", MiHoYoBBSConstants.SALT_6X_final);
        String salt4x = configPreferences.getString("SALT_4X", MiHoYoBBSConstants.SALT_4X_final);
        String lk2 = configPreferences.getString("LK2", MiHoYoBBSConstants.LK2_final);
        String k2 = configPreferences.getString("K2", MiHoYoBBSConstants.K2_final);
        String bbsVersion = configPreferences.getString("bbs_version", MiHoYoBBSConstants.bbs_version_final);
        String update_time = configPreferences.getString("update_time", "2025.09");
        String update_time_Local = configPreferences.getString("update_time_local", "2025.09");

        salt6xValue.setText(new StringBuilder("SALT_6X: " + salt6x));
        salt4xValue.setText(new StringBuilder("SALT_4X: " + salt4x));
        lk2Value.setText(new StringBuilder("LK2: " + lk2));
        k2Value.setText(new StringBuilder("K2: " + k2));
        bbsVersionValue.setText(new StringBuilder("BBS版本: " + bbsVersion));
        updateTimeLocal.setText(new StringBuilder("本机参数更新时间: " + update_time_Local));
        updateTime.setText(new StringBuilder("云端参数更新时间: " + update_time));
    }

    /**
     * 更新配置信息
     */
    private void updateConfig(View view) {
        new Thread(() -> {
            try {
                // 发送GET请求获取配置信息
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Venus/1.0");
                String response = tools.sendGetRequest(Constants.Urls.MUXIAO_MINE_UPDATE_SALT_URL, headers, null);
                if (!response.isEmpty()) {
                    // 解析响应数据
                    JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                    SharedPreferences configPrefs = requireActivity().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = configPrefs.edit();
                    // 使用解析的数据，如果不存在则使用默认值
                    editor.putString("SALT_6X", getDataOrDefault(data, "SALT_6X",  MiHoYoBBSConstants.SALT_6X_final));
                    editor.putString("SALT_4X", getDataOrDefault(data, "SALT_4X",  MiHoYoBBSConstants.SALT_4X_final));
                    editor.putString("LK2", getDataOrDefault(data, "LK2", MiHoYoBBSConstants.LK2_final));
                    editor.putString("K2", getDataOrDefault(data, "K2", MiHoYoBBSConstants.K2_final));
                    editor.putString("bbs_version", getDataOrDefault(data, "bbs_version", MiHoYoBBSConstants.bbs_version_final));
                    editor.putString("update_time", getDataOrDefault(data, "update_time", "2025-09"));
                    editor.putString("update_time_local", getDataOrDefault(data, "update_time_local", "2025-09"));
                    editor.apply();
                    requireActivity().runOnUiThread(() -> {
                        displayCurrentConfigValues();
                        showCustomSnackbar(view, requireContext(), "配置更新成功");
                    });
                } else {
                    requireActivity().runOnUiThread(() ->
                            showCustomSnackbar(view, requireContext(), "配置更新失败：无响应数据"));
                }
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        showCustomSnackbar(view, requireContext(), "配置更新失败：" + e.getMessage()));
            }
        }).start();
    }

    /**
     * 计算并显示当前缓存大小
     *
     * @param cacheSizeText 显示缓存大小的TextView
     */
    private void calculateCacheSize(MaterialTextView cacheSizeText) {
        new Thread(() -> {
            try {
                long cacheSize = getDirSize(requireContext().getCacheDir()) + getDirSize(requireContext().getExternalCacheDir());
                String sizeText = formatFileSize(cacheSize);
                requireActivity().runOnUiThread(() ->
                        cacheSizeText.setText(new StringBuilder("当前缓存大小: " + sizeText)));
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        cacheSizeText.setText("当前缓存大小: 计算失败"));
            }
        }).start();
    }

    /**
     * 获取目录大小
     *
     * @param dir 目录
     * @return 目录大小（字节）
     */
    private long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        if (dir.isFile()) return dir.length();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files)
                if (file.isFile()) size += file.length();
                else size += getDirSize(file);
        }
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
     * 清理缓存
     *
     * @param cacheSizeText 显示缓存大小的TextView
     */
    private void clearCache(MaterialTextView cacheSizeText) {
        new Thread(() -> {
            try {
                // 清理内部缓存
                deleteDir(requireContext().getCacheDir());
                // 清理外部缓存
                File externalCacheDir = requireContext().getExternalCacheDir();
                if (externalCacheDir != null) deleteDir(externalCacheDir);
                requireActivity().runOnUiThread(() -> {
                    cacheSizeText.setText("当前缓存大小: 0 B");
                    showCustomSnackbar(getView(), requireContext(), "缓存清理完成");
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        showCustomSnackbar(getView(), requireContext(), "缓存清理失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 删除目录及其内容
     *
     * @param dir 目录
     * @return 是否删除成功
     */
    private boolean deleteDir(File dir) {
        if (dir == null || !dir.exists()) return true;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null)
                for (File file : files)
                    if (!deleteDir(file)) return false;
        }
        return dir.delete();
    }

    // 上面方法的辅助方法
    private String getDataOrDefault(JsonObject data, String key, String defaultValue) {
        if (data != null && data.has(key) && !data.get(key).isJsonNull())
            return data.get(key).getAsString();
        return defaultValue;
    }
}
