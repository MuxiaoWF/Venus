package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HomeFragment 的 UI 状态持有者（ViewModel + 不可变 UiState + 单向数据流）。
 *
 * <p>把原先散落在 Fragment 的三类职责收进来：任务列表构建与状态持久化（Fragment 不再直接 new
 * TaskStatusManager / 读 SharedPreferences）；运行形态改由 {@link RunMode} 三态互斥，按钮可见性由
 * {@link TaskUiState} 派生，Fragment 只做渲染；UiState 每次整体替换、items 只读，避免 UI 持有可变模型。
 *
 * <p>继承 AndroidViewModel 以取 Application Context（无需 @HiltViewModel，默认工厂即可构造）。
 * 后台线程用 {@link #publishAsync}、主线程用 {@link #publish}，避免裸 runOnUiThread 竞态。
 */
public class HomeViewModel extends AndroidViewModel {

    /** 任务运行形态（互斥三态）。 */
    public enum RunMode {
        /** 空闲：可启动前台/后台任务。 */
        IDLE,
        /** 前台任务进行中（本页线程执行）。 */
        FOREGROUND,
        /** 后台任务进行中（ForegroundTaskService 执行）。 */
        BACKGROUND
    }

    /**
     * 不可变 UI 状态快照。可见性一律由本类派生，禁止 Fragment 自行判断，保证「同一时刻只有一套可见性真相」。
     */
    public static final class TaskUiState {
        public final List<TaskItem> items;
        public final RunMode runMode;
        /** 设置项「后台任务」是否开启（决定后台按钮是否出现）。 */
        public final boolean bgFeatureEnabled;

        TaskUiState(List<TaskItem> items, RunMode runMode, boolean bgFeatureEnabled) {
            this.items = List.copyOf(items);
            this.runMode = runMode;
            this.bgFeatureEnabled = bgFeatureEnabled;
        }

        TaskUiState withItems(List<TaskItem> newItems) {
            return new TaskUiState(newItems, runMode, bgFeatureEnabled);
        }

        TaskUiState withRunMode(RunMode newMode) {
            return new TaskUiState(items, newMode, bgFeatureEnabled);
        }

        TaskUiState withBgFeatureEnabled(boolean enabled) {
            return new TaskUiState(items, runMode, enabled);
        }

        /**
         * 后台按钮文案：后台运行中显示「取消任务」，否则显示「后台运行」。
         * <p>
         * 可见性（下拉框/启动/取消/重试/后台行）的派生已随主页迁移到 Compose，见
         * {@code common/ui/compose/HomeScreen.kt} 顶部的私有扩展属性——规则只有那一份；这里只保留运行形态供
         * Java 侧判定文案与流程分支。
         */
        public boolean bgButtonShowsCancel() {
            return runMode == RunMode.BACKGROUND;
        }
    }

    /**
     * 权威状态。刻意不用 LiveData.getValue() 作为读取来源：任务回调会从后台线程读当前快照，
     * volatile 字段的可见性语义比 LiveData 内部字段更明确；LiveData 仅作向 UI 广播的镜像。
     */
    private volatile TaskUiState state;
    private final MutableLiveData<TaskUiState> uiState;
    /** 主线程 Handler：publishAsync 投递时重读最新 state，避免 postValue 异步乱序。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 当前选中用户（单一可信来源，供任务状态持久化使用）。 */
    private volatile String currentUser = "";

    /**
     * 本轮已收到过状态回调的任务，键为 {@code userId|taskId}。运行期间凡 COMPLETED 且不在本集合中的任务按
     * {@code RETAINED} 展示（见 TaskItem.TaskStatus.RETAINED）。由 {@link #beginRun()} 每轮清空。
     */
    private final Set<String> touchedTasks = new HashSet<>();

    /** 触达集合的键：账号 + 任务 id。 */
    private static String touchKey(String userId, String taskId) {
        return (userId == null ? "" : userId) + "|" + taskId;
    }

    public HomeViewModel(@NonNull Application application) {
        super(application);
        state = new TaskUiState(new ArrayList<>(), RunMode.IDLE, readBgFeatureEnabled());
        uiState = new MutableLiveData<>(state);
    }

    public LiveData<TaskUiState> getUiState() {
        return uiState;
    }

    @NonNull
    private TaskUiState current() {
        return state;
    }

    /** 主线程发布新快照。 */
    private void publish(TaskUiState next) {
        state = next;
        uiState.setValue(next);
    }

    /** 任意线程发布新快照。
     * 不能直接用 uiState.postValue(next)：与 {@link #publish} 的 setValue 存在投递乱序——例如后台任务取消时
     * CANCELLED 广播先到（postValue 排队旧 BACKGROUND 快照）、is_running=false 后到（setValue 立即置 IDLE），
     * 随后排队的 postValue 会把 IDLE 覆盖回 BACKGROUND，导致「取消后开始按钮不回显」。
     * 这里改为投递时重读最新 state，保证 LiveData 永远与权威 state 一致。 */
    private void publishAsync(TaskUiState next) {
        state = next;
        mainHandler.post(() -> uiState.setValue(state));
    }

    public String getCurrentUser() {
        return currentUser;
    }

    /** 新一轮运行开始：清空触达集合。批量运行只在整批开始时调用一次。 */
    public void beginRun() {
        touchedTasks.clear();
    }

    /**
     * 展示态派生：运行期间（非 IDLE）凡 COMPLETED 且本账号本轮未触达的任务以 RETAINED 展示。
     * RETAINED 仅为展示态——持久化与回调链路始终用原始状态，回到 IDLE 后自然还原为绿色。
     *
     * @param taskId 稳定任务 id（触达集合按 {@code userId|id} 记录）
     */
    private TaskItem.TaskStatus deriveDisplayStatus(String taskId, TaskItem.TaskStatus status) {
        if (status == TaskItem.TaskStatus.COMPLETED
                && current().runMode != RunMode.IDLE
                && !touchedTasks.contains(touchKey(currentUser, taskId)))
            return TaskItem.TaskStatus.RETAINED;
        return status;
    }

    public void setCurrentUser(String user) {
        currentUser = user != null ? user : "";
    }

    /** 主线程切换运行形态。 */
    public void setRunMode(RunMode mode) {
        publish(current().withRunMode(mode));
    }

    /**
     * 依据后台服务广播同步运行形态。
     *
     * <p>刻意不覆盖 FOREGROUND：后台服务未运行的广播不应把正在跑的前台任务误判为空闲
     * （否则启动按钮会在前台任务期间恢复可见、与取消按钮同时出现）。
     */
    public void applyBackgroundRunning(boolean backgroundRunning) {
        RunMode mode = current().runMode;
        if (mode == RunMode.FOREGROUND) return;
        // IDLE → BACKGROUND 意味着页面外（如小组件）启动了新一轮运行，
        // 与手动入口的 beginRun 同口径清空触达集合
        if (backgroundRunning && mode == RunMode.IDLE) touchedTasks.clear();
        setRunMode(backgroundRunning ? RunMode.BACKGROUND : RunMode.IDLE);
        // 形态切换后重建列表：进入 BACKGROUND 时把未触达的已完成项派生为 RETAINED，
        // 回到 IDLE 时清除 RETAINED、恢复持久层原状（itemsEqual 保证无变化时不发布）
        refreshTaskItems();
    }

    /**
     * 以真实运行状态对齐 UI（onResume 等时机调用），前台优先。
     *
     * @param foregroundRunning 前台任务线程是否存活
     * @param backgroundRunning 后台服务是否在运行
     */
    public void syncRunMode(boolean foregroundRunning, boolean backgroundRunning) {
        RunMode mode = foregroundRunning ? RunMode.FOREGROUND
                : (backgroundRunning ? RunMode.BACKGROUND : RunMode.IDLE);
        publish(current()
                .withRunMode(mode)
                .withBgFeatureEnabled(readBgFeatureEnabled()));
        // onResume 对齐运行形态后重建列表：运行中回到本页时，
        // 未触达的已完成任务按 RETAINED 展示（触达集合随 ViewModel 实例存活，进程内存活期内准确）
        refreshTaskItems();
    }

    /** 重新读取「后台任务」设置项（设置页可能已变更）。 */
    public void refreshBgFeatureEnabled() {
        publish(current().withBgFeatureEnabled(readBgFeatureEnabled()));
    }

    private boolean readBgFeatureEnabled() {
        SharedPreferences prefs = getApplication()
                .getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(BACKGROUND_TASK_ENABLED, false);
    }

    /** 依据当前设置与持久化状态重建任务列表（主线程）。 */
    public void refreshTaskItems() {
        // 任务配置按账号：读的是「本页当前账号」的配置，不是全局配置
        refreshTaskItems(TaskSettings.fromPreferences(getApplication(), currentUser));
    }

    /** 依据给定设置重建任务列表（主线程）。 */
    public void refreshTaskItems(TaskSettings settings) {
        List<TaskItem> newItems = buildTaskItems(settings);
        // 与当前快照完全一致时跳过发布：onResume/切页返回等时机不再引发
        // 无谓的整列 notifyDataSetChanged 重绑（重绑虽安静，但属纯浪费的闪烁隐患）
        if (itemsEqual(current().items, newItems)) return;
        publish(current().withItems(newItems));
    }

    private static boolean itemsEqual(List<TaskItem> a, List<TaskItem> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getId().equals(b.get(i).getId())
                    // 名称参与比较：切换语言后同一 id 的显示名会变，须触发列表重建
                    || !a.get(i).getName().equals(b.get(i).getName())
                    || a.get(i).getStatus() != b.get(i).getStatus()
                    // 配置变动（补填 token）要能触发列表重建，否则划线不会消失
                    || a.get(i).isRunnable() != b.get(i).isRunnable())
                return false;
        }
        return true;
    }

    /**
     * 开始新一轮任务前调用：把用户桶内的瞬态（进行中/已取消）复位为待执行并重建列表，当日终态
     * （已完成/失败）保留——任务被真正执行到时才切回进行中并覆盖结果，重跑中途终止不会丢失已完成记录。
     * 语义以 {@link TaskStatusManager#resetTransientStatuses} 为准；后台按钮与小组件入口的复位由
     * ForegroundTaskService 在启动时统一执行，本方法覆盖本页前台运行。
     */
    public void resetForNewRun() {
        TaskSettings settings = TaskSettings.fromPreferences(getApplication(), currentUser);
        new TaskStatusManager(getApplication())
                .resetTransientStatuses(currentUser, settings.getTaskIds());
        refreshTaskItems(settings);
    }

    private List<TaskItem> buildTaskItems(TaskSettings settings) {
        // 取名必须用「当前设置语言」的 Context：Application 的语言只在进程启动时 attachBaseContext 锁定一次，
        // 运行期切换语言只会 recreate Activity；直接用 getApplication() 会拿到旧语言的名称，
        // 与小组件（每次现算 LocaleHelper.wrap）不一致——旧版正是因此在切语言后两侧列表对不上。
        Context context = LocaleHelper.wrap(getApplication());
        TaskStatusManager statusManager = new TaskStatusManager(context);
        List<TaskItem> items = new ArrayList<>();
        // 凭证未配齐的任务仍留在列表里（用户看得见自己开了什么），只是标记为不可执行：
        // 列表划线灰显、运行入口直接跳过，不发请求也不报错。
        java.util.Map<String, Boolean> readiness = settings.getTaskReadiness(context, currentUser);
        // 状态按用户分桶、按稳定 id 归属：列表始终反映当前选中用户自己的完成情况，且与语言无关
        for (TaskDescriptor task : settings.getTasks(context)) {
            TaskItem item = new TaskItem(task.id, task.name);
            Boolean ready = readiness.get(task.id);
            item.setRunnable(ready == null || ready);
            item.setStatus(deriveDisplayStatus(task.id,
                    TaskStatusManager.toTaskStatus(statusManager.getStatus(currentUser, task.id))));
            items.add(item);
        }
        return items;
    }

    /**
     * 更新单个任务状态：持久化 + 发布新快照 + 刷新小组件。用于「本页线程执行任务」的场景（本页是唯一写入方）。
     * 可从任意线程调用。
     *
     * @param taskId 稳定任务 id
     */
    public void updateTaskStatus(String taskId, TaskItem.TaskStatus status) {
        // 收到回调即视为本轮已触达（带账号）
        touchedTasks.add(touchKey(currentUser, taskId));
        persistTaskStatus(taskId, status);
        applyExternalTaskStatus(currentUser, taskId, status);
        TaskWidgetProvider.refreshAllWidgets(getApplication());
    }

    /**
     * 仅更新 UI 快照，不做持久化、不刷新小组件。
     * <p>
     * 用于「后台服务 → 广播 → 本页」：{@code ForegroundTaskService} 已完成按运行用户的分桶持久化与小组件刷新，
     * 本页只把图标刷成最新状态；复用 {@link #updateTaskStatus} 会让同一条状态写两遍 DataStore、小组件刷两遍。
     *
     * @param userId 状态归属的运行用户：后台运行 A 期间切到 B 时不改写 B 的列表，切回 A 时
     *               {@link #refreshTaskItems()} 会从 A 的桶重新渲染实时进度
     * @param taskId 稳定任务 id（广播载荷，不是显示名）
     */
    public void applyExternalTaskStatus(String userId, String taskId, TaskItem.TaskStatus status) {
        // 先于展示用户判断打点（带账号）
        touchedTasks.add(touchKey(userId, taskId));
        // 展示用户 ≠ 运行用户时跳过：避免后台运行中切换用户后，A 的进度按 id 混入 B 的列表
        String owner = userId == null ? "" : userId;
        if (!owner.equals(currentUser)) return;
        // 基于旧快照复制出新列表，保持 UiState 不可变语义
        TaskUiState snapshot = current();
        List<TaskItem> updated = new ArrayList<>(snapshot.items.size());
        for (TaskItem old : snapshot.items) {
            TaskItem copy = new TaskItem(old.getId(), old.getName());
            copy.setStatus(old.getId().equals(taskId)
                    ? deriveDisplayStatus(taskId, status) : old.getStatus());
            copy.setRunnable(old.isRunnable());
            updated.add(copy);
        }
        publishAsync(snapshot.withItems(updated));
    }

    /** 记录当前用户供 Widget 显示。 */
    public void persistCurrentUserForWidget() {
        new TaskStatusManager(getApplication()).setCurrentUser(currentUser);
    }

    /**
     * 持久化任务状态。不逐条调 {@code setCurrentUser}：当前用户已在 {@link #persistCurrentUserForWidget()}
     * 于运行开始时写一次、运行期间恒定，逐条重写只会让 DataStore 多背无意义的事务。
     */
    private void persistTaskStatus(String taskId, TaskItem.TaskStatus status) {
        new TaskStatusManager(getApplication()).applyStatus(currentUser, taskId, status);
    }
}
