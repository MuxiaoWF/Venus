package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.describeError;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.FutureTarget;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.BaseActivity;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.ui.compose.CropScreenView;

import java.io.File;
import java.io.FileOutputStream;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * 背景图裁剪页（Compose 实现，替代原 UCrop 三方 Activity）：
 * Activity 只负责三件事——解析来源 Uri、按屏幕宽高比下发裁剪框比例、确认后压缩 JPEG 落临时文件
 * 并经 {@code cropImageLauncher} 回传路径给 SettingsFragment 保存。手势交互与裁剪计算全在
 * {@link CropScreenView}（拖动/双指缩放，图片恒覆盖裁剪框）。
 * 解码要点：先量边界算目标尺寸（最长边/总像素双上限，永不放大）再按精确 override 交给 Glide
 * 降采样（自动按 EXIF 旋转），防超大照片解码 OOM 或超硬件画布绘制红线；临时文件沿用原文件名
 * temp_cropped_background.jpg，saveBackgroundImage 落盘后自会清理。
 */
@AndroidEntryPoint
public class CropActivity extends BaseActivity {

    public static final String EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_RESULT_PATH = "extra_result_path";

    /**
     * 解码降采样上限：最长边 ≤ {@code 4800}，且总像素 ≤ {@link #MAX_DECODE_PIXELS}。
     * 注意 Glide 默认 DownsampleStrategy.CENTER_OUTSIDE 是「放大到覆盖 override 区域」——只传
     * override(4800) 会把小图（如 1080×2418 截图）放大成 4800×10747 ≈ 206MB，
     * Compose Canvas drawImage 超过硬件画布单张位图 100MB 红线直接抛
     * 「Canvas: trying to draw too large bitmap」。故必须先量边界、精确算目标尺寸（永不超过源图）。
     */
    private static final int MAX_DECODE_DIM = 4800;
    /** 总像素上限（≈64MB @ARGB_8888，低于硬件画布 100MB 绘制红线）。 */
    private static final double MAX_DECODE_PIXELS = 16_000_000d;

    private CropScreenView pane;

    /** 屏幕像素尺寸：裁剪框比例与输出尺寸的统一基准（onCreate 一次解析）。 */
    private int screenWidth, screenHeight;
    /**
     * Glide 请求管理器：必须在 onCreate 时取得引用并复用——onDestroy 里再调 {@code Glide.with(activity)}
     * 会触发 assertNotDestroyed 抛「You cannot start a load for a destroyed activity」。
     */
    private RequestManager glideRequests;
    /** Glide 异步解码句柄：onDestroy 时取消，防Activity销毁后回调持有过期 pane。 */
    private FutureTarget<Bitmap> decodeTarget;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 与其他二级页面一致：setContentView 之前应用所选主题（基础主题 + 色彩 Overlay）
        SettingsFragment.applyAppTheme(this);
        super.onCreate(savedInstanceState);

        String source = getIntent().getStringExtra(EXTRA_SOURCE);
        if (source == null) {
            // 无来源 Uri：静默关闭（与旧流程「null 进裁剪页报错收场」不同，这里直接当作取消）
            finish();
            return;
        }

        pane = new CropScreenView(this);
        pane.setOnCancelClickListener(this::finish);
        pane.setOnConfirmListener(this::saveAndFinish);
        setContentView(pane);
        EdgeToEdge.enable(this);

