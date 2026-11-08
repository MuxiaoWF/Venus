package com.muxiao.Venus.Home;

public class TaskItem {

    public enum TaskStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        ERROR,
        WARNING,
        CANCELLED,
        /**
         * 仅 UI 展示态、不持久化：任务当日已完成（COMPLETED），但本轮运行尚未执行到，
         * 绿色结果系沿用上一次运行——与「本轮已重签成功」的实心绿区分，
         * 避免重跑中途终止时未触达任务看起来像本轮已完成。
         */
        RETAINED
    }

    private final String name;
    private TaskStatus status;
    /**
     * 是否具备执行条件（纯展示态，不持久化）。
     *
     * <p>为 false 表示「开关打开了，但这项任务所需的凭证还没配」（云游戏 token / 森空岛 token）。
     * 列表里以删除线 + 置灰呈现，运行入口直接跳过、不报错——避免用户以为漏跑。
     */
    private boolean runnable = true;

    public TaskItem(String name) {
        this.name = name;
        this.status = TaskStatus.PENDING;
    }

    public String getName() {
        return name;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public boolean isRunnable() {
        return runnable;
    }

    public void setRunnable(boolean runnable) {
        this.runnable = runnable;
    }
}
