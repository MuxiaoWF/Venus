package com.muxiao.Venus.Home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.Nullable;

import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.OrtLoader;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 极验九宫格识别（真实推理）：Yiffyi/resnet-geetest-v3-nine-pic 的 ResNet18 ONNX。
 *
 * <p>推理口径与 inference.py 一致：输入 [9,3,224,224]、输出 [9,90] logits；hint 类别走 90 张参考小图
 * 像素差标准差比对；答案按 argmax==target 全选，坐标 {@code col_row}（1 基）。
 */
public final class NineGridSolver {

    /** 病态分布防御：最多提交的格子数。 */
    static final int MAX_SELECTED = 6;
    /** hint 参考小图匹配阈值（原作者阈值 10）。 */
    static final float HINT_MATCH_THRESHOLD = 10.0f;

    private static final int SRC_SIZE = 344;
    private static final int CELL = 112;
    private static final int[] CELL_ORIGINS = {0, 116, 232};
    private static final int HINT_SIZE = 40;
    private static final int TARGET = 224;
    private static final int BATCH = 9;
    private static final int CLASSES = 90;
    private static final float MEAN_R = 0.485f, MEAN_G = 0.456f, MEAN_B = 0.406f;
    private static final float STD_R = 0.229f, STD_G = 0.224f, STD_B = 0.225f;
    private static final String TAG = "VenusCaptcha";

    private static volatile OrtSession session;
    private static volatile long sessionModelStamp;

    private NineGridSolver() {
    }

