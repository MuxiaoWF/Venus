package com.muxiao.Venus.Home;

public class TaskItem {
    private String name;
    private boolean completed;
    private boolean inProgress;
    private boolean error;
    private boolean warning;

    public TaskItem(String name, boolean completed) {
        this.name = name;
        this.completed = completed;
        this.inProgress = false;
        this.error = false;
        this.warning = false;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isInProgress() {
        return inProgress;
    }

    public void setInProgress(boolean inProgress) {
        this.inProgress = inProgress;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public boolean isWarning() {
        return warning;
    }

    public void setWarning(boolean warning) {
        this.warning = warning;
    }
}
