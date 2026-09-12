package com.muxiao.Venus.widget;

import android.content.Context;

import com.muxiao.Venus.Home.TaskItem;
import com.muxiao.Venus.common.data.TaskStatusRepository;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 持久化每日任务完成状态，按日期自动重置。
 * Widget 通过此类读取状态，App（前台/后台任务）通过此类写入状态。
 *
 * <p>状态由两条键共同表达，且二者必须满足单一真相不变量：
 * <ul>
 *   <li>{@code status_<name>}：任务当前状态字符串（pending/in_progress/completed/error/warning/canceled）；</li>
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

    /**
     * 进程内「已校验过的日期」标记。
     * <p>
     * {@link #ensureToday()} 的实际工作（写历史日志 + 清库）只在跨天时需要，但 TaskStatusManager
     * 是重灾区：每次任务状态变化、每次小组件刷新、每次列表重建都会 new 一个实例。若每次都走完整流程，
     * 就要重复做日期格式化与仓库读取，跨天那一刻还会在主线程触发文件写。
     * 用一个静态标记把「今天已校验」的快速路径挡在锁外，跨天只处理一次。
     */
    private static volatile String lastEnsuredDate;
    private static final Object ENSURE_LOCK = new Object();

    /**
     * 日期格式化器：SimpleDateFormat 非线程安全，按线程复用避免高频 new。
     * 注：ThreadLocal.withInitial 需 API 26，此处用匿名子类兼容 minSdk 23。
     */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
        new ThreadLocal<>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            }
        };

    public TaskStatusManager(Context context) {
        this.context = context.getApplicationContext();
        repo = new TaskStatusRepository(context);
        ensureToday();
    }

    private static String todayString() {
        return Objects.requireNonNull(DATE_FORMAT.get()).format(new Date());
    }

    private void ensureToday() {
        String today = todayString();
        // 快速路径：本进程已确认过今天，无需再读仓库
        if (today.equals(lastEnsuredDate)) return;
        synchronized (ENSURE_LOCK) {
            if (today.equals(lastEnsuredDate)) return;
            if (!today.equals(repo.getString(KEY_DATE, ""))) {
                // 保留 current_user 跨日持久化，清除任务状态
                String savedUser = repo.getString(KEY_CURRENT_USER, "");
                // 先写入历史日志（此时缓存还保留着昨日数据）
                writeDailyLog(savedUser);
                repo.clear();
                repo.putAll(dailyResetKeys(today, savedUser));
            }
            lastEnsuredDate = today;
        }
    }

    /** 跨日重置后的首批写入（日期 + 沿用用户）：合并为一次事务。 */
    private static Map<String, Object> dailyResetKeys(String today, String savedUser) {
        Map<String, Object> values = new HashMap<>();
        values.put(KEY_DATE, today);
        values.put(KEY_CURRENT_USER, savedUser);
        return values;
    }

    /**
     * 标记任务为已完成（等价于 {@code markStatus(taskName, STATUS_COMPLETED)}，
     * 由后者统一维护 done_ 镜像，避免两条写入路径各写一份）。
     */
    public void markCompleted(String taskName) {
        markStatus(taskName, STATUS_COMPLETED);
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
        Map<String, Object> values = new HashMap<>(2);
        values.put(KEY_PREFIX_STATUS + taskName, status);
        values.put(KEY_PREFIX_DONE + taskName, STATUS_COMPLETED.equals(status));
        repo.putAll(values);
    }

    /** 标记任务为错误态（等价于 markStatus(taskName, STATUS_ERROR)）。 */
    public void markError(String taskName) {
        markStatus(taskName, STATUS_ERROR);
    }

    // ========== UI 层状态（TaskItem.TaskStatus）与持久化状态的映射 ==========
    // 说明：正向（UI → 持久化）与反向（持久化 → UI）映射都只在本类维护，
    // 避免前台服务与 ViewModel 各写一份 switch 而出现分歧。

    /**
     * 将 UI 层任务状态写入持久化：COMPLETED 与 {@link #markCompleted(String)} 等价。
     * PENDING 无对应写入（保持原实现的 default 分支语义：不改变已存状态）。
     */
    public void applyStatus(String taskName, TaskItem.TaskStatus status) {
        switch (status) {
            case COMPLETED:
                markCompleted(taskName);
                break;
            case ERROR:
                markError(taskName);
                break;
            case IN_PROGRESS:
                markStatus(taskName, STATUS_IN_PROGRESS);
                break;
            case WARNING:
                markStatus(taskName, STATUS_WARNING);
                break;
            case CANCELLED:
                markStatus(taskName, STATUS_CANCELLED);
                break;
            default:
                break;
        }
    }

    /** 将持久化状态字符串（status_）映射为 UI 层任务状态；未知/缺失一律按 {@code PENDING}。 */
    public static TaskItem.TaskStatus toTaskStatus(String status) {
        if (status == null) return TaskItem.TaskStatus.PENDING;
        switch (status) {
            case STATUS_COMPLETED:
                return TaskItem.TaskStatus.COMPLETED;
            case STATUS_ERROR:
                return TaskItem.TaskStatus.ERROR;
            case STATUS_IN_PROGRESS:
                return TaskItem.TaskStatus.IN_PROGRESS;
            case STATUS_WARNING:
                return TaskItem.TaskStatus.WARNING;
            case STATUS_CANCELLED:
                return TaskItem.TaskStatus.CANCELLED;
            default:
                return TaskItem.TaskStatus.PENDING;
        }
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
        if (names.isEmpty()) return;
        // 全部任务合并为一次事务：逐个 markStatus 会产生 2N 次 DataStore 写入
        Map<String, Object> values = new HashMap<>(names.size() * 2);
        for (String name : names) {
            values.put(KEY_PREFIX_STATUS + name, STATUS_PENDING);
            values.put(KEY_PREFIX_DONE + name, false);
        }
        repo.putAll(values);
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
