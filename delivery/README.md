# GuoZhan 国战插件 - 交付包

## 📦 包含内容

```
delivery/
├── plugin/
│   └── Guozhan-1.0-SNAPSHOT.jar     # 插件主文件
├── config/
│   ├── config-test.yml              # 测试环境配置
│   └── config-production.yml        # 正式环境配置
├── docs/
│   ├── 配置差异说明.md               # 配置版本差异详解
│   ├── ADMIN_GUIDE.md               # 管理员指南
│   └── critical-high-issues-fix-v1.3.43.md  # 最新修复报告
└── README.md                        # 本文件
```

---

## 🚀 快速安装

### 1. 环境要求

**必需依赖**：
- **Folia 1.21.5** 或更高版本（不支持 Paper/Spigot）
- **Java 21** 或更高版本
- **PlaceholderAPI** 插件
- **squaremap** 插件（地图显示功能）

**可选依赖**：
- **ProtocolLib** 插件（增强功能支持）

### 2. 安装步骤

1. **停止服务器**
   ```bash
   # 在服务器控制台执行
   stop
   ```

2. **安装插件**
   ```bash
   # 复制插件文件到服务器
   cp plugin/Guozhan-1.0-SNAPSHOT.jar /path/to/server/plugins/
   ```

3. **选择配置文件**
   
   **测试环境**（推荐用于开发/测试）：
   ```bash
   cp config/config-test.yml /path/to/server/plugins/Guozhan/config.yml
   ```
   
   **正式环境**（推荐用于生产服务器）：
   ```bash
   cp config/config-production.yml /path/to/server/plugins/Guozhan/config.yml
   ```

4. **启动服务器**
   ```bash
   # 启动 Folia 服务器
   java -Xmx4G -Xms2G -XX:+UseG1GC -jar folia-1.21.5-12.jar --nogui
   ```

---

## ⚙️ 配置说明

### 配置文件选择

| 环境类型 | 配置文件 | 特点 | 适用场景 |
|----------|----------|------|----------|
| **测试环境** | `config-test.yml` | • 自动发放资源<br>• 降低各种成本<br>• 缩短等待时间 | • 功能测试<br>• 开发调试<br>• 演示展示 |
| **正式环境** | `config-production.yml` | • 平衡的游戏经济<br>• 完整的保护机制<br>• 策略性体验 | • 生产服务器<br>• 正式运营<br>• 长期游戏 |

### 关键配置差异

**⚠️ 重要**：两个配置版本的主要差异在于：

1. **测试环境功能**：
   - 测试版：`test-environment.enabled: true`（自动发放资源）
   - 正式版：`test-environment.enabled: false`（关闭测试功能）

2. **游戏平衡**：
   - 测试版：低成本、快速进度、便于测试
   - 正式版：平衡成本、正常进度、策略游戏

详细差异请参考：`docs/配置差异说明.md`

---

## 🗄️ 数据库配置

### SQLite（推荐，默认）

插件默认使用 SQLite 数据库，无需额外配置：

```yaml
database:
  type: SQLITE
```

数据库文件将自动创建在：`plugins/Guozhan/guozhan.db`

### MySQL（可选）

如需使用 MySQL，请修改配置：

```yaml
database:
  type: MYSQL
  host: 127.0.0.1
  port: 3306
  username: your_username
  password: your_password
  database: your_database
```

**MySQL 要求**：
- MySQL 5.7+ 或 MariaDB 10.2+
- 支持 UTF-8 编码
- 用户需要 CREATE、SELECT、INSERT、UPDATE、DELETE 权限

---

## 🎮 基本使用

### 管理员命令

```bash
# 查看插件状态
/guozhan status

# 重载配置文件
/guozhan reload

# 查看国家信息
/guozhan info [国家名]

# 强制保存数据
/guozhan save

# 查看帮助
/guozhan help
```

### 玩家命令

```bash
# 创建国家
/guozhan create <国家名>

# 加入国家
/guozhan join <国家名>

# 查看国家信息
/guozhan info

# 占领领土
/guozhan claim

# 查看科技
/guozhan tech

# 查看职业
/guozhan profession
```

---

## 🔧 故障排除

### 常见问题

1. **插件无法加载**
   - 检查是否使用 Folia（不支持 Paper/Spigot）
   - 确认 Java 版本为 21+
   - 检查依赖插件是否安装

2. **数据库连接失败**
   - SQLite：检查文件权限
   - MySQL：验证连接信息和用户权限

3. **配置文件错误**
   - 检查 YAML 语法
   - 确认缩进使用空格而非制表符
   - 参考提供的配置文件模板

4. **测试环境功能在正式服务器启用**
   - 立即设置 `test-environment.enabled: false`
   - 重启服务器
   - 检查玩家资源是否异常

### 日志查看

插件日志位于：`logs/latest.log`

关键日志标识：
- `[国战]` - 插件主要日志
- `[国战] [测试环境]` - 测试环境相关
- `[国战] [数据库]` - 数据库操作
- `[国战] [错误]` - 错误信息

---

## 📞 技术支持

### 文档资源

- **管理员指南**：`docs/ADMIN_GUIDE.md`
- **配置差异说明**：`docs/配置差异说明.md`
- **最新修复报告**：`docs/critical-high-issues-fix-v1.3.43.md`

### 版本信息

- **插件版本**：v1.3.43
- **支持的 Minecraft 版本**：1.21.5
- **支持的服务端**：Folia 1.21.5+
- **编译日期**：2025-01-18

### 更新记录

**v1.3.43 主要修复**：
- ✅ 修复玩家上贡功能总是返回失败
- ✅ 修复税收系统线程安全问题
- ✅ 修复护盾、职业、科技系统的线程安全问题
- ✅ 优化忠诚度系统性能
- ✅ 减少税收系统冗余计算

详细修复内容请查看：`docs/critical-high-issues-fix-v1.3.43.md`

---

## ⚠️ 重要提醒

1. **生产环境部署前**，请务必：
   - 使用 `config-production.yml` 配置
   - 确认 `test-environment.enabled: false`
   - 备份现有数据
   - 在测试环境验证功能

2. **定期备份**：
   - 数据库文件：`plugins/Guozhan/guozhan.db`
   - 配置文件：`plugins/Guozhan/config.yml`
   - 世界文件（如有重要建筑）

3. **性能监控**：
   - 监控服务器 TPS
   - 观察内存使用情况
   - 检查数据库文件大小

---

**祝您使用愉快！** 🎉
