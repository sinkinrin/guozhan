# GuoZhan项目部署指南

## 📋 项目概述

GuoZhan是一个基于Folia的Minecraft国战插件，支持多线程区域化处理。本文档详细说明如何进行项目部署。

## ⚠️ 重要说明

**测试相关内容已迁移**：
- Folia测试方法请参考：`docs/testing/folia-testing-guide.md`
- 测试运行脚本位于：`testing/run-tests.sh` 和 `testing/run-tests.bat`
- 关于为什么不使用Docker测试：`docs/testing/why-not-docker.md`

## 🏗️ 构建配置分析

### Gradle构建配置
```kotlin
// build.gradle.kts 关键配置
plugins {
    id("java")
    alias(libs.plugins.kotlin)
    id("com.gradleup.shadow") version "8.3.6"  // 用于打包依赖
}

group = "cn.lcofficial"
version = "1.0-SNAPSHOT"

// Folia API依赖
dependencies {
    compileOnly("dev.folia:folia-api:1.21.5-R0.1-SNAPSHOT")
    // 其他依赖...
}

// Shadow插件配置 - 重定位依赖避免冲突
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("kotlin", "cn.lcofficial.guozhan.libs.kotlin")
    relocate("org.jetbrains.exposed", "cn.lcofficial.guozhan.libs.exposed")
    relocate("com.alibaba.fastjson2", "cn.lcofficial.guozhan.libs.fastjson")
}
```

### 插件配置
```yaml
# plugin.yml 关键配置
name: 'Guozhan'
main: 'cn.lcofficial.guozhan.Guozhan'
version: ${version}
folia-supported: true  # 声明Folia兼容性
api-version: 1.21

# 运行时库依赖
libraries:
  - "org.jetbrains.kotlin:kotlin-stdlib:2.2.0"
  - "org.jetbrains.exposed:exposed-core:0.61.0"
  - "org.jetbrains.exposed:exposed-dao:0.61.0"
  - "org.jetbrains.exposed:exposed-jdbc:0.61.0"
  - "com.alibaba.fastjson2:fastjson2:2.0.58"
  - "com.mysql:mysql-connector-j:9.4.0"
  - "com.zaxxer:HikariCP:5.0.1"
```

## 🔨 构建步骤

### 1. 编译检查
```bash
# 检查编译状态
./gradlew compileKotlin

# 清理并重新编译
./gradlew clean compileKotlin
```

### 2. 生成插件JAR
```bash
# 生成带依赖的JAR文件
./gradlew shadowJar

# 输出位置: build/libs/Guozhan-1.0-SNAPSHOT.jar
```

### 3. 验证构建产物
```bash
# 检查JAR文件
ls -la build/libs/

# 验证JAR内容
jar tf build/libs/Guozhan-1.0-SNAPSHOT.jar | head -20
```

## 🚀 部署环境准备

### Folia服务器要求
- **Java版本**: Java 21+
- **服务器软件**: Folia 1.21.5+
- **内存要求**: 最少4GB RAM
- **数据库**: MySQL 8.0+

### 服务器配置
```yaml
# folia.yml 推荐配置
global-config:
  threaded-regions:
    threads: 6  # 建议为CPU核心数的80%
```

## 🧪 测试环境搭建

### 方法1: Docker测试环境
```yaml
# docker-compose.yml
version: "3.8"
services:
  minecraft:
    image: "itzg/minecraft-server"
    container_name: guozhan-test
    ports:
      - "25565:25565"
    environment:
      EULA: "TRUE"
      TYPE: "FOLIA"
      VERSION: "1.21.5"
      MEMORY: "4G"
      MOTD: "GuoZhan Test Server"
      ENABLE_RCON: "TRUE"
      RCON_PASSWORD: "test123"
    volumes:
      - ./minecraft-data:/data
      - ./build/libs/Guozhan-1.0-SNAPSHOT.jar:/data/plugins/Guozhan.jar
    networks:
      - minecraft-net
    depends_on:
      - db

  db:
    image: mariadb:10
    container_name: guozhan-db
    environment:
      MYSQL_ROOT_PASSWORD: "rootpass"
      MYSQL_DATABASE: "guozhan"
      MYSQL_USER: "guozhan"
      MYSQL_PASSWORD: "guozhan123"
    volumes:
      - db_data:/var/lib/mysql
    networks:
      - minecraft-net

networks:
  minecraft-net:
    driver: bridge

volumes:
  db_data:
    driver: local
```

### 方法2: 本地测试环境
```bash
# 1. 下载Folia服务器
wget https://api.papermc.io/v2/projects/folia/versions/1.21.5/builds/latest/downloads/folia-1.21.5-latest.jar

# 2. 创建服务器目录
mkdir guozhan-test-server
cd guozhan-test-server

# 3. 复制插件
cp ../build/libs/Guozhan-1.0-SNAPSHOT.jar plugins/

# 4. 启动服务器
java -Xmx4G -jar folia-1.21.5-latest.jar --nogui
```

