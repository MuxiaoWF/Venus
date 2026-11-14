package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.res.Configuration;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.MiHoYoBBSConstants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 任务稳定标识（持久化/匹配键）的唯一定义处，并负责「id ↔ 当前语言显示名」互转。
 *
 * <p>为什么不用任务名当键：任务名是本地化字符串，切换语言后同一任务会得到不同的键——
 * 今日状态因此「消失」（小组件整表回落待执行），历史日志与重试名单也会跨语言对不上。
 * 键改用与语言无关的稳定 id 后，名称只承担展示职责，可随语言自由变化。
 *
 * <p>id 结构刻意可读（{@code game_signin:原神} 这类），既方便读日志，也让 {@link #displayName}
 * 能脱离设置快照独立还原名称；未知 id 原样返回，便于云端新增任务时向前兼容。
 */
public final class TaskIds {

    /** 米游币社区签到（整块任务，不按板块拆分）。 */
    public static final String BBS_DAILY = "bbs_daily";
    /** 游戏签到前缀：完整 id = 前缀 + 内部游戏标识（如 {@code game_signin:原神}）。 */
    public static final String GAME_SIGNIN_PREFIX = "game_signin:";
    /** 云游戏签到前缀：完整 id = 前缀 + {@link CloudGameDaily#GAME_GENSHIN} / {@link CloudGameDaily#GAME_ZZZ}。 */
    public static final String CLOUD_GAME_PREFIX = "cloud_game:";
    public static final String SKLAND_ARKNIGHTS = "skland:arknights";
    public static final String SKLAND_ENDFIELD = "skland:endfield";

    /**
     * 游戏签到的全部内部游戏标识：与 {@link TaskSettings} 的 {@code GAME_DAILY_*} 开关一一对应。
     * 仅用于「旧版本地化名 → id」的枚举迁移，不参与执行（执行以设置快照为准）。
     */
    public static final String[] SIGN_IN_GAMES = {
            "原神", "绝区零", "星铁", "崩坏3", "崩坏2", "未定事件簿"
    };

    /** 云游戏的全部内部游戏标识（同上，仅用于迁移枚举）。 */
    public static final String[] CLOUD_GAMES = {
            CloudGameDaily.GAME_GENSHIN, CloudGameDaily.GAME_ZZZ
    };

    /** 旧版本地化名 → id 索引（进程级缓存：枚举三种语言要构造 4 个 Context，不宜反复做）。 */
    private static volatile Map<String, String> legacyNameIndex;

    private TaskIds() {
    }

    public static String gameSignIn(String game) {
        return GAME_SIGNIN_PREFIX + game;
    }

    public static String cloudGame(String game) {
        return CLOUD_GAME_PREFIX + game;
    }

    /** 是否为可识别的（本版本已知的）任务 id。 */
    public static boolean isKnown(String taskId) {
        if (taskId == null || taskId.isEmpty()) return false;
        if (BBS_DAILY.equals(taskId) || SKLAND_ARKNIGHTS.equals(taskId) || SKLAND_ENDFIELD.equals(taskId))
            return true;
        if (taskId.startsWith(GAME_SIGNIN_PREFIX))
            return contains(SIGN_IN_GAMES, taskId.substring(GAME_SIGNIN_PREFIX.length()));
        if (taskId.startsWith(CLOUD_GAME_PREFIX))
            return contains(CLOUD_GAMES, taskId.substring(CLOUD_GAME_PREFIX.length()));
        return false;
    }

    /**
     * 任务 id 在当前语言下的显示名（通知、日志、列表通用）。
     *
     * @return 未知 id 原样返回——宁可展示 id 也不要空行，且旧版残留的名称键也能继续可读
     */
    public static String displayName(Context context, String taskId) {
        if (taskId == null || taskId.isEmpty()) return "";
        switch (taskId) {
            case BBS_DAILY:
                return context.getString(R.string.task_name_bbs_daily);
            case SKLAND_ARKNIGHTS:
                return context.getString(R.string.task_name_skland_arknights);
            case SKLAND_ENDFIELD:
                return context.getString(R.string.task_name_skland_endfield);
        }
        if (taskId.startsWith(GAME_SIGNIN_PREFIX)) {
            String game = taskId.substring(GAME_SIGNIN_PREFIX.length());
            return context.getString(R.string.task_name_game_sign_in,
                    MiHoYoBBSConstants.game_to_display_name(context, game));
        }
        if (taskId.startsWith(CLOUD_GAME_PREFIX)) {
            return CloudGameDaily.taskName(context, taskId.substring(CLOUD_GAME_PREFIX.length()));
        }
        return taskId;
    }

    /**
     * 旧版本地化名 → id 映射（迁移专用）。
     *
     * <p>旧版把「当时的本地化任务名」直接写进持久化键，历史数据的键语言取决于写入时设置：
     * 简中 / 繁中 / 英文，以及未匹配到 values-* 时回落的默认资源。故这里按 4 种语言各枚举一遍，
     * 覆盖全部可能出现过的键。
     */
    public static Map<String, String> legacyNameIndex(Context context) {
        Map<String, String> cached = legacyNameIndex;
        if (cached != null) return cached;
        synchronized (TaskIds.class) {
            if (legacyNameIndex != null) return legacyNameIndex;
            Context base = context.getApplicationContext();
            Map<String, String> index = new LinkedHashMap<>();
            Locale[] locales = {null, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.ENGLISH};
            for (Locale locale : locales) {
                Context localized = locale == null ? base : withLocale(base, locale);
                if (localized == null) continue;
                index.put(localized.getString(R.string.task_name_bbs_daily), BBS_DAILY);
                for (String game : SIGN_IN_GAMES)
                    index.put(localized.getString(R.string.task_name_game_sign_in,
                            MiHoYoBBSConstants.game_to_display_name(localized, game)), gameSignIn(game));
                for (String game : CLOUD_GAMES)
                    index.put(CloudGameDaily.taskName(localized, game), cloudGame(game));
                index.put(localized.getString(R.string.task_name_skland_arknights), SKLAND_ARKNIGHTS);
                index.put(localized.getString(R.string.task_name_skland_endfield), SKLAND_ENDFIELD);
            }
            legacyNameIndex = Collections.unmodifiableMap(index);
            return legacyNameIndex;
        }
    }

    /** 用指定语言重建 Context；构造失败（极少数定制 ROM）返回 null，调用方跳过该语言。 */
    private static Context withLocale(Context base, Locale locale) {
        try {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(locale);
            return base.createConfigurationContext(config);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) if (value.equals(target)) return true;
        return false;
    }
}
