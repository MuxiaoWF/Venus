package com.muxiao.Venus.User;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserManager {
    private static final String PREF_NAME = "user_accounts";
    private static final String KEY_USERS = "users";
    private static final String KEY_CURRENT_USER = "current_user";

    private final SharedPreferences sharedPreferences;
    private final Gson gson;

    public UserManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    /**
     * 添加新用户（只保存用户名，不保存密码）
     */
    public void addUser(String username) {
        Map<String, String> users = getUsers();
        // 确保用户名唯一性
        if (!users.containsKey(username)) {
            users.put(username, ""); // 只保存用户名
            String usersJson = gson.toJson(users);
            sharedPreferences.edit().putString(KEY_USERS, usersJson).apply();
        }
    }

    /**
     * 删除用户
     */
    public void removeUser(String username) {
        Map<String, String> users = getUsers();
        if (users.containsKey(username)) {
            users.remove(username);
            String usersJson = gson.toJson(users);
            sharedPreferences.edit().putString(KEY_USERS, usersJson).apply();
            
            // 如果删除的是当前用户，则清除当前用户设置
            if (username.equals(getCurrentUser())) {
                sharedPreferences.edit().remove(KEY_CURRENT_USER).apply();
            }
        }
    }

    /**
     * 获取所有用户
     */
    public Map<String, String> getUsers() {
        String usersJson = sharedPreferences.getString(KEY_USERS, "");
        if (usersJson.isEmpty()) {
            return new HashMap<>();
        }

        Type type = new TypeToken<Map<String, String>>(){}.getType();
        return gson.fromJson(usersJson, type);
    }

    /**
     * 获取所有用户名列表
     */
    public List<String> getUsernames() {
        return new ArrayList<>(getUsers().keySet());
    }

    /**
     * 检查用户是否存在
     */
    public boolean isUserExists(String username) {
        return getUsers().containsKey(username);
    }

    /**
     * 设置当前用户（确保用户存在）
     */
    public void setCurrentUser(String username) {
        if (isUserExists(username)) {
            sharedPreferences.edit().putString(KEY_CURRENT_USER, username).apply();
        }
    }

    /**
     * 获取当前用户
     */
    public String getCurrentUser() {
        return sharedPreferences.getString(KEY_CURRENT_USER, "");
    }
}
