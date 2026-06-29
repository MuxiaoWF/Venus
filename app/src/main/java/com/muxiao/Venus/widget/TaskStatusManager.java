package com.muxiao.Venus.widget;

import android.content.Context;
import android.content.SharedPreferences;

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
 * Widget 通过此类读取状态，App 通过此类写入状态。
 */
public class TaskStatusManager {

    private static final String PREFS_NAME = "task_status_prefs";
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
    private final SharedPreferences prefs;

    public TaskStatusManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureToday();
    }

    private static String todayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void ensureToday() {
        String today = todayString();
        if (!today.equals(prefs.getString(KEY_DATE, ""))) {
            // 保留 current_user 跨日持久化，清除任务状态
            String savedUser = prefs.getString(KEY_CURRENT_USER, "");
            // 先写入历史日志（此时 prefs 还保留着昨日数据）
            writeDailyLog(savedUser);
            prefs.edit().clear()
                    .putString(KEY_DATE, today)
                    .putString(KEY_CURRENT_USER, savedUser)
                    .apply();
        }
    }

    public void markCompleted(String taskName) {
        prefs.edit()
                .putBoolean(KEY_PREFIX_DONE + taskName, true)
                .putString(KEY_PREFIX_STATUS + taskName, STATUS_COMPLETED)
                .apply();
    }

    public void markStatus(String taskName, String status) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_PREFIX_STATUS + taskName, status);
        if (STATUS_COMPLETED.equals(status)) {
            editor.putBoolean(KEY_PREFIX_DONE + taskName, true);
        }
        editor.apply();
    }

    public void markError(String taskName) {
        markStatus(taskName, STATUS_ERROR);
    }

    public String getStatus(String taskName) {
        return prefs.getString(KEY_PREFIX_STATUS + taskName, STATUS_PENDING);
    }

    public boolean isCompleted(String taskName) {
        return prefs.getBoolean(KEY_PREFIX_DONE + taskName, false);
    }

    public void setCurrentUser(String userId) {
        prefs.edit().putString(KEY_CURRENT_USER, userId).apply();
    }

    public String getCurrentUser() {
        return prefs.getString(KEY_CURRENT_USER, "");
    }

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
        String yesterday = prefs.getString(KEY_DATE, "");
        if (yesterday.isEmpty()) return;

        // 收集前一日的状态
        Map<String, String> statuses = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
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