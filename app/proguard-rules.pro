# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# TrustTunnel (vendored AAR). VpnServiceConfig parses TOML with ktoml — that
# is the TUN-mode helper we never call (the bridge feeds VpnClient a raw
# string), so the library is not shipped. slf4j has no binding on purpose:
# its LoggerFactory falls back to a no-op logger.
-dontwarn com.akuleshov7.ktoml.**
-dontwarn org.slf4j.impl.**

# Release builds keep no debug or verbose lines in the system log: the calls
# to Log.d and Log.v are removed. Those lines carried configs, server details
# and the sites of single connections (Google's core app quality,
# Sensitive_Data_Logging); what is logged at info and above names none of them
# (LogPrivacy). Debug builds keep everything.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Glance creates a widget's ActionCallback by its class name and no-argument
# constructor (RunCallbackAction). Glance's own rule keeps the class, but R8's
# full mode then drops the constructor nobody calls in code, and the widget's
# Connect/Disconnect does nothing in a release build.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
