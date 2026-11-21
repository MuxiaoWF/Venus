package com.muxiao.Venus.Home;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个账号的任务配置快照（{@link TaskProfileStore} 的序列化载体）。
 *
 * <p>记录的是「用户勾了什么」这个原始意图，<b>不含服务器类型过滤</b>：哪些任务在该账号的服务器上
 * 实际可用，由 {@link TaskSettings} 在执行期判定。这样同一份配置不会因为换服或复用到别的账号而丢掉
 * 勾选（例如国际服看不到「崩坏2」的 chip，但切回国服后勾选仍在）。
 *
 * <p>字段为 public：Gson 直读直写，与 {@code widget/TaskStatusManager.Bucket} 同风格。
 */
public class TaskProfile {

    /** 当前结构版本；读到更高版本时按当前版本处理（前向兼容），更低版本由 Store 补默认值。 */
    public static final int VERSION = 1;

    public int v = VERSION;

    /** 米游币签到总开关（国际服没有 stoken 链路，由 TaskSettings 强制关闭）。 */
    public boolean daily = true;
    /** 米游币签到的讨论区（游戏名，输出顺序由 {@link TaskProfileStore#DAILY_FORUM_ORDER} 规范）。 */
    public List<String> dailyForums = new ArrayList<>();

    /** 游戏签到总开关。 */
    public boolean gameDaily = true;
    /** 游戏签到勾选的游戏。 */
    public List<String> gameDailyGames = new ArrayList<>();

    /** 云游戏总开关：与「勾了哪几款」分开——总开关开着却一款未勾时，运行入口要给可读提示。 */
    public boolean cloudGame = false;
    public List<String> cloudGames = new ArrayList<>();

    /** 森空岛总开关 + 两款游戏（子项独立保存：关掉总开关再打开不会丢勾选）。 */
    public boolean skland = false;
    public boolean sklandArknights = false;
    public boolean sklandEndfield = false;
}
