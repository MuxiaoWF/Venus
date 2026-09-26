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
}
