package com.muxiao.Venus.Setting;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.muxiao.Venus.R;

public class SettingsFragment extends Fragment {
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "settings_prefs";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

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

        TextView githubLink = view.findViewById(R.id.github_link);
        githubLink.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        githubLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MuxiaoWF/Venus"));
            startActivity(intent);
        });

        TextView blogLink = view.findViewById(R.id.blog_link);
        blogLink.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        blogLink.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://muxiaowf.dpdns.org/"));
            startActivity(intent);
        });

        return view;
    }
}
