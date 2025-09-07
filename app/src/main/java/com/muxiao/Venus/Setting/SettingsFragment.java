package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.muxiao.Venus.common.tools;

import java.util.HashMap;
import java.util.Map;

public class SettingsFragment extends Fragment {
    private SharedPreferences sharedPreferences;
    private SharedPreferences configPreferences;
    private static final String PREFS_NAME = "settings_prefs";
    private static final String CONFIG_PREFS_NAME = "config_prefs";
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

    // 配置显示文本视图
    private MaterialTextView salt6xValue;
    private MaterialTextView salt4xValue;
    private MaterialTextView lk2Value;
    private MaterialTextView k2Value;
    private MaterialTextView bbsVersionValue;
    private MaterialTextView updateTimeLocal;
    private MaterialTextView updateTime;

    private boolean bbs_toggle = false;
    private boolean daily_toggle = false;
    private boolean theme_toggle = false;
    private boolean background_toggle = false;
    private boolean config_toggle = false;
    
    // 背景设置相关
    private SharedPreferences backgroundPreferences;
    private static final String BACKGROUND_IMAGE_URI = "background_image_uri";
    private static final String BACKGROUND_ALPHA = "background_alpha";

    // 用于选择图片的Activity结果启动器
    private ActivityResultLauncher<Intent> selectImageLauncher;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        configPreferences = requireActivity().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
        backgroundPreferences = requireActivity().getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化Activity结果启动器
        initActivityLauncher();

        // 查找所有Switch和CheckBox控件
        SwitchMaterial dailySwitchButton = view.findViewById(R.id.daily_switch_button);
        SwitchMaterial gameDailySwitchButton = view.findViewById(R.id.game_daily_switch_button);

        MaterialCheckBox dailyCheckboxGenshin = view.findViewById(R.id.daily_checkbox_genshin);
        MaterialCheckBox dailyCheckboxZzz = view.findViewById(R.id.daily_checkbox_zzz);
        MaterialCheckBox dailyCheckboxSrg = view.findViewById(R.id.daily_checkbox_srg);
        MaterialCheckBox dailyCheckboxHr3 = view.findViewById(R.id.daily_checkbox_hr3);
        MaterialCheckBox dailyCheckboxHr2 = view.findViewById(R.id.daily_checkbox_hr2);
        MaterialCheckBox dailyCheckboxWeiding = view.findViewById(R.id.daily_checkbox_weiding);
        MaterialCheckBox dailyCheckboxDabieye = view.findViewById(R.id.daily_checkbox_dabieye);
        MaterialCheckBox dailyCheckboxHna = view.findViewById(R.id.daily_checkbox_hna);

        MaterialCheckBox gameDailyCheckboxGenshin = view.findViewById(R.id.game_daily_checkbox_genshin);
        MaterialCheckBox gameDailyCheckboxZzz = view.findViewById(R.id.game_daily_checkbox_zzz);
        MaterialCheckBox gameDailyCheckboxSrg = view.findViewById(R.id.game_daily_checkbox_srg);
        MaterialCheckBox gameDailyCheckboxHr3 = view.findViewById(R.id.game_daily_checkbox_hr3);
        MaterialCheckBox gameDailyCheckboxHr2 = view.findViewById(R.id.game_daily_checkbox_hr2);
        MaterialCheckBox gameDailyCheckboxWeiding = view.findViewById(R.id.game_daily_checkbox_weiding);

        // 查找配置显示文本视图
        salt6xValue = view.findViewById(R.id.salt_6x_value);
        salt4xValue = view.findViewById(R.id.salt_4x_value);
        lk2Value = view.findViewById(R.id.lk2_value);
        k2Value = view.findViewById(R.id.k2_value);
        bbsVersionValue = view.findViewById(R.id.bbs_version_value);
        updateTimeLocal = view.findViewById(R.id.update_time_local);
        updateTime = view.findViewById(R.id.update_time);

        // 恢复保存的状态
        dailySwitchButton.setChecked(sharedPreferences.getBoolean("daily_switch_button", true));
        gameDailySwitchButton.setChecked(sharedPreferences.getBoolean("game_daily_switch_button", true));

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

