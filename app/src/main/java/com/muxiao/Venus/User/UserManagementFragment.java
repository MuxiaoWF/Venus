package com.muxiao.Venus.User;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.muxiao.Venus.R;

import java.util.List;

public class UserManagementFragment extends Fragment {

    private UserManager userManager;
    private LinearLayout userListContainer;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_user_management, container, false);

        userManager = new UserManager(requireContext());
        userListContainer = rootView.findViewById(R.id.user_list_container);

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
                Toast.makeText(requireContext(), "已切换到用户: " + username, Toast.LENGTH_SHORT).show();
            });

            renameButton.setOnClickListener(v -> showRenameUserDialog(username));
            deleteButton.setOnClickListener(v -> showDeleteUserDialog(username));

            userListContainer.addView(userItemView);
        }
    }

    private void showRenameUserDialog(String oldUsername) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("重命名用户");

        final EditText input = new EditText(requireContext());
        input.setText(oldUsername);
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String newUsername = input.getText().toString().trim();
            if (!newUsername.isEmpty() && !newUsername.equals(oldUsername)) {
                if (userManager.isUserExists(newUsername)) {
                    Toast.makeText(requireContext(), "用户名已存在", Toast.LENGTH_SHORT).show();
                } else {
                    renameUser(oldUsername, newUsername);
                }
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void renameUser(String oldUsername, String newUsername) {
        // 由于UserManager没有直接提供重命名方法，我们需要手动实现
        // 这里我们通过添加新用户并删除旧用户的方式来实现重命名
        userManager.addUser(newUsername);
        if (userManager.getCurrentUser().equals(oldUsername)) {
            userManager.setCurrentUser(newUsername);
        }
        userManager.removeUser(oldUsername);
        refreshUserList();
        Toast.makeText(requireContext(), "用户已重命名为: " + newUsername, Toast.LENGTH_SHORT).show();
    }

    private void showDeleteUserDialog(String username) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("删除用户");
        builder.setMessage("确定要删除用户 \"" + username + "\" 吗？此操作无法撤销。");

        builder.setPositiveButton("删除", (dialog, which) -> {
            userManager.removeUser(username);
            refreshUserList();
            Toast.makeText(requireContext(), "用户已删除: " + username, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();
    }
}
