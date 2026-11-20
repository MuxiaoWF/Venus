package com.muxiao.Venus.common;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;

/**
 * 应用背景（壁纸）管线：解码 → 按淡化预设做色彩处理 → 与主题底色一次性掺合。
 * 供所有需要「壁纸 + 液态玻璃」的宿主复用（主界面外壳、图库页、全屏看图），改动请同步核对三处调用方。
 * 输出是全不透明成品位图，尺寸即显示区（已按 Crop 裁好），背景层直接铺满即可，不要再叠视图 alpha
 * （否则双重淡化、画面发蒙，预设档差距被压缩）。
 * 纯位图运算、不触碰 View/主线程，但解码与合成开销大，必须在后台线程调用。
 */
public final class AppBackground {

    private AppBackground() {
    }

    /**
     * 淡化预设矩阵：[饱和度, 亮度偏移(0~1)，对比度]。下标即设置页「背景淡化风格」的取值，
     * 与 {@code BACKGROUND_PRESETS} 的历史口径一致，不可重排。
     */
    private static final float[][] PRESETS = {
            {0.25f, 0.15f, 0.85f},  // 0 轻盈：水彩感
            {0.60f, 0.06f, 0.95f},  // 1 标准（默认）：柔和
            {1.00f, 0.00f, 1.10f},  // 2 浓郁：接近原图
    };

    /** 采样目标长边上限：壁纸被淡化 + 玻璃遮盖，更高分辨率肉眼不可辨；压到原图 1/2 以下还能触发采样解码。 */
    private static final int MAX_TARGET_DIM = 2000;

    /**
     * 一步产出可直接铺满的背景位图：解码 + 淡化 + 与底色掺合。
     *
     * @param presetIndex 淡化预设下标（越界自动收敛）
     * @param imageWeight 图片权重 0~1（设置页不透明度滑条）
     * @param surfaceColor 主题底色（须主线程解析后传入，后台线程读主题属性不安全）
     * @return 全不透明位图；uri 为 null 时返回 null
     * @throws SecurityException 权限不足，调用方应清除该背景设置并提示
     */
    @Nullable
    public static Bitmap loadComposited(
            @NonNull Context context,
            @Nullable Uri uri,
            int presetIndex,
            float imageWeight,
            int surfaceColor) throws Exception {
        if (uri == null) return null;
        int[] target = resolveTargetSize(context);
        Bitmap source = decode(context, uri, target[0], target[1]);
        if (source == null) return null;
        Bitmap result = composite(context, source, presetIndex, imageWeight, surfaceColor, target[0], target[1]);
        if (result != source && !source.isRecycled()) source.recycle();
        return result;
    }

