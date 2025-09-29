# 为什么GuoZhan项目不使用Docker进行Folia测试

## 📋 背景说明

在GuoZhan项目的早期开发阶段，我们曾经考虑使用Docker来搭建Folia测试环境。然而，经过深入研究和实践，我们发现Docker并不是Folia插件测试的最佳选择。

## ❌ Docker测试的问题

### 1. 不是标准的Folia测试方法
- **官方推荐**：PaperMC官方文档推荐使用`jpenilla/run-task`插件
- **社区实践**：Folia插件开发社区普遍使用run-task进行测试
- **工具支持**：专门的Folia测试工具更加成熟和可靠

### 2. Folia多线程特性的复杂性
- **容器限制**：Docker容器的资源限制可能影响Folia的多线程性能
- **网络隔离**：容器网络可能干扰Folia的区域间通信测试
- **调试困难**：在容器中调试多线程问题更加复杂

### 3. 开发体验问题
- **启动时间**：Docker容器启动比直接运行Gradle任务慢
- **资源消耗**：需要额外的Docker守护进程和容器开销
- **平台兼容性**：在某些开发环境中Docker可能不可用

### 4. 测试准确性
- **环境差异**：容器环境与实际部署环境可能存在差异
- **性能测试**：容器的性能特性可能影响Folia性能测试的准确性
- **并发测试**：容器的资源限制可能影响多线程并发测试

## ✅ 推荐的Folia测试方法

### 1. jpenilla/run-task插件
```gradle
plugins {
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

runPaper {
    folia.registerTask()
    runDirectory = file("run/folia-test")
}
```

**优势**：
- 官方推荐的标准方法
- 自动下载和配置Folia服务器
- 完美支持Folia的多线程特性
- 与IDE集成良好，便于调试

### 2. MockBukkit单元测试
```kotlin
dependencies {
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:3.9.0")
}
```

**适用场景**：
- 纯逻辑代码测试
- 不涉及Folia特性的功能
- 快速单元测试

### 3. 双层测试策略
- **单元测试层**：使用MockBukkit测试纯逻辑
- **集成测试层**：使用run-task测试Folia特性

## 🔄 迁移说明

### 从Docker到run-task的迁移

#### 之前的Docker方式：
```yaml
# docker-compose.test.yml (已移除)
services:
  minecraft:
    image: "itzg/minecraft-server"
    environment:
      TYPE: "FOLIA"
```

#### 现在的run-task方式：
```bash
# 启动Folia测试服务器
./gradlew runFolia

# 运行测试
./gradlew test
```

### 配置文件迁移
- **之前**：`docker-compose.test.yml` + `test-config/config.yml`
- **现在**：`testing/folia-test-config.yml` + `src/test/resources/test-config.yml`

### 测试脚本更新
- **之前**：Docker Compose命令
- **现在**：`testing/run-tests.sh` 和 `testing/run-tests.bat`

## 📊 性能对比

| 方面 | Docker方式 | run-task方式 |
|------|------------|--------------|
| 启动时间 | 2-5分钟 | 30-60秒 |
| 内存使用 | 高（容器+JVM） | 中等（仅JVM） |
| 调试便利性 | 困难 | 简单 |
| 平台兼容性 | 需要Docker | 仅需要Java |
| 测试准确性 | 中等 | 高 |
| 社区支持 | 有限 | 广泛 |

## 🎯 最佳实践建议

### 1. 开发阶段
- 使用`./gradlew runFolia`进行快速测试
- 使用IDE调试功能调试插件逻辑
- 使用MockBukkit进行单元测试

### 2. CI/CD阶段
```yaml
# GitHub Actions示例
- name: Run Folia Tests
  run: |
    ./gradlew test
    ./gradlew runFolia &
    sleep 60
    ./gradlew test --tests "*integration*"
```

### 3. 生产部署
- 在类似生产环境的服务器上进行最终测试
- 使用真实的数据库和配置
- 进行负载测试和性能测试

## 🔮 未来考虑

虽然我们目前不推荐使用Docker进行Folia测试，但在以下情况下可能会重新考虑：

1. **官方支持**：如果PaperMC官方提供了专门的Folia Docker镜像
2. **工具改进**：如果出现了专门针对Folia的Docker测试工具
3. **特殊需求**：如果项目有特殊的隔离或部署需求

## 📚 相关资源

- [jpenilla/run-task GitHub](https://github.com/jpenilla/run-task)
- [PaperMC官方文档](https://docs.papermc.io/)
- [MockBukkit文档](https://github.com/MockBukkit/MockBukkit)
- [Folia API文档](https://docs.papermc.io/folia)

---

## 📝 总结

选择正确的测试方法对于Folia插件开发至关重要。通过使用官方推荐的`jpenilla/run-task`插件和MockBukkit的组合，我们可以：

- 获得更准确的测试结果
- 享受更好的开发体验
- 遵循社区最佳实践
- 确保与Folia多线程架构的完美兼容

这种方法不仅提高了测试的可靠性，也为项目的长期维护和发展奠定了坚实的基础。
