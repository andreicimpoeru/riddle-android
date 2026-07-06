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
        maven { url = uri("https://jitpack.io") }
        // Onyx Boox SDK repository
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

rootProject.name = "riddle-android"
include(":app")