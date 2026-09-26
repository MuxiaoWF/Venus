package com.muxiao.Venus.common;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.muxiao.Venus.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 底栏悬浮覆盖高度（底栏高度 + 下外边距 + 系统导航栏 inset）的发布中心。
 *
 * <p>内容避让拆除后，ViewPager 页面直通屏幕底、内容可滚进玻璃下方；滚动容器
 * 经 {@link #attachClearance} 叠加本高度作为底部留白，保证最后一个列表项能
 * 完整滚出玻璃区；锚定底部的悬浮控件（如用户管理页的扩展 FAB）经
 * {@link #attachMargin} 抬到玻璃之上。高度在底栏布局/insets 就绪后由
 * MainActivity.update() 发布，变化时自动通知已注册的滚动容器/悬浮视图重涂。</p>
 *
 * <p>监听用弱引用持有：滚动容器随 Fragment 视图销毁后自动清理，不反向保活。</p>
 */
public final class BottomBarOverlay {

    private static volatile int heightPx;
    private static final List<WeakReference<Runnable>> listeners = new ArrayList<>();

    private BottomBarOverlay() {
    }

    /** 当前覆盖高度（px），底栏未就绪时为 0。 */
    public static int get() {
        return heightPx;
    }

    /** 发布覆盖高度（UI 线程，MainActivity 的底栏 insets 监听器调用）。 */
    public static void update(int px) {
        if (px == heightPx) return;
        heightPx = px;
        Iterator<WeakReference<Runnable>> it = listeners.iterator();
        while (it.hasNext()) {
            Runnable r = it.next().get();
            if (r == null) it.remove();
            else r.run();
        }
    }

    /**
     * 给滚动容器叠加覆盖高度留白（保留其原有 padding，重复调用不叠加——
     * 基准为首次调用时的 paddingBottom，后续变化全量重涂）。
     * 重涂回调经 keyed tag 在滚动容器上强持有：监听表只存弱引用，若无强引用
     * 回调可能在下次高度更新前被 GC 回收，导致留白失效。
     */
    public static void attachClearance(@NonNull View scrollRoot) {
        Runnable existing = (Runnable) scrollRoot.getTag(R.id.tag_bottom_bar_clearance);
        if (existing != null) {
            existing.run(); // 重复 attach（视图未重建）：按新基准重算并复用监听
            return;
        }
        final int baseBottom = scrollRoot.getPaddingBottom();
        Runnable apply = () -> scrollRoot.setPadding(
                scrollRoot.getPaddingLeft(), scrollRoot.getPaddingTop(),
                scrollRoot.getPaddingRight(), baseBottom + heightPx);
        scrollRoot.setTag(R.id.tag_bottom_bar_clearance, apply);
        apply.run();
        listeners.add(new WeakReference<>(apply));
    }

    /**
     * 给锚定在父容器底部的悬浮视图（如用户管理页的扩展 FAB）叠加底栏避让：
     * bottomMargin 加上悬浮高度，把浮动控件抬到玻璃底栏之上（底栏随导航栏
     * inset 变化时自动跟随）。保留其原有 margin，重复调用不叠加——基准为
     * 首次调用时的 bottomMargin，后续变化全量重涂；keyed tag 强持有同
     * {@link #attachClearance}，防弱引用监听被 GC 回收。
     */
    public static void attachMargin(@NonNull View floating) {
        Runnable existing = (Runnable) floating.getTag(R.id.tag_bottom_bar_anchor);
        if (existing != null) {
            existing.run(); // 重复 attach（视图未重建）：按新基准重算并复用监听
            return;
        }
        final int baseBottom =
                ((ViewGroup.MarginLayoutParams) floating.getLayoutParams()).bottomMargin;
        Runnable apply = () -> {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) floating.getLayoutParams();
            lp.bottomMargin = baseBottom + heightPx;
            floating.setLayoutParams(lp);
        };
        floating.setTag(R.id.tag_bottom_bar_anchor, apply);
        apply.run();
        listeners.add(new WeakReference<>(apply));
    }
}
