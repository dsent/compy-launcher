plugins {
    alias(libs.plugins.android.application)
}

// Version identity is supplied by the packaging environment so that the
// embedded version and the APK file name both identify the exact source
// commit that produced the build. A plain `./gradlew assembleDebug` in a
// standalone clone falls back to a placeholder so the build still works.
val buildVersionName: String =
    providers.environmentVariable("VERSION_NAME").orNull
        ?.takeIf { it.isNotBlank() }
        ?: "0.0.0"

val buildVersionCode: Int =
    providers.environmentVariable("VERSION_CODE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let {
            it.toIntOrNull()
                ?: throw GradleException("VERSION_CODE must be an integer: $it")
        }
        ?: 1

// When set, names the output APK exactly. Otherwise the name is derived
// from the version, so it still varies with the build.
val buildOutputApk: String? =
    providers.environmentVariable("OUTPUT_APK").orNull?.takeIf { it.isNotBlank() }

val signingEnvironment =
    listOf(
        "COMPY_ANDROID_KEYSTORE_PATH",
        "COMPY_ANDROID_KEYSTORE_PASSWORD",
        "COMPY_ANDROID_KEY_ALIAS",
        "COMPY_ANDROID_KEY_PASSWORD",
    ).associateWith { name ->
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    }
val configuredSigningValues = signingEnvironment.values.count { it != null }

if (configuredSigningValues != 0 && configuredSigningValues != signingEnvironment.size) {
    val missing = signingEnvironment.filterValues { it == null }.keys.joinToString()
    throw GradleException("Incomplete Compy Android signing configuration; missing: $missing")
}

val stableSigningConfigured = configuredSigningValues == signingEnvironment.size

android {
    namespace = "toys.compy.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "toys.compy.launcher"
        minSdk = 24
        targetSdk = 33
        versionCode = buildVersionCode
        versionName = buildVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val stableReleaseSigning =
        if (stableSigningConfigured) {
            signingConfigs.create("stableRelease") {
                storeFile = file(signingEnvironment.getValue("COMPY_ANDROID_KEYSTORE_PATH")!!)
                storeType = "PKCS12"
                storePassword = signingEnvironment.getValue("COMPY_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingEnvironment.getValue("COMPY_ANDROID_KEY_ALIAS")
                keyPassword = signingEnvironment.getValue("COMPY_ANDROID_KEY_PASSWORD")
            }
        } else {
            null
        }

    buildTypes {
        release {
            isMinifyEnabled = false
            stableReleaseSigning?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Keep Gradle sync and debug-only work usable without product credentials while
// preventing an unsigned release artifact from being assembled accidentally.
gradle.taskGraph.whenReady {
    val packagesRelease =
        allTasks.any { task ->
            task.name == "assembleRelease" ||
                task.name == "bundleRelease" ||
                task.name == "packageRelease"
        }
    if (packagesRelease && !stableSigningConfigured) {
        throw GradleException(
            "Release packaging requires COMPY_ANDROID_KEYSTORE_PATH, " +
                "COMPY_ANDROID_KEYSTORE_PASSWORD, COMPY_ANDROID_KEY_ALIAS, and " +
                "COMPY_ANDROID_KEY_PASSWORD",
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val derivedOutput =
                if (variant.buildType == "debug") {
                    "toys.compy.launcher-debug-${output.versionName.get()}.apk"
                } else {
                    "toys.compy.launcher-${output.versionName.get()}.apk"
                }
            output.outputFileName.set(
                buildOutputApk
                    ?: derivedOutput
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
