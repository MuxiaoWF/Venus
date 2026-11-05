package com.muxiao.Venus.Setting;

import com.muxiao.Venus.common.BaseActivity;
import dagger.hilt.android.AndroidEntryPoint;


import static com.muxiao.Venus.common.tools.showCustomSnackbar;
import static com.muxiao.Venus.common.tools.show_error_dialog;
import static com.muxiao.Venus.common.Constants.WRITE_PERMISSION_REQUEST_CODE;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.ui.compose.FullscreenImageScreenView;
import com.muxiao.Venus.common.ui.compose.FullscreenPage;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全屏图片查看：支持双指缩放、左右滑动切换、下载保存到相册。
 *
 * <p>界面已迁移为 Compose（{@link FullscreenImageScreenView}）：`ViewPager2` → `HorizontalPager`，
 * 缩放为 Compose 原生实现（捏合/双击/拖动钳制，TouchImageView 依赖已拆除）。缩放与翻页的手势冲突由
 * 「放大后关闭 pager 滚动」处理，替代原来的 `requestDisallowInterceptTouchEvent`。</p>
 *
 * <p>本类只负责：解析 Intent 数据、拍平页列表、下载与权限。</p>
 */
@AndroidEntryPoint
public class FullscreenImageActivity extends BaseActivity {
    private FullscreenImageScreenView pane;
    /** 拍平后的页列表（帖子 × 图片），下载与计数都按此列表取。 */
    private List<FullscreenPage> pages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用选定的主题（含换肤 overlay）
        SettingsFragment.applyAppTheme(this);

        super.onCreate(savedInstanceState);

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

        pages = buildPages(imageDataList);
        if (pages.isEmpty()) {
            finish();
            return;
        }

        pane = new FullscreenImageScreenView(this);
        // 必须在 setContentView（触发首次组合）之前下发数据，pager 的初始页才生效
        pane.setPages(pages, Math.min(Math.max(initialPosition, 0), pages.size() - 1));
        pane.setOnBackClickListener(this::finish);
        pane.setOnDownloadClickListener(this::checkPermissionAndDownload);
        setContentView(pane);

        // 设置状态栏
        EdgeToEdge.enable(this);
    }

    /**
     * 把「帖子列表」拍平成「图片页列表」：一帖多图时每张图都成为一页，元信息随之复制。
     * <p>
     * 与原 `ImagePagerAdapter` 的拍平规则一致（无图帖子也占一页，显示错误图标），
     * 因此 position 语义、计数「当前/总数」与原实现相同。
     */
    private List<FullscreenPage> buildPages(List<Map<String, Object>> imageDataList) {
        List<FullscreenPage> result = new ArrayList<>();
        for (Map<String, Object> imageData : imageDataList) {
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) imageData.get("images");
            String title = safeString(imageData.get("title"));
            String author = safeString(imageData.get("author"));
            String description = safeString(imageData.get("description"));
            String time = formatTimestamp(imageData.get("timestamp"));
            if (images != null && !images.isEmpty()) {
                for (String imageUrl : images)
                    result.add(new FullscreenPage(imageUrl, title, author, time, description));
            } else {
                result.add(new FullscreenPage(null, title, author, time, description));
            }
        }
        return result;
    }

    /** 时间戳（秒）→ `yyyy-MM-dd HH:mm:ss`；缺失或非正数返回空串（此时不显示时间）。 */
    private String formatTimestamp(Object timestampObj) {
        Long timestamp = null;
        if (timestampObj instanceof Double)
            timestamp = ((Double) timestampObj).longValue();
        else if (timestampObj instanceof Long)
            timestamp = (Long) timestampObj;
        else if (timestampObj instanceof Integer)
            timestamp = ((Integer) timestampObj).longValue();
        if (timestamp == null || timestamp <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp * 1000));
    }

    private String safeString(Object value) {
        return value instanceof String ? (String) value : "";
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
        if (pane == null || pages == null) return;

        int currentPosition = pane.getCurrentPage();
        if (currentPosition < 0 || currentPosition >= pages.size()) return;
        String imageUrl = pages.get(currentPosition).getImageUrl();

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

    /**
     * 将 Bitmap 写入相册 Pictures/Venus 目录并提示保存结果。
     * 写盘（MediaStore IO）走共享线程池，Snackbar/错误框回 UI 线程展示。
     */
    private void saveImageToGallery(Bitmap bitmap, int position) {
        String fileName = "venus_image_" + System.currentTimeMillis() + "_" + position + ".jpg";
        AppExecutors.get().execute(() -> {
            try {
                com.muxiao.Venus.common.tools.saveBitmapToGallery(this, bitmap, fileName);
                runOnUiThread(() -> showCustomSnackbar(this,
                        getString(R.string.snack_image_saved_to, "Pictures/Venus/" + fileName)));
            } catch (IOException e) {
                runOnUiThread(() -> show_error_dialog(this,
                        getString(R.string.err_save_image_failed,
                                com.muxiao.Venus.common.tools.describeError(e))));
            }
        });
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
                showCustomSnackbar(this, getString(R.string.snack_grant_storage_permission));
        }
    }
}
