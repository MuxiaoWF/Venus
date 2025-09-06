# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 禁用混淆但保持代码压缩
-dontobfuscate

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 优化相关规则
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# 保留Activity、Fragment等Android组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# 保留自定义View
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留Parcelable
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# 保留R文件中的特定字段
-keepclassmembers class **.R$* {
    public static <fields>;
}

# 保护Material Components相关类
-keep class com.google.android.material.** { *; }

# 保护Gson相关类
-keep class com.google.gson.** { *; }

# 保护OkHttp相关类
-keep class okhttp3.** { *; }

# 保护项目特定类
-keep class com.muxiao.Venus.** { *; }

# 不跳过非公共库类
-dontskipnonpubliclibraryclasses

# 优化时允许访问修饰符更改
-allowaccessmodification

# 混淆时保持泛型信息
-keepattributes Signature

# 保持注解
-keepattributes *Annotation*

# 保持异常信息
-keepattributes Exceptions
