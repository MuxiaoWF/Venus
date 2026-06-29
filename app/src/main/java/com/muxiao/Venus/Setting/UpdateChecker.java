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
                String currentVersion = getCurrentVersion();
                // 从GitHub获取最新版本信息
                JsonObject releaseInfo = getLatestReleaseInfo();
                if (releaseInfo == null) {
                    if (isManual)
                        showNoUpdateDialog("\n" + context.getString(R.string.update_fetch_failed, currentVersion));
                    return;
                }

                String latestVersion = releaseInfo.get("tag_name").getAsString();

                // 比较版本
                if (isUpdateAvailable(currentVersion, latestVersion))
                    showUpdateDialog(latestVersion, releaseInfo);
                else if (isManual)
                    showNoUpdateDialog("");
            } catch (Exception e) {
                if (isManual)
                    showNoUpdateDialog(e.toString());
            }
        }).start();
    }

    /**
     * 获取当前应用版本
     */
    private String getCurrentVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /**
     * 从GitHub获取最新发布信息
     */
    private JsonObject getLatestReleaseInfo() {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Venus-Android/" + getCurrentVersion());
            String response = com.muxiao.Venus.common.tools.sendGetRequest(Constants.Urls.MUXIAO_MINE_UPDATE_URL, headers, null);
            if (!response.isEmpty()) {
                return JsonParser.parseString(response).getAsJsonObject();
            }
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("403")) {
                postErrorToMainThread(context.getString(R.string.update_rate_limited));
            } else {
                postErrorToMainThread(context.getString(R.string.update_fetch_release_failed, msg));
            }
        } catch (Exception e) {
            postErrorToMainThread(context.getString(R.string.update_fetch_release_failed, e.toString()));
        }
        return null;
    }

    private void postErrorToMainThread(String message) {
        new Handler(Looper.getMainLooper()).post(() -> show_error_dialog(context, message));
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

    private int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int part1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int part2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (part1 != part2)
                return part1 - part2;
        }
        return 0;
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

    private void showNoUpdateDialog(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (message == null || message.isEmpty())
                new MaterialAlertDialogBuilder(context)
                        .setTitle(context.getString(R.string.dialog_no_update))
                        .setMessage(context.getString(R.string.msg_already_latest_version))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            else
                new MaterialAlertDialogBuilder(context)
                        .setTitle(context.getString(R.string.dialog_no_update_question))
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
        });
    }
}
