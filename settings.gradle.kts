pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://api.xposed.info/")
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "RosePods"
include(":app")