    /**
     * 对一张九宫格原图（344×344 JPEG 字节）做识别，返回提交坐标列表（{@code col_row}，1 基）。
     * 任何失败返回 null（调用方据此回落人工验证）。
     */
    @Nullable
    public static List<String> answer(Context context, byte[] picBytes) {
        try {
            Bitmap src = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.length);
            if (src == null) return null;
            // 坐标按实际尺寸等比缩放（极验正常下发 344×344，防御性处理其他尺寸）
            float sx = src.getWidth() / (float) SRC_SIZE;
            float sy = src.getHeight() / (float) SRC_SIZE;

            Bitmap hint = crop(src, Math.round(2 * sx), Math.round(344 * sy),
                    Math.round(HINT_SIZE * sx), Math.round(HINT_SIZE * sy));
            List<Bitmap> cells = new ArrayList<>(BATCH);
            for (int row : CELL_ORIGINS) {
                for (int col : CELL_ORIGINS) {
                    cells.add(crop(src, Math.round(col * sx), Math.round(row * sy),
                            Math.round(CELL * sx), Math.round(CELL * sy)));
                }
            }
            if (src != hint && src != cells.get(0)) src.recycle();

            OrtSession ort = session(context);
            if (ort == null) return null;

            int target = guessCategory(context, hint, ort);
            Logger.debug(TAG, "NineGridSolver: target=" + target);
            if (target < 0 || target >= CLASSES) return null;

            float[][] probs = softmax(inferCells(ort, cells));

            List<Integer> selected = selectByArgmax(probs, target);
            if (selected.isEmpty()) {
                Logger.debug(TAG, "NineGridSolver: no argmax match, giving up this attempt (target=" + target + ")");
                return null;
            }
            if (selected.size() > MAX_SELECTED) {
                selected.sort((a, b) -> Float.compare(probs[b][target], probs[a][target]));
                selected = new ArrayList<>(selected.subList(0, MAX_SELECTED));
            }
            Logger.debug(TAG, "NineGridSolver: selected=" + selected + " probs="
                    + probsFor(probs, selected, target));

            List<String> points = new ArrayList<>(selected.size());
            for (int idx : selected) {
                int col = idx % 3;
                int row = idx / 3;
                points.add((col + 1) + "_" + (row + 1));
            }
            return points;
        } catch (Throwable t) {
            Logger.debug(TAG, "NineGridSolver failed: " + t);
            return null;
        }
    }

    // ==================== ONNX 会话与推理 ====================

    /** 惰性建会话：按模型文件的 lastModified 缓存，升级组件后自动重建。 */
    private static OrtSession session(Context context) throws Exception {
        OrtLoader.load(context);
        File model = new File(OrtLoader.modelDir(context), "nine_resnet18.onnx");
        if (!model.isFile()) return null;
        long stamp = model.lastModified();
        OrtSession cached = session;
        if (cached != null && sessionModelStamp == stamp) return cached;
        synchronized (NineGridSolver.class) {
            if (session == null || sessionModelStamp != stamp) {
                if (session != null) session.close();
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                session = env.createSession(model.getAbsolutePath(), options);
                sessionModelStamp = stamp;
                Logger.debug(TAG, "NineGridSolver: session created for " + model.getName());
            }
            return session;
        }
    }

    /** 9 格批量推理（输入固定 [9,3,224,224]），返回 [9][90] logits。 */
    private static float[][] inferCells(OrtSession ort, List<Bitmap> cells) throws Exception {
        float[] batch = new float[BATCH * 3 * TARGET * TARGET];
        for (int i = 0; i < BATCH; i++) {
            float[] chw = preprocess(cells.get(i));
            System.arraycopy(chw, 0, batch, i * chw.length, chw.length);
        }
        return runBatch(ort, batch, new long[]{BATCH, 3, TARGET, TARGET});
    }

    private static int inferHint(OrtSession ort, Bitmap hint) throws Exception {
        float[] chw = preprocess(hint);
        float[] batch = new float[BATCH * chw.length];
        for (int i = 0; i < BATCH; i++) System.arraycopy(chw, 0, batch, i * chw.length, chw.length);
        float[][] logits = runBatch(ort, batch, new long[]{BATCH, 3, TARGET, TARGET});
        return argmax(logits[0]);
    }

    private static float[][] runBatch(OrtSession ort, float[] batch, long[] shape) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        String inputName = ort.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(batch), shape);
             OrtSession.Result result = ort.run(Collections.singletonMap(inputName, tensor))) {
            //noinspection unchecked
            return (float[][]) result.get(0).getValue();
        }
    }

    // ==================== hint 类别判定 ====================

    /** 优先与参考小图像素差比对，不中则退化为模型分类。 */
    private static int guessCategory(Context context, Bitmap hint, OrtSession ort) throws Exception {
        int[][] ref = loadHintReferences(context, hint.getWidth(), hint.getHeight());
        if (ref != null) {
            int[] cur = pixels(hint);
            float best = Float.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < ref.length; i++) {
                float std = diffStd(ref[i], cur);
                if (std < best) {
                    best = std;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0 && best <= HINT_MATCH_THRESHOLD) {
                Logger.debug(TAG, "NineGridSolver: hint matched ref " + bestIdx + " (std=" + best + ")");
                return bestIdx;
            }
            Logger.debug(TAG, "NineGridSolver: hint ref match missed (std=" + best + "), fallback to model");
        }
        return inferHint(ort, hint);
    }

    private static volatile int[][] hintRefs;

    private static int[][] loadHintReferences(Context context, int w, int h) {
        int[][] refs = hintRefs;
        if (refs != null) return refs;
        synchronized (NineGridSolver.class) {
            if (hintRefs != null) return hintRefs;
            File dir = new File(OrtLoader.modelDir(context), "hints");
            List<int[]> list = new ArrayList<>(CLASSES);
            for (int i = 0; i < CLASSES; i++) {
                File f = new File(dir, "hint_" + i + ".jpg");
                if (!f.isFile()) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                if (bmp == null) return null;
                if (bmp.getWidth() != w || bmp.getHeight() != h) {
                    bmp = Bitmap.createScaledBitmap(bmp, w, h, true);
                }
                list.add(pixels(bmp));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (bmp.getConfig() != Bitmap.Config.HARDWARE) bmp.recycle();
                } else {
                    bmp.recycle();
                }
            }
            hintRefs = list.toArray(new int[0][]);
            return hintRefs;
        }
    }

    private static float diffStd(int[] a, int[] b) {
        if (a.length != b.length) return Float.MAX_VALUE;
        long sum = 0, sumSq = 0;
        int n = a.length * 3;
        for (int i = 0; i < a.length; i++) {
            int dr = Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
            int dg = Math.abs(((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF));
            int db = Math.abs(((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF));
            sum += dr + dg + db;
            sumSq += (long) dr * dr + (long) dg * dg + (long) db * db;
        }
        // np.std 的自由度是 N（不是 N-1），等价换算
        double mean = (double) sum / n;
        double var = (double) sumSq / n - mean * mean;
        return (float) Math.sqrt(Math.max(0, var));
    }

    private static int[] pixels(Bitmap bmp) {
        int[] out = new int[bmp.getWidth() * bmp.getHeight()];
        bmp.getPixels(out, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
        return out;
    }

    // ==================== 图像预处理 ====================

    /** 缩放 → /255 → ImageNet 归一化 → CHW 布局。 */
    private static float[] preprocess(Bitmap cell) {
        Bitmap scaled = cell;
        if (cell.getWidth() != TARGET || cell.getHeight() != TARGET) {
            scaled = Bitmap.createScaledBitmap(cell, TARGET, TARGET, true);
        }
        int[] pixels = new int[TARGET * TARGET];
        scaled.getPixels(pixels, 0, TARGET, 0, 0, TARGET, TARGET);
        if (scaled != cell) scaled.recycle();
        float[] chw = new float[3 * TARGET * TARGET];
        int plane = TARGET * TARGET;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;
            chw[i] = (r - MEAN_R) / STD_R;
            chw[plane + i] = (g - MEAN_G) / STD_G;
            chw[2 * plane + i] = (b - MEAN_B) / STD_B;
        }
        return chw;
    }

    private static Bitmap crop(Bitmap src, int x, int y, int w, int h) {
        x = Math.max(0, Math.min(x, src.getWidth() - 1));
        y = Math.max(0, Math.min(y, src.getHeight() - 1));
        int cw = Math.max(1, Math.min(w, src.getWidth() - x));
        int ch = Math.max(1, Math.min(h, src.getHeight() - y));
        return Bitmap.createBitmap(src, x, y, cw, ch);
    }

    // ==================== 数值工具 ====================

    /** 数值稳定的 softmax（逐行）。 */
    private static float[][] softmax(float[][] logits) {
        float[][] out = new float[logits.length][];
        for (int r = 0; r < logits.length; r++) {
            float[] row = logits[r];
            float max = row[0];
            for (float v : row) max = Math.max(max, v);
            float sum = 0;
            float[] e = new float[row.length];
            for (int i = 0; i < row.length; i++) {
                e[i] = (float) Math.exp(row[i] - max);
                sum += e[i];
            }
            for (int i = 0; i < e.length; i++) e[i] /= sum;
            out[r] = e;
        }
        return out;
    }

    private static int argmax(float[] row) {
        int best = 0;
        for (int i = 1; i < row.length; i++) if (row[i] > row[best]) best = i;
        return best;
    }

    /** argmax == target 即入选（全选，无概率门槛）。 */
    private static List<Integer> selectByArgmax(float[][] probs, int target) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < probs.length; i++) {
            if (argmax(probs[i]) == target) out.add(i);
        }
        return out;
    }

    private static String probsFor(float[][] probs, List<Integer> selected, int target) {
        StringBuilder sb = new StringBuilder("[");
        for (int i : selected) sb.append(i).append(':').append(String.format(java.util.Locale.US, "%.2f", probs[i][target])).append(' ');
        return sb.append(']').toString();
    }
}
