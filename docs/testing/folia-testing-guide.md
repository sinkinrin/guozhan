# GuoZhan项目Folia测试指南

## 📋 概述

本文档详细说明如何为GuoZhan项目进行正确的Folia测试。Folia的多线程区域化架构需要特殊的测试方法和工具。

## 🔧 测试框架架构

### 双层测试策略

我们采用双层测试策略来应对Folia的特殊需求：

1. **单元测试层**（MockBukkit）
   - 用于纯逻辑代码测试
   - 不涉及Folia特性的功能
   - 快速执行，适合TDD开发

2. **集成测试层**（jpenilla/run-task）
   - 用于Folia特性测试
   - 真实多线程环境
   - 验证RegionScheduler和EntityScheduler

### 测试工具配置

#### build.gradle.kts配置
```kotlin
plugins {
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

dependencies {
    // 单元测试依赖
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:3.9.0")
    
    // 集成测试依赖
    testImplementation("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
}

// Folia测试环境配置
runPaper {
    folia.registerTask()
    runDirectory = file("run/folia-test")
    jvmArgs = listOf("-Xmx4G", "-XX:+UseG1GC")
}
```

## 🧪 测试类型和用例

### 1. 单元测试（MockBukkit）

**适用场景**：
- 数据模型验证
- 纯逻辑算法
- 工具类方法
- 配置解析

**示例**：
```kotlin
@Test
fun testCountryNameValidation() {
    assertTrue(isValidCountryName("ValidName"))
    assertFalse(isValidCountryName("Invalid@Name"))
}
```

**限制**：
- 不能测试Folia特性
- 不能测试多线程行为
- 不能测试异步API

### 2. 集成测试（Folia环境）

**适用场景**：
- RegionScheduler使用
- EntityScheduler使用
- 异步传送API
- 跨区域数据一致性
- 多线程安全性

**示例**：
```kotlin
@Test
fun testRegionSchedulerUsage() {
    val future = CompletableFuture<Boolean>()
    
    Bukkit.getRegionScheduler().execute(plugin, location) {
        // 区域特定的逻辑
        future.complete(true)
    }
    
    assertTrue(future.get(5, TimeUnit.SECONDS))
}
```

## 🚀 运行测试

### 单元测试
```bash
# 运行所有单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests "CountryManagerTest"

# 运行测试并生成报告
./gradlew test jacocoTestReport
```

### 集成测试
```bash
# 启动Folia测试环境
./gradlew runFolia

# 在另一个终端运行集成测试
./gradlew test --tests "*integration*"
```

## 📊 Folia特有测试要点

### 1. RegionScheduler测试
```kotlin
// ✅ 正确：使用RegionScheduler
Bukkit.getRegionScheduler().execute(plugin, location) {
    // 区域特定的逻辑
}

// ❌ 错误：在Folia中不可用
Bukkit.getScheduler().runTask(plugin, runnable)
```

### 2. EntityScheduler测试
```kotlin
// ✅ 正确：使用EntityScheduler
Bukkit.getEntityScheduler().execute(plugin, entity) {
    // 实体特定的逻辑
}
```

### 3. 异步API测试
```kotlin
// ✅ 正确：使用异步传送
player.teleportAsync(location).thenAccept { success ->
    // 处理传送结果
}

// ❌ 错误：同步传送在Folia中可能不安全
player.teleport(location)
```

### 4. 线程安全测试
```kotlin
@Test
fun testThreadSafety() {
    val futures = mutableListOf<CompletableFuture<Boolean>>()
    
    // 并发执行多个任务
    repeat(10) { index ->
        val future = CompletableFuture<Boolean>()
        futures.add(future)
        
        val location = Location(world, index * 100.0, 64.0, 0.0)
        Bukkit.getRegionScheduler().execute(plugin, location) {
            // 测试并发安全性
            future.complete(performThreadSafeOperation())
        }
    }
    
    // 验证所有任务成功
    val results = futures.map { it.get(10, TimeUnit.SECONDS) }
    assertTrue(results.all { it })
}
```

## 🔍 测试最佳实践

### 1. 测试环境隔离
- 每个测试使用独立的数据
- 测试后清理状态
- 使用内存数据库进行单元测试

### 2. 异步测试处理
```kotlin
// 使用CompletableFuture处理异步结果
val future = CompletableFuture<Boolean>()

Bukkit.getRegionScheduler().execute(plugin, location) {
    try {
        val result = performAsyncOperation()
        future.complete(result)
    } catch (e: Exception) {
        future.completeExceptionally(e)
    }
}

// 设置合理的超时时间
val result = future.get(5, TimeUnit.SECONDS)
```

### 3. 错误处理和日志
```kotlin
@Test
fun testWithProperErrorHandling() {
    assertDoesNotThrow {
        Bukkit.getRegionScheduler().execute(plugin, location) {
            try {
                performRiskyOperation()
            } catch (e: Exception) {
                logger.error("测试操作失败", e)
                throw e
            }
        }
    }
}
```

## 📁 测试文件组织

```
src/test/kotlin/
├── cn/lcofficial/guozhan/test/
│   ├── unit/                    # 单元测试
│   │   ├── CountryManagerTest.kt
│   │   ├── TerritoryManagerTest.kt
│   │   └── EconomyManagerTest.kt
│   ├── integration/             # 集成测试
│   │   ├── FoliaRandomSpawnTest.kt
│   │   ├── FoliaTerritoryTest.kt
│   │   └── FoliaDiplomacyTest.kt
│   └── util/                    # 测试工具类
│       ├── TestHelper.kt
│       └── FoliaTestUtils.kt

src/test/resources/
├── test-config.yml              # 单元测试配置
└── logback-test.xml            # 测试日志配置

testing/
├── folia-test-config.yml       # Folia集成测试配置
└── test-server.properties      # 测试服务器配置
```

## ⚠️ 注意事项和限制

### MockBukkit限制
- 不支持Folia的RegionScheduler
- 不支持EntityScheduler
- 不能模拟真实的多线程环境
- 异步API支持有限

### Folia集成测试注意事项
- 需要更多内存（建议4GB+）
- 启动时间较长
- 需要真实的服务器环境
- 测试执行时间较长

### 性能考虑
- 单元测试：快速执行（< 1秒）
- 集成测试：较慢执行（5-30秒）
- 合理分配测试资源
- 使用并行测试提高效率

## 🎯 测试覆盖率目标

- **单元测试覆盖率**：≥ 80%
- **集成测试覆盖率**：≥ 60%
- **关键路径覆盖率**：100%
- **Folia特性覆盖率**：100%

## 📈 持续集成

### GitHub Actions配置
```yaml
name: Folia Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
      - run: ./gradlew test

  integration-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
      - run: ./gradlew runFolia &
      - run: sleep 60  # 等待服务器启动
      - run: ./gradlew test --tests "*integration*"
```

---

## 📚 相关资源

- [jpenilla/run-task文档](https://github.com/jpenilla/run-task)
- [MockBukkit文档](https://github.com/MockBukkit/MockBukkit)
- [Folia API文档](https://docs.papermc.io/folia)
- [JUnit 5文档](https://junit.org/junit5/docs/current/user-guide/)
