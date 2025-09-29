# GuoZhan 开发文档

## 技术栈

- **运行环境**: Java 21 (plugin.yml `api-version: 1.21`)
- **开发语言**: Kotlin 2.2.x (shade/relocate到私有命名空间)
- **服务器API**: Folia API 1.21.5 (`folia-supported: true`)
- **构建工具**: Gradle + Shadow插件 (重定位kotlin、Exposed、fastjson2)
- **数据库**: JetBrains Exposed (Core/DAO/JDBC/Migration) + HikariCP连接池 + MySQL/SQLite
- **集成**: PlaceholderAPI、ProtocolLib、squaremap-api (compileOnly)

## 代码结构

### 入口与生命周期
- **主类**: `cn.lcofficial.guozhan.Guozhan`
- **onEnable**: 初始化配置与数据、注册命令/监听器、启动任务
- **onDisable**: 停止任务、清理内存资源

### 核心管理器 (Managers)
- **DataManager**: 数据库连接，执行迁移
- **CountryManager/CityManager/TerritoryManager/UserManager**: 缓存+数据访问+领域逻辑
- **EconomyManager/TaxSystem/RegionalTaxSystem/TributeSystem**: 经济与税收、进贡
- **DiplomacyManager**: 外交关系管理，缓存与事件派发
- **WarManager**: 战争状态、增伤、击杀奖励、超时检查、广播
- **CoreManager**: 王城核心 (BossBar、受击、回血、摧毁广播)
- **ProfessionManager**: 职业与药水效果映射/应用
- **SpawnManager**: 安全出生点计算 (随机传送)

### 监听器 (Listeners)
- **TerritoryListener**: 方块破坏/放置/容器交互权限限制、进入领土提示
- **PlayerListener**: 首次加入/无国死亡随机传送
- **WarListener**: 战争相关的伤害/死亡处理、外交关系变更联动
- **DiplomacyListener**: 登陆外交提示、外交关系变更提示
- **EconomyListener**: 经济信息提示与忠诚度定时衰减
- **CoreListener**: 点击玻璃对核心造成伤害、出生/重生处理
- **TaxRegionListener**: 进入不同税区提示
- **RandomSpawnListener**: 随机出生系统监听器

### 命令系统 (Commands)
- **`/u`**: 主命令 - 国家创建与信息、领土操作、经济税收、外交与战争、护盾、返回王城
- **`/tax`**: 税收信息查看 (区块/国家/区域)
- **`/tribute`**: 进贡系统
- **`/c`**: 全服聊天
- **`/w`**: 私聊

### 定时任务 (Tasks)
- **EconomyTasks**: 自动收税、按资源类型的周期生成、区域税收任务驱动
- **WarEventScheduler**: 每周六19:00-22:00的国战流程 (准备/正式/结算)
- **LoyaltySystem**: 领土忠诚度周期性衰减与灭国判定

### 效果与集成
- **WarEffects**: 战争期间/胜利/失败效果
- **SquaremapIntegration**: 领土/王城在squaremap上的展示
- **GuozhanPlaceholderExpansion**: PAPI占位符 (国家/头衔/爵位/职业)

## 数据模型 (Exposed)

### 核心表结构
```kotlin
// 用户表
Users(gz_users): id(uuid), name, countryId, rank, title, profession, professionLevel

// 国家表  
Countries(gz_countries): id, owner, name, capital, createTime, public, shield, gold, diamond, 
                         coreHealth, coreLocationX/Y/Z, coreWorld, lastHealthRegenTime

// 城市表
Cities(gz_cities): id, owner?, x, z, loyalty

// 领土区块表
TerritoryBlocks(gz_territory_blocks): id, owner?, x, z, world, loyalty, resourceType, 
                                     resourceAmount, lastHarvestTime, lastLoyaltyUpdateTime, 
                                     isCapital, coreHealth, lastCoreHealthUpdateTime

// 外交关系表
DiplomaticRelations(gz_diplomatic_relations): id, country1Id, country2Id, relationType, 
                                             createdAt, updatedAt
```

## 配置系统

### config.yml
- 数据库配置 (MySQL/SQLite)
- 世界与出生点半径
- 国家核心血量/回血
- 忠诚度参数
- 王战时间段与范围
- 护盾设置
- 职业配置
- 税收区 (六环)

### diplomacy.yml
- 外交冷却时间
- 默认关系类型
- 战争参数 (持续时间、伤害倍率、击杀奖励、胜败效果配置)

### plugin.yml
- 插件元数据
- 命令注册
- 软依赖 (ProtocolLib、PAPI)
- libraries (runtime shading)

## 启动流程

1. **初始化阶段**: 加载配置、连接数据库、迁移表结构、实例化管理器
2. **注册阶段**: 注册命令和监听器
3. **启动阶段**: 启动定时任务 (经济、国战、忠诚度)
4. **集成阶段**: 初始化PAPI扩展、squaremap集成

## 当前实现状态

### ✅ 已实现功能
- Folia兼容性
- 基础国家创建和管理
- 领土占领系统
- 外交关系管理
- 经济和税收基础
- 随机出生系统 (v1.1.0新增)
- 核心血量系统
- 职业系统
- 战争系统基础

### 🔄 部分实现功能
- 首登/无国死亡随机传送 (存在方法调用问题)
- `/u info` (缺少科技/外交/上限解锁显示)
- `/u contribute` (缺少24h冷却)
- `/u return` (缺少受攻击中断)
- `/u kick` (缺少冷却追踪)
- `/u shield` (缺少限制和squaremap覆盖)

### ❌ 未实现功能
- `/u map` 15x15实时小地图
- `/u claimmode` 圈地模式切换
- `/u tech` 科技菜单
- `/u invite` 邀请系统
- `/u disband` 解散国家
- `/u move` 王城迁移
- `/u rename` 国家重命名
- 权限型外交系统

### 🐛 存在问题
- PlayerListener调用不存在方法
- SpawnManager.isInTerritory为stub
- Squaremap API接口不一致
- 忠诚度系统需要统一

## 开发优先级

### 高优先级 (v1.1.0)
1. 修复随机出生系统的技术债务
2. 实现15x15疆域小地图
3. 完善BossBar核心血量显示

### 中优先级 (v1.2.0)
1. 实现占领模式系统
2. 完善传送系统
3. 添加更多管理命令

### 低优先级 (v1.3.0+)
1. 科技系统
2. 高级外交功能
3. 性能优化和扩展性改进

---

*本文档基于v1.1.0-DEV版本，持续更新中*
