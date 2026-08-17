package com.muxiao.Venus;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.google.android.material.color.DynamicColors;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.LocaleHelper;

import dagger.hilt.android.HiltAndroidApp;
import dagger.hilt.android.qualifiers.ApplicationContext;

import javax.inject.Inject;

@HiltAndroidApp
public class VenusApplication extends Application {
    // 触发 Hilt 内置 @ApplicationContext 绑定被依赖图实际使用，从而消除 Dagger 生成的
    // applicationContextModule(ApplicationContextModule) 弃用告警（dagger issue #3601，
    // Hilt 2.56 仍存在；该绑定未被任何注入点使用时 Dagger 会将其裁剪并标记弃用）。
    // appContext 与 this 等价，这里仅作为绑定使用锚点；后续 dagger#3601 修复后可删除。
    @Inject @ApplicationContext Context appContext;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        // 注意：绝不能在此处调用 attachBaseContext()，它只能在应用创建时调用一次，
        // 重复调用会抛出 IllegalStateException: Base context already set 并导致崩溃。
        // 语言设置已在 attachBaseContext() 中通过 LocaleHelper.wrap() 处理：
        // 当设置为「跟随系统」时 wrap() 直接返回原 Context，由系统语言决定，无需此处再处理；
        // 手动切换语言时应由各 Activity 自行 recreate() 以重新走 attachBaseContext 流程。
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 应用选定的主题深浅色（使用 Hilt 注入的 ApplicationContext，确保该绑定被使用）
        SettingsFragment.applyThemeVariant(appContext);
        // 启用material动态颜色
        DynamicColors.applyToActivitiesIfAvailable(this);
        // 注册设备标识（gzu.liyujiang.android.cn.oaid库）
        // DeviceIdentifier.register(this);

        // 预热 HeaderManager 的设备标识（OAID / device_fp）。
        // HeaderManager 构造会同步阻塞等待设备标识就绪（waitForDeviceId 最多 ~5s，
        // getExtFields 内还会再等 ~5s）。若首次 new HeaderManager 发生在 UI 线程
        // （如直接打开图片浏览页而此前未经过登录/签到），主线程会被阻塞约 10s，
        // 触发 ANR 被系统杀掉，表现为「一进页面就闪退」。
        // 这里在后台线程预热一次，填满静态缓存，使后续任意线程的 new HeaderManager
        // 都直接命中缓存、不再阻塞。
        new Thread(() -> {
            try {
                // 触发 initSharedDeviceUtils 的静态缓存填充（首次会阻塞在后台线程）
                new HeaderManager(appContext, false);
            } catch (Throwable ignored) {
                // 预热失败不应影响启动；UI 路径仍有超时回退兜底
            }
        }, "HeaderManager-Prewarm").start();
    }
}
