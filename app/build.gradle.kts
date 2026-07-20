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

    buildTypes {
        release {
            isMinifyEnabled = false
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

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                buildOutputApk
                    ?: "toys.compy.launcher-${output.versionName.get()}.apk"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
