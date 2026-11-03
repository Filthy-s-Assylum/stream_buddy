<<<<<<< HEAD
import java.util.Base64
=======
import java.util.Properties
>>>>>>> origin/main

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
<<<<<<< HEAD
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
=======
 * Release signing is configured from `keystore.properties` (untracked) or, in CI, from
 * environment variables. Without either, `assembleRelease` still runs and produces an unsigned
 * APK — convenient for CI, but such a build cannot be installed over a signed one.
 *
 *   STREAMBUDDY_KEYSTORE       path to the .jks
 *   STREAMBUDDY_KEYSTORE_PASS  store password
 *   STREAMBUDDY_KEY_ALIAS      key alias
 *   STREAMBUDDY_KEY_PASS       key password
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "STREAMBUDDY_KEYSTORE")
val releaseStorePassword = signingValue("storePassword", "STREAMBUDDY_KEYSTORE_PASS")
val releaseKeyAlias = signingValue("keyAlias", "STREAMBUDDY_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "STREAMBUDDY_KEY_PASS")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
>>>>>>> origin/main

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
<<<<<<< HEAD

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // libtorrent4j publishes one native library per ABI. A debug APK is
            // universal so it can be installed on phones and emulators.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
=======
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // libtorrent ships a native library per ABI. Shipping only the two that matter keeps the
        // APK ~40 MB smaller; Play's app bundle splits per device anyway.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 is on: the keep rules for Room, WorkManager, Media3 and OkHttp live in
            // app/proguard-rules.pro, and the CI build compiles this variant on every run.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
>>>>>>> origin/main
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
<<<<<<< HEAD
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
=======
        // Media3 marks most of its cache/datasource API @UnstableApi (opt-in level ERROR).
        // Every call site is also annotated; this keeps the build safe if one is missed.
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
>>>>>>> origin/main
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

<<<<<<< HEAD
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
=======
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
            )
        }
>>>>>>> origin/main
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
<<<<<<< HEAD
        abortOnError = false
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
=======
        // Treat lint errors as build failures, but do not fail on the warning backlog.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        // Plain-text report so CI can surface every error as a check annotation.
        textReport = true
        textOutput = File(project.layout.buildDirectory.get().asFile, "reports/lint-results-debug.txt")
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
>>>>>>> origin/main
    }
}

dependencies {
<<<<<<< HEAD
    val media3 = "1.4.1"
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

=======
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
>>>>>>> origin/main
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.runtime:runtime")
<<<<<<< HEAD
=======
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
>>>>>>> origin/main

    // Persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background pre-caching
    implementation("androidx.work:work-runtime-ktx:2.9.1")

<<<<<<< HEAD
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-session:$media3")
=======
    // Media playback + on-disk cache
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // CacheDataSource/SimpleCache/CacheWriter live in media3-datasource;
    // StandaloneDatabaseProvider lives in media3-database. There is no
    // media3-datasource-cache artifact.
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
>>>>>>> origin/main

    // BitTorrent: Java API + native library for every ABI in one AAR, with its own keep rules.
    implementation("com.frostwire:jlibtorrent-android:2.0.15.3")

    // Networking / coroutines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
<<<<<<< HEAD

    // Android-compatible JNI binding for libtorrent. One artifact per ABI.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-39")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
=======
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

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
>>>>>>> origin/main
}
