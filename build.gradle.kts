plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.legacy.kapt) apply false
    alias(libs.plugins.baselineprofile) apply false
}

tasks.register<Exec>("validateParity") {
    group = "verification"
    description = "Validates docs/platform-parity.yaml."
    commandLine("node", "scripts/validate-parity.mjs")
}
