package com.muxiao.Venus.widget;

import android.content.Context;

import com.google.gson.Gson;
import com.muxiao.Venus.Home.TaskItem;
import com.muxiao.Venus.common.data.TaskStatusRepository;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 持久化每日任务完成状态，按日期自动重置、按用户分桶存储。
 * Widget 通过此类读取状态，App（前台/后台任务）通过此类写入状态。
 *
 * <p>存储结构（经 {@link TaskStatusRepository}/DataStore）：
 * <ul>
 *   <li>{@code task_status_bucket_<userId>}：单用户状态桶，JSON 序列化的 {@link Bucket}
 *       （归属日期 + 任务名→状态映射）。一个用户一个键、键后缀即用户名，无需前缀解析；</li>
 *   <li>{@code current_user}：「最后一次运行任务的用户」，跨日保留，仅作小组件目标用户兜底。</li>
 * </ul>
 *
 * <p>「是否完成」不再有独立存储的 done_ 镜像键：done 恒等推导自 {@code status == completed}，
 * 聚合计数与逐条展示共用同一份真相，从结构上消除旧版 status_/done_ 双键分歧的可能。
 * 旧版全局键（status_/done_/status_date）由 {@link #ensureToday()} 一次性迁移到最后运行用户的桶。
 */
public class TaskStatusManager {

    /** 单用户状态桶键前缀：完整键 = 前缀 + userId。 */
    private static final String KEY_PREFIX_BUCKET = "task_status_bucket_";
    private static final String KEY_CURRENT_USER = "current_user";
    // 旧版全局单桶键：仅升级迁移时读取，迁移完成后清除
    private static final String LEGACY_KEY_DATE = "status_date";
    private static final String LEGACY_PREFIX_STATUS = "status_";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_WARNING = "warning";
    public static final String STATUS_CANCELLED = "cancelled";

    private final Context context;
    private final TaskStatusRepository repo;
    private final Gson gson = new Gson();

    /** 单用户状态桶：状态归属日期 + 任务名→状态。 */
    private static final class Bucket {
        String date = "";
        LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
    }

    /**
     * 桶读改写互斥：整桶「读-改-写」期间持有，防止两路写入交错覆盖丢更新。
     * 读路径（getStatus 等）不加锁，DataStore 缓存的原子引用足够。
     * 锁序恒为 ENSURE_LOCK → BUCKET_WRITE_LOCK（写方先 ensureToday 再进桶锁，避免死锁）。
     */
    private static final Object BUCKET_WRITE_LOCK = new Object();

    /**
     * 进程内「已校验过的日期」标记。
     * <p>
     * {@link #ensureToday()} 的实际工作（迁移 + 写历史日志 + 跨天复位）只在跨天后首次触达时需要，
     * 而本类是重灾区：每次任务状态变化、每次小组件刷新、每次列表重建都会 new 一个实例。
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
    }

    private static String todayString() {
        return Objects.requireNonNull(DATE_FORMAT.get()).format(new Date());
    }

    private static String str(Object value) {
        return value != null ? value.toString() : "";
    }

    // ========== 跨天校验与旧版迁移 ==========

    /**
     * 跨天全局校验：把所有用户桶中「昨日及更早」的状态写入历史日志后复位到今天，
     * 并完成旧版全局键（升级前）到用户桶的一次性迁移。每进程每天最多执行一次完整流程。
     */
    private void ensureToday() {
        String today = todayString();
        // 快速路径：本进程已确认过今天，无需再读仓库
        if (today.equals(lastEnsuredDate)) return;
        synchronized (ENSURE_LOCK) {
            if (today.equals(lastEnsuredDate)) return;
            synchronized (BUCKET_WRITE_LOCK) {
                ensureTodayLocked(today);
            }
            lastEnsuredDate = today;
        }
    }

    private void ensureTodayLocked(String today) {
        Map<String, ?> all = repo.getAll();
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        // 历史日志按归属日期归并（设备停用多日时可能出现多个日期）
        TreeMap<String, StringBuilder> logs = new TreeMap<>();

        // —— 旧版全局键迁移：status_<name>（done_ 是镜像无需迁移）→ 最后一次运行用户的桶 ——
        String legacyDate = str(all.get(LEGACY_KEY_DATE));
        if (!legacyDate.isEmpty()) {
            Bucket legacy = new Bucket();
            legacy.date = legacyDate;
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey().startsWith(LEGACY_PREFIX_STATUS) && !e.getKey().equals(LEGACY_KEY_DATE))
                    legacy.statuses.put(e.getKey().substring(LEGACY_PREFIX_STATUS.length()), str(e.getValue()));
            }
            String owner = str(all.get(KEY_CURRENT_USER));
            if (legacy.date.equals(today) && !owner.isEmpty()) {
                // 当日数据并入用户桶：升级不丢「今天已运行」的完成记录
                Bucket target = bucketOf(buckets, owner, today);
                for (Map.Entry<String, String> e : legacy.statuses.entrySet()) {
                    if (!target.statuses.containsKey(e.getKey()))
                        target.statuses.put(e.getKey(), e.getValue());
                }
            } else {
                appendLogSection(logs, owner, legacy);
            }
        }

        // —— 既有用户桶：跨天的先记日志再复位到今天 ——
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(KEY_PREFIX_BUCKET)) continue;
            String userId = key.substring(KEY_PREFIX_BUCKET.length());
            Bucket b = parseBucket(str(e.getValue()));
            if (b == null) continue;
            if (!b.date.equals(today)) {
                appendLogSection(logs, userId, b);
                b.statuses.clear();
                b.date = today;
            }
            buckets.put(userId, b);
        }

        // —— 一次性重写仓库：clear 同时清掉全部旧版键（status_/done_/status_date）——
        Map<String, Object> rewrite = new HashMap<>(buckets.size() + 1);
        String currentUser = str(all.get(KEY_CURRENT_USER));
        if (!currentUser.isEmpty()) rewrite.put(KEY_CURRENT_USER, currentUser);
        for (Map.Entry<String, Bucket> e : buckets.entrySet())
            rewrite.put(KEY_PREFIX_BUCKET + e.getKey(), gson.toJson(e.getValue()));
        repo.clear();
        repo.putAll(rewrite);

        for (Map.Entry<String, StringBuilder> e : logs.entrySet())
            writeDailyLog(e.getKey(), e.getValue().toString());
    }

    private static Bucket bucketOf(Map<String, Bucket> buckets, String userId, String today) {
        Bucket b = buckets.get(userId);
        if (b == null) {
            b = new Bucket();
            b.date = today;
            buckets.put(userId, b);
        }
        return b;
    }

    // ========== 读写 ==========

    /** 反序列化桶 JSON；空串/损坏 JSON 返回 null，由调用方按新建桶处理。 */
    private Bucket parseBucket(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return gson.fromJson(raw, Bucket.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 读取单用户桶；无桶/损坏 JSON 一律按新建桶处理（空状态 + 今日归属）。 */
    private Bucket loadBucket(String userId) {
        Bucket b = parseBucket(repo.getString(KEY_PREFIX_BUCKET + userId, ""));
        if (b == null) b = new Bucket();
        if (b.date == null || b.date.isEmpty()) b.date = todayString();
        if (b.statuses == null) b.statuses = new LinkedHashMap<>();
        return b;
    }

    private void storeBucket(String userId, Bucket b) {
        repo.putString(KEY_PREFIX_BUCKET + userId, gson.toJson(b));
    }

    // ========== 状态写入（前台 ViewModel / 后台服务统一入口） ==========

    /**
     * 将 UI 层任务状态写入指定用户的桶。PENDING 无对应写入
     * （保持原实现的 default 分支语义：不改变已存状态）。
     */
    public void applyStatus(String userId, String taskName, TaskItem.TaskStatus status) {
        switch (status) {
            case COMPLETED:
                markStatus(userId, taskName, STATUS_COMPLETED);
                break;
            case ERROR:
                markStatus(userId, taskName, STATUS_ERROR);
                break;
            case IN_PROGRESS:
                markStatus(userId, taskName, STATUS_IN_PROGRESS);
                break;
            case WARNING:
                markStatus(userId, taskName, STATUS_WARNING);
                break;
            case CANCELLED:
                markStatus(userId, taskName, STATUS_CANCELLED);
                break;
            case RETAINED:
                // 仅 UI 展示态（本轮未触达的已完成任务），不持久化、不改变已存状态
                break;
            default:
                break;
        }
    }

    /**
     * 设置任务状态（通用入口）。
     * 「是否完成」恒等推导自 {@code status == STATUS_COMPLETED}，无独立镜像键，
     * 也就不存在旧版 status_/done_ 双键分歧（聚合计数虚高）的可能。
     */
    public void markStatus(String userId, String taskName, String status) {
        ensureToday();
        synchronized (BUCKET_WRITE_LOCK) {
            Bucket b = loadBucket(userId);
            b.statuses.put(taskName, status);
            storeBucket(userId, b);
        }
    }

    /**
     * 新一轮任务开始前复位指定用户桶：仅把「瞬态」复位为 {@link #STATUS_PENDING}——
     * 进行中（可能来自被杀进程的残留）、已取消（上一轮终止的痕迹）、warning（旧版本
     * 等待验证码时写入的残留）一律归零；
     * 当日「终态结果」（已完成 / 失败）保留，直到该任务在本轮被真正重跑
     * （置 IN_PROGRESS）才被覆盖——因此重跑中途终止不会抹掉之前已取得的完成记录。
     * 逐名重建映射：配置中已去掉的旧任务名随重建一并清除，归属日期刷新为今天。
     * 跨天由 {@link #ensureToday()} 另行自动复位。
     */
    public void resetTransientStatuses(String userId, java.util.Collection<String> taskNames) {
        ensureToday();
        synchronized (BUCKET_WRITE_LOCK) {
            Bucket b = loadBucket(userId);
            LinkedHashMap<String, String> next = new LinkedHashMap<>(taskNames.size());
            for (String name : taskNames) {
                String prev = b.statuses.get(name);
                boolean terminal = STATUS_COMPLETED.equals(prev) || STATUS_ERROR.equals(prev);
                next.put(name, terminal ? prev : STATUS_PENDING);
            }
            b.statuses = next;
            b.date = todayString();
            storeBucket(userId, b);
        }
    }

    // ========== 状态读取 ==========

    /** 读取指定用户任务的当前状态；未知/缺失默认 {@link #STATUS_PENDING}。 */
    public String getStatus(String userId, String taskName) {
        ensureToday();
        String s = loadBucket(userId).statuses.get(taskName);
        return s != null ? s : STATUS_PENDING;
    }

    /** 指定用户全部任务状态快照（键=任务名），供列表/Widget 批量渲染，避免逐条重复解析。 */
    public Map<String, String> getAllStatuses(String userId) {
        ensureToday();
        return new LinkedHashMap<>(loadBucket(userId).statuses);
    }

    /** 读取指定用户的状态归属日期（格式 yyyy-MM-dd；尚无桶时即今天）。 */
    public String getStatusDate(String userId) {
        ensureToday();
        return loadBucket(userId).date;
    }

    // ========== current_user（全局键） ==========

    /**
     * 记录「本次运行任务的用户」（运行开始时写入一次，跨日重置时保留）。
     * 历史日志归属已随分桶自带（桶键即用户名），本键现仅作小组件目标用户的兜底。
     */
    public void setCurrentUser(String userId) {
        repo.putString(KEY_CURRENT_USER, userId);
    }

    /** 读取「最后一次运行任务的用户」，未设置时返回空串。 */
    public String getCurrentUser() {
        return repo.getString(KEY_CURRENT_USER, "");
    }

    // ========== UI 层状态（TaskItem.TaskStatus）与持久化状态的映射 ==========

    /** 将持久化状态字符串映射为 UI 层任务状态；未知/缺失一律按 {@code PENDING}。
     *  旧版本的 warning（等待验证码时短暂写入，现已不再产生）按进行中展示，
     *  避免当日残留数据被渲染成绿色「已完成」。 */
    public static TaskItem.TaskStatus toTaskStatus(String status) {
        if (status == null) return TaskItem.TaskStatus.PENDING;
        switch (status) {
            case STATUS_COMPLETED:
                return TaskItem.TaskStatus.COMPLETED;
            case STATUS_ERROR:
                return TaskItem.TaskStatus.ERROR;
            case STATUS_IN_PROGRESS:
            case STATUS_WARNING:
                return TaskItem.TaskStatus.IN_PROGRESS;
            case STATUS_CANCELLED:
                return TaskItem.TaskStatus.CANCELLED;
            default:
                return TaskItem.TaskStatus.PENDING;
        }
    }

    // ========== 任务历史日志 ==========

    private static final String LOG_DIR = "task_history";

    /** 归并一段用户的历史日志：同一天的所有用户写进同一个文件，按 "## user=" 分节区分。 */
    private static void appendLogSection(TreeMap<String, StringBuilder> logs, String userId, Bucket b) {
        if (b.statuses.isEmpty() || b.date.isEmpty()) return;
        StringBuilder sb = logs.get(b.date);
        if (sb == null) {
            sb = new StringBuilder();
            logs.put(b.date, sb);
        }
        sb.append("## user=").append(userId.isEmpty() ? "(unknown)" : userId).append('\n');
        for (Map.Entry<String, String> e : b.statuses.entrySet())
            sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
    }

    /**
     * 将跨天日志写入 task_history/yyyy-MM-dd.log（每日覆盖写）。
     * 文件头 "# date=yyyy-MM-dd"，每用户一节 "## user=<name>"，节内每行 "状态\t任务名"。
     */
    private void writeDailyLog(String date, String content) {
        File dir = new File(context.getFilesDir(), LOG_DIR);
        if (!dir.exists() && !dir.mkdirs()) return;

        File logFile = new File(dir, date + ".log");
        try (FileWriter fw = new FileWriter(logFile, false)) {
            fw.write("# date=" + date + "\n");
            fw.write(content);
        } catch (IOException ignored) {
        }
    }
}
