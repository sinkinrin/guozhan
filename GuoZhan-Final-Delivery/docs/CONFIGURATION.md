# GuoZhan 插件配置指南

## 📁 配置文件概览

GuoZhan插件包含以下配置文件：

| 文件名 | 用途 | 重要性 |
|--------|------|--------|
| `config.yml` | 主配置文件 | ⭐⭐⭐ |
| `message.yml` | 消息和文本配置 | ⭐⭐ |
| `technology.yml` | 科技系统配置 | ⭐⭐ |
| `diplomacy.yml` | 外交系统配置 | ⭐ |

## ⚙️ config.yml 详细配置

### 数据库配置
```yaml
database:
  type: "mysql"              # 数据库类型: mysql 或 sqlite
  host: "localhost"          # 数据库主机地址
  port: 3306                 # 数据库端口
  database: "guozhan"        # 数据库名称
  username: "root"           # 数据库用户名
  password: "password"       # 数据库密码
  pool-size: 10              # 连接池大小
```

**配置说明**:
- **生产环境推荐使用MySQL**，性能更好，支持并发
- **测试环境可使用SQLite**，无需额外配置数据库服务
- `pool-size` 建议设置为服务器核心数的2-3倍

### 世界设置
```yaml
world:
  name: "world"              # 主世界名称
  spawn-radius: 7500         # 出生点保护半径（方块）
  min-y: 64                  # 最低Y坐标
  max-y: 100                 # 最高Y坐标
```

**配置说明**:
- `spawn-radius`: 出生点周围的保护区域，玩家无法在此区域建国
- `min-y/max-y`: 限制玩家活动的Y坐标范围

### 国家系统配置
```yaml
country:
  max-name-length: 12        # 国家名称最大长度
  min-name-length: 3         # 国家名称最小长度
  creation:
    item_cost:
      material: "IRON_INGOT" # 建国所需物品类型
      amount: 64             # 建国所需物品数量
  core-health-max: 1000      # 国家核心最大血量
  core-regen-interval: 60    # 核心血量回复间隔（秒）
  core-regen-amount: 1       # 每次回复的血量
```

**配置说明**:
- `material`: 支持所有Minecraft物品类型（如DIAMOND、GOLD_INGOT等）
- `core-health-max`: 国家核心的最大血量，影响战争平衡

### 领土系统配置
```yaml
territory:
  max-loyalty: 100           # 最大忠诚度
  loyalty-decay-interval: 300 # 忠诚度衰减检查间隔（秒）
  loyalty-restore-cost: 0.5  # 恢复忠诚度的成本系数
```

### 王战系统配置
```yaml
war:
  start-time: "19:20"        # 王战开始时间
  end-time: "22:00"          # 王战结束时间
  day-of-week: 6             # 王战日期（1=周一，6=周六，7=周日）
  preparation-minutes: 20    # 准备阶段时长（分钟）
  damage-multiplier: 1.5     # 战争期间伤害倍率
  kill-reward: 10            # 击杀敌国玩家奖励（经济点数）
```

**王战系统详细说明**：
- `start-time`: 每周王战开始的具体时间（24小时制）
- `end-time`: 每周王战结束的具体时间（24小时制）
- `day-of-week`: 王战进行的星期几（1=周一，6=周六，7=周日）
- `preparation-minutes`: 王战正式开始前的准备时间，期间可以进行最后的准备
- `damage-multiplier`: 王战期间玩家对玩家造成的伤害倍率
- `kill-reward`: 在王战期间击杀敌国玩家获得的经济点数奖励

**王战时间安排**：
- **准备阶段**: 19:00-19:20（20分钟）
- **正式王战**: 19:20-22:00（2小时40分钟）
- **王战日期**: 每周六

**王战期间特殊规则**：
- 护盾系统无法激活
- 核心可以被攻击
- 伤害倍率提升
- 击杀奖励生效

**⚠️ 重要提示**: 修改王战配置需要重启服务器生效。

### 科技系统配置
```yaml
technology:
  research_duration: 0                    # 研究时间（秒，0=瞬间完成）
  enable_progress_notifications: true     # 是否启用进度通知
  progress_notification_interval: 3600   # 进度通知间隔（秒）
```

### 护盾系统配置
```yaml
shield:
  cost-per-hour: 5           # 每小时护盾成本（倍数）
  cooldown-minutes: 30       # 护盾冷却时间（分钟）
  max-duration-hours: 24     # 最大持续时间（小时）
  min-duration-hours: 1      # 最小持续时间（小时）
  max-aspect-ratio: 2.0      # 最大长宽比（重要：影响护盾激活条件）
  diamond-to-gold-rate: 10   # 钻石到金币转换率
```

