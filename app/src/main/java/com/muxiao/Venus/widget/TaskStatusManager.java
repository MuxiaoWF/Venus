package com.muxiao.Venus.widget;

import android.content.Context;

import com.google.gson.Gson;
import com.muxiao.Venus.Home.TaskIds;
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
 * 每日任务完成状态的持久化：经 {@link TaskStatusRepository} 按用户分桶存储
 * （{@code task_status_bucket_<userId>} = JSON 化的 {@link Bucket}：归属日期 + 任务 id→状态），
 * {@code current_user} 仅作小组件目标用户兜底；Widget 读、App 前台/后台写。
 *
 * <p><b>键是稳定 id（{@link TaskIds}），不是任务名</b>：任务名随语言变化，曾经当键导致切换语言后
 * 今日状态整表失配（小组件全回落待执行、日志与重试名单跨语言对不上）。旧版写入的本地化名称键由
 * {@link #ensureToday()} 一次性迁移为 id。
 *
 * <p>无 done_ 镜像键：done 恒等推导自 {@code status == completed}，聚合计数与逐条展示同源；
 * 旧版全局键（status_/done_/status_date）同样在 {@link #ensureToday()} 中迁移到最后运行用户的桶。
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
    /** 挂起等待人工完成人机验证：不是失败，续跑轮会带着验证结果重跑。 */
    public static final String STATUS_WAITING_CAPTCHA = "waiting_captcha";

    private final Context context;
    private final TaskStatusRepository repo;
    private final Gson gson = new Gson();

    /** 单用户状态桶：状态归属日期 + 任务名→状态。 */
    private static final class Bucket {
        String date = "";
        LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
    }

    /**
     * 桶读改写互斥：整桶「读-改-写」期间持有，防两路写入交错覆盖；读路径不加锁（DataStore 缓存原子引用）。
     * 锁序恒为 ENSURE_LOCK → BUCKET_WRITE_LOCK（先 ensureToday 再进桶锁），避免死锁。
     */
    private static final Object BUCKET_WRITE_LOCK = new Object();

    /**
     * 进程内「今日已校验」标记：本类每次状态变化/小组件刷新/列表重建都会 new 实例，
     * 用静态标记把快速路径挡在锁外，跨天的迁移+日志+复位每进程只做一次。
     */
    private static volatile String lastEnsuredDate;
    private static final Object ENSURE_LOCK = new Object();

    /**
     * SimpleDateFormat 非线程安全，按线程复用；ThreadLocal.withInitial 需 API 26，故用匿名子类兼容 minSdk 23。
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
    /**
     * 跨天校验：把各用户桶中「昨日及更早」的状态写入历史日志后复位到今天，
     * 并完成旧版全局键到用户桶的一次性迁移；每进程每天最多走一次完整流程。
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
        // 旧版本地化名 → id：本次重写时把桶内键一次性换成稳定 id（进程内只枚举一次语言）
        Map<String, String> legacyNames = TaskIds.legacyNameIndex(context);

        // —— 旧版全局键迁移：status_<name>（done_ 是镜像无需迁移）→ 最后一次运行用户的桶 ——
        String legacyDate = str(all.get(LEGACY_KEY_DATE));
        if (!legacyDate.isEmpty()) {
            Bucket legacy = new Bucket();
            legacy.date = legacyDate;
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey().startsWith(LEGACY_PREFIX_STATUS) && !e.getKey().equals(LEGACY_KEY_DATE))
                    legacy.statuses.put(e.getKey().substring(LEGACY_PREFIX_STATUS.length()), str(e.getValue()));
            }
            // 先换 id：后续并入/落日志都按当前语言呈现，且不会再写回旧名称键
            normalizeLegacyKeys(legacy, legacyNames);
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
            normalizeLegacyKeys(b, legacyNames);
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
    /** 反序列化桶 JSON；空串/损坏 JSON 返回 null，由调用方按新建桶处理。 */
    private Bucket parseBucket(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return gson.fromJson(raw, Bucket.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把桶内的旧版本地化名称键换成稳定 id（键序保持原样）。
     *
     * <p>容错口径：认不出的键原样保留（不吞用户数据，{@link TaskIds#displayName} 也能原样展示）；
     * 同一任务在多语言下各写过一份时，保留「非 pending」的那条——pending 是默认值，真状态信息量更大。
     */
    private void normalizeLegacyKeys(Bucket bucket, Map<String, String> legacyNames) {
        if (bucket.statuses == null || bucket.statuses.isEmpty()) return;
        LinkedHashMap<String, String> next = new LinkedHashMap<>(bucket.statuses.size());
        for (Map.Entry<String, String> e : bucket.statuses.entrySet()) {
            String key = e.getKey();
            String id = TaskIds.isKnown(key) ? key : legacyNames.get(key);
            if (id == null) id = key;
            String existing = next.get(id);
            if (existing == null || STATUS_PENDING.equals(existing)) next.put(id, e.getValue());
        }
        bucket.statuses = next;
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
    /** 写入指定用户桶；PENDING 无对应写入（保持原 default 分支语义：不改变已存状态）。 */
    public void applyStatus(String userId, String taskId, TaskItem.TaskStatus status) {
        switch (status) {
            case COMPLETED:
                markStatus(userId, taskId, STATUS_COMPLETED);
                break;
            case ERROR:
                markStatus(userId, taskId, STATUS_ERROR);
                break;
            case IN_PROGRESS:
                markStatus(userId, taskId, STATUS_IN_PROGRESS);
                break;
            case WARNING:
                markStatus(userId, taskId, STATUS_WARNING);
                break;
            case CANCELLED:
                markStatus(userId, taskId, STATUS_CANCELLED);
                break;
            case WAITING_CAPTCHA:
                markStatus(userId, taskId, STATUS_WAITING_CAPTCHA);
                break;
            case RETAINED:
                // 仅 UI 展示态（本轮未触达的已完成任务），不持久化、不改变已存状态
                break;
            default:
                break;
        }
    }

    /**
     * 设置任务状态（通用入口）。done 恒等推导自 {@code status == STATUS_COMPLETED}，
     * 无独立镜像键，也就没有 status_/done_ 双键分歧导致聚合计数虚高的可能。
     *
     * @param taskId 稳定任务 id（见 {@link TaskIds}），不是显示名
     */
    public void markStatus(String userId, String taskId, String status) {
        ensureToday();
        synchronized (BUCKET_WRITE_LOCK) {
            Bucket b = loadBucket(userId);
            b.statuses.put(taskId, status);
            storeBucket(userId, b);
        }
    }

    /**
     * 新一轮任务前复位指定用户桶：瞬态（进行中残留、已取消、旧版 warning）归 {@link #STATUS_PENDING}，
     * 当日终态（已完成/失败）保留到该任务被真正重跑，故中途终止不会抹掉已有完成记录。
     * 逐 id 重建映射，配置中已移除的任务随之清除；跨天由 {@link #ensureToday()} 处理。
     */
    public void resetTransientStatuses(String userId, java.util.Collection<String> taskIds) {
        ensureToday();
        synchronized (BUCKET_WRITE_LOCK) {
            Bucket b = loadBucket(userId);
            LinkedHashMap<String, String> next = new LinkedHashMap<>(taskIds.size());
            for (String taskId : taskIds) {
                String prev = b.statuses.get(taskId);
                boolean terminal = STATUS_COMPLETED.equals(prev) || STATUS_ERROR.equals(prev);
                next.put(taskId, terminal ? prev : STATUS_PENDING);
            }
            b.statuses = next;
            b.date = todayString();
            storeBucket(userId, b);
        }
    }

    /** 读取指定用户任务的当前状态；未知/缺失默认 {@link #STATUS_PENDING}。 */
    public String getStatus(String userId, String taskId) {
        ensureToday();
        String s = loadBucket(userId).statuses.get(taskId);
        return s != null ? s : STATUS_PENDING;
    }

    /** 指定用户全部任务状态快照（键=任务 id），供列表/Widget 批量渲染，避免逐条重复解析。 */
    public Map<String, String> getAllStatuses(String userId) {
        ensureToday();
        return new LinkedHashMap<>(loadBucket(userId).statuses);
    }

    /** 读取指定用户的状态归属日期（格式 yyyy-MM-dd；尚无桶时即今天）。 */
    public String getStatusDate(String userId) {
        ensureToday();
        return loadBucket(userId).date;
    }
    /** 记录本次运行任务的用户（运行开始写一次，跨日保留），现仅作小组件目标用户兜底。 */
    public void setCurrentUser(String userId) {
        repo.putString(KEY_CURRENT_USER, userId);
    }

    /** 读取「最后一次运行任务的用户」，未设置时返回空串。 */
    public String getCurrentUser() {
        return repo.getString(KEY_CURRENT_USER, "");
    }
    /** 持久化状态 → UI 状态；未知/缺失按 {@code PENDING}。旧版 warning（已不再产生）
     *  按进行中展示，避免当日残留被渲染成绿色「已完成」。 */
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
            case STATUS_WAITING_CAPTCHA:
                return TaskItem.TaskStatus.WAITING_CAPTCHA;
            default:
                return TaskItem.TaskStatus.PENDING;
        }
    }
    private static final String LOG_DIR = "task_history";

    /** 归并一段用户的历史日志：同一天的所有用户写进同一个文件，按 "## user=" 分节区分。 */
    private void appendLogSection(TreeMap<String, StringBuilder> logs, String userId, Bucket b) {
        if (b.statuses.isEmpty() || b.date.isEmpty()) return;
        StringBuilder sb = logs.get(b.date);
        if (sb == null) {
            sb = new StringBuilder();
            logs.put(b.date, sb);
        }
        sb.append("## user=").append(userId.isEmpty() ? "(unknown)" : userId).append('\n');
        // 日志面向用户阅读：键是 id，写出前换成当前语言的显示名
        for (Map.Entry<String, String> e : b.statuses.entrySet())
            sb.append(e.getValue()).append('\t').append(TaskIds.displayName(context, e.getKey())).append('\n');
    }

    /**
     * 跨天日志写 task_history/yyyy-MM-dd.log（每日覆盖）：头 "# date=yyyy-MM-dd"，
     * 每用户一节 "## user=<name>"，节内每行 "状态\t任务名"。
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
