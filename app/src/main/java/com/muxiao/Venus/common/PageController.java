package com.muxiao.Venus.common;

/**
 * 主界面页面控制器的生命周期契约（原 Fragment 时代 FragmentStateAdapter
 * maxLifecycle 语义的收敛形态）。
 *
 * <p>外壳迁移到 Compose 后，四个页面控制器不再是 Fragment：
 * <ul>
 *   <li>{@link #show()}：页面成为当前选中页（或 Activity 恢复且本页正被选中）——
 *       对应原 {@code onResume}；</li>
 *   <li>{@link #hide()}：页面失去选中（或 Activity 暂停且本页正被选中）——
 *       对应原 {@code onPause}；</li>
 * </ul>
 * 视图创建与释放走各自的 {@code createView(Context)} / {@code release()}：
 * createView 在外壳首次组合时逐页调用一次（等价原 onCreateView），
 * release 在 Activity 销毁时统一调用（等价原 onDestroyView/onDestroy）。</p>
 */
public interface PageController {

    /** 页面成为当前选中页 / Activity 恢复且本页正被选中（原 onResume）。 */
    void show();

    /** 页面失去选中 / Activity 暂停且本页正被选中（原 onPause）。 */
    void hide();
}
