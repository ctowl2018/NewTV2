# TV
-keep class androidx.leanback.widget.** { *; }

-keep class com.fongmi.android.tv.bean.** { *; }

-keep class com.fongmi.android.tv.ui.activity.WebActivity {
  public static boolean openWeb(java.lang.String);
  public static java.lang.String getUserAgent();
}

-keep class androidx.core.content.FileProvider {
  public static android.net.Uri getUriForFile(android.content.Context, java.lang.String, java.io.File);
}

# Gson
-keep class com.google.gson.** { *; }

# Gson 泛型必需：R8 默认剥离 Signature 属性后，Gson 拿不到字段的泛型参数
# （如 List<DoubanHotItem>、Map<String, TmdbArt>、TypeToken 匿名子类的父类签名），
# 会把元素反序列化成 LinkedTreeMap，运行期强转直接 ClassCastException。
# 只在 release（minifyEnabled）出现，debug 不复现。
-keepattributes Signature
-keepattributes *Annotation*
# 反射解析的 Gson 模型：字段名不混淆，避免读写键名随版本漂移。
-keepclassmembers class com.fongmi.android.tv.utils.DoubanHot$Cache {
  <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# SLF4J
-keep class org.slf4j.** { *; }

# Kotlin
-keep class kotlin.** { *; }

# JUPnP
-dontwarn org.jupnp.**
-keep class org.jupnp.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# TVBus
-keep class com.tvbus.engine.** { *; }

# Zxing QRCode ABI
-keep class com.google.zxing.* { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.common.HybridBinarizer { *; }
-keep class com.google.zxing.qrcode.QRCodeReader { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.qrcode.decoder.ErrorCorrectionLevel { *; }
