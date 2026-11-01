# Scaffold ProGuard rules

# Keep Room annotations & implementations
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep WebKit & JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Gson models
-keepclassmembers class net.mangadot.scaffold.data.** { *; }

# Keep AdBlock native JNI bindings
-keepclasseswithmembernames class * {
    native <methods>;
}
