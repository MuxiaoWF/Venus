package com.muxiao.Venus.User;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.R;

import java.util.List;
import java.util.Objects;

public class UserManagementFragment extends Fragment {

    private UserManager userManager;
    private ViewGroup userListContainer;
    private MaterialTextView noUserPrompt;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_user_management, container, false);

        userManager = new UserManager(requireContext());
        userListContainer = rootView.findViewById(R.id.user_list_container);
        noUserPrompt = rootView.findViewById(R.id.no_user_prompt);
        noUserPrompt.setVisibility(View.GONE);

        MaterialButton userLoginBtn = rootView.findViewById(R.id.user_login_btn);
        userLoginBtn.setOnClickListener(v -> {
            if (isTaskRunning()) {
                showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法添加用户");
            } else {
                Intent intent = new Intent(getActivity(), UserLoginActivity.class);
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
        List<String> users = userManager.getUsernames();
        // 如果没有用户，显示提示信息
        if (users.isEmpty()) {
            noUserPrompt.setVisibility(View.VISIBLE);
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

            userName.setText(username);

            reloginButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法重新登录");
                else
                    performRelogin(username);
            });
            
            renameButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法重命名用户");
                else
                    showRenameUserDialog(username);
            });
            
            deleteButton.setOnClickListener(v -> {
                if (isTaskRunning())
                    showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法删除用户");
                else
                    showDeleteUserDialog(username);
            });

            userListContainer.addView(userItemView);
        }
    }

    /**
     * 显示重命名用户对话框
     * @param oldUsername 旧用户名
     */
    private void showRenameUserDialog(String oldUsername) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("重命名用户");

        final TextInputLayout inputLayout = new TextInputLayout(requireContext());
        final TextInputEditText input = new TextInputEditText(requireContext());
        input.setText(oldUsername);
        inputLayout.addView(input);
        builder.setView(inputLayout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            if (isTaskRunning()) {
                showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法重命名用户");
                return;
            }
            String newUsername = Objects.requireNonNull(input.getText()).toString().trim();
            if (!newUsername.isEmpty() && !newUsername.equals(oldUsername)) {
                if (userManager.isUserExists(newUsername))
                    showCustomSnackbar(requireView(), requireContext(), "用户名已存在");
                else
                    renameUser(oldUsername, newUsername);
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    /**
     * 重命名用户
     * @param oldUsername 旧用户名
     * @param newUsername 新用户名
     */
    private void renameUser(String oldUsername, String newUsername) {
        if (isTaskRunning()) {
            showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法重命名用户");
            return;
        }
        // 添加新用户并删除旧用户的方式来实现重命名
        userManager.addUser(newUsername);
        if (userManager.getCurrentUser().equals(oldUsername))
            userManager.setCurrentUser(newUsername);
        userManager.removeUser(oldUsername);
        refreshUserList();
        showCustomSnackbar(requireView(), requireContext(), "用户已重命名为: " + newUsername);
    }

    /**
     * 显示删除用户对话框
     * @param username 用户名
     */
    private void showDeleteUserDialog(String username) {
        if (isTaskRunning()) {
            showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法删除用户");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("删除用户");
        builder.setMessage("确定要删除用户 \"" + username + "\" 吗？此操作无法撤销。");

        builder.setPositiveButton("删除", (dialog, which) -> {
            if (isTaskRunning()) {
                showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法删除用户");
                return;
            }
            userManager.removeUser(username);
            refreshUserList();
            showCustomSnackbar(requireView(), requireContext(), "用户已删除: " + username);
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();
    }
    
    /**
     * 检查是否有任务正在运行
     */
    private boolean isTaskRunning() {
        return HomeFragment.isTaskRunning();
    }

    /**
     * 执行重新登录操作
     * @param username 用户名
     */
    private void performRelogin(String username) {
        if (isTaskRunning()) {
            showCustomSnackbar(requireView(), requireContext(), "任务运行中，无法重新登录");
            return;
        }
        // 切换到该用户并启动登录活动以刷新cookie
        userManager.setCurrentUser(username);
        Intent intent = new Intent(getActivity(), UserLoginActivity.class);
        intent.putExtra("RELOGIN_MODE", true);
        intent.putExtra("USERNAME", username); // 传递用户名
        startActivity(intent);
    }
}
