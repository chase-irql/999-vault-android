plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.legacy.kapt)
}
android {
    namespace = "com.vault999.android.database"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
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
}
