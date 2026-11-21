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
 *
 * <p>存储 id 与展示名是两个概念：同名跨服账号的存储 id 会带 {@code @os}/{@code @cn} 后缀
 * （见 {@link #resolveStorageId}），展示名用 {@link #displayName} 去掉它。单服账号两者相同。
 */
public class UserManager {
    private static final String PREF_NAME = "user_accounts";
    private static final String KEY_USERS = "users";
    private static final String KEY_CURRENT_USER = "current_user";
    /** 由 {@link #resolveStorageId} 追加了后缀的存储 id 集合：与「真名自带 @os/@cn」区分开。 */
    private static final String KEY_SUFFIXED_IDS = "suffixed_ids";

    /** 同名异服存储 id 的服务器后缀。仅在真冲突时追加。 */
    public static final String SUFFIX_OVERSEA = "@os";
    /** 同名异服存储 id 的服务器后缀（国服）。 */
    public static final String SUFFIX_CHINA = "@cn";

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
            if (suffixedIds().contains(username)) {
                java.util.Set<String> set = suffixedIds();
                set.remove(username);
                editor.putStringSet(KEY_SUFFIXED_IDS, set);
            }
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
     * 为新登录的账号决定存储 id：名字空闲 → 原样；同服已占用 → 原样（重登录覆盖原凭证）；
     * 异服已占用 → 追加后缀并登记（见 {@link #KEY_SUFFIXED_IDS}），后缀仍冲突时再加序号兜底。
     */
    public String resolveStorageId(String inputName, boolean isOversea) {
        if (inputName == null || inputName.isEmpty()) return "";
        if (!isUserExists(inputName)) return inputName;
        if (isOversea(inputName) == isOversea) return inputName;
        String base = inputName + (isOversea ? SUFFIX_OVERSEA : SUFFIX_CHINA);
        if (!isUserExists(base)) {
            markSuffixed(base, true);
            return base;
        }
        int n = 2;
        while (isUserExists(base + "-" + n)) n++;
        return base + "-" + n;
    }

    /** 展示名：去掉已登记的系统后缀。仅用于显示，不能当存储键。 */
    public String displayName(String userId) {
        if (userId == null || userId.isEmpty()) return "";
        String suffix = serverSuffixOf(userId);
        return suffix.isEmpty() ? userId : userId.substring(0, userId.length() - suffix.length());
    }

    /**
     * 该 id 携带的有效系统后缀（{@code "@os"} / {@code "@cn"}），没有时返回空串。
     * 仅认登记过的 id：真名以 {@code @os}/{@code @cn} 结尾的账号不会被误剪。
     */
    public String serverSuffixOf(String userId) {
        if (userId == null || userId.isEmpty()) return "";
        if (!suffixedIds().contains(userId)) return "";
        if (userId.endsWith(SUFFIX_OVERSEA)) return SUFFIX_OVERSEA;
        if (userId.endsWith(SUFFIX_CHINA)) return SUFFIX_CHINA;
        return "";
    }

    /** 同名异服标记的迁移（重命名时调用）：旧 id 未登记则不动作。 */
    public void transferSuffixedMark(String oldId, String newId) {
        if (!suffixedIds().contains(oldId)) return;
        markSuffixed(oldId, false);
        markSuffixed(newId, true);
    }

    private java.util.Set<String> suffixedIds() {
        return new java.util.HashSet<>(
                sharedPreferences.getStringSet(KEY_SUFFIXED_IDS, new java.util.HashSet<>()));
    }

    private void markSuffixed(String id, boolean marked) {
        java.util.Set<String> set = suffixedIds();
        boolean changed = marked ? set.add(id) : set.remove(id);
        if (changed)
            sharedPreferences.edit().putStringSet(KEY_SUFFIXED_IDS, set).apply();
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
     *       {@link #isUserMatchingServerType} 也是这么判的，两处必须同口径，否则会出现
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

    /** 全部账号（不按服务器过滤），首页 / 用户管理 / 抽卡页的账号列表真源。 */
    public List<String> getAllUsernames() {
        return new ArrayList<>(getUsers().keySet());
    }

    /**
     * 该账号是否属于指定服务器。登录页用它拦截「同名且同服」的重复登录。
     */
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
