# GuoZhan 项目参考文档集合

本文件夹包含GuoZhan（国战）插件开发所需的所有参考文档，包括原始需求、开发文档、技术参考和相关API文档。

## 📁 文档结构

### 核心项目文档
- `original-requirements.md` - 原始需求文档
- `development-docs.md` - 开发文档和架构说明
- `roadmap.md` - 项目路线图
- `changelog.md` - 版本变更记录

### 技术参考文档
- `folia-api-reference.md` - Folia API参考文档
- `exposed-orm-reference.md` - Exposed ORM使用指南
- `kotlin-minecraft-reference.md` - Kotlin Minecraft插件开发参考
- `bukkit-api-reference.md` - Bukkit/Spigot API参考

### 集成参考文档
- `placeholderapi-reference.md` - PlaceholderAPI集成参考
- `squaremap-api-reference.md` - Squaremap API集成参考
- `protocollib-reference.md` - ProtocolLib使用参考

## 🎯 项目概述

GuoZhan是一个综合性的Minecraft国家建设插件，具有以下核心特性：

### 技术栈
- **运行环境**: Java 21, Folia API 1.21.5
- **开发语言**: Kotlin 2.2.x
- **数据库**: Exposed ORM + HikariCP + MySQL/SQLite
- **构建工具**: Gradle + Shadow插件

### 核心功能模块
1. **领土管理系统** - 区块占领、边界管理、忠诚度系统
2. **外交系统** - 国家关系、联盟、战争声明
3. **经济系统** - 税收、贡献、区域经济
4. **战争系统** - 国战调度、伤害计算、胜负判定
5. **随机出生系统** - 新玩家安全出生点分配
6. **核心系统** - 王城核心血量、BossBar显示

### 当前开发状态
- **版本**: v1.1.0-DEV
- **完成度**: 核心框架已完成，部分功能需要完善
- **重点**: 随机出生系统、BossBar增强、地图系统

## 📋 开发重点

### 已实现功能
- ✅ Folia兼容性
- ✅ 基础国家创建和管理
- ✅ 领土占领系统
- ✅ 外交关系管理
- ✅ 经济和税收基础
- ✅ 随机出生系统（v1.1.0新增）

### 待完善功能
- 🔄 疆域小地图系统（15x15实时地图）
- 🔄 圈地模式（手动/自动）
- 🔄 完整的战争流程
- 🔄 Squaremap集成优化
- 🔄 科技系统
- 🔄 完整的权限模型

### 技术债务
- 🐛 PlayerListener中的方法调用问题
- 🐛 SpawnManager的isInTerritory方法需要实现
- 🐛 Squaremap API接口不一致问题
- 🐛 忠诚度系统需要统一

## 🔧 开发指南

### 代码规范
- 使用Kotlin编写所有新代码
- 遵循异步处理原则（Folia要求）
- 使用Exposed进行数据库操作
- 保持模块化设计

### 测试要求
- 单元测试覆盖核心逻辑
- 集成测试验证功能完整性
- 性能测试确保大规模可用性

### 文档要求
- 所有新功能必须有对应文档
- API变更需要更新接口文档
- 重大变更需要更新CHANGELOG

## 📚 参考资源

本文件夹中的参考文档将帮助开发者：
1. 理解项目架构和设计决策
2. 快速上手相关技术栈
3. 解决常见开发问题
4. 保持代码质量和一致性

---

*最后更新: 2024-12-28*
*维护者: GuoZhan开发团队*
