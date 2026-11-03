# ProGuard/R8 rules for Stream Buddy.
#
# The release build type runs R8 with resource shrinking; CI compiles that variant on every
# pull request, so a rule that is too aggressive shows up as a build failure rather than a
# runtime crash on a device.

# --- App entry points ---
# Workers are instantiated by name through WorkManager's default WorkerFactory.
-keep class com.streambuddy.app.cache.PreCacheWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class com.streambuddy.app.cache.SmartPrefetchWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
# The notification action target is referenced from the manifest.
-keep class com.streambuddy.app.cache.CancelPreCacheReceiver { *; }

# --- Kotlin metadata / coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- Room: entities and generated implementations are reached through reflection ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep interface com.streambuddy.app.data.*Dao { *; }
-dontwarn androidx.room.paging.**

# --- WorkManager ---
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }

# --- Media3 / ExoPlayer ---
# Media3 ships consumer rules; these keep the classes ExoPlayer instantiates reflectively
# (renderers, extractors, data sources named from media items).
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keepclassmembers class * implements androidx.media3.common.util.** { *; }
-dontwarn androidx.media3.**

# --- jlibtorrent (from its README) ---
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-keep class com.frostwire.jlibtorrent.swig.** { *; }
-dontwarn com.frostwire.jlibtorrent.**

# --- libtorrent4j ---
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Diagnostics ---
# Keep line numbers so a stack trace from a release build is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
