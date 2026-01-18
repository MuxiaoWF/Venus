package com.muxiao.Venus;

import static com.muxiao.Venus.common.Constants.Prefs.*;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;

import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.LinkFragment;
import com.muxiao.Venus.User.UserManagementFragment;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.Setting.UpdateChecker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 状态栏设置
        EdgeToEdge.enable(this);

        // 设置背景图片
        setupBackground();

        // 检查更新
        checkForUpdatesIfNeeded();
        checkAndUpdateConfig(this);

        // 初始化ViewPager2和底部导航
        viewPager = findViewById(R.id.viewPager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        // 设置ViewPager2适配器
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 设置页面切换监听器，使滑动与底部导航联动
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                bottomNavigationView.setSelectedItemId(getMenuIdByPosition(position));
            }
        });

        // 设置底部导航选择监听器，与ViewPager2联动
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            int position = getPositionByMenuId(itemId);
            if (position != -1) {
                viewPager.setCurrentItem(position);
                return true;
            }
            return false;
        });

        // 默认加载首页Fragment
        if (savedInstanceState == null)
            viewPager.setCurrentItem(0);
    }

    /**
     * 检查更新（如果需要）
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
     * ViewPager2适配器
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
     * 设置背景图片
     */
    private void setupBackground() {
        ImageView backgroundImage = findViewById(R.id.background_image);
        Uri backgroundImageUri = SettingsFragment.getBackgroundImageUri(this);
        float backgroundAlpha = SettingsFragment.getBackgroundAlpha(this);

        if (backgroundImageUri != null) {
            try {
                Drawable drawable = getDrawableFromUri(backgroundImageUri);
                if (drawable != null) {
                    backgroundImage.setImageDrawable(drawable);
                    backgroundImage.setAlpha(backgroundAlpha);
                    backgroundImage.setVisibility(android.view.View.VISIBLE);
                } else {
                    backgroundImage.setVisibility(android.view.View.GONE);
                }
            } catch (SecurityException e) {
                // 权限不足，清除背景设置
                show_error_dialog(this, "没有权限访问背景图片，清除背景设置" + e);
                getSharedPreferences(BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove("background_image_uri")
                        .apply();
                backgroundImage.setVisibility(android.view.View.GONE);
            } catch (Exception e) {
                // 出现其他异常时隐藏背景图片
                backgroundImage.setVisibility(android.view.View.GONE);
                show_error_dialog(this, "设置背景图片出错，隐藏背景图片" + e);
            }
        } else {
            backgroundImage.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * 从Uri获取Drawable
     */
    private Drawable getDrawableFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9.0及以上版本使用ImageDecoder
                return ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                        }
                );
            } else {
                // Android 9.0以下版本使用BitmapFactory
                InputStream inputStream = getContentResolver().openInputStream(uri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                if (inputStream != null)
                    inputStream.close();
                return bitmap != null ? new android.graphics.drawable.BitmapDrawable(getResources(), bitmap) : null;
            }
        } catch (Exception e) {
            show_error_dialog(this, "从Uri获取Drawable出错" + e);
            return null;
        }
    }

    /**
     * 检查应用版本并在更新时清除配置
     */
    public static void checkAndUpdateConfig(Context context) {
        SharedPreferences appPrefs = context.getSharedPreferences(APP_INFO_PREFS_NAME, Context.MODE_PRIVATE);
        int lastVersion = appPrefs.getInt(LAST_VERSION, 0);
        int currentVersion;
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long fullVersionCode = PackageInfoCompat.getLongVersionCode(pInfo);
            // 低32位的versionCode部分（去除高32位的versionCodeMajor）
            currentVersion = (int)(fullVersionCode & 0xFFFFFFFFL);
        } catch (PackageManager.NameNotFoundException e) {
            currentVersion = 0;
        }
        if (currentVersion > lastVersion) {
            // 应用已更新，清除旧配置
            SharedPreferences prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            // 更新最后运行的版本号
            appPrefs.edit().putInt(LAST_VERSION, currentVersion).apply();
        }
    }
}