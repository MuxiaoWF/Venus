package com.muxiao.Venus.Setting;

import dagger.hilt.android.AndroidEntryPoint;

import com.muxiao.Venus.BaseActivity;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;
import static com.muxiao.Venus.common.tools.show_error_dialog;
import static com.muxiao.Venus.common.Constants.WRITE_PERMISSION_REQUEST_CODE;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.muxiao.Venus.R;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 全屏图片查看：支持双指缩放、左右滑动切换、下载保存到相册。
 */
@AndroidEntryPoint
public class FullscreenImageActivity extends BaseActivity {
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
        // 共享元素转场需要启用 Content Transitions；「减少动态效果」开启时跳过。
        // 注意：来源 Activity（ImageActivity）使用的是标准 makeSceneTransitionAnimation +
        // transitionName 共享元素转场，因此本页也走标准共享元素配对，不再叠加
        // MaterialContainerTransform（MCT 跨 Activity 需要正确的 start/end view 配对，
        // 此前把共享元素映射到外层 viewPager 导致框架找不到起点、回退到
        // android.R.id.content 时断言失败并崩溃：IllegalArgumentException
        // "android:id/content is not a valid ancestor"）。
        boolean motionEnabled = !com.muxiao.Venus.common.tools.isReducedMotionEnabled(this);
        if (motionEnabled)
            getWindow().requestFeature(android.view.Window.FEATURE_CONTENT_TRANSITIONS);

        // 应用选定的主题（含换肤 overlay）
        SettingsFragment.applyAppTheme(this);

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

        // Intent 数据缺失 / JSON 解析失败时不再以 Object.requireNonNull 抛 NPE（表现为「点开大图闪退」），
        // 而是直接关闭本页并把结果交回来源页。
        if (imageDataList == null || imageDataList.isEmpty()) {
            finish();
            return;
        }

        viewPager = findViewById(R.id.viewPager);

        // 首图 ImageView 的 transitionName 由 ImagePagerAdapter 按 "image_<position>"
        // 设置，与来源网格缩略图的 transitionName 完全一致，系统据此自动完成标准
        // 共享元素转场配对，无需手动 onMapSharedElements 映射。
        titleTextView = findViewById(R.id.titleTextView);
        authorTextView = findViewById(R.id.authorTextView);
        timeTextView = findViewById(R.id.timeTextView);
        descriptionTextView = findViewById(R.id.descriptionTextView);
        counterTextView = findViewById(R.id.counterTextView);
        MaterialButton downloadButton = findViewById(R.id.downloadButton);
        bottomInfoCard = findViewById(R.id.bottomInfoCard); // 引用底部信息卡片

        imageAdapter = new ImagePagerAdapter(this, Objects.requireNonNull(imageDataList)); // 保存adapter引用
        viewPager.setAdapter(imageAdapter);
        viewPager.setCurrentItem(initialPosition, false);

        // 设置页面间间距和边缘装饰效果
        viewPager.setPageTransformer((page, position) -> {
            page.setTranslationX(-position * page.getWidth() * 0.15f);

            float absPos = Math.abs(position);
            if (absPos >= 1.0f) {
                page.setAlpha(0.6f);
            } else {
                page.setAlpha(1.0f - absPos * 0.4f);
            }
            page.setScaleX(1f - absPos * 0.04f);
            page.setScaleY(1f - absPos * 0.04f);
        });
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateInfoText(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_IDLE && !isBottomInfoVisible) {
                    showBottomInfo();
                }
            }
        });

        updateInfoText(initialPosition);

        downloadButton.setOnClickListener(v -> checkPermissionAndDownload());

        MaterialButton backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finishAfterTransition());

        ViewCompat.setOnApplyWindowInsetsListener(backButton, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            params.topMargin = statusBarHeight + (int) (8 * getResources().getDisplayMetrics().density);
            v.setLayoutParams(params);
            return insets;
        });
    }

    /**
     * 根据 position 刷新标题/作者/时间/简介与“当前/总数”计数；时间为 0 时不显示。
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
            authorTextView.setText(author != null ? getString(R.string.image_author_prefix) + author : "");
            timeTextView.setText(!timeStr.isEmpty() ? getString(R.string.image_time_prefix) + timeStr : "");
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
     * 加载图片选择菜单（含下载项）。
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
     * 取当前页图片 URL，用 Glide 下载为 Bitmap 后保存到相册；下载失败弹错误框。
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
                            public void onResourceReady(@NonNull Bitmap resource, com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                saveImageToGallery(resource, currentPosition);
                            }

                            @Override
                            public void onLoadCleared(Drawable placeholder) {
                                // 加载被清除
                            }

                            @Override
                            public void onLoadFailed(Drawable errorDrawable) {
                                show_error_dialog(FullscreenImageActivity.this, getString(R.string.err_image_load_failed_download));
                            }
                        });
            }
        }
    }

    /** 将 Bitmap 写入相册 Pictures/Venus 目录并提示保存结果。 */
    private void saveImageToGallery(Bitmap bitmap, int position) {
        try {
            String fileName = "venus_image_" + System.currentTimeMillis() + "_" + position + ".jpg";
            com.muxiao.Venus.common.tools.saveBitmapToGallery(this, bitmap, fileName);
            showCustomSnackbar(rootview, this, getString(R.string.snack_image_saved_to, "Pictures/Venus/" + fileName));
        } catch (IOException e) {
            show_error_dialog(this, getString(R.string.err_save_image_failed, e.getMessage()));
        }
    }

    /**
     * 存储权限授予后继续下载当前图片，拒绝则提示需要存储权限。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WRITE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                downloadCurrentImage();
            else
                showCustomSnackbar(rootview, this, getString(R.string.snack_grant_storage_permission));
        }
    }

    /**
     * 隐藏底部信息栏
     */
    public void hideBottomInfo() {
        if (isBottomInfoVisible && bottomInfoCard != null && bottomInfoCard.getVisibility() == View.VISIBLE)
            bottomInfoCard.animate()
                    .translationY(bottomInfoCard.getHeight())
                    .alpha(0f)
                    .setDuration(300)
                    .setInterpolator(AnimationUtils.loadInterpolator(this,
                            android.R.interpolator.fast_out_linear_in))
                    .withEndAction(() -> isBottomInfoVisible = false)
                    .start();
    }

    /**
     * 显示底部信息栏
     */
    public void showBottomInfo() {
        if (!isBottomInfoVisible && bottomInfoCard != null) {
            bottomInfoCard.setAlpha(0f);
            bottomInfoCard.animate()
                    .translationY(0)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(AnimationUtils.loadInterpolator(this,
                            android.R.interpolator.fast_out_slow_in))
                    .withEndAction(() -> isBottomInfoVisible = true)
                    .start();
        }
    }
}
