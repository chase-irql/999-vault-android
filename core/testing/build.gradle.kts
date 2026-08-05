plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.vault999.android.testing"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.junit)
}

