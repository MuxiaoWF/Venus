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
import com.muxiao.Venus.R;

import java.util.List;
import java.util.Objects;

public class UserManagementFragment extends Fragment {

    private UserManager userManager;
    private ViewGroup userListContainer;
    private android.widget.TextView noUserPrompt;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_user_management, container, false);

        userManager = new UserManager(requireContext());
        userListContainer = rootView.findViewById(R.id.user_list_container);
        noUserPrompt = new android.widget.TextView(requireContext());
        noUserPrompt.setText("暂无用户，请添加新用户");
        noUserPrompt.setTextSize(16);
        noUserPrompt.setPadding(16, 16, 16, 16);

        MaterialButton userLoginBtn = rootView.findViewById(R.id.user_login_btn);
        userLoginBtn.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), UserLoginActivity.class);
            startActivity(intent);
        });

        refreshUserList();

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUserList();
    }

    private void refreshUserList() {
        userListContainer.removeAllViews();
        List<String> users = userManager.getUsernames();
        // 如果没有用户，显示提示信息
        if (users.isEmpty()) {
            userListContainer.addView(noUserPrompt);
            return;
        }
        // 为每个用户创建新的视图实例
        for (String username : users) {
            // 为每个用户inflate一个新的视图
            View userItemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.user_item, userListContainer, false);

            MaterialButton userNameButton = userItemView.findViewById(R.id.user_name_button);
            MaterialButton renameButton = userItemView.findViewById(R.id.rename_user_button);
            MaterialButton deleteButton = userItemView.findViewById(R.id.delete_user_button);

            userNameButton.setText(username);
            userNameButton.setOnClickListener(v -> {
                userManager.setCurrentUser(username);
                showCustomSnackbar(requireView(), requireParentFragment(), "已切换到用户: " + username);
            });

            renameButton.setOnClickListener(v -> showRenameUserDialog(username));
            deleteButton.setOnClickListener(v -> showDeleteUserDialog(username));

            userListContainer.addView(userItemView);
        }
    }

    private void showRenameUserDialog(String oldUsername) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("重命名用户");

        final TextInputLayout inputLayout = new TextInputLayout(requireContext());
        final TextInputEditText input = new TextInputEditText(requireContext());
        input.setText(oldUsername);
        inputLayout.addView(input);
        builder.setView(inputLayout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String newUsername = Objects.requireNonNull(input.getText()).toString().trim();
            if (!newUsername.isEmpty() && !newUsername.equals(oldUsername)) {
                if (userManager.isUserExists(newUsername)) {
                    showCustomSnackbar(requireView(), this, "用户名已存在");
                } else {
                    renameUser(oldUsername, newUsername);
                }
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void renameUser(String oldUsername, String newUsername) {
        // 添加新用户并删除旧用户的方式来实现重命名
        userManager.addUser(newUsername);
        if (userManager.getCurrentUser().equals(oldUsername)) {
            userManager.setCurrentUser(newUsername);
        }
        userManager.removeUser(oldUsername);
        refreshUserList();
        showCustomSnackbar(requireView(), this, "用户已重命名为: " + newUsername);
    }

    private void showDeleteUserDialog(String username) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("删除用户");
        builder.setMessage("确定要删除用户 \"" + username + "\" 吗？此操作无法撤销。");

        builder.setPositiveButton("删除", (dialog, which) -> {
            userManager.removeUser(username);
            refreshUserList();
            showCustomSnackbar(requireView(), this, "用户已删除: " + username);
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();
    }
}
