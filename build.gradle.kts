plugins {
    id("java")
    alias(libs.plugins.kotlin)
    id("com.gradleup.shadow") version "8.3.6"
}

group = "cn.lcofficial"
version = "1.0-SNAPSHOT"

repositories {
    maven("https://nexus.cyanbukkit.cn/repository/maven-public/")
    maven("https://maven.aliyun.com/repository/public")
    maven("https://nexus.nextcraft.cn/repository/maven-public")
    maven("https://repo.papermc.io/repository/maven-public")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.jpenilla.xyz/snapshots/")
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("dev.folia:folia-api:1.21.5-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")
    compileOnly("xyz.jpenilla:squaremap-api:1.2.7")
    compileOnly(libs.exposed.core)
    compileOnly(libs.exposed.dao)
    compileOnly(libs.exposed.jdbc)
    compileOnly(libs.exposed.migrations)
    compileOnly(libs.fastjson)
    compileOnly(libs.mysql)
    compileOnly(libs.hikari)
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("kotlin", "cn.lcofficial.guozhan.libs.kotlin")
    relocate("org.jetbrains.exposed", "cn.lcofficial.guozhan.libs.exposed")
    relocate("com.alibaba.fastjson2", "cn.lcofficial.guozhan.libs.fastjson")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.compileJava {
    options.encoding = "UTF-8"
}
tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand(project.properties)
        expand(inputs.properties)
    }
}