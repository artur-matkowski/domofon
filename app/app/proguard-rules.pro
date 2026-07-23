# Keep the QML wrapper classes the Qt Gradle Plugin generates; they are referenced
# reflectively from the Qt runtime.
-keep class org.qtproject.** { *; }
-dontwarn org.qtproject.**

# --- HiveMQ MQTT client (shaded) ----------------------------------------------
# Netty resolves transports, allocators and codecs by name at runtime, so R8 cannot see
# those references and strips them; the symptom is a ClassNotFoundException on the first
# connect in a release build while debug works fine.
#
# NOTE the package prefix. HiveMQ's own Android documentation gives these rules for
# `io.netty.**` and `org.jctools.**`, which is correct for the plain artifact and a silent
# no-op for the `-shaded` one we use — the shaded jar relocates everything under
# com.hivemq.client.internal.shaded. Copying the documented rules verbatim produces rules
# that match nothing and a release build that misbehaves for no visible reason.
-keepclassmembernames class com.hivemq.client.internal.shaded.io.netty.** { *; }
-keepclassmembers class com.hivemq.client.internal.shaded.org.jctools.** { *; }

# Netty probes for optional codecs, TLS providers and loggers that exist on neither
# Android nor this classpath. Without these, R8 fails the build outright with
# "Missing classes detected".
-dontwarn com.hivemq.client.internal.shaded.io.netty.internal.tcnative.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.slf4j.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.invoke.LambdaMetafactory
-dontwarn javax.naming.ldap.**

# --- Car App Library ----------------------------------------------------------
# Deliberately nothing here. Both androidx.car.app AARs ship their own consumer rules,
# which keep the IInterface subtypes and everything annotated @KeepFields — and the model
# classes are annotated, because the host serializes them across the binder by class name
# and declared-field name. A blanket `-keep class androidx.car.app.** { *; }` would only
# defeat shrinking of a library that already protects itself correctly.

# --- Preference screen --------------------------------------------------------
# Preference subclasses are named in XML and instantiated reflectively.
-keep class * extends androidx.preference.Preference { *; }

# --- Release log hygiene ------------------------------------------------------
# The app logs gate transitions and arrival-at-home. Those are readable via adb, bug
# reports, and anything holding READ_LOGS, so the chatty levels come out of release
# builds. Log.w and Log.e stay: they are what makes a field failure diagnosable.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
