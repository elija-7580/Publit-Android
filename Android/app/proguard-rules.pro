-keeppackagenames **
-keep class skip.** { *; }
-keep class tools.skip.** { *; }
-keep class kotlin.jvm.functions.** {*;}
-keep interface kotlinx.coroutines.Job { void cancel(java.util.concurrent.CancellationException); }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-keep class * implements com.sun.jna.** { *; }
-keep class * implements skip.bridge.** { *; }
-keep class **._ModuleBundleAccessor_* { *; }
-keep class publit.module.** { *; }
# Swift reaches Droid, MapSpec, MapComposer … through Kotlin reflection (skip.bridge.Reflector):
# it needs the Kotlin metadata of those classes, or release builds fail every call from Swift.
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,Signature,InnerClasses,EnclosingMethod
