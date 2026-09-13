package com.muxiao.Venus.User;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 用户自定义头像存取：相册选图经方向校正 → 居中裁方 → 缩放后以 JPEG 存入
 * {@code filesDir/avatars/<md5(username)>.jpg}（文件名经 MD5 避免用户名里的非法字符）。
 * 文件存在即视为已设置自定义头像；删除文件即恢复默认人像图标。
 * 展示统一走 {@link #loadInto}：默认态保留布局里的圆底 + 主题着色，自定义态铺满圆形裁剪。
 */
public final class AvatarStore {

    private static final String DIR = "avatars";
    /** 落盘边长：足够 40dp 头像位与放大查看，远小于原图避免占空间。 */
    private static final int TARGET_SIZE = 256;

    private AvatarStore() {
    }

    /** 头像文件路径（md5(用户名).jpg）。 */
    public static File avatarFile(Context context, String username) {
        return new File(new File(context.getFilesDir(), DIR), tools.md5Hex(username) + ".jpg");
    }

    /** 是否已设置自定义头像。 */
    public static boolean hasAvatar(Context context, String username) {
        return avatarFile(context, username).isFile();
    }

    /** 从相册选取的 Uri 保存自定义头像。返回是否成功。 */
    public static boolean saveFromUri(Context context, String username, Uri uri) {
        Bitmap src = decodeScaled(context, uri);
        if (src == null) return false;
        try {
            Bitmap avatar = centerCropSquare(src);
            File out = avatarFile(context, username);
            File dir = out.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (!avatar.compress(Bitmap.CompressFormat.JPEG, 90, fos)) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            src.recycle();
        }
    }

    /** 删除自定义头像（恢复默认图标；用户删除/主动重置时调用）。 */
    public static void deleteAvatar(Context context, String username) {
        File file = avatarFile(context, username);
        if (file.isFile()) file.delete();
    }

    /** 用户重命名时迁移头像文件（文件名键随用户名变化）。 */
    public static void renameAvatar(Context context, String oldUsername, String newUsername) {
        File from = avatarFile(context, oldUsername);
        if (from.isFile()) {
            File to = avatarFile(context, newUsername);
            if (!to.exists()) from.renameTo(to);
        }
    }

    /**
     * 加载头像到 ImageView：有自定义图则 Glide 圆形裁剪铺满（去着色、去内边距），
     * 否则恢复布局默认态（内边距 + ic_user + 主题着色）。
     */
    public static void loadInto(Context context, String username, ImageView view) {
        if (hasAvatar(context, username)) {
            view.setPadding(0, 0, 0, 0);
            view.setImageTintList(null);
            Glide.with(view).load(avatarFile(context, username)).circleCrop().into(view);
        } else {
            // 先取消可能在途的 Glide 请求，避免异步结果覆盖默认图标
            Glide.with(view).clear(view);
            int pad = context.getResources().getDimensionPixelSize(R.dimen.space_2);
            view.setPadding(pad, pad, pad, pad);
            view.setImageResource(R.drawable.ic_user);
            // MaterialColors 解析主题属性：colorPrimary 可能指向 ColorStateList 资源，
            // 直接 resolveAttribute 拿到的是引用而非颜色值。
            // colorPrimary 声明在 appcompat（material 未重复声明该 attr）
            view.setImageTintList(ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                            view, androidx.appcompat.R.attr.colorPrimary)));
        }
    }

    // ========== 图片解码与加工 ==========

    /** 解码并按边长采样到接近 TARGET_SIZE，同时按 EXIF 方向校正（API 24+ 支持流式读取）。 */
    private static Bitmap decodeScaled(Context context, Uri uri) {
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight);
            opts.inJustDecodeBounds = false;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int rotation = exifRotation(context, uri);
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                            bmp.getWidth(), bmp.getHeight(), matrix, true);
                    if (rotated != bmp) {
                        bmp.recycle();
                        bmp = rotated;
                    }
                }
            }
        } catch (Exception ignored) {
            // 解码/读流失败返回已得结果（可能为 null），由调用方按失败处理
        }
        return bmp;
    }

    /** 采样倍数：最长边缩到 TARGET_SIZE 的 1~2 倍之间，避免大图整张进内存。 */
    private static int sampleSize(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= TARGET_SIZE) sample *= 2;
        return sample;
    }

    private static int exifRotation(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 居中裁方并缩放到 TARGET_SIZE（已是小方图则原样返回）。 */
    private static Bitmap centerCropSquare(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int side = Math.min(w, h);
        Bitmap square = Bitmap.createBitmap(src, (w - side) / 2, (h - side) / 2, side, side);
        if (side <= TARGET_SIZE) return square;
        return Bitmap.createScaledBitmap(square, TARGET_SIZE, TARGET_SIZE, true);
    }
}