**护盾系统详细说明**：
- `cost-per-hour`: 护盾成本计算倍数，每小时护盾消耗 = 国家每小时收入 × 护盾小时数 × 此倍数
- `cooldown-minutes`: 护盾关闭后的冷却时间，期间无法重新激活护盾
- `max-duration-hours`: 单次护盾最长持续时间，超过此时间护盾自动失效
- `min-duration-hours`: 单次护盾最短持续时间，防止频繁开关护盾
- `max-aspect-ratio`: **关键配置** - 领土长宽比限制，超过此比例无法激活护盾（如2.0表示最长边不能超过最短边的2倍）
- `diamond-to-gold-rate`: 计算护盾成本时钻石收入转换为金币的汇率

**⚠️ 重要提示**: 修改护盾配置需要重启服务器生效。

### 随机出生系统配置
```yaml
random-spawn:
  enabled: true              # 是否启用随机出生
  spawn-radius: 5000         # 随机出生半径
  max-attempts: 50           # 最大尝试次数
  safety-check-radius: 3     # 安全检查半径
  min-distance-from-spawn: 1000  # 距离出生点最小距离
  allowed-worlds:            # 允许随机出生的世界
    - "world"
  unsafe-blocks:             # 不安全的方块类型
    - "LAVA"
    - "WATER"
    - "FIRE"
  min-y-level: 60            # 最低Y坐标
  max-y-level: 120           # 最高Y坐标
```

### 职业系统配置
```yaml
profession:
  unlock-delay-hours: 2      # 建国后多久可以设置职业（小时）
  upgrade-delay-hours: 24    # 设置职业后多久可以升级（小时）
  upgrade-cost: 50           # 升级到2级需要的钻石数
```

**职业系统详细说明**：
- `unlock-delay-hours`: 新建国家后需要等待的时间才能设置职业，防止快速建国刷职业
- `upgrade-delay-hours`: 设置职业后需要等待的时间才能升级，增加职业升级的策略性
- `upgrade-cost`: 职业从1级升级到2级所需的钻石数量

**可用职业类型**：
- **侦察兵 (SCOUT)**: 提供速度效果，1级速度I，2级速度IV
- **工匠 (CRAFTSMAN)**: 提供急迫效果，提升挖掘和建造速度
- **狂战士 (BERSERKER)**: 提供力量效果，增加攻击伤害
- **守护者 (GUARDIAN)**: 提供抗性效果，减少受到的伤害
- **跳跃者 (LEAPER)**: 提供跳跃提升效果，1级跳跃III，2级跳跃V
- **牧师 (PRIEST)**: 提供再生效果，持续恢复生命值
- **征服者 (CONQUEROR)**: 特殊职业，具有独特的征服能力

**⚠️ 重要提示**: 修改职业配置需要重启服务器生效。

### 税收系统配置
```yaml
tax:
  base-rate: 0.1             # 基础税率
  max-rate: 0.5              # 最大税率
  collection-interval: 3600  # 税收收集间隔（秒）
  regions:                   # 税收区域配置
    - "spawn"                # 出生点区域
    - "inner"                # 内环区域
    - "middle"               # 中环区域
    - "outer"                # 外环区域
    - "border"               # 边境区域
    - "wilderness"           # 荒野区域
```

**税收系统详细说明**：
- `base-rate`: 基础税收率，影响所有领土的基础税收计算
- `max-rate`: 最大税收率，防止税率过高影响游戏平衡
- `collection-interval`: 自动税收收集的时间间隔（秒）
- `regions`: 税收区域列表，不同区域有不同的税收倍率

**税收计算公式**：
```
领土税收 = 基础资源价值 × 忠诚度系数 × 区域倍率 × 基础税率
```

**区域税收特点**：
- **出生点区域**: 税收最高，竞争最激烈
- **内环区域**: 税收较高，适合发展经济
- **中环区域**: 税收中等，平衡发展
- **外环区域**: 税收较低，适合扩张
- **边境区域**: 税收低，但战略价值高
- **荒野区域**: 税收最低，适合新手发展

**⚠️ 重要提示**: 修改税收配置需要重启服务器生效。

## 💬 message.yml 配置

### 基础消息配置
```yaml
prefix: "&8[&b国战&8] &r"   # 消息前缀
reload: "&a配置文件已重载"   # 重载成功消息
no-permission: "&c你没有权限执行此命令"  # 权限不足消息
```

