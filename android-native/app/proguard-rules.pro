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
