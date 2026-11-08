import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The app's Baseline Profile: BaselineProfileGenerator walks the main screens
// and the plugin writes what ran into app/src/main/generated/baselineProfiles.
// Play compiles those classes and methods ahead of time at install, so a cold
// start and the first screens run less code through the JIT (TV boxes most).
//
// Regenerate after big UI changes, on an emulator or device with Android 13+
// (or a rooted Android 9-12). With several devices connected, name one:
//   ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.vpn4tv.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // The app's single flavor.
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
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    // A connected emulator or device, not a Gradle managed device (no system
    // images to download).
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
