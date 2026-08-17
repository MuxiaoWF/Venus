package com.muxiao.Venus.di;

import android.content.Context;

import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.data.ConfigRepository;
import com.muxiao.Venus.common.data.TaskStatusRepository;
import com.muxiao.Venus.common.data.UserRepository;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

import javax.inject.Singleton;

/**
 * 应用级 DI 模块（Hilt / 阶段 1）。
 * 仅收口进程级单例：线程池与三个已落地的 Repository。
 * 说明：Application 与 @ApplicationContext Context 由 @HiltAndroidApp 自动绑定，
 * 此处无需（也不应）重复 @Provides，否则会与 Hilt 默认绑定冲突。
 */
@Module
@InstallIn(SingletonComponent.class)
public final class AppModule {

    @Provides
    @Singleton
    AppExecutors provideAppExecutors() {
        // 复用既有进程级单例，保证与 AppExecutors.get() 返回同一实例（行为一致）
        return AppExecutors.get();
    }

    @Provides
    @Singleton
    UserRepository provideUserRepository(@ApplicationContext Context context) {
        return new UserRepository(context);
    }

    @Provides
    @Singleton
    ConfigRepository provideConfigRepository(@ApplicationContext Context context) {
        return new ConfigRepository(context);
    }

    @Provides
    @Singleton
    TaskStatusRepository provideTaskStatusRepository(@ApplicationContext Context context) {
        return new TaskStatusRepository(context);
    }
}
