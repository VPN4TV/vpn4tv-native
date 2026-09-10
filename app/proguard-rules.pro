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