        MaterialTextView githubLink = view.findViewById(R.id.github_link);
        githubLink.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        githubLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MuxiaoWF/Venus"));
            startActivity(intent);
        });

        MaterialTextView blogLink = view.findViewById(R.id.blog_link);
        blogLink.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        blogLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://muxiaowf.dpdns.org/"));
            startActivity(intent);
        });

        // 添加配置更新按钮的点击事件
        MaterialButton updateConfigButton = view.findViewById(R.id.update_config_button);
        updateConfigButton.setOnClickListener(v -> updateConfig(view));

        // 显示当前配置值
        displayCurrentConfigValues();

        MaterialButton daily_toggle_button = view.findViewById(R.id.daily_toggle_button);
        View daily_content_layout = view.findViewById(R.id.daily_content_layout);
        daily_toggle_button.setOnClickListener(v -> {
            daily_toggle = !daily_toggle;
            if (daily_toggle)
                daily_content_layout.setVisibility(View.VISIBLE);
            else
                daily_content_layout.setVisibility(View.GONE);
        });

        MaterialButton bbs_toggle_button = view.findViewById(R.id.bbs_toggle_button);
        View bbs_content_layout = view.findViewById(R.id.bbs_content_layout);
        bbs_toggle_button.setOnClickListener(v -> {
            bbs_toggle = !bbs_toggle;
            if (bbs_toggle)
                bbs_content_layout.setVisibility(View.VISIBLE);
            else
                bbs_content_layout.setVisibility(View.GONE);
        });

        MaterialButton config_toggle_button = view.findViewById(R.id.config_toggle_button);
        View config_content_layout = view.findViewById(R.id.config_content_layout);
        config_toggle_button.setOnClickListener(v -> {
            config_toggle = !config_toggle;
            if (config_toggle)
                config_content_layout.setVisibility(View.VISIBLE);
            else
                config_content_layout.setVisibility(View.GONE);
        });

        MaterialButton theme_toggle_button = view.findViewById(R.id.theme_toggle_button);
        View theme_content_layout = view.findViewById(R.id.theme_content_layout);
        theme_toggle_button.setOnClickListener(v -> {
            theme_toggle = !theme_toggle;
            if (theme_toggle)
                theme_content_layout.setVisibility(View.VISIBLE);
            else
                theme_content_layout.setVisibility(View.GONE);
        });
        // 设置主题选择功能
        setupThemeSelection(view);
        
        // 设置背景选择功能
        setupBackgroundSelection(view);

        return view;
    }

    /**
     * 初始化Activity结果启动器
     */
    private void initActivityLauncher() {
        selectImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            // 获取持久权限访问该URI
                            requireActivity().getContentResolver().takePersistableUriPermission(
                                    selectedImageUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                            
                            // 保存选择的图片URI
                            backgroundPreferences.edit()
                                    .putString(BACKGROUND_IMAGE_URI, selectedImageUri.toString())
                                    .apply();
                            
                            // 显示提示信息
                            View view = getView();
                            if (view != null) {
                                showCustomSnackbar(view, this, "背景图片已设置，将在下次启动时生效");
                            }
                        }
                    }
                }
        );
    }

    /**
     * 设置背景选择功能
     */
    private void setupBackgroundSelection(View view) {
        MaterialButton backgroundToggleButton = view.findViewById(R.id.background_toggle_button);
        View backgroundContentLayout = view.findViewById(R.id.background_content_layout);
        MaterialButton selectBackgroundButton = view.findViewById(R.id.select_background_button);
        MaterialButton clearBackgroundButton = view.findViewById(R.id.clear_background_button);
        Slider backgroundAlphaSlider = view.findViewById(R.id.background_alpha_slider);

        backgroundToggleButton.setOnClickListener(v -> {
            background_toggle = !background_toggle;
            if (background_toggle)
                backgroundContentLayout.setVisibility(View.VISIBLE);
            else
                backgroundContentLayout.setVisibility(View.GONE);
        });

        // 设置选择背景按钮
        selectBackgroundButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            selectImageLauncher.launch(intent);
        });

        // 设置清除背景按钮
        clearBackgroundButton.setOnClickListener(v -> {
            // 清除之前先释放权限
            String uriString = backgroundPreferences.getString(BACKGROUND_IMAGE_URI, null);
            if (uriString != null) {
                try {
                    Uri oldUri = Uri.parse(uriString);
                    requireActivity().getContentResolver().releasePersistableUriPermission(
                            oldUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception e) {
                    show_error_dialog("设置背景图片出错："+ e);
                }
            }
            
            backgroundPreferences.edit()
                    .remove(BACKGROUND_IMAGE_URI)
                    .apply();
            showCustomSnackbar(view, this, "背景图片已清除，将在下次启动时生效");
        });

        // 设置透明度滑块
        float currentAlpha = backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.3f);
        backgroundAlphaSlider.setValue(currentAlpha * 100);
        backgroundAlphaSlider.addOnChangeListener((slider, value, fromUser) -> {
            float alpha = value / 100.0f;
            backgroundPreferences.edit()
                    .putFloat(BACKGROUND_ALPHA, alpha)
                    .apply();
            showCustomSnackbar(view, this, "透明度已设置为 " + (int) value + "%，将在下次启动时生效");
        });
    }

    /**
     * 设置主题选择功能
     */
    private void setupThemeSelection(View view) {
        // 获取主题SharedPreferences
        SharedPreferences themePreferences = requireActivity().getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE);
        int selectedTheme = themePreferences.getInt(SELECTED_THEME, THEME_DEFAULT);
        // 查找所有主题单选按钮
        MaterialRadioButton themeDefault = view.findViewById(R.id.theme_default);
        MaterialRadioButton themeBlue = view.findViewById(R.id.theme_blue);
        MaterialRadioButton themeGreen = view.findViewById(R.id.theme_green);
        MaterialRadioButton themeRed = view.findViewById(R.id.theme_red);
        MaterialRadioButton themeYellow = view.findViewById(R.id.theme_yellow);
        MaterialRadioButton themeLight = view.findViewById(R.id.theme_light);
        MaterialRadioButton themeDark = view.findViewById(R.id.theme_dark);
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
        if (view != null) {
            showCustomSnackbar(view, this, "主题已保存，请重启应用以应用新主题");
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
     * 获取背景图片URI
     *
     * @param context 上下文
     * @return 背景图片URI，如果没有设置则返回null
     */
    public static Uri getBackgroundImageUri(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = backgroundPreferences.getString(BACKGROUND_IMAGE_URI, null);
        return uriString != null ? Uri.parse(uriString) : null;
    }
    
    /**
     * 获取背景透明度
     *
     * @param context 上下文
     * @return 背景透明度，范围0.0-0.8，默认为0.3
     */
    public static float getBackgroundAlpha(Context context) {
        SharedPreferences backgroundPreferences = context.getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE);
        return backgroundPreferences.getFloat(BACKGROUND_ALPHA, 0.3f);
    }

    /**
     * 显示当前配置值
     */
    private void displayCurrentConfigValues() {
        String salt6x = configPreferences.getString("SALT_6X", "t0qEgfub6cvueAPgR5m9aQWWVciEer7v");
        String salt4x = configPreferences.getString("SALT_4X", "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs");
        String lk2 = configPreferences.getString("LK2", "IDMtPWQJfBCJSLOFxOlNjiIFVasBLttg");
        String k2 = configPreferences.getString("K2", "aApXDrhCxFhZkKZQVWWyfoAlyHTlJkis");
        String bbsVersion = configPreferences.getString("bbs_version", "2.92.0");
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
    // 合并后的正确写法
    private void updateConfig(View view) {
        new Thread(() -> {
            try {
                // 发送GET请求获取配置信息
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Venus/1.0");
                String response = tools.sendGetRequest("https://muxiaowf.dpdns.org/api", headers, new HashMap<>() {{
                    put("type", "salt");
                }});
                if (!response.isEmpty()) {
                    // 解析响应数据
                    JsonObject data = JsonParser.parseString(response).getAsJsonObject();
                    SharedPreferences configPrefs = requireActivity().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = configPrefs.edit();
                    // 使用解析的数据，如果不存在则使用默认值
                    editor.putString("SALT_6X", getDataOrDefault(data, "SALT_6X", "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"));
                    editor.putString("SALT_4X", getDataOrDefault(data, "SALT_4X", "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"));
                    editor.putString("LK2", getDataOrDefault(data, "LK2", "IDMtPWQJfBCJSLOFxOlNjiIFVasBLttg"));
                    editor.putString("K2", getDataOrDefault(data, "K2", "aApXDrhCxFhZkKZQVWWyfoAlyHTlJkis"));
                    editor.putString("bbs_version", getDataOrDefault(data, "bbs_version", "2.92.0"));
                    editor.putString("update_time", getDataOrDefault(data, "update_time", "2025-09"));
                    editor.putString("update_time_local", getDataOrDefault(data, "update_time_local", "2025-09"));
                    editor.apply();
                    requireActivity().runOnUiThread(() -> {
                        displayCurrentConfigValues();
                        showCustomSnackbar(view, this, "配置更新成功");
                    });
                } else {
                    requireActivity().runOnUiThread(() ->
                            showCustomSnackbar(view, this, "配置更新失败：无响应数据"));
                }
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        showCustomSnackbar(view, this, "配置更新失败：" + e.getMessage()));
            }
        }).start();
    }

    private String getDataOrDefault(JsonObject data, String key, String defaultValue) {
        if (data != null && data.has(key) && !data.get(key).isJsonNull())
            return data.get(key).getAsString();
        return defaultValue;
    }
    /**
     * 显示错误信息并提供复制
     *
     * @param error_message 错误信息
     */
    private void show_error_dialog(String error_message) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("错误")
                .setMessage(error_message)
                .setPositiveButton("复制错误信息", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("错误信息", error_message);
                    clipboard.setPrimaryClip(clip);
                    requireActivity().runOnUiThread(() ->
                            android.widget.Toast.makeText(requireContext(), "错误信息已复制到剪切板", android.widget.Toast.LENGTH_SHORT).show()
                    );
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
