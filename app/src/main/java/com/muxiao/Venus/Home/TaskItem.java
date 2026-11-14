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
         * 挂起等待人工完成人机验证：本轮已结束、凭证尚未就绪，续跑轮会带着验证结果重跑。
         * 与 ERROR 区分——它不是失败，不需要重试额度，也不该让用户去点「重试失败任务」。
         */
        WAITING_CAPTCHA,
        /**
         * 仅 UI 展示态、不持久化：任务当日已完成（COMPLETED）但本轮运行尚未执行到，绿色系沿用
         * 上一次运行——与「本轮已重签成功」的实心绿区分，避免重跑中途终止时看起来像已完成。
         */
        RETAINED
    }

    /** 稳定任务 id（持久化/匹配键，与语言无关，见 {@link TaskIds}）。 */
    private final String id;
    private final String name;
    private TaskStatus status;
    /**
     * 是否具备执行条件（纯展示态，不持久化）。false = 开关打开但所需凭证未配（云游戏 token /
     * 森空岛 token）：列表以删除线 + 置灰呈现，运行入口直接跳过、不报错，避免用户以为漏跑。
     */
    private boolean runnable = true;

    public TaskItem(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = TaskStatus.PENDING;
    }

    public String getId() {
        return id;
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
