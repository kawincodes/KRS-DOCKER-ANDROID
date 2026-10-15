# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.krstech.dockers.**$$serializer { *; }
-keepclassmembers class com.krstech.dockers.** { *** Companion; }
-keepclasseswithmembers class com.krstech.dockers.** { kotlinx.serialization.KSerializer serializer(...); }
# OkHttp
-dontwarn okhttp3.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Built-in ADB client (libadb-android) loads Conscrypt reflectively; Conscrypt/SPAKE2 use JNI.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.android.org.conscrypt.**
# Started by name from `app_process` as the shell user (Shizuku-style service).
-keep class com.krstech.dockers.shell.ShellServer { public static void main(java.lang.String[]); *; }
