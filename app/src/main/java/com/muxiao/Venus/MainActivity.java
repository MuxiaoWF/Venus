package com.muxiao.Venus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Link.LinkFragment;
import com.muxiao.Venus.User.UserManagementFragment;
import com.muxiao.Venus.Setting.SettingsFragment;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    // 添加当前Fragment跟踪
    private int currentFragmentId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 设置背景图片
        setupBackground();

        // 初始化底部导航
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                loadFragment(HomeFragment.class, R.id.navigation_home);
                return true;
            } else if (itemId == R.id.navigation_users) {
                loadFragment(UserManagementFragment.class, R.id.navigation_users);
                return true;
            } else if (itemId == R.id.navigation_settings) {
                loadFragment(SettingsFragment.class, R.id.navigation_settings);
                return true;
            } else if (itemId == R.id.navigation_link) {
                loadFragment(LinkFragment.class, R.id.navigation_link);
                return true;
            }
            return false;
        });

        // 默认加载首页Fragment
        if (savedInstanceState == null)
            bottomNavigationView.setSelectedItemId(R.id.navigation_home);
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
                show_error_dialog("没有权限访问背景图片，清除背景设置" + e);
                getSharedPreferences("background_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("background_image_uri")
                        .apply();
                backgroundImage.setVisibility(android.view.View.GONE);
            } catch (Exception e) {
                // 出现异常时隐藏背景图片
                backgroundImage.setVisibility(android.view.View.GONE);
                show_error_dialog("设置背景图片出错，隐藏背景图片" + e);
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
                            // 配置解码选项
                        }
                );
            } else {
                // Android 9.0以下版本使用BitmapFactory
                InputStream inputStream = getContentResolver().openInputStream(uri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                if (inputStream != null) {
                    inputStream.close();
                }
                return bitmap != null ? new android.graphics.drawable.BitmapDrawable(getResources(), bitmap) : null;
            }
        } catch (Exception e) {
            show_error_dialog("从Uri获取Drawable出错" + e);
            return null;
        }
    }

    private void loadFragment(Class<? extends Fragment> fragmentClass, int fragmentId) {
        // 避免重复加载相同的Fragment
        if (currentFragmentId == fragmentId) return;

        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

        // 首先隐藏当前显示的Fragment
        if (currentFragmentId != -1) {
            Fragment currentFragment = fragmentManager.findFragmentByTag(String.valueOf(currentFragmentId));
            if (currentFragment != null)
                fragmentTransaction.hide(currentFragment);
        }

        // 查找目标Fragment，如果不存在则创建并添加，否则显示
        Fragment fragment = fragmentManager.findFragmentByTag(String.valueOf(fragmentId));
        if (fragment == null) {
            try {
                fragment = fragmentClass.newInstance();
                fragmentTransaction.add(R.id.fragment_container, fragment, String.valueOf(fragmentId));
            } catch (Exception e) {
                show_error_dialog("创建Fragment实例时出错" + e);
            }
        } else {
            fragmentTransaction.show(fragment);
        }
        currentFragmentId = fragmentId;
        fragmentTransaction.commit();
    }

    /**
     * 显示错误信息并提供复制
     *
     * @param error_message 错误信息
     */
    private void show_error_dialog(String error_message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("错误")
                .setMessage(error_message)
                .setPositiveButton("复制错误信息", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("错误信息", error_message);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "错误信息已复制到剪切板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}