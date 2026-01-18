package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;
import static com.muxiao.Venus.common.tools.show_error_dialog;
import static com.muxiao.Venus.common.Constants.WRITE_PERMISSION_REQUEST_CODE;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.muxiao.Venus.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FullscreenImageActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private MaterialTextView titleTextView;
    private MaterialTextView authorTextView;
    private MaterialTextView timeTextView;
    private MaterialTextView descriptionTextView;
    private MaterialTextView counterTextView;
    private ImagePagerAdapter imageAdapter;
    private View rootview;
    private View bottomInfoCard; // 底部信息卡片视图
    private boolean isBottomInfoVisible = true; // 记录底部信息栏是否可见

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_image);

        // 设置状态栏
        EdgeToEdge.enable(this);

        rootview = findViewById(android.R.id.content);
        // 获取从上一个activity中传递的数据
        String jsonData = getIntent().getStringExtra("imageDataListJson");
        List<Map<String, Object>> imageDataList = new Gson().fromJson(jsonData, new TypeToken<List<Map<String, Object>>>() {
        }.getType());
        int initialPosition = getIntent().getIntExtra("position", 0);

        viewPager = findViewById(R.id.viewPager);
        titleTextView = findViewById(R.id.titleTextView);
        authorTextView = findViewById(R.id.authorTextView);
        timeTextView = findViewById(R.id.timeTextView);
        descriptionTextView = findViewById(R.id.descriptionTextView);
        counterTextView = findViewById(R.id.counterTextView);
        MaterialButton downloadButton = findViewById(R.id.downloadButton);
        bottomInfoCard = findViewById(R.id.bottomInfoCard); // 引用底部信息卡片

        // 设置进入动画
        viewPager.setAlpha(0f);
        viewPager.animate().alpha(1f).setDuration(300).start();

        imageAdapter = new ImagePagerAdapter(this, Objects.requireNonNull(imageDataList)); // 保存adapter引用
        viewPager.setAdapter(imageAdapter);
        viewPager.setCurrentItem(initialPosition, false);

        // 设置页面间间距和边缘装饰效果
        viewPager.setPageTransformer((page, position) -> {
            page.setTranslationX(-position * page.getWidth() * 0.1f);

            if (position <= -1.0f || position >= 1.0f) {
                // 页面完全不可见
                page.setAlpha(0.5f);
            } else if (position == 0.0f) {
                // 当前页面
                page.setAlpha(1.0f);
            } else {
                // 过渡效果
                page.setAlpha(1.0f - Math.abs(position) * 0.5f);
            }
        });
        // 设置页面变化监听器
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateInfoText(position);
            }
        });

        // 更新简介文本
        updateInfoText(initialPosition);

        // 设置按钮点击事件
        downloadButton.setOnClickListener(v -> checkPermissionAndDownload());

        // 返回按钮点击事件
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // 设置页面滑动监听，用于处理缩放时的底部信息栏显示
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels);
            }

            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateInfoText(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                super.onPageScrollStateChanged(state);
                // 当页面停止滑动时，确保底部信息栏显示
                if (state == ViewPager2.SCROLL_STATE_IDLE && !isBottomInfoVisible) {
                    showBottomInfo();
                }
            }
        });
    }

    /**
     * 更新简介文本
     */
    private void updateInfoText(int position) {
        ImagePagerAdapter.ImageItem imageItem = imageAdapter.getItem(position);
        if (imageItem != null) {
            Map<String, Object> imageData = imageItem.getImageData();
            String title = (String) imageData.get("title");
            String author = (String) imageData.get("author");
            Long timestamp = null;
            Object timestampObj = imageData.get("timestamp");
            if (timestampObj instanceof Double)
                timestamp = ((Double) timestampObj).longValue();
            else if (timestampObj instanceof Long)
                timestamp = (Long) timestampObj;
            else if (timestampObj instanceof Integer)
                timestamp = ((Integer) timestampObj).longValue();
            String description = (String) imageData.get("description");
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle((String) imageData.get("title"));
            // 格式化时间显示
            String timeStr = "";
            if (timestamp != null && timestamp > 0) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                timeStr = sdf.format(new java.util.Date(timestamp * 1000));
            }
            titleTextView.setText(title != null ? title : "");
            authorTextView.setText(author != null ? "作者: " + author : "");
            timeTextView.setText(!timeStr.isEmpty() ? "时间: " + timeStr : "");
            if (description != null && !description.trim().isEmpty()) {
                String trimmedDescription = description.replaceAll("\\s+", " ").trim();
                if (!trimmedDescription.isEmpty()) {
                    descriptionTextView.setText(trimmedDescription);
                    if (descriptionTextView.getVisibility() == View.GONE)
                        descriptionTextView.setVisibility(View.VISIBLE);
                } else {
                    descriptionTextView.setVisibility(View.GONE);
                }
            } else {
                descriptionTextView.setVisibility(View.GONE);
            }

            counterTextView.setText(new StringBuilder((position + 1) + "/" + imageAdapter.getItemCount()));
        }
    }

    /**
     * 选项菜单布局
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.image_selection_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_download) {
            checkPermissionAndDownload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 检查权限并下载当前图片
     */
    private void checkPermissionAndDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上版本不需要存储权限，可以直接保存
            downloadCurrentImage();
        } else {
            // Android 10以下版本需要存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) { // 未给权限
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        WRITE_PERMISSION_REQUEST_CODE);
            } else {
                downloadCurrentImage();
            }
        }
    }

    /**
     * 下载当前图片
     */
    private void downloadCurrentImage() {
        if (imageAdapter == null)
            return;

        int currentPosition = viewPager.getCurrentItem();
        ImagePagerAdapter.ImageItem imageItem = imageAdapter.getItem(currentPosition);

        if (imageItem != null) {
            String imageUrl = imageItem.getImageUrl();
            if (imageUrl != null && !imageUrl.isEmpty()) {
                Glide.with(this)
                        .asBitmap()
                        .load(imageUrl)
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                                saveImageToGallery(resource, currentPosition);
                            }

                            @Override
                            public void onLoadCleared(Drawable placeholder) {
                                // 加载被清除
                            }

                            @Override
                            public void onLoadFailed(Drawable errorDrawable) {
                                show_error_dialog(FullscreenImageActivity.this, "图片加载失败，无法下载");
                            }
                        });
            }
        }
    }

    /**
     * 保存图片到相册
     */
    private void saveImageToGallery(Bitmap bitmap, int position) {
        try {
            String fileName = "venus_image_" + System.currentTimeMillis() + "_" + position + ".jpg";
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上版本使用MediaStore API
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Venus");

                // 获取内容解析器并插入图片
                OutputStream fos = getContentResolver().openOutputStream(
                        Objects.requireNonNull(getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)));
                if (fos != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                    fos.flush();
                    fos.close();
                    showCustomSnackbar(rootview, this, "图片已保存到: Pictures/Venus/" + fileName);
                }
            } else {
                // Android 10以下版本使用传统方法
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            WRITE_PERMISSION_REQUEST_CODE);
                    return;
                }

                // 获取公共图片目录
                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File venusDir = new File(picturesDir, "Venus");
                if (!venusDir.exists())
                    venusDir.mkdirs();
                File imageFile = new File(venusDir, fileName);
                
                // 保存图片
                OutputStream fos = new FileOutputStream(imageFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.flush();
                fos.close();
                showCustomSnackbar(rootview, this, "图片已保存到: " + imageFile.getAbsolutePath());
            }
        } catch (IOException e) {
            show_error_dialog(this, "保存图片失败: " + e.getMessage());
        }
    }

    /**
     * 授权后调用
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WRITE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                downloadCurrentImage();
            else
                showCustomSnackbar(rootview, this, "请授予存储权限以保存图片");
        }
    }

    /**
     * 隐藏底部信息栏
     */
    public void hideBottomInfo() {
        if (isBottomInfoVisible && bottomInfoCard != null && bottomInfoCard.getVisibility() == View.VISIBLE)
            bottomInfoCard.animate()
                    .translationY(bottomInfoCard.getHeight())
                    .setDuration(200)
                    .withEndAction(() -> isBottomInfoVisible = false)
                    .start();
    }

    /**
     * 显示底部信息栏
     */
    public void showBottomInfo() {
        if (!isBottomInfoVisible && bottomInfoCard != null)
            bottomInfoCard.animate()
                    .translationY(0)
                    .setDuration(200)
                    .withEndAction(() -> isBottomInfoVisible = true)
                    .start();
    }
}