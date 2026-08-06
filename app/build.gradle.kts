plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

val configuredAccountOrigin = providers.gradleProperty("vaultAccountOrigin").orElse("").get()
val accountOriginLiteral = configuredAccountOrigin.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.vault999.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vault999.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["benchmarkSeedEnabled"] = "false"
        buildConfigField("String", "ACCOUNT_API_ORIGIN", "\"$accountOriginLiteral\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "USE_FIXTURES", "false")
        }
        create("fixture") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".fixture"
            versionNameSuffix = "-fixture"
            buildConfigField("boolean", "USE_FIXTURES", "true")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "USE_FIXTURES", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions.unitTests.isIncludeAndroidResources = true
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.name == "nonMinifiedRelease") {
            variant.manifestPlaceholders.put("benchmarkSeedEnabled", "true")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:preferences"))
    implementation(project(":core:auth"))
    implementation(project(":core:playback"))
    implementation(project(":core:downloads"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.ui.compose)
    baselineProfile(project(":benchmark"))

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.media3.session)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
