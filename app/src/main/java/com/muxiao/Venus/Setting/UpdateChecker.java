package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.BuildConfig;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;

import java.util.HashMap;
import java.util.Map;

/**
 * 版本更新检查：查询 GitHub Releases API，比较版本号，弹窗提示用户更新。
 */
public class UpdateChecker {
    private static final String LAST_CHECK_TIME = "last_check_time";
    private final Context context;
    private final SharedPreferences sharedPreferences;

    public UpdateChecker(Context context) {
        this.context = context;
        this.sharedPreferences = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 检查是否需要执行更新检查（每隔3天）
     */
    public void checkForUpdatesIfNeeded() {
        long lastCheckTime = sharedPreferences.getLong(LAST_CHECK_TIME, 0);
        long currentTime = System.currentTimeMillis();
        long interval = 3 * 24 * 60 * 60 * 1000; // 3天转换为毫秒
        if (currentTime - lastCheckTime >= interval)
            checkForUpdates(false);
    }

    /**
     * 立即检查更新
     */
    public void checkForUpdatesImmediately() {
        checkForUpdates(true);
    }

    /**
     * 检查更新
     *
     * @param isManual 是否是用户手动触发的检查
     */
    private void checkForUpdates(boolean isManual) {
        new Thread(() -> {
            try {
                // 保存检查时间
                sharedPreferences.edit().putLong(LAST_CHECK_TIME, System.currentTimeMillis()).apply();
                // 获取当前应用版本
                String currentVersion = BuildConfig.VERSION_NAME;
                // 从GitHub获取最新版本信息
                JsonObject releaseInfo = getLatestReleaseInfo();
                if (releaseInfo == null) {
                    // 失败只报一次错：错误文案已由 getLatestReleaseInfo 记入 lastFetchError；
                    // 自动检查（每 3 天随启动触发）失败保持静默，下次启动会再试，不打扰用户。
                    if (isManual)
                        showErrorDialog(lastFetchError);
                    return;
                }

                // tag_name 缺失时无法比较版本，按「检查失败」处理而不是抛 NPE
                String latestVersion = releaseInfo.has("tag_name") && !releaseInfo.get("tag_name").isJsonNull()
                        ? releaseInfo.get("tag_name").getAsString() : null;
                if (latestVersion == null) {
                    if (isManual)
                        showErrorDialog("\n" + context.getString(R.string.update_fetch_failed, currentVersion));
                    return;
                }

                // 比较版本
                if (isUpdateAvailable(currentVersion, latestVersion))
                    showUpdateDialog(latestVersion, releaseInfo);
                else if (isManual)
                    showNoUpdateDialog();
            } catch (Exception e) {
                // JSON 解析等意外异常：仅手动检查时给出含原因的错误框，自动检查保持静默
                if (isManual) {
                    String cause = e.getMessage() != null ? e.getMessage() : e.toString();
                    showErrorDialog(context.getString(R.string.update_fetch_release_failed, cause));
                }
            }
        }).start();
    }

    /**
     * 从GitHub获取最新发布信息；失败时把友好错误文案记入 {@link #lastFetchError} 并返回 null。
     * 不在此处直接弹窗：是否提示、何时提示由调用方按「手动/自动检查」决定，避免一次失败弹两个对话框。
     */
    private JsonObject getLatestReleaseInfo() {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Venus-Android/" + BuildConfig.VERSION_NAME);
            String response = com.muxiao.Venus.common.tools.sendGetRequest(Constants.Urls.MUXIAO_MINE_UPDATE_URL, headers, null);
            if (!response.isEmpty()) {
                return JsonParser.parseString(response).getAsJsonObject();
            }
            lastFetchError = context.getString(R.string.update_fetch_release_failed,
                    context.getString(R.string.update_empty_response));
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("403")) {
                lastFetchError = context.getString(R.string.update_rate_limited);
            } else {
                lastFetchError = context.getString(R.string.update_fetch_release_failed, msg);
            }
        } catch (Exception e) {
            lastFetchError = context.getString(R.string.update_fetch_release_failed, e.toString());
        }
        return null;
    }

    /** 最近一次获取发布信息的友好错误文案（线程内先后读写，无跨线程共享问题）。 */
    private String lastFetchError = "";

    /** 在主线程弹出「检查更新失败」错误对话框（含复制错误入口）。 */
    private void showErrorDialog(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (context instanceof android.app.Activity && !((android.app.Activity) context).isFinishing())
                show_error_dialog(context, context.getString(R.string.dialog_update_check_failed), message);
        });
    }

    /**
     * 比较版本号判断是否有更新
     */
    private boolean isUpdateAvailable(String currentVersion, String latestVersion) {
        if (latestVersion.startsWith("v"))
            latestVersion = latestVersion.substring(1);
        if (currentVersion.startsWith("v"))
            currentVersion = currentVersion.substring(1);
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    /**
     * 按“.”分段比较版本号，返回 version1 减 version2 的差值（>0 表示 v1 更新）。
     * 段内出现非纯数字（如 {@code 1.2.3-beta}）时退化为字符串比较，
     * 避免 Integer.parseInt 抛 NumberFormatException 导致整个更新检查以错误弹窗收场。
     */
    private int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            String part1 = i < parts1.length ? parts1[i] : "0";
            String part2 = i < parts2.length ? parts2[i] : "0";
            Integer number1 = parseIntOrNull(part1);
            Integer number2 = parseIntOrNull(part2);
            int compared = number1 != null && number2 != null
                    ? Integer.compare(number1, number2)
                    : part1.compareTo(part2);
            if (compared != 0)
                return compared;
        }
        return 0;
    }

    /** 纯数字段返回其值，否则返回 null（交由字符串比较兜底）。 */
    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 显示更新对话框，引导用户前往浏览器下载
     */
    private void showUpdateDialog(String latestVersion, JsonObject releaseInfo) {
        new Handler(Looper.getMainLooper()).post(() -> {
            String releaseNotes = releaseInfo.has("body") ? releaseInfo.get("body").getAsString() : "";
            // 构造 releases 页面链接
            String tagName = releaseInfo.has("tag_name") ? releaseInfo.get("tag_name").getAsString() : latestVersion;
            String releasesPageUrl = Constants.Urls.MUXIAO_MINE_GITHUB_URL + "/releases/tag/" + tagName;

            String message = context.getString(R.string.update_new_version_msg, latestVersion);
            if (releaseNotes != null && !releaseNotes.isEmpty())
                message += "\n\n" + context.getString(R.string.update_release_notes) + "\n" + releaseNotes;
            new MaterialAlertDialogBuilder(context)
                    .setTitle(context.getString(R.string.dialog_new_version_found))
                    .setMessage(message)
                    .setPositiveButton(context.getString(R.string.btn_go_to_download), (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(releasesPageUrl));
                        context.startActivity(intent);
                    })
                    .setNegativeButton(context.getString(R.string.btn_update_later), null)
                    .show();
        });
    }

    /** 弹「已是最新版本」对话框（仅手动检查到达该分支时出现）。 */
    private void showNoUpdateDialog() {
        new Handler(Looper.getMainLooper()).post(() ->
                new MaterialAlertDialogBuilder(context)
                        .setTitle(context.getString(R.string.dialog_no_update))
                        .setMessage(context.getString(R.string.msg_already_latest_version))
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
    }
}
