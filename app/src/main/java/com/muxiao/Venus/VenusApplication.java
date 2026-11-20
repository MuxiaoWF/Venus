package com.muxiao.Venus;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.muxiao.Venus.Home.DailyTaskScheduler;
import com.muxiao.Venus.Home.SklandDaily;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import dagger.hilt.android.HiltAndroidApp;
import dagger.hilt.android.qualifiers.ApplicationContext;

import javax.inject.Inject;

@HiltAndroidApp
public class VenusApplication extends Application {
    /** 全局应用上下文（onCreate 时赋值）。供无 Context 参数的底层工具（如 tools 的网络异常文案）取本地化字符串。 */
    private static volatile Context sAppContext;

    /** 返回全局应用上下文；仅 Application 创建后可用（应用生命周期内必然已创建）。 */
    public static Context getAppContext() {
        return sAppContext;
    }

    /**
     * 设置项 / 语言变更即刷新小组件。
     *
     * <p>为什么挂在 Application 而不是各个开关的回调里：任务开关、定时配置、语言、缩放等由多个页面
     * 分头写 settings_prefs，逐处补刷新必然漏；小组件又关闭了系统周期刷新（updatePeriodMillis=0），
     * 旧版「设置页改了任务开关、回桌面仍列旧任务集」只能等用户回一次首页才同步。
     *
     * <p>必须静态强引用：SharedPreferences 内部用 WeakHashMap 持有监听器，局部变量会被回收。
     * 刷新本身有签名比对兜底——与可见画面无关的键（如主题）不会产生任何 IPC。
     */
    private static final SharedPreferences.OnSharedPreferenceChangeListener WIDGET_REFRESH_LISTENER =
            (prefs, key) -> TaskWidgetProvider.refreshAllWidgets(VenusApplication.getAppContext());

    // 触发 Hilt 内置 @ApplicationContext 绑定被依赖图实际使用，消除 Dagger 生成的
    // applicationContextModule(ApplicationContextModule) 弃用告警（dagger issue #3601，Hilt 2.56 仍存在；
    // 该绑定未被任何注入点使用时 Dagger 会将其裁剪并标记弃用）。修复后可删除。
    @Inject @ApplicationContext Context appContext;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        // 绝不能在此处调用 attachBaseContext()：它只能在应用创建时调用一次，重复调用抛
        // IllegalStateException: Base context already set 并崩溃。语言已在 attachBaseContext 经 LocaleHelper.wrap
        // 处理（「跟随系统」时直接返回原 Context）；手动切换语言由各 Activity 自行 recreate() 重走该流程。
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = this;
        // 设置项/语言变更 → 小组件重渲染（见 WIDGET_REFRESH_LISTENER 注释）
        getSharedPreferences(Constants.Prefs.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(WIDGET_REFRESH_LISTENER);
        getSharedPreferences(Constants.Prefs.LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(WIDGET_REFRESH_LISTENER);
        // 深浅色由 BaseActivity.attachBaseContext（LocaleHelper.wrap）按偏好覆写 uiMode，启动时无需处理
        // 动态取色已随 View 版 material 库移除：Theme.Venus 直接映射 res/color-v31 系统动态色
        // （Android 12+ 生效；以下版本回落 M3 基线），无需运行时包装 Activity 主题。

        // 预热 HeaderManager 的设备标识（OAID / device_fp）：构造会同步阻塞等设备标识就绪
        // （waitForDeviceId 最多 ~5s，getExtFields 内还会再等 ~5s）。若首次 new HeaderManager 发生在 UI 线程
        // （如直接打开图片浏览页而此前未经过登录/签到），主线程被阻塞约 10s 触发 ANR，表现为「一进页面就闪退」。
        // 这里在后台预热一次填满静态缓存，后续任意线程 new HeaderManager 都直接命中缓存、不再阻塞。
        new Thread(() -> {
            try {
                new HeaderManager(appContext, false);
            } catch (Throwable ignored) {
                // 预热失败不应影响启动；UI 路径仍有超时回退兜底
            }
        }, "HeaderManager-Prewarm").start();

        // 每日定时签到：冷启动时修复调度链。链式调度（Worker 执行完 APPEND 下一次）在进程死亡/清除数据后
        // 可能断链，这里以 KEEP 幂等兜底：已有待执行调度则不动作，断链则按「当天必跑一次」口径重建。
        AppExecutors.get().execute(() -> DailyTaskScheduler.ensureScheduled(this));

        // 森空岛 token 从「全局一份」改为「按用户存放」：启动时把历史值迁给当前账号。
        // 走后台线程：首次访问会触发该用户数据仓储的冷盘预热（同步读），不宜占用主线程。
        AppExecutors.get().execute(() -> {
            try {
                SklandDaily.migrateLegacyToken(this);
            } catch (Throwable ignored) {
                // 迁移失败不应影响启动；用户在设置页重新填写即可
            }
        });
    }
}
