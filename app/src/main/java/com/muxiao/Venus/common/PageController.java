package com.muxiao.Venus.common;

/**
 * 主界面页面控制器的生命周期契约。
 *
 * <p>{@link #show()} = 页面成为当前选中页（或 Activity 恢复且本页正被选中）；
 * {@link #hide()} = 页面失去选中（或 Activity 暂停且本页正被选中）。视图创建与释放走各自的
 * {@code createView(Context)} / {@code release()}：createView 在外壳首次组合时逐页调用一次，
 * release 在 Activity 销毁时统一调用。</p>
 */
public interface PageController {

    /** 页面成为当前选中页，或 Activity 恢复且本页正被选中。 */
    void show();

    /** 页面失去选中，或 Activity 暂停且本页正被选中。 */
    void hide();
}
