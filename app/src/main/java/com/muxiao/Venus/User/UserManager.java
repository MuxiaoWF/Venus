package com.muxiao.Venus.User;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.muxiao.Venus.common.Constants;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户账号管理：增删用户、设置当前用户、按服务器类型（国服/国际服）过滤。
 * 存储：用户列表在 SP "user_accounts"，每个用户的详细数据在 "user_{username}"。
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

    /** 添加新用户（只保存用户名，不保存密码）。 */
    public void addUser(String username) {
        Map<String, String> users = getUsers();
        if (!users.containsKey(username)) {
            users.put(username, ""); // 只保存用户名
            String usersJson = gson.toJson(users);
            sharedPreferences.edit().putString(KEY_USERS, usersJson).apply();
        }
    }

    /**
     * 从账户表中移除该用户；若其正为当前用户，则一并清除 current_user 标记。
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
     * 读取并反序列化账户表（用户名 → 空串占位），无数据返回空 Map。
     */
    public Map<String, String> getUsers() {
        String usersJson = sharedPreferences.getString(KEY_USERS, "");
        if (usersJson.isEmpty())
            return new HashMap<>();

        Type type = new TypeToken<Map<String, String>>(){}.getType();
        try {
            Map<String, String> parsed = gson.fromJson(usersJson, type);
            // 存储内容损坏（非 JSON 对象）时 fromJson 返回 null，此处兜底为空表，
            // 避免调用方 containsKey/put 抛 NullPointerException 而无法进入任何页面。
            return parsed != null ? parsed : new HashMap<>();
        } catch (RuntimeException e) {
            return new HashMap<>();
        }
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
     * 返回当前选中用户名，未设置时返回空串。
     */
    public String getCurrentUser() {
        return sharedPreferences.getString(KEY_CURRENT_USER, "");
    }

    /**
     * 账号自身的服务器类型（真源）。
     *
     * <p>读的是账号维度 {@code user_{id}} 的 {@code server_type}（{@code UserLoginActivity} /
     * {@code OAuthLoginActivity} 登录成功时写入 "0"/"1"），<b>不是</b>全局 {@code SERVER_TYPE}：多账号可以
     * 分属国服/国际服，签到接口域名、appId/package、Referer、云游戏 token 键都随它变。
     *
     * <p>两种兜底，刻意与既有口径保持一致：
     * <ul>
     *   <li>{@code userId} 为空（界面还没绑定账号）→ 跟随全局 {@code SERVER_TYPE}，这是唯一能用的信号；</li>
     *   <li>有账号但无 {@code server_type} 记录（P0 之前登录的旧账号）→ 按国服处理。
     *       {@link #getUsernamesByServerType} 也是这么判的，两处必须同口径，否则会出现
     *       「列表里按国服显示、执行时按国际服取接口」。</li>
     * </ul>
     */
    public boolean isOversea(String userId) {
        if (userId == null || userId.isEmpty()) return isGlobalOversea();
        return "1".equals(serverTypeOf(userId));
    }

    /** 全局服务器类型（settings_prefs 的 SERVER_TYPE，1=国际服）。无账号上下文时的兜底，也是设置页切换的目标。 */
    public boolean isGlobalOversea() {
        return context.getSharedPreferences(Constants.Prefs.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(Constants.Prefs.SERVER_TYPE, 0) == 1;
    }

    /** 账号原始 server_type 值（""/"0"/"1"）；未记录时为空串。 */
    private String serverTypeOf(String username) {
        return context.getSharedPreferences("user_" + username, Context.MODE_PRIVATE)
                .getString(Constants.Prefs.SERVER_TYPE, "");
    }

    public List<String> getUsernamesByServerType(boolean isOversea) {
        List<String> result = new ArrayList<>();
        Map<String, String> allUsers = getUsers();
        for (String username : allUsers.keySet()) {
            if (isUserMatchingServerType(username, isOversea)) result.add(username);
        }
        return result;
    }

    public boolean isUserMatchingServerType(String username, boolean isOversea) {
        String serverType = serverTypeOf(username);
        if (serverType.isEmpty()) {
            // 对于没有服务器类型信息的旧用户，只在国服下显示
            return !isOversea;
        }
        boolean userIsOversea = "1".equals(serverType);
        return userIsOversea == isOversea;
    }
}
