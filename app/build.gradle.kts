import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Reads an optional repository-root .env file. .env is git-ignored and must
 * never be committed. Real environment variables win, which lets CI pass the
 * key from a GitHub secret without writing a file.
 */
fun readDotEnv(): Map<String, String> {
    val file = rootProject.file(".env")
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) null
        else trimmed.substring(0, index).trim() to
            trimmed.substring(index + 1).trim().trim('"', '\'')
    }.toMap()
}

fun buildConfigString(value: String): String {
    val cleaned = value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "")
    return "\"" + cleaned + "\""
}

val dotEnv = readDotEnv()
fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

/**
 * Release signing.
 *
 * Android refuses to install an unsigned APK, which used to make
 * `assembleRelease` produce an uninstallable `app-release-unsigned.apk`.
 *
 * Configuration comes from, in order: Gradle properties, environment
 * variables, then the optional repository-root .env file. When no release
 * keystore is configured the build falls back to the debug keystore so a
 * release build is always installable (with a warning), instead of silently
 * emitting an unsigned package.
 *
 * CI passes RELEASE_STORE_FILE as a base64 blob via secrets; see
 * .github/workflows/android-release.yml.
 */
val releaseStoreFileProp = firstNonBlank(
    providers.gradleProperty("RELEASE_STORE_FILE").orNull,
    System.getenv("RELEASE_STORE_FILE"),
    dotEnv["RELEASE_STORE_FILE"],
)
val releaseStorePassword = firstNonBlank(
    providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull,
    System.getenv("RELEASE_STORE_PASSWORD"),
    dotEnv["RELEASE_STORE_PASSWORD"],
)
val releaseKeyAlias = firstNonBlank(
    providers.gradleProperty("RELEASE_KEY_ALIAS").orNull,
    System.getenv("RELEASE_KEY_ALIAS"),
    dotEnv["RELEASE_KEY_ALIAS"],
)
val releaseKeyPassword = firstNonBlank(
    providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull,
    System.getenv("RELEASE_KEY_PASSWORD"),
    dotEnv["RELEASE_KEY_PASSWORD"],
)

// Materialize a base64 keystore blob (RELEASE_STORE_FILE_BASE64) to disk when
// CI hands us the key that way. Never committed: it lands in build/.
val releaseStoreFileBase64 = firstNonBlank(
    System.getenv("RELEASE_STORE_FILE_BASE64"),
    dotEnv["RELEASE_STORE_FILE_BASE64"],
)
val materializedReleaseStore: File = if (releaseStoreFileBase64.isNotEmpty()) {
    val target = layout.buildDirectory.file("release-keystore/release.jks").get().asFile
    target.parentFile.mkdirs()
    target.writeBytes(Base64.getDecoder().decode(releaseStoreFileBase64))
    target
} else if (releaseStoreFileProp.isNotEmpty()) {
    rootProject.file(releaseStoreFileProp)
} else {
    File("")
}
val releaseSigningConfigured =
    materializedReleaseStore.isFile && releaseStorePassword.isNotEmpty() && releaseKeyAlias.isNotEmpty()

val buildTimeTmdbKey = firstNonBlank(
    System.getenv("TMDB_API_KEY"),
    dotEnv["TMDB_API_KEY"],
)
val buildTimeMotnKey = firstNonBlank(
    System.getenv("MOTN_API_KEY"),
    System.getenv("MOVIE_OF_THE_NIGHT_KEY"),
    dotEnv["MOTN_API_KEY"],
    dotEnv["MOVIE_OF_THE_NIGHT_KEY"],
)

android {
    namespace = "com.streambuddy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streambuddy.app"
        // 26 is the floor set by libtorrent's prebuilt native library (Android 8.0, 2017).
        minSdk = 26
        // Play requires targeting a recent API level; 35 is also enforced edge-to-edge.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // libtorrent4j publishes one native library per ABI. A debug APK is
            // universal so it can be installed on phones and emulators.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Media3 marks most of its cache/datasource API @UnstableApi (opt-in level ERROR).
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = materializedReleaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Convenience for personal debug builds: keys from .env (ignored
            // by git) or environment variables. Release builds deliberately do
            // not embed any key.
            buildConfigField("String", "TMDB_API_KEY", buildConfigString(buildTimeTmdbKey))
            buildConfigField("String", "MOTN_API_KEY", buildConfigString(buildTimeMotnKey))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "TMDB_API_KEY", buildConfigString(""))
            buildConfigField("String", "MOTN_API_KEY", buildConfigString(""))
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // A release APK must be signed or Android will refuse to install
            // it. Prefer the real release keystore; otherwise fall back to the
            // debug keystore so `assembleRelease` still yields an installable
            // package instead of app-release-unsigned.apk.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "Stream Buddy: no release keystore configured; signing release with the debug key. " +
                        "Set RELEASE_STORE_FILE_BASE64 / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / " +
                        "RELEASE_KEY_PASSWORD to ship a properly signed release.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = File(project.layout.buildDirectory.get().asFile, "reports/lint-results-debug.txt")
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

/**
 * Copies finished APKs to a stable, obvious place: <repo>/dist/.
 *
 * Gradle's own outputs live under app/build/outputs/apk/<variant>/, which is
 * git-ignored and deleted by every `gradlew clean`. Publishing them to dist/
 * means there is one predictable location to grab an installable APK from,
 * with a clear name that says which variant and version it is.
 */
val distDir = rootProject.layout.projectDirectory.dir("dist").asFile

tasks.register<Copy>("stageReleaseApk") {
    group = "distribution"
    description = "Copies the signed release APK to <repo>/dist/ with a versioned name."
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    exclude("*unsigned*.apk")
    into(distDir)
    rename { name ->
        if (name.contains("-unsigned")) name
        else "stream-buddy-release-v${android.defaultConfig.versionName}.apk"
    }
    dependsOn("assembleRelease")
}

tasks.register<Copy>("stageDebugApk") {
    group = "distribution"
    description = "Copies the debug APK to <repo>/dist/ with a versioned name."
    from(layout.buildDirectory.dir("outputs/apk/debug"))
    include("*.apk")
    into(distDir)
    rename { "stream-buddy-debug-v${android.defaultConfig.versionName}.apk" }
    dependsOn("assembleDebug")
}

// Guard: fail loudly instead of silently publishing an uninstallable package.
tasks.register("verifyReleaseSigned") {
    group = "distribution"
    description = "Fails if the release APK is unsigned or missing."
    dependsOn("stageReleaseApk")
    doLast {
        val apks = distDir.listFiles { f -> f.isFile && f.extension == "apk" }?.toList().orEmpty()
        val release = apks.filter { it.name.contains("release") }
        check(release.isNotEmpty()) {
            "No release APK found in $distDir. Did assembleRelease succeed?"
        }
        release.forEach { apk ->
            check(!apk.name.contains("unsigned")) {
                "${apk.name} is unsigned and cannot be installed. " +
                    "Configure RELEASE_STORE_FILE_BASE64 / RELEASE_STORE_PASSWORD / " +
                    "RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD."
            }
        }
        logger.lifecycle("Release APK ready: ${release.joinToString { it.absolutePath }}")
    }
}

dependencies {
    val media3 = "1.4.1"
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background pre-caching
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-session:$media3")

    // BitTorrent: Java API + native library for every ABI in one AAR, with its own keep rules.
    implementation("com.frostwire:jlibtorrent-android:2.0.15.3")

    // Networking / coroutines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Android-compatible JNI binding for libtorrent. One artifact per ABI.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-39")

    // Unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Instrumented tests (device / emulator)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
