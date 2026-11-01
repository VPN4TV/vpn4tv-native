import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun getProps(propName: String): String {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        return props.getProperty(propName) ?: ""
    }
    return ""
}

android {
    // JVM unit tests: android.jar is stubs only, so calls like android.util.Log
    // return defaults instead of throwing; org.json comes from the real library.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    namespace = "com.vpn4tv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vpn4tv.hiddify"
        minSdk = 23
        targetSdk = 36
        versionCode = 54000
        versionName = "5.4.0"
        base.archivesName.set("VPN4TV-Native-${versionName}")

        // libbox.aar ships only armeabi-v7a + arm64-v8a; x86 variants would
        // bloat the AAR by ~160 MB. Declare the restriction explicitly so
        // Play Store marks the bundle as incompatible with x86/x86_64
        // devices (including pre-launch report emulators) instead of
        // trying to install and crashing at onCreate.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("geo.jks")
            storePassword = getProps("KEYSTORE_PASS")
            keyAlias = getProps("ALIAS_NAME")
            keyPassword = getProps("ALIAS_PASS")
        }
    }

    buildTypes {
        debug {
            if (getProps("KEYSTORE_PASS").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (getProps("KEYSTORE_PASS").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            vcsInfo.include = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
        }
    }

    // Single distribution flavor. ALL self-update / install-from-outside-Play
    // capability has been removed from the codebase entirely (Device & Network
    // Abuse policy — the appcast+APK self-updater got the app suspended). There
    // is no `direct` flavor anymore: the risky second variant was the vector.
    // The bell.a4e.ar sideload build is produced from this same flavor's
    // universal APK — it simply does not auto-update (users redownload manually).
    // Updates for Play users come only via Google Play (see PlayUpdateChecker,
    // the sanctioned in-app-update API).
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            aidl.directories.add("src/minApi23/aidl")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // libbox (sing-box JNI core with embedded xray-core for xhttp/splithttp)
    implementation(files("libs/libbox.aar"))

    // TrustTunnel client (AdGuard, Apache-2.0) — built from source for arm64 +
    // armeabi-v7a at minSdk 24 (see .scripts/build_trusttunnel.sh). Used in
    // SOCKS5 bridge mode under sing-box. Vendored AAR like libbox. slf4j-api is
    // its only hard transitive dep (via NativeLogger); we ship no binding so
    // slf4j no-ops (logback-android is JitPack-only — skipped).
    implementation(files("libs/trusttunnel-client.aar"))
    implementation("org.slf4j:slf4j-api:1.7.25")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")

    // Room DB
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager (subscription auto-update)
    implementation("androidx.work:work-runtime-ktx:2.11.1")

    // Play Core in-app update — used to force-prompt users on a stale build
    // when the corresponding release on Play Store has inAppUpdatePriority
    // set high (5). On devices without Google Play Services (SberBox, some
    // Chinese TVs, direct-APK installs) the manager gracefully returns a
    // failed Task; the checker catches that and the app starts normally.
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Compose + TV
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.tv:tv-foundation:1.0.0-rc01")
    implementation("androidx.tv:tv-material:1.0.0-rc01")

    // QR code generation
    implementation("com.google.zxing:core:3.5.4")


    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// TrustTunnel's socks outbound carries UDP (ConfigGenerator). That is safe only with the
// relay of our build of the library (branch vpn4tv-udp), which serves the one socket of
// this process that opened the association. The older relay takes datagrams from any app
// on the device, around the bridge password, and nothing else would show it: libs/ is not
// in git and the Java part of both builds is the same. The same build has the library's
// pings protect their sockets through our VpnService: the app stays inside its own VPN, and
// an older library hands an unprotected ping socket on as the endpoint connection, which
// then goes into the tunnel and drops (TrustTunnel never connects in VPN mode). So the
// build stops on an AAR without either.
val checkTrustTunnelAar by tasks.registering {
    description = "Fails unless libs/trusttunnel-client.aar has the own-client UDP relay and protected pings."
    val aar = layout.projectDirectory.file("libs/trusttunnel-client.aar")
    inputs.file(aar)
    doLast {
        // Log lines of the relay and of the ping protection, in every ABI.
        val markers = mapOf(
            "UDP association bound to" to "the old UDP relay, open to every app on the device",
            "Ping socket not protected by the application's VPN" to "pings that bypass the app's VpnService",
        )
        val wanted = setOf("arm64-v8a", "armeabi-v7a")
        val found = mutableSetOf<String>()
        ZipFile(aar.asFile).use { zip ->
            for (entry in zip.entries()) {
                val abi = Regex("jni/([^/]+)/libtrusttunnel_android\\.so")
                    .matchEntire(entry.name)?.groupValues?.get(1) ?: continue
                val so = String(zip.getInputStream(entry).use { it.readBytes() }, Charsets.ISO_8859_1)
                for ((marker, problem) in markers) {
                    if (!so.contains(marker)) throw GradleException(
                        "libs/trusttunnel-client.aar ($abi) has $problem: " +
                            "rebuild it from the TrustTunnel library's vpn4tv-udp branch")
                }
                found += abi
            }
        }
        if (!found.containsAll(wanted)) throw GradleException(
            "libs/trusttunnel-client.aar has no TrustTunnel library for ${wanted - found}")
    }
}

// The same for libbox: the configs need the core of the bridge-udp branch. An older one skips
// the dns field of the Outline and AmneziaWG bridges without a word (their servers' names then
// go to the tunnel's own DNS, a loop in bridge-only profiles), keeps UDP ASSOCIATE on and has
// no cap on UDP sessions.
val checkLibboxAar by tasks.registering {
    description = "Fails unless libs/libbox.aar is a core with the bridges' own DNS resolver."
    val aar = layout.projectDirectory.file("libs/libbox.aar")
    inputs.file(aar)
    doLast {
        val marker = "is not a usable server" // a log line of that resolver, in every ABI
        val wanted = setOf("arm64-v8a", "armeabi-v7a")
        val found = mutableSetOf<String>()
        ZipFile(aar.asFile).use { zip ->
            for (entry in zip.entries()) {
                val abi = Regex("jni/([^/]+)/libbox\\.so")
                    .matchEntire(entry.name)?.groupValues?.get(1) ?: continue
                val so = String(zip.getInputStream(entry).use { it.readBytes() }, Charsets.ISO_8859_1)
                if (!so.contains(marker)) throw GradleException(
                    "libs/libbox.aar ($abi) is an older core, without the bridges' DNS resolver: " +
                        "rebuild it from the core's bridge-udp branch")
                found += abi
            }
        }
        if (!found.containsAll(wanted)) throw GradleException(
            "libs/libbox.aar has no core for ${wanted - found}")
    }
}
tasks.named("preBuild") { dependsOn(checkTrustTunnelAar, checkLibboxAar) }
