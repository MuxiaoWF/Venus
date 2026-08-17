package com.muxiao.Venus.widget;

import android.content.Context;

import com.muxiao.Venus.common.data.TaskStatusRepository;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 持久化每日任务完成状态，按日期自动重置。
 * Widget 通过此类读取状态，App（前台/后台任务）通过此类写入状态。
 *
 * <p>状态由两条键共同表达，且二者必须满足单一真相不变量：
 * <ul>
 *   <li>{@code status_<name>}：任务当前状态字符串（pending/in_progress/completed/error/warning/cancelled）；</li>
 *   <li>{@code done_<name>}：布尔，恒等于 {@code status == completed} 的镜像，仅供
 *       {@link #isCompleted(String)} / {@link #getCompletedCount(String[])} 这样的「是否完成」聚合查询使用。</li>
 * </ul>
 * 任何写入路径都不得让 {@code done_} 与 {@code status_} 分歧，否则聚合计数会与逐条展示不一致。
 */
public class TaskStatusManager {

    private static final String KEY_DATE = "status_date";
    private static final String KEY_PREFIX_DONE = "done_";
    private static final String KEY_PREFIX_STATUS = "status_";
    private static final String KEY_CURRENT_USER = "current_user";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_WARNING = "warning";
    public static final String STATUS_CANCELLED = "cancelled";

    private final Context context;
    private final TaskStatusRepository repo;

    public TaskStatusManager(Context context) {
        this.context = context.getApplicationContext();
        repo = new TaskStatusRepository(context);
        ensureToday();
    }

    private static String todayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void ensureToday() {
        String today = todayString();
        if (!today.equals(repo.getString(KEY_DATE, ""))) {
            // 保留 current_user 跨日持久化，清除任务状态
            String savedUser = repo.getString(KEY_CURRENT_USER, "");
            // 先写入历史日志（此时缓存还保留着昨日数据）
            writeDailyLog(savedUser);
            repo.clear();
            repo.putString(KEY_DATE, today);
            repo.putString(KEY_CURRENT_USER, savedUser);
        }
    }

    /**
     * 标记任务为已完成。
     * 同时写入 status_=completed 与 done_=true，其中 done_ 始终作为「完成态」的镜像字段。
     */
    public void markCompleted(String taskName) {
        repo.putBoolean(KEY_PREFIX_DONE + taskName, true);
        repo.putString(KEY_PREFIX_STATUS + taskName, STATUS_COMPLETED);
    }

    /**
     * 设置任务状态（通用入口）。
     *
     * <p>关键不变量：{@code done_} 字段必须作为 {@code status == STATUS_COMPLETED} 的镜像同步维护——
     * 完成态置 true，其余状态（进行中 / 错误 / 警告 / 取消 / 待定）一律复位为 false。
     * 否则任务「先完成后被取消 / 失败」时，{@code done_} 会残留 true，导致
     * {@link #isCompleted(String)} / {@link #getCompletedCount(String[])} 与 {@link #getStatus(String)}
     * 产生分歧：小组件顶部「已完成 X/Y」计数虚高，而逐条列表却显示该任务为失败 / 进行中。
     *
     * @param taskName 任务名（与 status_/done_ 前缀键对应）
     * @param status   目标状态，取值见本类 STATUS_* 常量
     */
    public void markStatus(String taskName, String status) {
        repo.putString(KEY_PREFIX_STATUS + taskName, status);
        repo.putBoolean(KEY_PREFIX_DONE + taskName, STATUS_COMPLETED.equals(status));
    }

    /** 标记任务为错误态（等价于 markStatus(taskName, STATUS_ERROR)）。 */
    public void markError(String taskName) {
        markStatus(taskName, STATUS_ERROR);
    }

    /**
     * 新一轮任务开始前复位任务状态：将全部任务（含当日已完成）一律复位为 {@link #STATUS_PENDING}，
     * 使「点击运行」后旧状态被清除、任务可重新执行并重新标记。
     * 满足「运行后重置为未完成」规则（与「当天保持」「跨天自动重置」并存）。
     *
     * <p>注意：此处不再保留 STATUS_COMPLETED。已完成态仅在「程序退出后当日重新进入（未运行）」时保留，
     * 一旦用户主动点击「运行」，即视为开启新一轮，所有任务回到未完成以便重新打卡；
     * 跨天由 {@link #ensureToday()} 另行自动清空。
     *
     * <p>枚举范围取「库中实际存在的 status_ 键」，而非仅按配置名——避免持久化任务名与当前配置名不一致时漏重置。
     *
     * @param taskNames 当前配置的任务名集合（与 status_/done_ 前缀键对应），用于兜底覆盖
     */
    public void resetTransientStatuses(java.util.Collection<String> taskNames) {
        java.util.Set<String> names = new java.util.HashSet<>(taskNames);
        // 兜底：把仓库中残留的 status_ 键也纳入重置，防止遗漏
        for (String key : repo.getAll().keySet()) {
            if (key.startsWith(KEY_PREFIX_STATUS)) {
                names.add(key.substring(KEY_PREFIX_STATUS.length()));
            }
        }
        for (String name : names) {
            markStatus(name, STATUS_PENDING);
        }
    }

    /**
     * 读取任务当前状态字符串（status_）。
     * 列表逐条展示、Widget 逐条图标均以本方法为准；未知/缺失默认 {@link #STATUS_PENDING}。
     */
    public String getStatus(String taskName) {
        return repo.getString(KEY_PREFIX_STATUS + taskName, STATUS_PENDING);
    }

    /**
     * 任务是否已完成（done_ 镜像，等价于 status == completed）。
     * 仅用于聚合查询（如已完成计数），切勿与 getStatus() 混用造成真相分歧。
     */
    public boolean isCompleted(String taskName) {
        return repo.getBoolean(KEY_PREFIX_DONE + taskName, false);
    }

    /** 记录当前登录用户 ID（跨日重置时保留，用于历史日志归属）。 */
    public void setCurrentUser(String userId) {
        repo.putString(KEY_CURRENT_USER, userId);
    }

    /** 读取当前登录用户 ID，未设置时返回空串。 */
    public String getCurrentUser() {
        return repo.getString(KEY_CURRENT_USER, "");
    }

    /**
     * 统计给定任务名集合中「已完成」的数量。
     * 依赖 {@link #isCompleted(String)}（done_ 镜像），与逐条 getStatus() 展示保持单一真相。
     *
     * @param taskNames 任务名数组（需与 status_/done_ 前缀键对应）
     * @return 已完成任务数
     */
    public int getCompletedCount(String[] taskNames) {
        int count = 0;
        for (String name : taskNames) {
            if (isCompleted(name)) count++;
        }
        return count;
    }

    // ========== 任务历史日志 ==========

    private static final String LOG_DIR = "task_history";

    /**
     * 每日重置时，将前一日的任务完成状态写入日志文件。
     * 文件格式：task_history/yyyy-MM-dd.log，每行 "状态\t任务名"
     */
    private void writeDailyLog(String userId) {
        String yesterday = repo.getString(KEY_DATE, "");
        if (yesterday.isEmpty()) return;

        // 收集前一日的状态
        Map<String, String> statuses = new HashMap<>();
        for (Map.Entry<String, ?> entry : repo.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_PREFIX_STATUS)) {
                statuses.put(key.substring(KEY_PREFIX_STATUS.length()), entry.getValue().toString());
            }
        }
        if (statuses.isEmpty()) return;

        File dir = new File(context.getFilesDir(), LOG_DIR);
        if (!dir.exists() && !dir.mkdirs()) return;

        File logFile = new File(dir, yesterday + ".log");
        try (FileWriter fw = new FileWriter(logFile, false)) {
            fw.write("# date=" + yesterday + "\tuser=" + userId + "\n");
            for (Map.Entry<String, String> e : statuses.entrySet()) {
                fw.write(e.getValue() + "\t" + e.getKey() + "\n");
            }
        } catch (IOException ignored) {
        }
    }
}