pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wifi-adb"
include(":app")

includeBuild("../dadb") {
    dependencySubstitution {
        substitute(module("dev.mobile:dadb")).using(project(":dadb"))
    }
}
