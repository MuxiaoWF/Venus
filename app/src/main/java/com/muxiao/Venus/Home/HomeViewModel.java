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

import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * HomeFragment 的 UI 状态持有者（Now in Android：ViewModel + 不可变 UiState + 单向数据流，P0-2）。
 *
 * <p>本轮（P0-2 阶段 2）把原先散落在 Fragment 的三类职责收进 ViewModel：
 * <ol>
 *   <li><b>数据访问</b>：任务列表构建（TaskSettings + TaskStatusManager）与任务状态持久化，
 *       Fragment 不再直接 new TaskStatusManager / 读 SharedPreferences；</li>
 *   <li><b>运行形态</b>：以 {@link RunMode} 三态互斥模型取代原先分散在 4 处的 setVisibility 调用，
 *       按钮/下拉框可见性由 {@link TaskUiState} 派生，Fragment 只做渲染；</li>
 *   <li><b>快照不可变</b>：UiState 每次整体替换，items 为只读列表，避免 UI 持有可变模型。</li>
 * </ol>
 *
 * <p>继承 AndroidViewModel 以获取 Application Context（无需引入 @HiltViewModel，
 * 默认 ViewModelProvider 工厂即可构造，改动面最小）。
 * 后台线程用 postUiState，主线程用 setUiState，避免裸 runOnUiThread 竞态。
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
     * 不可变 UI 状态快照。可见性一律由本类派生，禁止 Fragment 自行判断，
     * 以保证「同一时刻只有一套可见性真相」。
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

        /** 用户下拉框：仅前台任务运行时隐藏（后台运行时仍可切换用户，与原行为一致）。 */
        public boolean userDropdownVisible() {
            return runMode != RunMode.FOREGROUND;
        }

        /** 取消按钮：仅前台任务运行时出现。 */
        public boolean cancelButtonVisible() {
            return runMode == RunMode.FOREGROUND;
        }

        /** 启动按钮：仅空闲时出现。 */
        public boolean startButtonVisible() {
            return runMode == RunMode.IDLE;
        }

        /** 后台按钮：设置开启且非前台运行时出现。 */
        public boolean bgButtonVisible() {
            return bgFeatureEnabled && runMode != RunMode.FOREGROUND;
        }

        /** 后台按钮文案：后台运行中显示「取消任务」，否则显示「后台运行」。 */
        public boolean bgButtonShowsCancel() {
            return runMode == RunMode.BACKGROUND;
        }
    }

    /**
     * 权威状态。刻意不用 LiveData.getValue() 作为读取来源：
     * 任务回调会从后台线程读取当前快照，volatile 字段的可见性语义比 LiveData 内部字段更明确。
     * LiveData 仅作为向 UI 广播的镜像。
     */
    private volatile TaskUiState state;
    private final MutableLiveData<TaskUiState> uiState;
    /** 主线程 Handler：publishAsync 投递时重读最新 state，避免 postValue 异步乱序。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 当前选中用户（单一可信来源，供任务状态持久化使用）。 */
    private volatile String currentUser = "";

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
     * 注意：不能直接用 uiState.postValue(next)，否则与 publish 的 setValue 存在投递乱序——
     * 例如后台任务取消时，CANCELLED 状态广播先到（postValue 排队旧 BACKGROUND 快照）、
     * is_running=false 广播后到（setValue 立即置 IDLE），随后排队的 postValue 会把 IDLE 覆盖回 BACKGROUND，
     * 导致「取消后开始按钮不回显」。这里改为投递时重读最新 state，保证 LiveData 永远与权威 state 一致。 */
    private void publishAsync(TaskUiState next) {
        state = next;
        mainHandler.post(() -> uiState.setValue(state));
    }

    public String getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(String user) {
        currentUser = user != null ? user : "";
    }

    // ========== 运行形态 ==========

    /** 主线程切换运行形态。 */
    public void setRunMode(RunMode mode) {
        publish(current().withRunMode(mode));
    }

    /**
     * 依据后台服务广播同步运行形态。
     *
     * <p>刻意不覆盖 FOREGROUND：后台服务未运行的广播不应把正在跑的前台任务误判为空闲
     * （原实现 updateBgButtonState(false) 会在前台任务运行期间把启动按钮恢复可见，
     * 与取消按钮同时出现，属状态机缺陷，此处一并修正）。
     */
    public void applyBackgroundRunning(boolean backgroundRunning) {
        RunMode mode = current().runMode;
        if (mode == RunMode.FOREGROUND) return;
        setRunMode(backgroundRunning ? RunMode.BACKGROUND : RunMode.IDLE);
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

    // ========== 任务列表 ==========

    /** 依据当前设置与持久化状态重建任务列表（主线程）。 */
    public void refreshTaskItems() {
        refreshTaskItems(TaskSettings.fromPreferences(getApplication()));
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
            if (!a.get(i).getName().equals(b.get(i).getName())
                    || a.get(i).getStatus() != b.get(i).getStatus())
                return false;
        }
        return true;
    }

    /**
     * 开始新一轮任务前调用：复位临时状态（保留当日已完成）并重建列表，
     * 避免上次取消/失败/进行中的状态在重新运行时被短暂显示。
     */
    public void resetForNewRun() {
        TaskSettings settings = TaskSettings.fromPreferences(getApplication());
        new TaskStatusManager(getApplication())
                .resetTransientStatuses(settings.getTaskNames(getApplication()));
        refreshTaskItems(settings);
    }

    private List<TaskItem> buildTaskItems(TaskSettings settings) {
        Context context = getApplication();
        TaskStatusManager statusManager = new TaskStatusManager(context);
        List<TaskItem> items = new ArrayList<>();
        for (String taskName : settings.getTaskNames(context)) {
            TaskItem item = new TaskItem(taskName);
            item.setStatus(TaskStatusManager.toTaskStatus(statusManager.getStatus(taskName)));
            items.add(item);
        }
        return items;
    }

    /**
     * 更新单个任务状态：持久化 + 发布新快照 + 刷新小组件。
     * 用于「本页线程执行任务」的场景（本页是唯一的状态写入方）。
     * 可从任意线程调用（内部用 postValue）。
     */
    public void updateTaskStatus(String taskName, TaskItem.TaskStatus status) {
        persistTaskStatus(taskName, status);
        applyExternalTaskStatus(taskName, status);
        TaskWidgetProvider.refreshAllWidgets(getApplication());
    }

    /**
     * 仅更新 UI 快照，不做任何持久化、不刷新小组件。
     * <p>
     * 用于「后台服务 → 广播 → 本页」的场景：{@code ForegroundTaskService} 已经在写入方
     * 完成了持久化与小组件刷新，本页只负责把图标刷新成最新状态。原实现复用
     * {@link #updateTaskStatus}，导致同一条状态被写两遍 DataStore（每遍含 current_user
     * 与成对的 status_/done_）、小组件也被全量刷新两遍。
     */
    public void applyExternalTaskStatus(String taskName, TaskItem.TaskStatus status) {
        // 基于旧快照复制出新列表，保持 UiState 不可变语义
        TaskUiState snapshot = current();
        List<TaskItem> updated = new ArrayList<>(snapshot.items.size());
        for (TaskItem old : snapshot.items) {
            TaskItem copy = new TaskItem(old.getName());
            copy.setStatus(old.getName().equals(taskName) ? status : old.getStatus());
            updated.add(copy);
        }
        publishAsync(snapshot.withItems(updated));
    }

    /** 记录当前用户供 Widget 显示。 */
    public void persistCurrentUserForWidget() {
        new TaskStatusManager(getApplication()).setCurrentUser(currentUser);
    }

    /**
     * 持久化任务状态。
     * <p>
     * 不再逐条调用 {@code setCurrentUser}：当前用户已在 {@link #persistCurrentUserForWidget()}
     * 于运行开始时写入一次，运行期间恒定；逐条重写只会让 DataStore 多背一条无意义的事务。
     */
    private void persistTaskStatus(String taskName, TaskItem.TaskStatus status) {
        new TaskStatusManager(getApplication()).applyStatus(taskName, status);
    }
}
