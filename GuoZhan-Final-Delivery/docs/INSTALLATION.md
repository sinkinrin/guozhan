# GuoZhan 插件安装指南

## 📋 安装前准备

### 系统要求

| 组件 | 最低要求 | 推荐配置 |
|------|----------|----------|
| 服务器核心 | Folia 1.21.5 | Folia 1.21.5+ |
| Java版本 | Java 21 | Java 21+ |
| 内存 | 2GB RAM | 4GB+ RAM |
| CPU | 2核心 | 4核心+ |
| 存储空间 | 1GB | 2GB+ |
| 数据库 | SQLite 3.0+ | MySQL 8.0+ |

### 依赖检查

1. **Folia服务器**: 确保使用Folia而非Paper/Spigot
2. **Java版本**: 运行 `java -version` 确认版本
3. **数据库**: 如使用MySQL，确保服务正常运行

## 🔧 安装步骤

### 第一步：下载Folia服务器

如果您还没有Folia服务器：

1. 访问 [PaperMC官网](https://papermc.io/downloads/folia)
2. 下载Folia 1.21.5或更高版本
3. 创建服务器目录并放置jar文件

### 第二步：安装GuoZhan插件

1. **复制插件文件**
   ```bash
   # 将插件文件复制到plugins目录
   cp GuoZhan-v1.3.18.jar /path/to/server/plugins/
   ```

2. **首次启动**
   - 启动服务器一次以生成插件目录
   - 服务器会在 `plugins/GuoZhan/` 创建默认配置

3. **停止服务器**
   - 使用 `stop` 命令停止服务器
   - 准备配置文件

### 第三步：配置数据库

#### 选项A：MySQL配置（推荐生产环境）

1. **创建数据库**
   ```sql
   CREATE DATABASE guozhan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'guozhan'@'localhost' IDENTIFIED BY 'your_secure_password';
   GRANT ALL PRIVILEGES ON guozhan.* TO 'guozhan'@'localhost';
   FLUSH PRIVILEGES;
   ```

2. **导入初始化脚本**
   ```bash
   mysql -u guozhan -p guozhan < database/init.sql
   ```

3. **配置连接信息**
   编辑 `plugins/GuoZhan/config.yml`:
   ```yaml
   database:
     type: "mysql"
     host: "localhost"
     port: 3306
     database: "guozhan"
     username: "guozhan"
     password: "your_secure_password"
     pool-size: 10
   ```

#### 选项B：SQLite配置（适合测试环境）

编辑 `plugins/GuoZhan/config.yml`:
```yaml
database:
  type: "sqlite"
```

### 第四步：配置插件设置

1. **复制配置文件**
   ```bash
   cp config/*.yml /path/to/server/plugins/GuoZhan/
   ```

2. **基础配置调整**
   
   编辑 `config.yml` 中的关键设置：
   ```yaml
   # 世界设置
   world:
     name: "world"              # 主世界名称
     spawn-radius: 7500         # 出生点保护半径
   
   # 国家设置
   country:
     max-name-length: 12        # 国家名称最大长度
     creation:
       item_cost:
         material: "IRON_INGOT" # 建国所需物品
         amount: 64             # 建国所需数量
   
   # 随机出生系统
   random-spawn:
     enabled: true              # 是否启用随机出生
     spawn-radius: 5000         # 随机出生半径
     max-attempts: 50           # 最大尝试次数
   ```

### 第五步：权限配置

#### 使用LuckPerms（推荐）

1. **安装LuckPerms插件**
2. **创建权限组**
   ```bash
   # 基础玩家权限
   /lp creategroup player
   /lp group player permission set guozhan.command.create true
   /lp group player permission set guozhan.command.info true
   /lp group player permission set guozhan.command.list true
   /lp group player permission set guozhan.command.claim true
   /lp group player permission set guozhan.command.contribute true
   
   # 管理员权限
   /lp creategroup admin
   /lp group admin permission set guozhan.admin.* true
   ```

#### 使用原生权限系统

编辑 `permissions.yml`:
```yaml
default:
  default: true
  permissions:
    guozhan.command.create: true
    guozhan.command.info: true
    guozhan.command.list: true
    guozhan.command.claim: true

admin:
  default: false
  permissions:
    guozhan.admin.*: true
```

### 第六步：启动和验证

1. **启动服务器**
   ```bash
   java -Xmx4G -Xms2G -jar folia-1.21.5.jar nogui
   ```

2. **验证安装**
   - 检查控制台是否有错误信息
   - 运行 `/plugins` 确认GuoZhan已加载
   - 测试基础命令 `/u help`

## 🔍 安装验证清单

- [ ] Folia服务器正常启动
- [ ] GuoZhan插件成功加载（绿色状态）
- [ ] 数据库连接成功
- [ ] 配置文件无错误
- [ ] 权限系统正常工作
- [ ] 基础命令可以执行
- [ ] 玩家可以创建国家

## ⚠️ 常见安装问题

### 问题1：插件无法加载
**症状**: 插件显示红色或无法启动
**解决方案**:
1. 检查Java版本是否为21+
2. 确认使用的是Folia而非Paper/Spigot
3. 查看控制台错误信息

### 问题2：数据库连接失败
**症状**: 出现数据库连接错误
**解决方案**:
1. 检查数据库服务是否运行
2. 验证用户名和密码
3. 确认数据库权限设置

### 问题3：权限不工作
**症状**: 玩家无法执行命令
**解决方案**:
1. 检查权限插件是否正确安装
2. 验证权限节点拼写
3. 重新加载权限配置

### 问题4：配置文件错误
**症状**: 插件启动时报配置错误
**解决方案**:
1. 检查YAML语法是否正确
2. 验证缩进是否使用空格
3. 使用YAML验证工具检查

## 🚀 性能优化建议

### JVM参数优化
```bash
java -Xmx4G -Xms4G \
     -XX:+UseG1GC \
     -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+DisableExplicitGC \
     -XX:+AlwaysPreTouch \
     -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 \
     -XX:G1HeapRegionSize=8M \
     -XX:G1ReservePercent=20 \
     -XX:G1HeapWastePercent=5 \
     -XX:G1MixedGCCountTarget=4 \
     -XX:InitiatingHeapOccupancyPercent=15 \
     -XX:G1MixedGCLiveThresholdPercent=90 \
     -XX:G1RSetUpdatingPauseTimePercent=5 \
     -XX:SurvivorRatio=32 \
     -XX:+PerfDisableSharedMem \
     -XX:MaxTenuringThreshold=1 \
     -jar folia-1.21.5.jar nogui
```

### 数据库优化
```sql
-- MySQL配置优化
SET GLOBAL innodb_buffer_pool_size = 1073741824;  -- 1GB
SET GLOBAL max_connections = 200;
SET GLOBAL query_cache_size = 67108864;           -- 64MB
```

## 📞 获取帮助

如果安装过程中遇到问题：

1. **查看日志**: 检查 `logs/latest.log` 文件
2. **参考FAQ**: 查看 `docs/FAQ.md`
3. **管理员指南**: 参考 `docs/ADMIN_GUIDE.md`
4. **配置示例**: 查看 `examples/` 目录

---

**安装完成后，请参考 `USER_GUIDE.md` 了解如何使用插件功能。**
