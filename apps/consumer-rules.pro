<?xml version="1.0" encoding="utf-8"?>
<!--
  Apps are discovered by @PlatformAppDefinition at build time, so nothing needs keeping here.
  Add rules for Apps that ship their own components or use reflection.
-->
-keep class com.personal.base.apps.** { *; }