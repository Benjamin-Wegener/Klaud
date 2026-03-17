pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Guardian Project Maven Repository for tor-android
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Klaud"
include(":app")
