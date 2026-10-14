<?xml version="1.0" encoding="utf-8"?>
<!--
  Core library ProGuard rules.
  Classes consumed by Apps outside this module must survive shrinking.
-->
-keep class com.personal.base.core.** { *; }
-keep class com.personal.base.platform.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.personal.base.** {
    *** Companion;
}
-keepclasseswithmembers class com.personal.base.** {
    kotlinx.serialization.KSerializer serializer(...);
}