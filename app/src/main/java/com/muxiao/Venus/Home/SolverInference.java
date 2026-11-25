package com.muxiao.Venus.Home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.OrtLoader;

import java.io.File;
import java.util.List;

/**
 * 验证码识别推理层：九宫格已接入 {@link NineGridSolver}（ResNet18 ONNX），点选待接入。
 */
public final class SolverInference {

    private SolverInference() {
    }

    /** 推理组件是否可用（native 库 + 模型都在位）。 */
    public static boolean isAvailable(Context context) {
        if (!OrtLoader.isReady(context)) return false;
        File models = OrtLoader.modelDir(context);
        // 至少要有九宫格或点选之一的模型文件，协议层才知道把图片交给谁
        File nine = new File(models, "nine_resnet18.onnx");
        File clickDet = new File(models, "click_yolov8s.onnx");
        return nine.isFile() || clickDet.isFile();
    }

    /** 答案识别器：picType="nine" 走九宫格；"icon"/"icon1" 返回 null（点选未接入）。 */
    public static GeetestV3Solver.AnswerProvider provider(Context context) {
        return (picType, picBytes) -> {
            if ("nine".equals(picType)) return NineGridSolver.answer(context, picBytes);
            return null; // icon / icon1：点选推理未接入
        };
    }

    // ==================== 供点选接入复用的解码工具 ====================

    /** 图片字节 → Bitmap（推理前处理入口；失败返回 null）。 */
    static Bitmap decode(byte[] picBytes) {
        try {
            return BitmapFactory.decodeByteArray(picBytes, 0, picBytes.length);
        } catch (Throwable t) {
            Logger.debug("VenusCaptcha", "decode pic failed: " + t);
            return null;
        }
    }

    /** 九宫格切图：344×344 原图按格起点 {0,116,232} 切 112×112，返回 9 张（行优先）。 */
    static List<Bitmap> cropNineGrid(Bitmap src, int cell, int[] origins) {
        List<Bitmap> cells = new java.util.ArrayList<>();
        for (int row : origins) {
            for (int col : origins) {
                cells.add(Bitmap.createBitmap(src, col, row, cell, cell));
            }
        }
        return cells;
    }
}
