# GuoZhan 国战插件 v1.3.18

## 📖 项目概述

GuoZhan（国战）是一个专为Minecraft Folia服务器设计的综合性国家建设与战争插件。玩家可以创建国家、管理领土、发展科技、进行外交和参与大规模战争，体验完整的国家治理和战略游戏。

### 🌟 核心特性

- **🏛️ 国家系统**: 创建和管理国家，设置君主、大臣等职位
- **🗺️ 领土管理**: 占领和管理领土，建设国家疆域
- **💰 经济系统**: 完整的税收、贡献和国库管理
- **🔬 科技树**: 8个科技分支，提升国家实力
- **🤝 外交系统**: 建立同盟、宣战、和平协议
- **⚔️ 战争机制**: 大规模国家间战争和领土争夺
- **🛡️ 护盾系统**: 保护国家免受攻击的防御机制
- **📊 15x15疆域地图**: 可视化领土显示

### 🎯 适用环境

- **服务器类型**: Folia 1.21.5+
- **数据库支持**: MySQL 8.0+ / SQLite 3.0+
- **Java版本**: Java 21+
- **推荐内存**: 4GB+

## 🚀 快速开始

### 1. 环境准备

确保您的服务器满足以下要求：
- Folia 1.21.5 或更高版本
- Java 21 或更高版本
- MySQL 8.0+ 或 SQLite 3.0+

### 2. 插件安装

1. 将 `GuoZhan-v1.3.18.jar` 放入服务器的 `plugins` 文件夹
2. 复制 `config` 文件夹中的配置文件到 `plugins/GuoZhan/` 目录
3. 根据需要修改 `config.yml` 中的数据库配置
4. 启动服务器

### 3. 数据库配置

#### MySQL配置（推荐）
```yaml
database:
  type: "mysql"
  host: "localhost"
  port: 3306
  database: "guozhan"
  username: "your_username"
  password: "your_password"
```

#### SQLite配置（简单部署）
```yaml
database:
  type: "sqlite"
```

### 4. 基础权限配置

为玩家分配基础权限：
```yaml
# 基础玩家权限
guozhan.command.create: true
guozhan.command.info: true
guozhan.command.list: true
guozhan.command.claim: true

# 管理员权限
guozhan.admin.*: true
```

## 🎮 基础使用

### 创建国家
```
/u create <国家名称>
```
需要64个铁锭作为建国成本。

### 查看国家信息
```
/u info <国家名称>
/u list [页码]
```

### 领土管理
```
/u claim          # 占领当前区块
/u unclaim        # 放弃当前区块
/u claimmode      # 切换占领模式（自动/手动）
/u map            # 获取15x15疆域地图
```

### 国家管理
```
/u invite <玩家>   # 邀请玩家加入
/u kick <玩家>     # 驱逐成员
/u promote <玩家>  # 提升为大臣
/u demote <玩家>   # 降级为国民
```

## 📁 文件结构

```
GuoZhan-Final-Delivery/
├── plugin/
│   └── GuoZhan-v1.3.18.jar     # 主插件文件
├── config/
│   ├── config.yml               # 主配置文件
│   ├── message.yml              # 消息配置
│   ├── technology.yml           # 科技配置
│   ├── diplomacy.yml            # 外交配置
│   └── plugin.yml               # 插件元数据
├── database/
│   └── init.sql                 # 数据库初始化脚本
├── docs/
│   ├── INSTALLATION.md          # 详细安装指南
│   ├── CONFIGURATION.md         # 配置说明
│   ├── USER_GUIDE.md            # 用户手册
│   ├── ADMIN_GUIDE.md           # 管理员手册
│   └── FAQ.md                   # 常见问题
└── examples/
    ├── production-config.yml    # 生产环境配置示例
    ├── test-config.yml          # 测试环境配置示例
    └── permissions.yml          # 权限配置示例
```

## � 依赖要求

### 必需依赖
| 依赖项 | 版本要求 | 说明 |
|--------|----------|------|
| **Folia** | 1.21.5+ | 服务器核心，必须使用Folia而非Paper/Spigot |
| **Java** | 21+ | 运行环境，推荐使用OpenJDK或Oracle JDK |

### 数据库依赖
| 数据库 | 版本要求 | 使用场景 |
|--------|----------|----------|
| **MySQL** | 8.0+ | 🏭 生产环境推荐，支持高并发 |
| **SQLite** | 3.0+ | 🧪 测试环境，内置支持无需安装 |

### 可选依赖
| 插件 | 版本要求 | 功能影响 | 推荐度 |
|------|----------|----------|--------|
| **PlaceholderAPI** | 2.11.6+ | 提供5个占位符变量 | ⭐⭐⭐ |
| **Squaremap** | 1.3.6+ | 网页地图显示领土 | ⭐⭐⭐ |
| **ProtocolLib** | 5.3.0+ | 协议增强（当前未使用） | ⭐ |

### 推荐依赖
| 插件 | 版本要求 | 功能 | 推荐度 |
|------|----------|------|--------|
| **LuckPerms** | 5.4+ | 权限管理系统 | ⭐⭐⭐⭐⭐ |

### 依赖安装说明
- **必需依赖**: 必须安装，否则插件无法运行
- **可选依赖**: 不安装不影响核心功能，但会失去相应特性
- **推荐依赖**: 虽然可选，但强烈建议安装以获得最佳体验

## �🔧 技术支持

### 系统要求
- **最低配置**: 2GB RAM, 2核CPU
- **推荐配置**: 4GB RAM, 4核CPU
- **数据库**: MySQL 8.0+ (推荐) 或 SQLite 3.0+

### 性能优化建议
1. 使用MySQL而非SQLite以获得更好的性能
2. 配置适当的数据库连接池大小
3. 定期清理过期的缓存数据
4. 监控服务器内存使用情况

### 故障排除
如遇到问题，请查看：
1. `docs/FAQ.md` - 常见问题解答
2. `docs/ADMIN_GUIDE.md` - 管理员故障排除指南
3. 服务器日志文件

## 📞 联系方式

- **技术支持**: 请查看 `docs/FAQ.md` 获取常见问题解答
- **文档**: 完整文档位于 `docs/` 目录
- **配置帮助**: 参考 `examples/` 目录中的配置示例

## 📄 许可证

本插件为商业软件，使用前请确保已获得合法授权。

---

**版本**: v1.3.18  
**发布日期**: 2025-10-09  
**兼容性**: Folia 1.21.5+
