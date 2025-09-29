plugins {
    id("java")
    alias(libs.plugins.kotlin)
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "2.3.1"
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
    maven("https://jitpack.io") // JitPack for MockBukkit
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

    // 测试依赖
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    // MockBukkit暂时无法使用，专注于纯逻辑测试
    // testImplementation("com.github.MockBukkit:MockBukkit:v1.21-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    testImplementation(kotlin("test"))
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.dao)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.fastjson)
    testImplementation(libs.mysql)
    testImplementation(libs.hikari)
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

// 测试配置
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Folia测试环境配置
runPaper {
    folia.registerTask()
}