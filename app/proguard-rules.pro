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

# ---------- RxJava3 ----------
# RxJava 无反射调用，无需 keep：全量 keep 会保留全部 1700+ 类（约 1/3 的 dex 体积）。
# R8 会只保留实际用到的操作符；dontwarn 处理 optional 依赖引用。
-dontwarn io.reactivex.rxjava3.**
-dontwarn org.reactivestreams.**

# ---------- DataStore ----------
# androidx 系列库自带 consumer rules；全量 keep 会连内部 protobuf 元数据一起保留。
-dontwarn androidx.datastore.**

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
# uCrop 的 UCropActivity / 自定义 View 已由 manifest/XML 引用自动 keep（AAPT 规则），
# 库自带 consumer rules；无需 app 侧全量 keep（否则 85+ 类全部保留）。

# ---------- Glide（自带 consumer-rules，补充公共 API） ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# ---------- ZXing 二维码 ----------
# 仅用到 QRCodeWriter/BitMatrix 等直调类，无反射，交给 R8 按需裁剪（全量 keep 会保留 289 类）。
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
# Material 自带完备的 consumer rules；XML 引用的组件由 AAPT 规则自动 keep。
# 全量 keep 会保留全部未用到的组件与联动资源（dex + resources.arsc 双重膨胀）。

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

# ---------- Hilt 依赖注入（阶段 1，无 DataStore） ----------
# Hilt 自带 consumer-rules 已保留大部分生成类；R8 fullMode 下补充 Fragment 上下文包装类，
# 避免 Hilt 注入的 Fragment 在 release 构建里被误删导致运行时崩溃。
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