    /**
     * 预合成：降饱和 + 亮度偏移（ColorMatrix）→ 与主题底色按图片权重一次性掺合。
     * 输出为全不透明位图（out = 预处理图 × w + 底色 × (1-w)），尺寸即目标显示区（ContentScale.Crop 语义）。
     */
    @NonNull
    public static Bitmap composite(
            @NonNull Context context,
            @NonNull Bitmap source,
            int presetIndex,
            float imageWeight,
            int surfaceColor,
            int targetWidth,
            int targetHeight) {
        float[] preset = PRESETS[Math.max(0, Math.min(presetIndex, PRESETS.length - 1))];
        boolean darkMode = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(preset[0]);
        float translate = (darkMode ? -1f : 1f) * preset[1] * 255f;
        ColorMatrix brightness = new ColorMatrix(new float[]{
                1, 0, 0, 0, translate,
                0, 1, 0, 0, translate,
                0, 0, 1, 0, translate,
                0, 0, 0, 1, 0
        });
        float contrast = preset[2];
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, (1 - contrast) * 128f,
                0, contrast, 0, 0, (1 - contrast) * 128f,
                0, 0, contrast, 0, (1 - contrast) * 128f,
                0, 0, 0, 1, 0
        });
        // 依序串联：降饱和 → 提亮/压暗 → 对比度（postConcat 语义为「后乘」，执行顺序按添加顺序）
        ColorMatrix effect = new ColorMatrix();
        effect.postConcat(saturation);
        effect.postConcat(brightness);
        effect.postConcat(contrastMatrix);

        // 先铺满主题底色，再按图片权重叠上预处理图：out = 预处理图 × w + 底色 × (1-w)，单次掺合、全不透明输出。
        Bitmap src = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && source.getConfig() == Bitmap.Config.HARDWARE)
                ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
        if (src == null) return source;
        Bitmap out = Bitmap.createBitmap(Math.max(1, targetWidth), Math.max(1, targetHeight),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(surfaceColor);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(Math.round(Math.max(0f, Math.min(1f, imageWeight)) * 255f));
        paint.setColorFilter(new ColorMatrixColorFilter(effect));
        // 按 ContentScale.Crop 语义居中铺满：等比缩放到覆盖目标区，超出部分裁掉（输出恒为显示区尺寸）
        float scale = Math.max((float) out.getWidth() / src.getWidth(),
                (float) out.getHeight() / src.getHeight());
        float drawWidth = src.getWidth() * scale;
        float drawHeight = src.getHeight() * scale;
        RectF dst = new RectF((out.getWidth() - drawWidth) / 2f, (out.getHeight() - drawHeight) / 2f,
                (out.getWidth() + drawWidth) / 2f, (out.getHeight() + drawHeight) / 2f);
        canvas.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), dst, paint);
        if (src != source && !src.isRecycled()) src.recycle();
        return out;
    }

    /**
     * 按系统版本从 Uri 解码 Bitmap（Android P+ 用 ImageDecoder，自动处理 EXIF 旋转；
     * 否则 BitmapFactory）。按「覆盖 targetWidth × targetHeight」采样，避免原图全尺寸解码。
     */
    @Nullable
    public static Bitmap decode(@NonNull Context context, @NonNull Uri uri,
                                int targetWidth, int targetHeight) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 必须强制软件分配：ImageDecoder 默认可能返回 HARDWARE 位图，
            // 而后续预合成要在软件 Canvas 上做色彩滤镜重绘，会直接崩溃。
            return ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.getContentResolver(), uri),
                    (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        Size size = info.getSize();
                        float scale = Math.max((float) targetWidth / size.getWidth(),
                                (float) targetHeight / size.getHeight());
                        if (scale < 1f) {
                            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)),
                                    Math.max(1, Math.round(size.getHeight() * scale)));
                        }
                    });
        }
        // 旧版本没有 setTargetSize：先量边界按 2 的幂粗采样，剩余缩放交给 composite
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            return BitmapFactory.decodeStream(is, null, options);
        }
    }

    /**
     * 壁纸目标尺寸：全显示区（含系统栏）等比收缩到长边不超过 {@link #MAX_TARGET_DIM}。
     * <p>上限不只是省内存：ImageDecoder 的缩放比例超过 1/2 时仍会解全尺寸再重采样，
     * 压到 1/2 以下才会走采样解码（耗时约为原来的 1/4）。壁纸经淡化 + 玻璃遮盖，这点损失看不出。
     */
    @SuppressWarnings("deprecation")
    private static int[] resolveTargetSize(Context context) {
        int width = 0;
        int height = 0;
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
                    width = bounds.width();
                    height = bounds.height();
                } else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    width = metrics.widthPixels;
                    height = metrics.heightPixels;
                }
            }
        } catch (Exception ignored) {
            // 非窗口 Context 或极端机型：走资源尺寸兜底
        }
        if (width <= 0 || height <= 0) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        int longest = Math.max(width, height);
        if (longest > MAX_TARGET_DIM) {
            float scale = (float) MAX_TARGET_DIM / longest;
            width = Math.round(width * scale);
            height = Math.round(height * scale);
        }
        return new int[]{Math.max(1, width), Math.max(1, height)};
    }
}
