pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "999-vault-android"

include(
    ":app",
    ":benchmark",
    ":core:model",
    ":core:network",
    ":core:database",
    ":core:preferences",
    ":core:auth",
    ":core:playback",
    ":core:downloads",
    ":core:designsystem",
    ":core:testing",
)

