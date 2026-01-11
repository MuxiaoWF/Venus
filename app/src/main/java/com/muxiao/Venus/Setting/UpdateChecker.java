package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;

public class UpdateChecker {
    private static final String PREFS_NAME = "update_prefs";
    private static final String LAST_CHECK_TIME = "last_check_time";
    private final Context context;
    private final SharedPreferences sharedPreferences;

    public UpdateChecker(Context context) {
        this.context = context;
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
     * @param updateAuto 是否是自动检查的更新
     */
    private void checkForUpdates(boolean updateAuto) {
        new Thread(() -> {
            try {
                // 保存检查时间
                sharedPreferences.edit().putLong(LAST_CHECK_TIME, System.currentTimeMillis()).apply();
                // 获取当前应用版本
                String currentVersion = getCurrentVersion();
                // 从GitHub获取最新版本信息
                JsonObject releaseInfo = getLatestReleaseInfo();
                if (releaseInfo == null) {
                    if (updateAuto) // 是否自动检查更新-是否展示提示
                        showNoUpdateDialog("\n没能成功获取GitHub更新，请切换更新方式并手动更新\n当前版本：" + currentVersion);
                    return;
                }

                String latestVersion = releaseInfo.get("tag_name").getAsString();
                String releaseNotes = releaseInfo.has("body") ? releaseInfo.get("body").getAsString() : "";
                String downloadUrl = null;

                // 获取APK下载链接
                if (releaseInfo.has("assets")) {
                    JsonObject assets = releaseInfo.getAsJsonArray("assets").get(0).getAsJsonObject();
                    if (assets.has("browser_download_url"))
                        downloadUrl = assets.get("browser_download_url").getAsString();
                }

                // 比较版本
                if (isUpdateAvailable(currentVersion, latestVersion))
                    showUpdateDialog(latestVersion, releaseNotes, downloadUrl);
                else if (updateAuto)
                    showNoUpdateDialog("");
            } catch (Exception e) {
                if (updateAuto)
                    showNoUpdateDialog(e.toString());
            }
        }).start();
    }

    /**
     * 获取当前应用版本
     */
    private String getCurrentVersion() throws PackageManager.NameNotFoundException {
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return packageInfo.versionName;
    }

    /**
     * 从GitHub获取最新发布信息
     */
    private JsonObject getLatestReleaseInfo() {
        try {
            String response = com.muxiao.Venus.common.tools.sendGetRequest(Constants.Urls.MUXIAO_MINE_UPDATE_URL, null, null);
            if (!response.isEmpty()) {
                return JsonParser.parseString(response).getAsJsonObject();
            }
        } catch (Exception e) {
            show_error_dialog(context, "获取GitHub发布信息失败" + e);
        }
        return null;
    }

    /**
     * 比较版本号判断是否有更新
     *
     * @param currentVersion 当前版本
     * @param latestVersion  最新版本
     * @return 是否有更新
     */
    private boolean isUpdateAvailable(String currentVersion, String latestVersion) {
        // 移除版本号前的"v"字符（如果有的话）
        if (latestVersion.startsWith("v"))
            latestVersion = latestVersion.substring(1);
        if (currentVersion.startsWith("v"))
            currentVersion = currentVersion.substring(1);
        // 按数字部分比较版本号
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    /**
     * 比较两个版本号
     *
     * @param version1 版本号1
     * @param version2 版本号2
     * @return 如果version1 > version2返回正数，相等返回0，小于返回负数
     */
    private int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int part1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int part2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (part1 != part2) {
                return part1 - part2;
            }
        }
        return 0;
    }

    /**
     * 显示更新对话框
     */
    private void showUpdateDialog(String latestVersion, String releaseNotes, String downloadUrl) {
        new Handler(Looper.getMainLooper()).post(() -> {
            String message = String.format("发现新版本 %1$s，是否立即更新？", latestVersion);
            if (releaseNotes != null && !releaseNotes.isEmpty())
                message += "\n\n更新内容：\n" + releaseNotes;
            new MaterialAlertDialogBuilder(context)
                    .setTitle("发现新版本")
                    .setMessage(message)
                    .setPositiveButton("立即更新", (dialog, which) -> {
                        if (downloadUrl != null && !downloadUrl.isEmpty()) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                            context.startActivity(intent);
                        }
                    })
                    .setNegativeButton("稍后更新", null)
                    .show();
        });
    }

    /**
     * 显示无更新对话框
     */
    private void showNoUpdateDialog(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (message == null || message.isEmpty())
                new MaterialAlertDialogBuilder(context)
                        .setTitle("无更新")
                        .setMessage("当前已是最新版本")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            else //有错误信息
                new MaterialAlertDialogBuilder(context)
                        .setTitle("无更新？")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
        });
    }
}
