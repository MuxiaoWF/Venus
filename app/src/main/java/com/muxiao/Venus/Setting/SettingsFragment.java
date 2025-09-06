package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.tools;

import java.util.HashMap;
import java.util.Map;

public class SettingsFragment extends Fragment {
    private SharedPreferences sharedPreferences;
    private SharedPreferences configPreferences;
    private static final String PREFS_NAME = "settings_prefs";
    private static final String CONFIG_PREFS_NAME = "config_prefs";

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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        configPreferences = requireActivity().getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);

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

        return view;
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

}
