# ==========================================
# Venus ProGuard / R8 Rules
# ==========================================

# ---------- 抑制 R8 警告 ----------
-dontwarn javax.imageio.**
-dontwarn javax.imageio.spi.**

# ---------- 通用优化配置 ----------
-allowaccessmodification
-repackageclasses ''
-optimizations !code/simplification/variable,!code/simplification/arithmetic

# 保留行号信息用于崩溃堆栈定位（发布时隐藏源文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 保留注解 ----------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# ---------- GeeTest 极验验证码（混淆会导致无法正常显示） ----------
-keep class com.geetest.sdk.** {*;}
-keep class com.geetest.captcha.** {*;}
-keepclassmembers class * {
    @com.geetest.sdk.* <methods>;
}

# ---------- OAID 库（使用反射获取设备 ID） ----------
-keep class com.github.gzuliyujiang.oaid.** {*;}
-keep class com.bun.miitmdid.** {*;}
-keep class a.a.a.** {*;}

# ---------- uCrop 图片裁剪 ----------
-keep class com.yalantis.ucrop.** {*;}
-keepclassmembers class com.yalantis.ucrop.** {*;}

# ---------- Glide（自带 consumer-rules，补充公共 API） ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# ---------- ZXing 二维码 ----------
-keep class com.google.zxing.** {*;}
# ZXing javase 模块包含桌面端类（java.awt/javax.swing），Android 上不存在
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.w3c.dom.bootstrap.**

# ---------- Gson 序列化 ----------
# 保留 Gson 的 TypeToken 及其泛型签名
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
# 保留 @SerializedName 注解的字段
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- AndroidX / Material ----------
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class com.google.android.material.** {*;}

# ---------- 保留 Application 和 Activity 入口 ----------
-keep class com.muxiao.Venus.VenusApplication { *; }
-keep class com.muxiao.Venus.MainActivity { *; }
-keep class com.muxiao.Venus.User.UserLoginActivity { *; }
-keep class com.muxiao.Venus.Setting.FullscreenImageActivity { *; }
-keep class com.muxiao.Venus.Setting.ImageActivity { *; }
-keep class com.muxiao.Venus.Home.ForegroundTaskService { *; }

# ---------- 保留 WebView 相关接口 ----------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# ---------- 移除 Log 调用（减小体积 + 消除调试字符串） ----------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
