plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI passes these in; local builds fall back to sensible defaults.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.0"

// Every release must be signed with the same key, or self-updates stop installing.
val releaseKeystore = System.getenv("SLOOTA_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "dev.pridgey.sloota"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pridgey.sloota"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName
        // Where the app looks for updates.
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("UPDATE_REPO") ?: "pridgey/Sloota"}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SLOOTA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SLOOTA_KEY_ALIAS")
                keyPassword = System.getenv("SLOOTA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the CI key, local release builds use the debug key so they still install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "ObsoleteSdkInt")
    }
}

base {
    archivesName.set("sloota-$ciVersionName")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
