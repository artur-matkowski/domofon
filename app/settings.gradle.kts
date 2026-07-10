pluginManagement {
    repositories {
        google()
        mavenCentral()          // the Qt Gradle Plugin lives here
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

rootProject.name = "domofon"
include(":app")
