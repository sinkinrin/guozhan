# GuoZhan (国战) Plugin Roadmap

This document outlines the development roadmap for the GuoZhan plugin, including planned features, improvements, and technical enhancements.

## 🎯 Vision

GuoZhan aims to be the most comprehensive and immersive nation-building plugin for Minecraft, providing players with deep strategic gameplay, complex diplomatic systems, and engaging territorial warfare mechanics.

## 📋 Development Principles

- **Documentation-Driven Development**: All features are documented before implementation
- **Modular Architecture**: Each feature module can be developed and tested independently  
- **Performance First**: Optimize for large-scale multiplayer environments
- **Community Feedback**: Regular community input drives feature prioritization
- **Backward Compatibility**: Maintain compatibility with existing saves and configurations

## 🚀 Release Schedule

### Short-term Goals (Q1 2025)

#### ✅ v1.2.0 - Territory Map System (COMPLETED)
**Release Date**: 2025-10-05
**Status**: ✅ 完成并发布
**Focus**: 疆域可视化和地图系统

##### Completed Features
- ✅ **15x15疆域小地图系统** `[COMPLETED]`
  - 实时显示周围15x15区块的领土状况
  - 混合渲染技术：BufferedImage基础层 + setPixel动态层
  - 颜色编码：灰色(未占领)、绿色(我方)、红色(敌方)、蓝色(友好)、黄色(中立)
  - 玩家位置实时标记：白色闪烁十字标记
  - 忠诚度可视化：颜色深浅表示忠诚度高低
  - 接壤面数显示：边框样式表示接壤情况
  - **技术实现**: TerritoryMapUtil工具类 + TerritoryMapRenderer
  - **实际工作量**: 8天
  - **测试覆盖**: 31个测试100%通过

- ✅ **随机出生系统** `[COMPLETED in v1.1.0]`
  - 新玩家自动传送到15000格范围内无国家区域
  - 避免海洋出生，确保陆地安全出生点
  - 出生点缓存机制，提升性能
  - **技术实现**: RandomSpawnManager + RandomSpawnListener
  - **实际工作量**: 5天
  - **状态**: 功能完整，存在少量技术债务

#### 🔥 v1.3.0 - Advanced Territory Management
**Target Release**: November 2025
**Focus**: 领土管理和占领机制完善

##### New Features
- **BossBar核心血量显示增强** `[HIGH PRIORITY]`
  - 攻击核心时显示血量进度条
  - 双方国家成员实时查看
  - 自定义样式和颜色
  - **技术方案**: BossBar API + 事件监听
  - **工作量**: 2-3天
  - **依赖**: 核心攻击系统

- **占领模式系统** `[HIGH PRIORITY]`
  - 手动/自动模式切换：/u claimmode
  - 手动模式：木斧右键占领机制
  - 占领进度显示和取消机制
  - **技术方案**: 自定义事件系统 + 进度条显示
  - **工作量**: 5-7天
  - **依赖**: 领土系统

- **传送系统完善** `[MEDIUM PRIORITY]`
  - 回城传送：/u return (10秒等待，不能移动)
  - 受攻击打断传送机制
  - 传送冷却时间和费用
  - **技术方案**: 异步任务调度 + 事件监听
  - **工作量**: 3-4天
  - **依赖**: 国家管理系统

- **更多管理命令** `[MEDIUM PRIORITY]`
  - /u move - 迁移王城 (12小时冷却，Y轴64-300限制)
  - /u rename - 更改国家名称
  - /u transfer - 禅让君主之位
  - /u title - 册封国民头衔
  - **技术方案**: 扩展现有命令系统
  - **工作量**: 4-6天
  - **依赖**: 权限系统

##### Improvements
- **命令系统优化** - 改进Tab补全和错误提示
- **性能优化** - 优化数据库查询和缓存机制
- **用户界面** - 统一消息格式和颜色方案

##### Bug Fixes
- 修复多线程环境下的数据竞争问题
- 解决长时间运行的内存泄漏
- 修复Squaremap集成的同步延迟

##### Improvements
- **占领反馈** - 改进占领过程的视觉和音效反馈
- **权限细化** - 更精细的权限控制和验证
- **数据验证** - 加强输入验证和错误处理

---

### Medium-term Goals (Q2-Q3 2025)

#### 🏗️ v1.4.0 - Technology & Strategy Systems
**Target Release**: December 2025
**Focus**: 国家科技系统和策略深度

