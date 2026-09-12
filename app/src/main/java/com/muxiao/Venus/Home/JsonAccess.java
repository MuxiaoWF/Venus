package com.muxiao.Venus.Home;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 接口响应 JSON 的安全读取工具。
 *
 * <p>背景：米游社接口在风控、登录态失效等场景下会返回结构不完整的 JSON
 * （缺少 {@code retcode}/{@code data}/{@code message} 或对应字段为 {@code null}）。
 * 原先各处直接 {@code obj.get("retcode").getAsInt()} 会抛
 * {@link NullPointerException} / {@link UnsupportedOperationException} / {@link IllegalStateException}，
 * 最终以「网络请求失败」之类的泛化文案暴露，掩盖真实原因。
 *
 * <p>本类把所有读取收敛为一组「缺失即返回兜底值」的方法，让调用方只需处理
 * 兜底值（如哨兵 retcode），不再各自 try/catch。
 */
final class JsonAccess {

    /** {@code retcode} 缺失或非法时的哨兵值（不可能是接口真实返回值）。 */
    static final int RETCODE_UNKNOWN = Integer.MIN_VALUE;

    private JsonAccess() {
    }

    /**
     * 读取 {@code retcode}：字段缺失、{@code JsonNull} 或非数值时返回 {@link #RETCODE_UNKNOWN}。
     */
    static int retcode(JsonObject obj) {
        if (obj == null) return RETCODE_UNKNOWN;
        return optInt(obj, "retcode", RETCODE_UNKNOWN);
    }

    /**
     * 读取 {@code message}：字段缺失、{@code JsonNull} 或空串时返回 {@code fallback}。
     */
    static String message(JsonObject obj, String fallback) {
        if (obj == null) return fallback;
        return optString(obj, "message", fallback);
    }

    /**
     * 读取 {@code "data"} 载荷子对象：字段缺失、为 {@code JsonNull} 或不是对象时返回 {@code null}。
     * 米游社响应包的业务载荷统一在 {@code data} 字段下（全项目唯一取值），故键名内建。
     */
    static JsonObject data(JsonObject obj) {
        if (obj == null) return null;
        JsonElement element = obj.get("data");
        if (element == null || !element.isJsonObject()) return null;
        return element.getAsJsonObject();
    }

    /** 读取子数组：字段缺失、为 {@code JsonNull} 或不是数组时返回 {@code null}。 */
    static JsonArray array(JsonObject obj, String member) {
        if (obj == null) return null;
        JsonElement element = obj.get(member);
        if (element == null || !element.isJsonArray()) return null;
        return element.getAsJsonArray();
    }

    /** 读取整数字段：缺失、{@code JsonNull} 或类型不符时返回 {@code fallback}。 */
    static int optInt(JsonObject obj, String member, int fallback) {
        if (obj == null) return fallback;
        JsonElement element = obj.get(member);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * 读取 {@code total_sign_day}（本月累计签到天数）：缺失、{@code JsonNull} 或类型不符时
     * 返回 {@code null}（区别于合法的 0）。全项目唯一取值即该字段，故键名内建。
     */
    static Integer optTotalSignDay(JsonObject obj) {
        String member = "total_sign_day";
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) return null;
        try {
            return obj.get(member).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 读取布尔字段：缺失、{@code JsonNull} 或类型不符时返回 {@code null}（区别于合法的 false）。 */
    static Boolean optBooleanOrNull(JsonObject obj, String member) {
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) return null;
        try {
            return obj.get(member).getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 读取 {@code is_get_award}（今日是否已领取奖励）：缺失、{@code JsonNull} 或类型不符时
     * 返回 {@code false}。全项目唯一取值即该字段且兜底恒为 false，故参数内建。
     */
    static boolean optIsGetAward(JsonObject obj) {
        String member = "is_get_award";
        if (obj == null) return false;
        JsonElement element = obj.get(member);
        if (element == null || element.isJsonNull()) return false;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 读取字符串字段：缺失、{@code JsonNull} 或取值为空串时返回 {@code fallback}。
     * 数值类型会被转为其字符串形式（接口中 {@code cnt} 等字段偶有数值返回）。
     */
    static String optString(JsonObject obj, String member, String fallback) {
        if (obj == null) return fallback;
        JsonElement element = obj.get(member);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            String value = element.getAsString();
            return value == null || value.isEmpty() ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