        // 返回键 = 取消（RESULT_CANCELED），与旧 UCrop 行为一致
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        // 裁剪框与屏幕同比例（对齐原 UCrop withAspectRatio(screenWidth, screenHeight) 行为）
        resolveScreenSize();
        float aspect = screenHeight > 0 ? (float) screenWidth / screenHeight : 0f;
        decodeImage(Uri.parse(source), aspect);
    }

    /**
     * 全显示区像素尺寸：裁剪框比例与输出尺寸的统一基准（onCreate 一次解析）。
     * 必须用 maximumWindowMetrics / getRealSize——不能用 getCurrentWindowMetrics：
     * onCreate 时窗口尚未按 EdgeToEdge 重排完，返回的是扣除系统栏的视口（如 1080×2204），
     * 与主窗口实际绘制区（全显示区 1080×2400）比例不一致，偏差会传导到裁剪框与落盘尺寸，
     * 表现为背景被 ContentScale.Crop 放大裁边或拉伸变形。旧 UCrop 从已布局的 MainActivity
     * 窗口取值故未踩坑；此实现改为取与窗口状态、调用时机无关的全显示区指标。
     */
    private void resolveScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = getSystemService(android.view.WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            android.graphics.Point real = legacyDisplaySize();
            screenWidth = real.x;
            screenHeight = real.y;
        }
    }

    /**
     * API 30 以下取「全显示区（含系统栏）」的唯一途径：{@code Display#getRealSize}。
     *
     * <p>为什么不改用非弃用 API：{@code WindowMetrics} 是它的官方替代，但该类 API 30 才存在，
     * minSdk 24 的回落分支取不到；{@code Resources#getDisplayMetrics()} 虽然不弃用，语义却不等价
     * ——它返回可用显示区（已扣掉导航栏/刘海），正是 {@link #resolveScreenSize()} 注释里要避免的偏差
     * （背景按 ContentScale.Crop 放大后裁边/变形）。故此处保留旧 API 并就地抑制告警，
     * 抑制范围只限这一个方法，避免把 API 30+ 分支也一起屏蔽。
     */
    @SuppressWarnings("deprecation")
    private android.graphics.Point legacyDisplaySize() {
        android.graphics.Point real = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(real);
        return real;
    }

    /** 页面已退出（用户取消/系统回收）时丢弃异步回调：dialog 挂在销毁的窗口上会抛 BadTokenException。 */
    private boolean isGone() {
        return isFinishing() || isDestroyed();
    }

    /**
     * 异步解码：先在后台量边界计算目标尺寸（双上限、不放大），主线程按精确 override
     * 发起 Glide 请求（EXIF 自动旋转），IO 线程取结果后回主线程下发。
     */
    private void decodeImage(Uri uri, float aspect) {
        if (glideRequests == null) glideRequests = Glide.with(this);
        AppExecutors.get().execute(() -> {
            int[] target;
            try {
                target = resolveDecodeTarget(uri);
            } catch (Exception e) {
                Logger.w("背景图边界解码失败: " + describeError(e));
                runOnUiThread(() -> {
                    if (isGone()) return;
                    show_error_dialog(this, getString(R.string.err_crop_failed, describeError(e)));
                    finish();
                });
                return;
            }
            runOnUiThread(() -> {
                if (isGone() || pane == null) return;
                decodeTarget = glideRequests.asBitmap().load(uri)
                        .override(target[0], target[1]).submit();
                AppExecutors.get().execute(() -> {
                    try {
                        Bitmap bitmap = decodeTarget.get();
                        runOnUiThread(() -> {
                            if (isGone() || pane == null) return;
                            pane.setSource(bitmap, aspect);
                        });
                    } catch (Exception e) {
                        Logger.w("背景图解码失败: " + describeError(e));
                        runOnUiThread(() -> {
                            if (isGone()) return;
                            show_error_dialog(this, getString(R.string.err_crop_failed, describeError(e)));
                            finish();
                        });
                    }
                });
            });
        });
    }

    /**
     * 边界解码 → 计算解码目标尺寸：最长边 ≤ MAX_DECODE_DIM 且总像素 ≤ MAX_DECODE_PIXELS，
     * scale 上限 1（永不放大）。Glide 收到与源图等比的目标尺寸后不会再触发 CENTER_OUTSIDE 上采样。
     */
    private int[] resolveDecodeTarget(Uri uri) throws Exception {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("source stream is null");
            android.graphics.BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("invalid image bounds");
        double scale = Math.min(1.0, Math.min(
                (double) MAX_DECODE_DIM / Math.max(bounds.outWidth, bounds.outHeight),
                Math.sqrt(MAX_DECODE_PIXELS / ((double) bounds.outWidth * bounds.outHeight))));
        return new int[]{
                Math.max(1, (int) Math.round(bounds.outWidth * scale)),
                Math.max(1, (int) Math.round(bounds.outHeight * scale))};
    }

    /** 确认：框内容等比放大到整屏尺寸（裁剪框比例=屏幕比例，无变形；对齐旧 UCrop 输出整屏尺寸的口径，
     *  否则外壳 ContentScale.Crop 会把小一圈的成品再放大铺屏，表现为背景异常放大），
     *  JPEG(质量100) 写入缓存临时文件，回传路径（保存链路复用 SettingsFragment.saveBackgroundImage）。 */
    private void saveAndFinish(Bitmap bitmap) {
        AppExecutors.get().execute(() -> {
            File out = new File(getCacheDir(), "temp_cropped_background.jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                Bitmap toSave = bitmap;
                if (screenWidth > 0 && screenHeight > 0
                        && (bitmap.getWidth() != screenWidth || bitmap.getHeight() != screenHeight)) {
                    toSave = Bitmap.createScaledBitmap(bitmap, screenWidth, screenHeight, true);
                }
                toSave.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT_PATH, out.getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
            } catch (Exception e) {
                Logger.w("背景图裁剪落盘失败: " + describeError(e));
                runOnUiThread(() -> {
                    if (isGone()) return;
                    show_error_dialog(this, getString(R.string.err_save_background_failed, describeError(e)));
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (decodeTarget != null) {
            // 用 onCreate 时持有的 RequestManager 清理：此时 Activity 已在销毁流程，
            // Glide.with(this) 会断言失败；RequestManager.clear 不走 retriever，安全
            try {
                glideRequests.clear(decodeTarget);
            } catch (Exception e) {
                Logger.w("裁剪页 Glide 清理失败: " + describeError(e));
            }
            decodeTarget = null;
        }
        super.onDestroy();
    }
}
