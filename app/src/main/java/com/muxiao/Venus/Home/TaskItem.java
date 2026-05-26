package com.muxiao.Venus.Home;

public class TaskItem {

    public enum TaskStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        ERROR,
        WARNING,
        CANCELLED
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