### 颜色代码支持
GuoZhan支持以下颜色格式：
- **传统颜色代码**: `&a`, `&c`, `&e` 等
- **MiniMessage格式**: `<green>`, `<red>`, `<yellow>` 等
- **十六进制颜色**: `<#FF5555>`, `<#55FF55>` 等

### 消息自定义示例
```yaml
commands:
  create:
    success: "<green>恭喜！国家创建成功！</green>"
    already: "<red>你已经拥有一个国家了</red>"
    invalid-name: "<yellow>国家名称不合法！请使用3-12个字符</yellow>"
```

## 🔬 technology.yml 配置

### 科技分支配置
```yaml
technologies:
  farming_basic:             # 科技ID
    name: "基础农业"         # 科技名称
    description: "提升农作物产量和食物效果"  # 科技描述
    icon: "WHEAT"            # 科技图标（Minecraft物品）
    max_level: 3             # 最大等级
    category: "basic"        # 科技分类
    enabled: true            # 是否启用
    prerequisites: []        # 前置科技（科技ID列表）
    costs:                   # 各等级成本
      1:
        gold: 100            # 金币成本
        diamond: 5           # 钻石成本
        territory_income: 10 # 领土收入成本
      2:
        gold: 200
        diamond: 10
        territory_income: 20
    effects:                 # 各等级效果
      1:
        - type: "POTION_EFFECT"
          effect: "SATURATION"
          amplifier: 0
          duration: 600
      2:
        - type: "ATTRIBUTE_MODIFIER"
          attribute: "MOVEMENT_SPEED"
          value: 0.1
          operation: "ADD_SCALAR"
```

### 科技效果类型
- **POTION_EFFECT**: 药水效果
- **ATTRIBUTE_MODIFIER**: 属性修改
- **PASSIVE_BONUS**: 被动加成

## 🤝 diplomacy.yml 配置

### 外交关系配置
```yaml
relations:
  default: "NEUTRAL"         # 默认关系类型
  cooldown: 3600            # 关系变更冷却时间（秒）

war:
  damage-multiplier: 1.5    # 战争状态伤害倍率
  kill-reward: 50           # 击杀奖励经济点数
  victory-reward: 1000      # 胜利奖励
  defeat-penalty: 500       # 失败惩罚
  max-duration: 604800      # 最大战争持续时间（秒）
```

## 🔧 高级配置

### 性能优化配置
```yaml
# config.yml 中的性能相关配置
bossbar:
  enabled: true
  display-range: 100        # BossBar显示范围
  update-interval: 20       # 更新间隔（tick）
  cache-duration: 30        # 缓存持续时间（秒）

# 数据库连接池优化
database:
  pool-size: 10             # 连接池大小
  connection-timeout: 30000 # 连接超时（毫秒）
  idle-timeout: 600000      # 空闲超时（毫秒）
  max-lifetime: 1800000     # 最大生命周期（毫秒）
```

### 调试配置
```yaml
# 启用调试模式
debug:
  enabled: false            # 是否启用调试模式
  log-level: "INFO"         # 日志级别
  performance-monitoring: false  # 性能监控
```

## ⚠️ 配置注意事项

### 1. YAML语法规则
- 使用空格缩进，不要使用Tab
- 冒号后必须有空格
- 字符串包含特殊字符时使用引号

### 2. 数值范围限制
- `spawn-radius`: 建议1000-10000
- `pool-size`: 建议5-20
- `max-loyalty`: 固定为100，不建议修改

### 3. 性能影响配置
- `update-interval`: 值越小性能消耗越大
- `cache-duration`: 值越大内存占用越多
- `max-attempts`: 值越大可能导致卡顿

### 4. 安全配置建议
- 数据库密码使用强密码
- 定期备份配置文件
- 测试配置修改后重启服务器

## 🔄 配置热重载

部分配置支持热重载，使用命令：
```
/u reload
```

**支持热重载的配置**:
- 消息配置（message.yml）
- 部分主配置（config.yml）

**需要重启的配置**:
- 数据库配置（database）
- 世界设置（world）
- 核心系统配置（country）
- 护盾系统配置（shield）
- 职业系统配置（profession）
- 税收系统配置（tax）
- 王战系统配置（war）
- 科技系统配置（technology）
- 随机出生配置（random-spawn）

---

**配置完成后，建议使用 `/u help` 测试插件是否正常工作。**
