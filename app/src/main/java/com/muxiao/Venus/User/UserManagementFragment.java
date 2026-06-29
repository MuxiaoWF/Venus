package com.muxiao.Venus.User;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.Context;
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
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.MiHoYoBBSConstants;

import java.util.List;
import java.util.Objects;

/**
 * 用户管理页：按服务器类型分组展示用户列表，支持添加/删除/重命名用户、重新登录。
 * 任务运行时禁止修改用户（防止数据不一致）。
 */
public class UserManagementFragment extends Fragment {

    {
        setEnterTransition(new android.transition.Fade(android.transition.Fade.IN).setDuration(300));
    }

    private UserManager userManager;
    private ViewGroup userListContainer;
    private MaterialTextView noUserPrompt;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_user_management, container, false);

        View bottomPaddingView = rootView.findViewById(R.id.bottom_padding_view);
        if (getActivity() instanceof MainActivity)
            ((MainActivity) getActivity()).applyBottomPadding(bottomPaddingView);

        userManager = new UserManager(requireContext());
        userListContainer = rootView.findViewById(R.id.user_list_container);
        noUserPrompt = rootView.findViewById(R.id.no_user_prompt);

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
     * 刷新用户列表
     */
    private void refreshUserList() {
        userListContainer.removeAllViews();
        // 根据当前服务器类型过滤用户
        boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
        List<String> users = userManager.getUsernamesByServerType(isOversea);
        // 如果没有用户，显示提示信息
        if (users.isEmpty()) {
            noUserPrompt.setVisibility(View.VISIBLE);
            String serverName = isOversea ? getString(R.string.server_os) : getString(R.string.server_cn);
            noUserPrompt.setText(getString(R.string.msg_no_server_user, serverName));
            return;
        }
        // 为每个用户创建新的视图实例
        for (String username : users) {
            // 为每个用户inflate一个新的视图
            View userItemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_user_management, userListContainer, false);

            MaterialTextView userName = userItemView.findViewById(R.id.user_name_text);
            MaterialButton renameButton = userItemView.findViewById(R.id.rename_user_button);
            MaterialButton deleteButton = userItemView.findViewById(R.id.delete_user_button);
            MaterialButton reloginButton = userItemView.findViewById(R.id.relogin_user_button);

            // 显示用户名和服务器类型
            String serverType = isOversea ? " [" + getString(R.string.server_os) + "]" : " [" + getString(R.string.server_cn) + "]";
            userName.setText(username + serverType);

            reloginButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_relogin));
                else
                    performRelogin(username);
            });

            renameButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), getString(R.string.snack_task_running_cannot_rename));
                else
                    showRenameUserDialog(username);
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
     * 重命名用户
     *
     * @param oldUsername 旧用户名
     * @param newUsername 新用户名
     */
    private void renameUser(String oldUsername, String newUsername) {
        // 迁移用户数据（SharedPreferences）
        android.content.SharedPreferences oldPrefs = requireContext()
                .getSharedPreferences("user_" + oldUsername, Context.MODE_PRIVATE);
        java.util.Map<String, ?> allData = oldPrefs.getAll();
        android.content.SharedPreferences.Editor newEditor = requireContext()
                .getSharedPreferences("user_" + newUsername, Context.MODE_PRIVATE).edit();
        for (java.util.Map.Entry<String, ?> entry : allData.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String) newEditor.putString(entry.getKey(), (String) v);
            else if (v instanceof Boolean) newEditor.putBoolean(entry.getKey(), (Boolean) v);
            else if (v instanceof Integer) newEditor.putInt(entry.getKey(), (Integer) v);
            else if (v instanceof Long) newEditor.putLong(entry.getKey(), (Long) v);
            else if (v instanceof Float) newEditor.putFloat(entry.getKey(), (Float) v);
        }
        newEditor.apply();
        oldPrefs.edit().clear().apply();

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
     * 执行重新登录操作
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
