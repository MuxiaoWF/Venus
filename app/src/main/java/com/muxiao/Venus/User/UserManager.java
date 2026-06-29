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

/**
 * 用户账号管理：添加/删除用户、设置当前用户、按服务器类型（国服/国际服）过滤用户列表。
 * 用户数据存储在 SharedPreferences "user_accounts" 中，每个用户的详细数据存储在 "user_{username}" 中。
 */
public class UserManager {
    private static final String PREF_NAME = "user_accounts";
    private static final String KEY_USERS = "users";
    private static final String KEY_CURRENT_USER = "current_user";

    private final SharedPreferences sharedPreferences;
    private final Gson gson;
    private final Context context;

    public UserManager(Context context) {
        this.context = context.getApplicationContext();
        sharedPreferences = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_USERS, gson.toJson(users));
            if (username.equals(getCurrentUser()))
                editor.remove(KEY_CURRENT_USER);
            editor.apply();
        }
    }

    /**
     * 获取所有用户
     */
    public Map<String, String> getUsers() {
        String usersJson = sharedPreferences.getString(KEY_USERS, "");
        if (usersJson.isEmpty())
            return new HashMap<>();

        Type type = new TypeToken<Map<String, String>>(){}.getType();
        return gson.fromJson(usersJson, type);
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
        if (isUserExists(username))
            sharedPreferences.edit().putString(KEY_CURRENT_USER, username).apply();
    }

    /**
     * 获取当前用户
     */
    public String getCurrentUser() {
        return sharedPreferences.getString(KEY_CURRENT_USER, "");
    }

    /**
     * 获取特定服务器类型的用户列表
     *
     * @param isOversea 是否为国际服
     * @return 该服务器类型的用户名列表
     */
    public List<String> getUsernamesByServerType(boolean isOversea) {
        List<String> result = new ArrayList<>();
        Map<String, String> allUsers = getUsers();
        for (String username : allUsers.keySet()) {
            SharedPreferences userPrefs = context.getSharedPreferences("user_" + username, Context.MODE_PRIVATE);
            String serverType = userPrefs.getString("server_type", "");
            // 如果用户没有服务器类型信息，根据当前选择的服务器类型决定是否显示
            if (serverType.isEmpty()) {
                // 对于没有服务器类型信息的旧用户，只在国服下显示
                if (!isOversea) {
                    result.add(username);
                }
            } else {
                // 根据存储的服务器类型过滤
                boolean userIsOversea = "1".equals(serverType);
                if (userIsOversea == isOversea) {
                    result.add(username);
                }
            }
        }
        return result;
    }

    /**
     * 检查用户是否属于指定服务器类型
     *
     * @param username  用户名
     * @param isOversea 是否为国际服
     * @return true 如果用户属于指定服务器类型
     */
    public boolean isUserMatchingServerType(String username, boolean isOversea) {
        SharedPreferences userPrefs = context.getSharedPreferences("user_" + username, Context.MODE_PRIVATE);
        String serverType = userPrefs.getString("server_type", "");
        if (serverType.isEmpty()) {
            // 对于没有服务器类型信息的旧用户，只在国服下显示
            return !isOversea;
        }
        boolean userIsOversea = "1".equals(serverType);
        return userIsOversea == isOversea;
    }
}
