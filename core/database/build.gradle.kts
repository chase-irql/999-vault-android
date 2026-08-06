plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.legacy.kapt)
}
android {
    namespace = "com.vault999.android.database"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}
dependencies {
    implementation(project(":core:model"))
    // VaultDatabase is exposed to application composition code, so its
    // RoomDatabase supertype must be present on downstream compile classpaths.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