##### New Features
- **国家科技系统** `[HIGH PRIORITY]`
  - 科技树界面：/u tech
  - 全体增益效果：攻击力、防御力、移动速度等
  - 科技研发消耗：领土收入或国民贡献
  - 科技等级和解锁条件
  - **技术方案**: GUI菜单系统 + 效果管理器
  - **依赖**: 经济系统、职业系统

- **忠诚度巡察系统** `[MEDIUM PRIORITY]`
  - 亲身巡察提高区块忠诚度
  - 巡察范围和效果计算
  - 巡察冷却时间和消耗
  - **技术方案**: 位置检测 + 定时任务
  - **工作量**: 4-6天
  - **依赖**: 忠诚度系统

##### Improvements
- **科技平衡性** - 调整科技效果和研发成本
- **界面优化** - 改进科技树的视觉设计
- **性能优化** - 优化大量玩家的效果计算

#### 🛡️ v1.4.0 - Advanced Shield & Defense Systems
**Target Release**: April 2025  
**Focus**: 护盾系统完善和防御机制

##### New Features  
- **护盾系统完善** `[HIGH PRIORITY]`
  - 资源消耗：每小时消耗5小时领土收入
  - 护盾限制条件检查：成员数量、长宽比、资源等
  - 30分钟冷却机制
  - 王战期间护盾失效
  - **技术方案**: 定时任务 + 条件验证系统
  - **工作量**: 6-8天
  - **依赖**: 经济系统、领土系统

- **敌对区块限制** `[MEDIUM PRIORITY]`
  - 敌对内陆区块建筑限制
  - 方块放置/挖掘权限控制
  - 边界区块特殊规则
  - **技术方案**: 事件拦截 + 权限检查
  - **工作量**: 4-5天
  - **依赖**: 领土系统、外交系统

##### Improvements
- **护盾视觉效果** - 添加护盾状态的粒子效果
- **防御平衡** - 调整护盾成本和效果平衡
- **通知系统** - 改进护盾状态变化的通知

#### ⚔️ v1.5.0 - Advanced Combat & Occupation
**Target Release**: May 2025  
**Focus**: 占领机制深化和战斗系统

##### New Features
- **占领时间计算系统** `[HIGH PRIORITY]`
  - 距离影响：每增加1区块距离，占领时间+0.25秒
  - 接壤面数影响：2面+50%，3面+100%时间
  - 多人协作：每多1人速度+25%，最多+100%
  - 无主区块占领加速：2面+100%，3面+200%速度
  - **技术方案**: 复杂算法系统 + 实时计算
  - **工作量**: 8-10天
  - **依赖**: 领土系统

- **领土系数计算** `[MEDIUM PRIORITY]`
  - 领土效率计算：面积/实际区块数
  - 长宽比影响：<=2:1正常，>2:1惩罚
  - 效率影响税收和其他收益
  - **技术方案**: 几何算法 + 效率评估系统
  - **工作量**: 5-7天
  - **依赖**: 领土系统、经济系统

##### Improvements
- **占领体验** - 改进占领过程的交互体验
- **算法优化** - 优化复杂计算的性能
- **平衡调整** - 根据测试反馈调整参数

#### 🌐 v1.6.0 - Enhanced Integration & UI
**Target Release**: June 2025
**Focus**: 第三方集成增强和用户界面改进

##### New Features
- **高级地图功能** `[MEDIUM PRIORITY]`
  - Squaremap高级标记：王城、重要建筑、战争区域
  - 实时战争状态显示
  - 历史领土变化回放
  - **技术方案**: Squaremap API扩展 + 数据可视化
  - **工作量**: 6-8天
  - **依赖**: Squaremap集成

- **增强的PlaceholderAPI** `[LOW PRIORITY]`
  - 更多占位符：国家排名、经济状况、战争状态
  - 动态占位符：实时更新的数据
  - 第三方插件兼容性
  - **技术方案**: PlaceholderAPI扩展
  - **工作量**: 3-4天
  - **依赖**: PlaceholderAPI集成

##### Improvements
- **用户界面统一** - 统一所有GUI界面的设计风格
- **多语言支持** - 添加英文、繁体中文支持
- **性能监控** - 添加性能指标和监控面板

#### 🔧 v1.7.0 - Performance & Stability
**Target Release**: July 2025
**Focus**: 性能优化和稳定性改进

##### New Features
- **高级缓存系统** `[HIGH PRIORITY]`
  - 多层缓存架构：内存、Redis、数据库
  - 智能缓存失效和更新
  - 缓存性能监控
  - **技术方案**: 分布式缓存 + 缓存管理器
  - **工作量**: 8-12天
  - **依赖**: 数据库系统

