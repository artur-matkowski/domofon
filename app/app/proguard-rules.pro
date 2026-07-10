# Keep the QML wrapper classes the Qt Gradle Plugin generates; they are referenced
# reflectively from the Qt runtime.
-keep class org.qtproject.** { *; }
