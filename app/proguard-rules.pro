# Keep the libtorrent JNI facade and SWIG generated classes. They are invoked
# from native code and through reflection in a few places.
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**

# Room generated implementations and entities.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }

# Media3 uses reflection to instantiate renderers/extractors on some devices.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin metadata used by coroutines and Compose.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers class **$WhenMappings { <fields>; }

# Do not keep source file names or line numbers in release stack traces.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