## 📝 配置文件准备

### 数据库配置
```yaml
# plugins/Guozhan/config.yml
database:
  host: "localhost"
  port: 3306
  database: "guozhan"
  username: "guozhan"
  password: "guozhan123"
  
world:
  default-world: "world"
  
random-spawn:
  enabled: true
  spawn-radius: 5000
  max-attempts: 50
```

## 🔍 测试检查清单

### 启动测试
- [ ] 服务器正常启动
- [ ] 插件成功加载
- [ ] 数据库连接成功
- [ ] 无错误日志

### 功能测试
- [ ] 玩家注册系统
- [ ] 国家创建功能
- [ ] 领土声明功能
- [ ] 外交系统
- [ ] 经济系统
- [ ] 战争系统
- [ ] 随机出生系统

### 性能测试
- [ ] 多玩家并发测试
- [ ] 内存使用监控
- [ ] CPU使用率检查
- [ ] 数据库性能

## 🐛 常见问题排查

### 插件加载失败
```bash
# 检查日志
tail -f logs/latest.log | grep -i guozhan

# 常见问题:
# 1. Folia兼容性 - 确保plugin.yml中有folia-supported: true
# 2. 依赖缺失 - 检查libraries配置
# 3. Java版本 - 确保使用Java 21+
```

### 数据库连接问题
```sql
-- 检查数据库权限
SHOW GRANTS FOR 'guozhan'@'%';

-- 创建数据库和用户
CREATE DATABASE guozhan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'guozhan'@'%' IDENTIFIED BY 'guozhan123';
GRANT ALL PRIVILEGES ON guozhan.* TO 'guozhan'@'%';
FLUSH PRIVILEGES;
```

## 📊 监控和日志

### 关键日志位置
- **服务器日志**: `logs/latest.log`
- **插件日志**: 搜索 `[Guozhan]` 标签
- **错误日志**: 搜索 `ERROR` 和 `WARN`

### 性能监控
```bash
# 使用Spark插件进行性能分析
/spark profiler start
# 运行测试...
/spark profiler stop
```

## 🚀 生产部署建议

### 服务器规格
- **CPU**: 8核心以上
- **内存**: 8GB以上
- **存储**: SSD推荐
- **网络**: 稳定的网络连接

### 安全配置
- 定期备份数据库
- 配置防火墙规则
- 使用强密码
- 定期更新服务器软件

### 监控设置
- 设置服务器监控
- 配置日志轮转
- 监控内存和CPU使用
- 设置告警机制

## 📁 项目文件结构

### 构建产物
```
build/
├── libs/
│   └── Guozhan-1.0-SNAPSHOT.jar  # 主插件文件 (347KB)
├── classes/
├── resources/
└── tmp/
```

### 测试环境文件
```
GuoZhan/
├── scripts/
│   ├── quick-test.bat           # Windows快速测试脚本
│   └── quick-test.sh            # Linux/Mac快速测试脚本
├── test-config/
│   └── config.yml               # 测试环境配置
├── sql/
│   └── init.sql                 # 数据库初始化脚本
├── docker-compose.test.yml      # Docker测试环境
└── docs/
    └── deployment-testing-guide.md  # 本文档
```

## 🚀 快速开始

### 1. 构建插件
```bash
# Windows
scripts\quick-test.bat

# Linux/Mac
chmod +x scripts/quick-test.sh
./scripts/quick-test.sh
```

### 2. Docker测试环境
```bash
# 启动完整测试环境
docker-compose -f docker-compose.test.yml up -d

# 查看日志
docker-compose -f docker-compose.test.yml logs -f minecraft

# 停止环境
docker-compose -f docker-compose.test.yml down -v
```

### 3. 访问测试环境
- **Minecraft服务器**: `localhost:25565`
- **数据库管理**: `http://localhost:8080` (phpMyAdmin)
- **RCON**: `localhost:25575` (密码: test123)

## ✅ 验证清单

### 构建验证
- [ ] `./gradlew compileKotlin` 成功
- [ ] `./gradlew shadowJar` 生成JAR文件
- [ ] JAR文件大小约347KB
- [ ] JAR包含plugin.yml和主类文件

### 部署验证
- [ ] 插件成功加载到Folia服务器
- [ ] 数据库连接正常
- [ ] 配置文件正确读取
- [ ] 命令系统响应正常

### 功能验证
- [ ] 玩家注册和登录
- [ ] 国家创建和管理
- [ ] 领土声明功能
- [ ] 外交关系系统
- [ ] 经济和贡献系统
- [ ] 随机出生功能
