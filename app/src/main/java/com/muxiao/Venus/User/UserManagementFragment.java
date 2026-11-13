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

import java.util.ArrayList;
import java.util.Arrays;
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
        // 凭证入口：一键跳到设置页配置该账号，省去「去设置里找哪一页」的来回
        root.setOnCredentialClickListener(this::onCredentialClick);

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
            rows.add(new UserCardRow(
                    username,
                    activity.getString(R.string.user_label_with_server, username, serverType),
                    // 当前用户标注「默认」chip（设计稿语义）
                    username.equals(currentUsername),
                    credentialChip(settings, username, isOversea)));
        }
        screenView.setUsers(rows);
    }

    /**
     * 一张用户卡上的单项凭证状态：名称 + 状态文案 + 是否就绪 + 进度计数。
     * 返回 null 表示「该功能未开启，卡片上不显示这一项」。
     *
     * <p>{@code total} 恒 ≥ 1：功能开着就等于「有一条凭证待配」。云游戏开了总开关却没勾
     * 任何游戏时若记 0，汇总就会把这一项从分母里抹掉——配齐森空岛后显示「凭证 1/1」，
     * 而云游戏其实一条都没配，用户会以为已经全部就绪。</p>
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
     * 把两项合并成卡片上的唯一一枚凭证入口。
     *
     * <p>只开一个功能时沿用该项的具体文案（「云游戏 1/2」），点了直接进对应抽屉；
     * 两个都开时压成汇总（「凭证 3/3」/「凭证 待配 2」），点开由 {@link #onCredentialClick}
     * 先让用户选。两个都没开则返回 null，卡片上整项不渲染。</p>
     */
    private CredentialChipRow credentialChip(TaskSettings settings, String username, boolean isOversea) {
        Credential cloud = cloudGameCredential(settings, username, isOversea);
        Credential skland = sklandCredential(settings, username);
        if (cloud == null && skland == null) return null;
        if (cloud == null || skland == null) {
            Credential only = cloud != null ? cloud : skland;
            return new CredentialChipRow(formatCredential(only.name, only.status), only.ready);
        }
        int done = cloud.configured + skland.configured;
        int total = cloud.total + skland.total;
        boolean allReady = total > 0 && done >= total;
        String label = allReady
                ? activity.getString(R.string.credential_chip_ready, done, total)
                : activity.getString(R.string.credential_chip_pending, Math.max(total - done, 0));
        return new CredentialChipRow(label, allReady);
    }

    /**
     * 云游戏凭证状态。
     *
     * <p>开关是全局的、token 是按账号的，两者必须一起看：开关没开显示「未开启」，
     * 开了但该账号没配显示「未配置」，配齐显示「已配置」，部分配置给出 n/m 进度。
     */
    private Credential cloudGameCredential(TaskSettings settings, String username, boolean isOversea) {
        // 功能没开就别在卡片上占位：返回 null，Compose 侧整项不渲染
        if (!settings.isCloudGameEnabled()) return null;
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
     *
     * <p>业务判断留在 Java 侧，Compose 只上报被点的用户名。</p>
     */
    private void onCredentialClick(String username) {
        TaskSettings settings = TaskSettings.fromPreferences(getContext());
        boolean isOversea = MiHoYoBBSConstants.is_oversea(getContext());
        Credential cloud = cloudGameCredential(settings, username, isOversea);
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
     * 把这张卡的用户作为「凭证配置目标账号」交给设置页，展开对应抽屉。
     *
     * <p>不再切换全局当前账号：设置页的凭证抽屉自带目标账号，读写都按它走。
     * 切账号会连带首页数据与任务上下文一起变，代价远大于收益；
     * 目标账号只对这一次跳转有效，设置页 {@code show()} 时即复位。</p>
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
