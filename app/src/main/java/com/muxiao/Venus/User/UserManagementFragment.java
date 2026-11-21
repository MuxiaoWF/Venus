package com.muxiao.Venus.User;

import static com.muxiao.Venus.common.Constants.Prefs.SKLAND_COOKIE;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.muxiao.Venus.Home.CloudGameDaily;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.Home.SklandDaily;
import com.muxiao.Venus.Home.TaskSettings;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.common.ui.compose.BottomBarOverlay;
import com.muxiao.Venus.common.ui.compose.VenusDialogs;
import com.muxiao.Venus.common.ui.compose.CredentialChipRow;
import com.muxiao.Venus.common.ui.compose.UserCardRow;
import com.muxiao.Venus.common.ui.compose.UserManagementScreenView;
import com.muxiao.Venus.widget.TaskStatusManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
* 用户管理页控制器：按服务器类型分组展示用户，支持增删/重命名/重登录；任务运行时禁止修改用户（防数据不一致）。
* 界面为 Compose（{@link UserManagementScreenView}），本类不持有子 View：取数据 → 写状态 → 处理回调，
* 「无用户」「默认账号」等显示态一律由 {@code UserManagementUiState} 派生，不做命令式 setVisibility。
*/
public class UserManagementFragment implements PageController {

    private final MainActivity activity;

    private UserManager userManager;
    /** Compose 宿主：所有 UI 更新经其 setter 触发重组。 */
    private UserManagementScreenView screenView;
    /** 待保存头像的用户名：照片选择器是页面级 launcher，结果返回时凭此定位目标用户。 */
    private String pendingAvatarUser;
    /** 底栏覆盖高度订阅（弱引用持有，必须由本类强持有并在 release 解除）。 */
    private final Runnable bottomBarListener = () -> {
        if (screenView != null) screenView.setBottomClearance(BottomBarOverlay.get());
    };
    /** 系统照片选择器（Photo Picker，无需存储权限）：MainActivity 注册后注入。 */
    private ActivityResultLauncher<PickVisualMediaRequest> avatarPicker;

    public UserManagementFragment(MainActivity activity) {
        this.activity = activity;
    }

    public void setAvatarPicker(ActivityResultLauncher<PickVisualMediaRequest> picker) {
        this.avatarPicker = picker;
    }

    /** 照片选择器返回回调（MainActivity 的 launcher 转发）。 */
    public void onAvatarPicked(Uri uri) {
        String username = pendingAvatarUser;
        pendingAvatarUser = null;
        if (username == null || uri == null) return;
        handleAvatarPicked(username, uri);
    }

    /** 构建页面 UI（外壳首次组合时调用一次）：绑定回调、初始化用户列表与底栏订阅。 */
    public View createView(Context context) {
        if (screenView != null) return screenView; // 幂等：外壳重组不会重复装配
        UserManagementScreenView root = new UserManagementScreenView(context);
        screenView = root;

        root.setOnAddUserClickListener(this::onAddUserClick);
        root.setOnAvatarClickListener(this::showAvatarActions);
        root.setOnRenameClickListener(this::onRenameClick);
        root.setOnReloginClickListener(this::performRelogin);
        root.setOnDeleteClickListener(this::showDeleteUserDialog);
        // 凭证入口：一键跳到设置页配置该账号，省去「去设置里找哪一页」的来回
        root.setOnCredentialClickListener(this::onCredentialClick);
        // 任务摘要行：直达设置页并锁定该账号为编辑目标（见 MainActivity.openSettingsForTaskConfig）
        root.setOnTaskSummaryClickListener(activity::openSettingsForTaskConfig);

        // 页眉日期 chip：MM/dd 周几（与设计稿一致）
        root.setDateChip(new java.text.SimpleDateFormat("MM/dd E", java.util.Locale.getDefault())
                .format(new java.util.Date()));

        // 内容不再容器级避让：列表底部留白 + 右下角 FAB 底距都随底栏覆盖高度走
        BottomBarOverlay.addListener(bottomBarListener);
        root.setBottomClearance(BottomBarOverlay.get());

        userManager = new UserManager(context);
        refreshUserList();

        return root;
    }

    /** 页面成为当前选中页 / Activity 恢复：刷新用户列表。 */
    @Override
    public void show() {
        refreshUserList();
    }

    /** 页面失去选中 / Activity 暂停（无逐帧资源，无操作）。 */
    @Override
    public void hide() {
    }

    /** 销毁：解除底栏订阅，清空宿主引用（Activity 销毁时由 MainActivity 调用）。 */
    public void release() {
        BottomBarOverlay.removeListener(bottomBarListener);
        screenView = null;
    }

