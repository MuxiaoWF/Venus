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
import com.muxiao.Venus.common.ui.compose.UserCardRow;
import com.muxiao.Venus.common.ui.compose.UserManagementScreenView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户管理页控制器（原 Fragment 收敛为控制器）：按服务器类型分组展示用户列表，
 * 支持添加/删除/重命名用户、重新登录。任务运行时禁止修改用户（防止数据不一致）。
 *
 * <p>界面为 Compose（{@link UserManagementScreenView}）：本类不再持有任何子 View 引用，
 * 只负责取数据 → 写状态 → 处理回调。「无用户」「默认账号」等显示态一律由
 * {@code UserManagementUiState} 派生，不再命令式 setVisibility。</p>
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

    /** 注入 MainActivity 注册的照片选择器启动器。 */
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
        // 凭证状态 chip：一键跳到设置页对应抽屉，省去「去设置里找哪一页」的来回
        root.setOnCloudGameClickListener(username ->
                openCredentialPane(username, SettingsFragment.PANE_CLOUD_GAME));
        root.setOnSklandClickListener(username ->
                openCredentialPane(username, SettingsFragment.PANE_SKLAND));

        // 页眉日期 chip：MM/dd 周几（与设计稿一致）
        root.setDateChip(new java.text.SimpleDateFormat("MM/dd E", java.util.Locale.getDefault())
                .format(new java.util.Date()));

        // 内容不再容器级避让：列表底部留白 + 右下角 FAB 底距都随底栏覆盖高度走
        // （原来分别是 BottomBarOverlay.attachClearance / attachMargin 的职责）
        BottomBarOverlay.addListener(bottomBarListener);
        root.setBottomClearance(BottomBarOverlay.get());

        userManager = new UserManager(context);
        refreshUserList();

        return root;
    }

    /** 页面成为当前选中页 / Activity 恢复：刷新用户列表。 */
    @Override
    public void show() {
        // 恢复页面也刷新用户列表
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

    /** 右下角「添加 / 登录用户」：任务运行中拦截，否则按服务器类型进对应登录页。 */
    private void onAddUserClick() {
        if (isTaskRunning()) {
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_add));
            return;
        }
        boolean isOversea = MiHoYoBBSConstants.is_oversea(getContext());
        Intent intent = new Intent(activity,
                isOversea ? OAuthLoginActivity.class : UserLoginActivity.class);
        activity.startActivity(intent);
    }

    /**
     * 按当前服务器类型重建用户卡列表状态（空态/区块标签/计数副标题一并派生）。
     *
     * <p>原实现逐条 inflate 视图、手动挂 LayoutTransition 做增删过渡；
     * 现在只交一份数据给 Compose，列表项动画由 {@code animateItem()} 逐项 diff 完成。</p>
     */
    private void refreshUserList() {
        if (screenView == null) return;
        // 根据当前服务器类型过滤用户
        boolean isOversea = MiHoYoBBSConstants.is_oversea(getContext());
        List<String> users = userManager.getUsernamesByServerType(isOversea);
        // 页眉副标题带账号计数（设计稿：管理登录账号 · 共 N 个账号）
        screenView.setSubtitle(activity.getResources().getQuantityString(
                R.plurals.user_count_fmt, users.size(), users.size()));
        // 如果没有用户，显示空态（Compose 侧同时隐藏区块标签）
        if (users.isEmpty()) {
            String serverName = isOversea ? activity.getString(R.string.server_os) : activity.getString(R.string.server_cn);
            screenView.setEmptyText(activity.getString(R.string.msg_no_server_user, serverName));
            screenView.setUsers(Collections.emptyList());
            return;
        }
        screenView.setEmptyText(null);
        String currentUsername = userManager.getCurrentUser();
        // 显示用户名和服务器类型（占位符格式化，避免 setText 拼接触发 Lint SetTextI18n）
        String serverType = activity.getString(isOversea ? R.string.server_os : R.string.server_cn);
        // 功能开关是全局配置、凭证是按账号的，所以每张卡的状态要按「开关 × 该账号 token」算
        TaskSettings settings = TaskSettings.fromPreferences(getContext());
        List<UserCardRow> rows = new ArrayList<>(users.size());
        for (String username : users) {
            Credential cloud = cloudGameCredential(settings, username, isOversea);
            Credential skland = sklandCredential(settings, username);
            rows.add(new UserCardRow(
                    username,
                    activity.getString(R.string.user_label_with_server, username, serverType),
                    // 当前用户标注「默认」chip（设计稿语义）
                    username.equals(currentUsername),
                    cloud.label, cloud.ready,
                    skland.label, skland.ready));
        }
        screenView.setUsers(rows);
    }

    /** 一张用户卡上的单项凭证状态：展示文案 + 是否就绪（决定 chip 配色）。 */
    private static final class Credential {
        final String label;
        final boolean ready;

        Credential(String label, boolean ready) {
            this.label = label;
            this.ready = ready;
        }
    }

    /**
     * 云游戏凭证状态。
     *
     * <p>开关是全局的、token 是按账号的，两者必须一起看：开关没开显示「未开启」，
     * 开了但该账号没配显示「未配置」，配齐显示「已配置」，部分配置给出 n/m 进度。
     */
    private Credential cloudGameCredential(TaskSettings settings, String username, boolean isOversea) {
        String name = activity.getString(R.string.user_cred_cloud_game);
        if (!settings.isCloudGameEnabled())
            return new Credential(formatCredential(name, activity.getString(R.string.user_cred_disabled)), false);
        String[] games = settings.getCloudGames();
        if (games == null || games.length == 0)
            return new Credential(formatCredential(name, activity.getString(R.string.user_cred_missing)), false);
        int ready = 0;
        for (String game : games) {
            if (CloudGameDaily.isValidToken(
                    tools.read(getContext(), username, CloudGameDaily.tokenKey(game, isOversea)))) ready++;
        }
        if (ready == games.length)
            return new Credential(formatCredential(name, activity.getString(R.string.user_cred_ready)), true);
        return new Credential(
                formatCredential(name, activity.getString(R.string.user_cred_partial, ready, games.length)), false);
    }

    /** 森空岛凭证状态（两款游戏共用同一个 token，状态必然一致）。 */
    private Credential sklandCredential(TaskSettings settings, String username) {
        String name = activity.getString(R.string.user_cred_skland);
        if (!settings.isSklandArknightsEnabled() && !settings.isSklandEndfieldEnabled())
            return new Credential(formatCredential(name, activity.getString(R.string.user_cred_disabled)), false);
        boolean ready = SklandDaily.isValidToken(tools.read(getContext(), username, SKLAND_COOKIE));
        return new Credential(
                formatCredential(name, activity.getString(
                        ready ? R.string.user_cred_ready : R.string.user_cred_missing)), ready);
    }

    private String formatCredential(String name, String status) {
        return activity.getString(R.string.user_cred_format, name, status);
    }

    /**
     * 点凭证 chip：先把当前账号切到这张卡的用户，再跳设置页展开对应抽屉。
     *
     * <p>必须先切账号——凭证按账号存放，不切就会把 token 配到另一个人名下。
     * 任务运行中禁止切换，与其它用户操作同口径（运行时 userId 已固定在任务执行器里）。
     */
    private void openCredentialPane(String username, int pane) {
        if (isTaskRunning()) {
            showCustomSnackbar(screenView, getContext(),
                    activity.getString(R.string.snack_task_running_cannot_switch));
            return;
        }
        if (!username.equals(userManager.getCurrentUser())) {
            userManager.setCurrentUser(username);
            // 立刻回灌首页：账号行不经过 ViewModel 的 UiState，不在这里通知的话，
            // 用户切回首页时会先看到上一个账号的名字
            activity.onCurrentUserChanged();
            refreshUserList(); // 「默认」chip 与状态跟着换位
        }
        activity.openSettingsPane(pane);
    }

    /** 信息行点击 = 重命名（设计稿无独立重命名按钮，保留原入口能力）。 */
    private void onRenameClick(String username) {
        if (isTaskRunning())
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_rename));
        else
            showRenameUserDialog(username);
    }

    /**
     * 显示重命名用户对话框（Compose 版输入框 + 就地校验）
     *
     * @param oldUsername 旧用户名
     */
    private void showRenameUserDialog(String oldUsername) {
        VenusDialogs.input(
                activity,
                activity.getString(R.string.dialog_rename_user),
                null,
                oldUsername,
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
                    if (newUsername.equals(oldUsername)) {
                        return activity.getString(R.string.username_hint);
                    }
                    if (userManager.isUserExists(newUsername)) {
                        return activity.getString(R.string.snack_username_exists);
                    }
                    return null;
                },
                newUsername -> renameUser(oldUsername, newUsername));
    }

    /**
     * 把用户数据整体迁移到新用户名，并同步 UserManager 中的当前用户标记与账户列表，最后刷新视图。
     * <p>
     * 数据迁移统一交给 {@link com.muxiao.Venus.common.data.UserRepository#migrate(String, String)}：
     * 原实现只复制旧 SP 文件，遗漏了 DataStore 与内存缓存，重命名后读取到的令牌可能为空
     * （读取命中缓存/DataStore，而两者仍是旧用户名下的键）。
     *
     * @param oldUsername 旧用户名
     * @param newUsername 新用户名
     */
    private void renameUser(String oldUsername, String newUsername) {
        new com.muxiao.Venus.common.data.UserRepository(getContext()).migrate(oldUsername, newUsername);
        // 头像文件按 md5(用户名) 键存储，随重命名一并迁移
        AvatarStore.renameAvatar(getContext(), oldUsername, newUsername);

        // 添加新用户并删除旧用户
        userManager.addUser(newUsername);
        if (userManager.getCurrentUser().equals(oldUsername))
            userManager.setCurrentUser(newUsername);
        userManager.removeUser(oldUsername);
        refreshUserList();
        // 用户名变了，首页的账号行 / 下拉列表 / 按账号分桶的任务列表都要跟着重建
        activity.onCurrentUserChanged();
        showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_user_renamed, newUsername));
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
        VenusDialogs.confirm(
                activity,
                activity.getString(R.string.dialog_delete_user),
                activity.getString(R.string.msg_delete_user_confirm, username),
                activity.getString(R.string.btn_delete),
                () -> {
                    userManager.removeUser(username);
                    // 头像文件随用户一并清除，避免残留
                    AvatarStore.deleteAvatar(getContext(), username);
                    refreshUserList();
                    // 删的可能正是当前账号（removeUser 会清掉当前用户标记）：
                    // 首页据此回落到剩余账号，否则账号行会空着、任务按已删除的用户分桶
                    activity.onCurrentUserChanged();
                    showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_user_deleted, username));
                },
                activity.getString(R.string.btn_cancel));
    }

    /**
     * 检查是否有任务正在运行
     */
    private boolean isTaskRunning() {
        return HomeFragment.isTaskRunning();
    }

    /**
     * 切换为指定用户并启动登录页（RELOGIN_MODE=true），用于刷新其 Cookie/凭证。
     *
     * @param username 用户名
     */
    private void performRelogin(String username) {
        if (isTaskRunning()) {
            showCustomSnackbar(screenView, getContext(), activity.getString(R.string.snack_task_running_cannot_relogin));
            return;
        }
        // 切换到该用户并启动登录活动以刷新cookie
        userManager.setCurrentUser(username);
        boolean isOversea = MiHoYoBBSConstants.is_oversea(getContext());
        Intent intent = new Intent(activity,
                isOversea ? OAuthLoginActivity.class : UserLoginActivity.class);
        intent.putExtra("RELOGIN_MODE", true);
        intent.putExtra("USERNAME", username); // 传递用户名
        activity.startActivity(intent);
    }
}
