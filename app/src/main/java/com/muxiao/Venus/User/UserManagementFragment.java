package com.muxiao.Venus.User;

import dagger.hilt.android.AndroidEntryPoint;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.util.List;
import java.util.Objects;

/**
 * 用户管理页：按服务器类型分组展示用户列表，支持添加/删除/重命名用户、重新登录。
 * 任务运行时禁止修改用户（防止数据不一致）。
 */
@AndroidEntryPoint
public class UserManagementFragment extends Fragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tools.setupFragmentTransitions(this);
    }

    private UserManager userManager;
    private ViewGroup userListContainer;
    private MaterialTextView noUserPrompt;
    /** 页面根视图：onCreateView 阶段 requireView() 尚不可用，列表刷新统一走此引用。 */
    private View pageRootView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_user_management, container, false);
        pageRootView = rootView;

        userManager = new UserManager(requireContext());
        userListContainer = rootView.findViewById(R.id.user_list_container);
        noUserPrompt = rootView.findViewById(R.id.no_user_prompt);

        // 页眉日期 chip：MM/dd 周几（与设计稿一致）
        android.widget.TextView dateChip = rootView.findViewById(R.id.users_date_chip);
        if (dateChip != null)
            dateChip.setText(new java.text.SimpleDateFormat("MM/dd E", java.util.Locale.getDefault())
                    .format(new java.util.Date()));

        ExtendedFloatingActionButton userLoginBtn = rootView.findViewById(R.id.user_login_btn);
        userLoginBtn.setOnClickListener(v -> {
            if (isTaskRunning()) {
                showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_add));
            } else {
                boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
                Intent intent = new Intent(getActivity(),
                        isOversea ? OAuthLoginActivity.class : UserLoginActivity.class);
                startActivity(intent);
            }
        });
        // 刷新用户列表
        refreshUserList();

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 恢复页面也刷新用户列表
        refreshUserList();
    }

    /**
     * 按当前服务器类型重建用户列表视图，并为每个条目绑定重登录/重命名/删除操作
     * （任务运行时这些操作均被拦截）。
     */
    private void refreshUserList() {
        userListContainer.removeAllViews();
        // 根据当前服务器类型过滤用户
        boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
        List<String> users = userManager.getUsernamesByServerType(isOversea);
        // 页眉副标题带账号计数（设计稿：管理登录账号 · 共 N 个账号）
        MaterialTextView subtitle = pageRootView.findViewById(R.id.users_subtitle);
        if (subtitle != null)
            subtitle.setText(getResources().getQuantityString(
                    R.plurals.user_count_fmt, users.size(), users.size()));
        // 如果没有用户，显示提示信息（隐藏区块标签）
        View sectionLabel = pageRootView.findViewById(R.id.users_section_label);
        if (users.isEmpty()) {
            if (sectionLabel != null) sectionLabel.setVisibility(View.GONE);
            noUserPrompt.setVisibility(View.VISIBLE);
            String serverName = isOversea ? getString(R.string.server_os) : getString(R.string.server_cn);
            noUserPrompt.setText(getString(R.string.msg_no_server_user, serverName));
            return;
        }
        if (sectionLabel != null) sectionLabel.setVisibility(View.VISIBLE);
        String currentUsername = userManager.getCurrentUser();
        // 为每个用户创建新的视图实例
        for (int i = 0; i < users.size(); i++) {
            String username = users.get(i);
            // 为每个用户inflate一个新的视图
            View userItemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_user_management, userListContainer, false);

            // 第一个用户卡片顶到容器,其余卡片之间留出间距
            if (i > 0) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) userItemView.getLayoutParams();
                lp.topMargin = getResources().getDimensionPixelSize(R.dimen.gap_card);
                userItemView.setLayoutParams(lp);
            }

            MaterialTextView userName = userItemView.findViewById(R.id.user_name_text);
            View defaultChip = userItemView.findViewById(R.id.user_default_chip);
            View infoRow = userItemView.findViewById(R.id.user_info_row);
            MaterialButton deleteButton = userItemView.findViewById(R.id.delete_user_button);
            MaterialButton reloginButton = userItemView.findViewById(R.id.relogin_user_button);

            // 显示用户名和服务器类型（占位符格式化，避免 setText 拼接触发 Lint SetTextI18n）
            String serverType = getString(isOversea ? R.string.server_os : R.string.server_cn);
            userName.setText(getString(R.string.user_label_with_server, username, serverType));
            // 当前用户标注「默认」chip（设计稿语义）
            defaultChip.setVisibility(username.equals(currentUsername) ? View.VISIBLE : View.GONE);

            // 信息行点击 = 重命名（设计稿无独立重命名按钮，保留原入口能力）
            infoRow.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_rename));
                else
                    showRenameUserDialog(username);
            });

            reloginButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_relogin));
                else
                    performRelogin(username);
            });

            deleteButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_delete));
                else
                    showDeleteUserDialog(username);
            });

            userListContainer.addView(userItemView);
        }
    }

    /**
     * 显示重命名用户对话框
     *
     * @param oldUsername 旧用户名
     */
    private void showRenameUserDialog(String oldUsername) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.dialog_rename_user));

        final TextInputLayout inputLayout = new TextInputLayout(requireContext());
        final TextInputEditText input = new TextInputEditText(requireContext());
        input.setText(oldUsername);
        inputLayout.addView(input);
        builder.setView(inputLayout);

        builder.setPositiveButton(getString(R.string.btn_ok), (dialog, which) -> {
            if (isTaskRunning()) {
                showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_rename));
                return;
            }
            String newUsername = Objects.requireNonNull(input.getText()).toString().trim();
            if (!newUsername.isEmpty() && !newUsername.equals(oldUsername)) {
                if (userManager.isUserExists(newUsername))
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_username_exists));
                else
                    renameUser(oldUsername, newUsername);
            }
        }).setNegativeButton(getString(R.string.btn_cancel), (dialog, which) -> dialog.cancel());
        builder.show();
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
        new com.muxiao.Venus.common.data.UserRepository(requireContext()).migrate(oldUsername, newUsername);

        // 添加新用户并删除旧用户
        userManager.addUser(newUsername);
        if (userManager.getCurrentUser().equals(oldUsername))
            userManager.setCurrentUser(newUsername);
        userManager.removeUser(oldUsername);
        refreshUserList();
        showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_user_renamed, newUsername));
    }

    /**
     * 显示删除用户对话框
     *
     * @param username 用户名
     */
    private void showDeleteUserDialog(String username) {
        if (isTaskRunning()) {
            showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_delete));
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_delete_user))
                .setMessage(getString(R.string.msg_delete_user_confirm, username))
                .setPositiveButton(getString(R.string.btn_delete), (dialog, which) -> {
                    userManager.removeUser(username);
                    refreshUserList();
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_user_deleted, username));
                }).setNegativeButton(getString(R.string.btn_cancel), (dialog, which) -> dialog.cancel())
                .show();
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
            showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_relogin));
            return;
        }
        // 切换到该用户并启动登录活动以刷新cookie
        userManager.setCurrentUser(username);
        boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
        Intent intent = new Intent(getActivity(),
                isOversea ? OAuthLoginActivity.class : UserLoginActivity.class);
        intent.putExtra("RELOGIN_MODE", true);
        intent.putExtra("USERNAME", username); // 传递用户名
        startActivity(intent);
    }
}