    private Context getContext() {
        return activity;
    }

    /**
     * 右下角「添加 / 登录用户」：任务运行中拦截，否则先选服务器——国服走账密/扫码登录页，
     * 国际服走网页授权页。服务器类型是「这次要登录哪一服」的一次性选择，不设常驻开关：
     * 选择写入全局 SERVER_TYPE（登录页内部读它），重登录时按目标账号自动对齐，无需用户再管。
     */
    private void onAddUserClick() {
        if (isTaskRunning()) {
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_add));
            return;
        }
        VenusDialogs.items(
                activity,
                activity.getString(R.string.server_type),
                java.util.Arrays.asList(
                        activity.getString(R.string.add_account_cn),
                        activity.getString(R.string.add_account_os)),
                which -> {
                    boolean oversea = which == 1;
                    alignGlobalServer(oversea);
                    activity.startActivity(new Intent(activity,
                            oversea ? OAuthLoginActivity.class : UserLoginActivity.class));
                },
                activity.getString(R.string.btn_cancel));
    }

    /** 对齐全局服别（登录页内部读全局值）：写 SERVER_TYPE 并清 {@code is_oversea} 缓存。 */
    private void alignGlobalServer(boolean oversea) {
        getContext().getSharedPreferences(
                        com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME,
                        android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt(com.muxiao.Venus.common.Constants.Prefs.SERVER_TYPE, oversea ? 1 : 0)
                .apply();
        MiHoYoBBSConstants.clearOverseaCache();
    }

    /**
     * 重建用户卡列表状态（空态/区块标签/计数副标题一并派生）。
     * 列表项增删动画由 Compose 侧 {@code animateItem()} 逐项 diff 完成。
     * <p>
     * 列表为全部账号（含跨服），服别逐卡按该账号自身的 {@code server_type} 渲染。
     */
    private void refreshUserList() {
        if (screenView == null) return;
        List<String> users = userManager.getAllUsernames();
        // 页眉副标题带账号计数（设计稿：管理登录账号 · 共 N 个账号）
        screenView.setSubtitle(activity.getResources().getQuantityString(
                R.plurals.user_count_fmt, users.size(), users.size()));
        // 如果没有用户，显示空态（Compose 侧同时隐藏区块标签）
        if (users.isEmpty()) {
            screenView.setEmptyText(activity.getString(R.string.home_no_user_hint));
            screenView.setUsers(Collections.emptyList());
            return;
        }
        screenView.setEmptyText(null);
        String currentUsername = userManager.getCurrentUser();
        // 任务开关与凭证都按账号存，每张卡按该账号自己的配置计算
        List<UserCardRow> rows = new ArrayList<>(users.size());
        for (String username : users) {
            TaskSettings settings = TaskSettings.fromPreferences(getContext(), username);
            // 服务器后缀按账号取
            String serverType = activity.getString(userManager.isOversea(username)
                    ? R.string.server_os : R.string.server_cn);
            rows.add(new UserCardRow(
                    username,
                    activity.getString(R.string.user_label_with_server,
                            userManager.displayName(username), serverType),
                    // 当前用户标注「默认」chip（设计稿语义）
                    username.equals(currentUsername),
                    credentialChip(settings, username),
                    buildTaskSummary(settings, username)));
        }
        screenView.setUsers(rows);
    }

    /**
     * 卡片上的任务摘要（「签到 12 项 · 今日 8/12」）；该账号四类任务全关时返回 null（不显示摘要行）。
     *
     * <p>只读内存缓存（状态桶热读）与 settings_prefs 的 JSON，N 个账号一次遍历的成本与首页聚合同级。
     */
    private String buildTaskSummary(TaskSettings settings, String username) {
        if (settings.hasAnyTaskDisabled()) return null;
        java.util.List<String> ids = settings.getTaskIds();
        if (ids.isEmpty()) return null;
        int done = 0;
        TaskStatusManager statusManager = new TaskStatusManager(getContext());
        for (String taskId : ids)
            if (TaskStatusManager.STATUS_COMPLETED.equals(statusManager.getStatus(username, taskId))) done++;
        return activity.getString(R.string.user_card_task_summary, ids.size(), done, ids.size());
    }

    /**
     * 一张用户卡上的单项凭证状态：名称 + 状态文案 + 是否就绪 + 进度计数；null 表示该功能未开启、不显示这一项。
     * {@code total} 恒 ≥ 1：云游戏开了总开关却没勾任何游戏时若记 0，汇总会把这项从分母抹掉，
     * 配齐森空岛后就显示「凭证 1/1」而云游戏其实一条都没配。
     */
    private static final class Credential {
        /** 「云游戏」/「森空岛」 */
        final String name;
        /** 「已配置」/「未配置」/「1/2」 */
        final String status;
        final boolean ready;
        /** 该项下的凭证条数与已配好条数，用于两功能合并后的「n/m」汇总。 */
        final int configured;
        final int total;

        Credential(String name, String status, boolean ready, int configured, int total) {
            this.name = name;
            this.status = status;
            this.ready = ready;
            this.configured = configured;
            this.total = total;
        }
    }

    /**
     * 把两项合并成卡片上唯一的凭证入口：只开一个功能时沿用该项文案（「云游戏 1/2」）并直达对应抽屉；
     * 两个都开时压成汇总（「凭证 3/3」/「凭证 待配 2」），点击后由 {@link #onCredentialClick} 让用户先选；
     * 都没开返回 null，整项不渲染。
     */
    private CredentialChipRow credentialChip(TaskSettings settings, String username) {
        Credential cloud = cloudGameCredential(settings, username);
        Credential skland = sklandCredential(settings, username);
        if (cloud == null && skland == null) return null;
        if (cloud == null || skland == null) {
            Credential only = cloud != null ? cloud : skland;
            return new CredentialChipRow(formatCredential(only.name, only.status), only.ready);
        }
        int done = cloud.configured + skland.configured;
        int total = cloud.total + skland.total;
        int pending = Math.max(total - done, 0);
        boolean allReady = total > 0 && done >= total;
        String label = allReady
                ? activity.getString(R.string.credential_chip_ready, done, total)
                : activity.getResources().getQuantityString(
                        R.plurals.credential_chip_pending, pending, pending);
        return new CredentialChipRow(label, allReady);
    }

    /**
     * 云游戏凭证状态：开关按账号（P0-1）、token 按账号且键含服务器类型，两者必须一起看——开关未开「未开启」，
     * 开了但未配「未配置」，配齐「已配置」，部分配置给 n/m 进度。
     */
    private Credential cloudGameCredential(TaskSettings settings, String username) {
        // 功能没开就别在卡片上占位：返回 null，Compose 侧整项不渲染
        if (!settings.isCloudGameEnabled()) return null;
        // token 键与账号所属服绑定：必须按该账号自己判定，不能读全局 SERVER_TYPE
        boolean isOversea = userManager.isOversea(username);
        String name = activity.getString(R.string.user_cred_cloud_game);
        String[] games = settings.getCloudGames();
        // 开了总开关却没勾游戏：仍占 1 条待配名额，否则汇总会把它从分母里漏掉
        if (games == null || games.length == 0)
            return new Credential(
                    name, activity.getString(R.string.user_cred_missing), false, 0, 1);
        int ready = 0;
        for (String game : games) {
            if (CloudGameDaily.isValidToken(
                    tools.read(getContext(), username, CloudGameDaily.tokenKey(game, isOversea)))) ready++;
        }
        if (ready == games.length)
            return new Credential(
                    name, activity.getString(R.string.user_cred_ready),
                    true, ready, games.length);
        return new Credential(
                name, activity.getString(R.string.user_cred_partial, ready, games.length),
                false, ready, games.length);
    }

    /** 森空岛凭证状态（两款游戏共用同一个 token，状态必然一致）。 */
    private Credential sklandCredential(TaskSettings settings, String username) {
        // 同上：两个子游戏都没勾选时这一项没有意义
        if (!settings.isSklandArknightsEnabled() && !settings.isSklandEndfieldEnabled()) return null;
        String name = activity.getString(R.string.user_cred_skland);
        boolean ready = SklandDaily.isValidToken(tools.read(getContext(), username, SKLAND_COOKIE));
        return new Credential(
                name,
                activity.getString(ready ? R.string.user_cred_ready : R.string.user_cred_missing),
                ready, ready ? 1 : 0, 1);
    }

    private String formatCredential(String name, String status) {
        return activity.getString(R.string.user_cred_format, name, status);
    }

    /**
     * 点凭证入口：只开一个功能时直达对应抽屉，两个都开时先让用户选要配哪一项。
     * 业务判断留在 Java 侧，Compose 只上报被点的用户名。
     */
    private void onCredentialClick(String username) {
        // 开关项按该账号自己的配置读（凭证抽屉的「需要配哪几项」由此决定）
        TaskSettings settings = TaskSettings.fromPreferences(getContext(), username);
        Credential cloud = cloudGameCredential(settings, username);
        Credential skland = sklandCredential(settings, username);
        // 两个都开才需要选。弹窗条目带该项自身状态（「云游戏 · 未配置」「森空岛 · 已配置」），
        // 汇总 chip 只显示「待配 N」看不出缺哪一项，这里补上。
        if (cloud != null && skland != null) {
            VenusDialogs.items(
                    activity,
                    activity.getString(R.string.credential_choose_pane),
                    Arrays.asList(credentialOption(cloud), credentialOption(skland)),
                    index -> openCredentialPane(username,
                            index == 0 ? SettingsFragment.PANE_CLOUD_GAME : SettingsFragment.PANE_SKLAND),
                    activity.getString(R.string.btn_cancel));
            return;
        }
        if (cloud == null && skland == null) return;
        openCredentialPane(username,
                cloud != null ? SettingsFragment.PANE_CLOUD_GAME : SettingsFragment.PANE_SKLAND);
    }

    /** 弹窗条目：`云游戏 · 未配置` / `森空岛 · 1/2`，一目了然该配哪一项。 */
    private String credentialOption(Credential credential) {
        return activity.getString(R.string.credential_dialog_item, credential.name, credential.status);
    }

    /**
     * 把这张卡的用户作为「凭证配置目标账号」交给设置页并展开对应抽屉。
     * 不切全局当前账号（切账号会连带首页数据与任务上下文一起变）：抽屉自带目标账号，
     * 只对这一次跳转有效，设置页 {@code show()} 时复位。
     */
    private void openCredentialPane(String username, int pane) {
        activity.openSettingsPane(pane, username);
    }

    /** 信息行点击 = 重命名（设计稿无独立重命名按钮，保留原入口能力）。 */
    private void onRenameClick(String username) {
        if (isTaskRunning())
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_rename));
        else
            showRenameUserDialog(username);
    }

    /** 显示重命名用户对话框（Compose 版输入框 + 就地校验）。 */
    private void showRenameUserDialog(String oldUsername) {
        // 预填展示名；后缀原样保留，避免与另一服的兄弟账号撞键
        String oldDisplay = userManager.displayName(oldUsername);
        String suffix = userManager.serverSuffixOf(oldUsername);
        VenusDialogs.input(
                activity,
                activity.getString(R.string.dialog_rename_user),
                null,
                oldDisplay,
                null,
                activity.getString(R.string.btn_ok),
                activity.getString(R.string.btn_cancel),
                true,
                newUsername -> {
                    if (isTaskRunning()) {
                        showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_rename));
                        return activity.getString(R.string.snack_task_running_cannot_rename);
                    }
                    if (newUsername.isEmpty()) {
                        return activity.getString(R.string.username_hint);
                    }
                    // 输入框是展示名，按展示名比较
                    if (newUsername.equals(oldDisplay)) {
                        return activity.getString(R.string.username_hint);
                    }
                    // 冲突检查针对「新名 + 原后缀」这个真实目标键
                    if (userManager.isUserExists(newUsername + suffix)) {
                        return activity.getString(R.string.snack_username_exists);
                    }
                    return null;
                },
                newUsername -> renameUser(oldUsername, newUsername));
    }

    /**
     * 把账号数据迁移到新名字并同步各处状态。入参为展示名，实际存储 id 会接回原账号的
     * {@code @os}/{@code @cn} 后缀。五类按账号存的存储都要跟着迁。
     */
    private void renameUser(String oldUsername, String newDisplayName) {
        String newUsername = newDisplayName + userManager.serverSuffixOf(oldUsername);
        // 系统后缀标记随 id 迁移
        userManager.transferSuffixedMark(oldUsername, newUsername);
        new com.muxiao.Venus.common.data.UserRepository(getContext()).migrate(oldUsername, newUsername);
        // 头像文件随重命名迁移
        AvatarStore.renameAvatar(getContext(), oldUsername, newUsername);
        // 任务配置跟随账号
        com.muxiao.Venus.Home.TaskProfileStore.rename(getContext(), oldUsername, newUsername);
        // 任务状态桶：不迁会丢当日完成态
        new com.muxiao.Venus.widget.TaskStatusManager(getContext()).renameUser(oldUsername, newUsername);
        // 验证码是一次性数据，换 id 后清掉即可
        com.muxiao.Venus.Home.CaptchaStore.clearSolution(getContext(), oldUsername);
        com.muxiao.Venus.Home.CaptchaStore.clearPending(getContext(), oldUsername);

        userManager.addUser(newUsername);
        if (userManager.getCurrentUser().equals(oldUsername))
            userManager.setCurrentUser(newUsername);
        userManager.removeUser(oldUsername);
        refreshUserList();
        // 用户名变了，首页的账号行 / 下拉列表 / 按账号分桶的任务列表都要跟着重建
        activity.onCurrentUserChanged();
        showCustomSnackbar(screenView, getContext(),
                activity.getString(R.string.snack_user_renamed, newDisplayName));
    }

    /** 头像操作入口：更换（拉起系统照片选择器）或恢复默认图标。 */
    private void showAvatarActions(String username) {
        VenusDialogs.items(
                activity,
                activity.getString(R.string.avatar_action_title),
                java.util.Arrays.asList(
                        activity.getString(R.string.avatar_change),
                        activity.getString(R.string.avatar_reset)
                ),
                which -> {
                    if (which == 0) {
                        pendingAvatarUser = username;
                        avatarPicker.launch(new PickVisualMediaRequest.Builder()
                                // Kotlin object 的 Java 互斥写法：取其单例 INSTANCE（与设置页同款）
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build());
                    } else {
                        AvatarStore.deleteAvatar(getContext(), username);
                        refreshUserList();
                        screenView.refreshAvatar();
                        // 重置的是当前账号时，首页头像也要跟着变回默认图
                        if (username.equals(userManager.getCurrentUser()))
                            activity.onAvatarChanged();
                    }
                },
                activity.getString(R.string.btn_cancel));
    }

    /** 照片选择器回调：后台线程解码落盘，完成后回主线程刷新列表并提示。 */
    private void handleAvatarPicked(String username, Uri uri) {
        android.content.Context appContext = getContext().getApplicationContext();
        AppExecutors.get().execute(() -> {
            boolean saved = AvatarStore.saveFromUri(appContext, username, uri);
            activity.runOnUiThread(() -> {
                if (screenView == null) return;
                refreshUserList();
                // 头像文件按用户名定址、原地覆盖：必须显式让卡片与首页重载，否则显示旧图
                screenView.refreshAvatar();
                if (saved && username.equals(userManager.getCurrentUser()))
                    activity.onAvatarChanged();
                showCustomSnackbar(screenView, getContext(), activity.getString(saved
                        ? R.string.snack_avatar_saved
                        : R.string.err_avatar_save_failed));
            });
        });
    }

    /**
     * 显示删除用户对话框
     *
     * @param username 用户名
     */
    private void showDeleteUserDialog(String username) {
        if (isTaskRunning()) {
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_delete));
            return;
        }
        String display = userManager.displayName(username);
        VenusDialogs.confirm(
                activity,
                activity.getString(R.string.dialog_delete_user),
                activity.getString(R.string.msg_delete_user_confirm, display),
                activity.getString(R.string.btn_delete),
                () -> {
                    userManager.removeUser(username);
                    // 头像文件随用户一并清除，避免残留
                    AvatarStore.deleteAvatar(getContext(), username);
                    // 任务配置同口径清理：否则 task_profile_<旧账号> 会永远留在 settings_prefs 里
                    com.muxiao.Venus.Home.TaskProfileStore.delete(getContext(), username);
                    // 任务状态桶与验证码一并清理
                    new com.muxiao.Venus.widget.TaskStatusManager(getContext()).clearUser(username);
                    com.muxiao.Venus.Home.CaptchaStore.clearPending(getContext(), username);
                    com.muxiao.Venus.Home.CaptchaStore.clearSolution(getContext(), username);
                    refreshUserList();
                    // 删的可能正是当前账号（removeUser 会清掉当前用户标记）：
                    // 首页据此回落到剩余账号，否则账号行会空着、任务按已删除的用户分桶
                    activity.onCurrentUserChanged();
                    showCustomSnackbar(screenView, getContext(),
                            activity.getString(R.string.snack_user_deleted, display));
                },
                activity.getString(R.string.btn_cancel));
    }

    /**
     * 检查是否有任务正在运行
     */
    private boolean isTaskRunning() {
        return HomeFragment.isTaskRunning();
    }

    /** 切换为指定用户并启动登录页（RELOGIN_MODE=true），用于刷新其 Cookie/凭证。 */
    private void performRelogin(String username) {
        if (isTaskRunning()) {
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_relogin));
            return;
        }
        userManager.setCurrentUser(username);
        // 重新登录进入该账号自身所属服的登录页；全局服别按目标账号对齐（登录页内部读全局值）
        boolean oversea = userManager.isOversea(username);
        alignGlobalServer(oversea);
        Intent intent = new Intent(activity,
                oversea ? OAuthLoginActivity.class : UserLoginActivity.class);
        intent.putExtra("RELOGIN_MODE", true);
        intent.putExtra("USERNAME", username); // 传递用户名
        activity.startActivity(intent);
    }
}
