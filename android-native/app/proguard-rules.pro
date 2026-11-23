# kotlinx.serialization: keep generated serializers of the API models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.elija7580.publit.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Readable stack traces in crash reports.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Tesseract4Android (foss build): the native code reads fields and calls methods by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
