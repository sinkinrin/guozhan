pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://nexus.nextcraft.cn/repository/maven-public")
        maven("https://repo.papermc.io/repository/maven-public")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        maven("https://repo.jpenilla.xyz/snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "Guozhan"