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
        // Huawei maven repository for HMS Push Kit
        maven { url = java.net.URI("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "Aura-Grid-Android"
include(":app")