- **集群支持** `[MEDIUM PRIORITY]`
  - 多服务器数据同步
  - 跨服务器国家管理
  - 负载均衡和故障转移
  - **技术方案**: 消息队列 + 分布式协调
  - **工作量**: 12-15天
  - **依赖**: 数据库系统、缓存系统

##### Improvements
- **数据库优化** - 查询优化、索引优化、连接池调优
- **内存管理** - 减少内存占用，防止内存泄漏
- **异步处理** - 更多操作异步化，提升响应速度

---

### Long-term Goals (Q4 2025 & Beyond)

#### 🚀 v2.0.0 - Major Architecture Upgrade
**Target Release**: October 2025
**Focus**: 架构重构和重大功能升级

##### Major Features
- **微服务架构** `[HIGH PRIORITY]`
  - 服务拆分：国家服务、领土服务、经济服务等
  - API网关和服务发现
  - 容器化部署支持
  - **技术方案**: Spring Boot + Docker + Kubernetes
  - **工作量**: 30-45天
  - **依赖**: 全面架构重构

- **高级外交系统** `[HIGH PRIORITY]`
  - 外交协议：贸易协定、军事同盟、互不侵犯条约
  - 外交事件：使节派遣、外交危机、和平谈判
  - 联合国系统：多国决议、制裁机制
  - **技术方案**: 复杂状态机 + 事件驱动架构
  - **工作量**: 15-20天
  - **依赖**: 外交系统重构

- **经济贸易系统** `[MEDIUM PRIORITY]`
  - 国际贸易：资源交换、贸易路线、关税系统
  - 货币系统：国家货币、汇率、通胀机制
  - 市场系统：供需平衡、价格波动
  - **技术方案**: 经济模拟引擎 + 市场算法
  - **工作量**: 12-18天
  - **依赖**: 经济系统重构

#### 🌟 v2.1.0+ - Advanced Features
**Target Release**: 2026+
**Focus**: 高级功能和生态扩展

##### Planned Features
- **联盟战争系统** - 多国联盟、大规模战争、战争策略
- **文明发展** - 科技树扩展、文明特色、历史记录
- **自然灾害** - 地震、洪水、瘟疫等随机事件
- **AI国家** - 电脑控制的NPC国家，增加游戏挑战
- **移动端支持** - 手机APP管理国家，查看地图
- **VR集成** - 虚拟现实体验，沉浸式国家管理

---

## 🔄 Development Workflow

### 1. Planning Phase
- **需求分析** - 详细分析功能需求和技术要求
- **技术设计** - 架构设计、API设计、数据库设计
- **工作量评估** - 准确评估开发时间和资源需求

### 2. Development Phase
- **模块化开发** - 每个功能模块独立开发和测试
- **代码审查** - 严格的代码审查流程
- **单元测试** - 100%测试覆盖率要求

### 3. Testing Phase
- **功能测试** - 完整的功能测试和边界测试
- **性能测试** - 大规模并发测试和压力测试
- **兼容性测试** - 多版本Minecraft和插件兼容性

### 4. Release Phase
- **Beta测试** - 社区Beta测试和反馈收集
- **文档更新** - 完善用户文档和开发文档
- **版本发布** - 正式版本发布和更新通知

---

## 📊 Success Metrics

### Technical Metrics
- **性能指标**: TPS保持20+，内存使用<2GB
- **稳定性**: 99.9%运行时间，零数据丢失
- **扩展性**: 支持1000+并发玩家，100+国家

### User Experience Metrics
- **用户满意度**: 4.5+星评分
- **活跃度**: 日活跃用户增长率>10%
- **留存率**: 30天留存率>60%

### Community Metrics
- **社区规模**: GitHub Stars>1000，Discord成员>5000
- **贡献度**: 月活跃贡献者>20人
- **生态发展**: 第三方插件集成>10个

---

## 🤝 Contributing

我们欢迎社区贡献！请查看 [CONTRIBUTING.md](CONTRIBUTING.md) 了解如何参与开发。

### Priority Areas for Contributors
1. **文档改进** - 用户文档、API文档、教程
2. **测试用例** - 单元测试、集成测试、性能测试
3. **本地化** - 多语言翻译和本地化
4. **插件集成** - 第三方插件适配和集成
5. **性能优化** - 算法优化、内存优化、数据库优化

---

## 📞 Feedback & Support

- **GitHub Issues**: [报告Bug和功能请求](https://github.com/lcofficial/guozhan/issues)
- **Discord社区**: [加入讨论](https://discord.gg/guozhan)
- **开发者邮箱**: dev@lcofficial.cn

---

*最后更新: 2025年10月5日*
*下次更新: 2025年11月1日*
