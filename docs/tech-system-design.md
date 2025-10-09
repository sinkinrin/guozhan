# GuoZhan 科技系统设计文档

**文档版本**: v1.0  
**创建日期**: 2025-10-05  
**设计目标**: 为GuoZhan项目设计并实现完整的国家科技系统  

## 📋 需求分析

### 原始需求（来自ROADMAP.md）
- **科技树界面**: `/u tech` 命令打开GUI菜单
- **全体增益效果**: 攻击力、防御力、移动速度等国民增益
- **科技研发消耗**: 领土收入或国民贡献资源
- **科技等级和解锁条件**: 支持前置科技和多级升级
- **技术方案**: GUI菜单系统 + 效果管理器

### 设计原则
1. **简单易理解**: 清晰的科技树结构，避免复杂依赖
2. **易于开发**: 使用现有架构模式（Manager + Data类 + 配置文件）
3. **易于维护**: 完整KDoc注释，模块化设计，配置驱动
4. **易于使用**: 直观的命令接口，GUI菜单展示

## 🏗️ 技术架构设计

### 数据层设计

#### 1. Technology数据类
```kotlin
data class Technology(
    val id: String,                    // 科技ID（唯一标识）
    val name: String,                  // 科技名称
    val description: String,           // 科技描述
    val icon: Material,                // GUI中的图标
    val maxLevel: Int,                 // 最大等级
    val prerequisites: List<String>,   // 前置科技ID列表
    val costs: Map<Int, TechCost>,     // 每级的研发成本
    val effects: Map<Int, List<TechEffect>> // 每级的效果列表
)

data class TechCost(
    val gold: Int,                     // 金币消耗
    val diamond: Int,                  // 钻石消耗
    val territoryIncome: Int           // 领土收入小时数
)

data class TechEffect(
    val type: TechEffectType,          // 效果类型
    val value: Double,                 // 效果数值
    val duration: Long = -1L           // 持续时间（-1为永久）
)

enum class TechEffectType {
    POTION_EFFECT,     // 药水效果（如力量、速度）
    ATTRIBUTE_MODIFIER, // 属性修改（如攻击力、防御力）
    PASSIVE_ABILITY,   // 被动能力（如经验加成、掉落加成）
    SPECIAL_ABILITY    // 特殊能力（如传送、飞行）
}
```

#### 2. 数据库表设计
```kotlin
object Technologies : Table("gz_technologies") {
    val id = varchar("id", 32).primaryKey()
    val name = varchar("name", 64)
    val description = text("description")
    val icon = varchar("icon", 32)
    val maxLevel = integer("max_level").default(1)
    val prerequisites = text("prerequisites") // JSON格式存储
    val costs = text("costs")                 // JSON格式存储
    val effects = text("effects")             // JSON格式存储
}

object CountryTechnologies : Table("gz_country_technologies") {
    val countryId = varchar("country_id", 36).references(Countries.id)
    val technologyId = varchar("technology_id", 32).references(Technologies.id)
    val level = integer("level").default(0)
    val researchStartTime = long("research_start_time").nullable()
    val researchEndTime = long("research_end_time").nullable()
    
    override val primaryKey = PrimaryKey(countryId, technologyId)
}
```

### 管理层设计

#### TechnologyManager核心功能
```kotlin
object TechnologyManager {
    // 科技数据管理
    fun loadTechnologies(): Map<String, Technology>
    fun getTechnology(id: String): Technology?
    fun getAllTechnologies(): List<Technology>
    
    // 国家科技状态管理
    fun getCountryTechLevel(country: Country, techId: String): Int
    fun canResearchTechnology(country: Country, techId: String): Boolean
    fun startResearch(country: Country, techId: String): Boolean
    fun completeResearch(country: Country, techId: String): Boolean
    
    // 科技效果管理
    fun applyTechnologyEffects(country: Country)
    fun removeTechnologyEffects(country: Country)
    fun getTechnologyEffects(country: Country): List<TechEffect>
    
    // 前置条件检查
    fun checkPrerequisites(country: Country, techId: String): Boolean
    fun checkResources(country: Country, techId: String, level: Int): Boolean
}
```

### 配置层设计

#### technology.yml配置文件
```yaml
# 科技系统配置
technologies:
  # 农业科技分支
  farming_basic:
    name: "基础农业"
    description: "提升农作物产量和食物效果"
    icon: WHEAT
    max_level: 3
    prerequisites: []
    costs:
      1: { gold: 500, diamond: 5, territory_income: 2 }
      2: { gold: 1000, diamond: 10, territory_income: 4 }
      3: { gold: 2000, diamond: 20, territory_income: 8 }
    effects:
      1:
        - type: POTION_EFFECT
          effect: SATURATION
          amplifier: 0
          duration: -1
      2:
        - type: POTION_EFFECT
          effect: SATURATION
          amplifier: 1
          duration: -1
      3:
        - type: PASSIVE_ABILITY
          ability: FOOD_BONUS
          value: 0.25

  # 军事科技分支
  military_basic:
    name: "基础军事"
    description: "提升战斗能力和装备效果"
    icon: IRON_SWORD
    max_level: 3
    prerequisites: []
    costs:
      1: { gold: 800, diamond: 8, territory_income: 3 }
      2: { gold: 1600, diamond: 16, territory_income: 6 }
      3: { gold: 3200, diamond: 32, territory_income: 12 }
    effects:
      1:
        - type: POTION_EFFECT
          effect: STRENGTH
          amplifier: 0
          duration: -1
      2:
        - type: POTION_EFFECT
          effect: STRENGTH
          amplifier: 1
          duration: -1
      3:
        - type: ATTRIBUTE_MODIFIER
          attribute: ATTACK_DAMAGE
          value: 2.0

  # 采矿科技分支
  mining_basic:
    name: "基础采矿"
    description: "提升挖矿效率和矿物产量"
    icon: DIAMOND_PICKAXE
    max_level: 3
    prerequisites: []
    costs:
      1: { gold: 600, diamond: 6, territory_income: 2 }
      2: { gold: 1200, diamond: 12, territory_income: 4 }
      3: { gold: 2400, diamond: 24, territory_income: 8 }
    effects:
      1:
        - type: POTION_EFFECT
          effect: HASTE
          amplifier: 0
          duration: -1
      2:
        - type: POTION_EFFECT
          effect: HASTE
          amplifier: 1
          duration: -1
      3:
        - type: PASSIVE_ABILITY
          ability: MINING_BONUS
          value: 0.15

  # 高级科技（需要前置）
  farming_advanced:
    name: "高级农业"
    description: "大幅提升农业效率和特殊能力"
    icon: GOLDEN_CARROT
    max_level: 2
    prerequisites: ["farming_basic"]
    costs:
      1: { gold: 3000, diamond: 30, territory_income: 15 }
      2: { gold: 6000, diamond: 60, territory_income: 30 }
    effects:
      1:
        - type: POTION_EFFECT
          effect: REGENERATION
          amplifier: 0
          duration: -1
      2:
        - type: SPECIAL_ABILITY
          ability: AUTO_REPLANT
          value: 1.0

# 科技系统设置
settings:
  research_time_base: 3600000      # 基础研究时间（1小时，毫秒）
  research_time_multiplier: 1.5    # 等级时间倍数
  max_concurrent_research: 1       # 最大同时研究数量
  effect_update_interval: 20       # 效果更新间隔（tick）
```

### 命令层设计

#### GuozhanCommand中的tech命令
```kotlin
"tech" -> {
    if (sender.hasPermission("guozhan.command.tech")) {
        if (args.size < 2) {
            // 打开科技树GUI
            openTechnologyGUI(sender)
        } else {
            when (args[1].lowercase()) {
                "research" -> {
                    if (args.size < 3) {
                        sender.sendUsage("/u tech research <科技ID>", "开始研究指定科技")
                        return
                    }
                    researchTechnology(sender, args[2])
                }
                "info" -> {
                    if (args.size < 3) {
                        sender.sendUsage("/u tech info <科技ID>", "查看科技详细信息")
                        return
                    }
                    showTechnologyInfo(sender, args[2])
                }
                "list" -> showTechnologyList(sender)
                else -> {
                    sender.sendError("未知的科技命令: ${args[1]}")
                    sender.sendUsage("/u tech", "打开科技树界面")
                }
            }
        }
    } else sender.sendPermissionError("guozhan.command.tech")
}
```

### 界面层设计

#### 科技树GUI设计
- **界面大小**: 54格（6行9列）Inventory
- **布局设计**:
  - 第1-2行: 基础科技（农业、军事、采矿等）
  - 第3-4行: 高级科技（需要前置条件）
  - 第5行: 特殊科技和传奇科技
  - 第6行: 功能按钮（关闭、刷新、帮助等）

#### GUI交互逻辑
```kotlin
class TechnologyGUI(private val player: Player, private val country: Country) {
    fun open() {
        val inventory = Bukkit.createInventory(null, 54, "§6${country.name} - 科技树")
        
        // 填充科技图标
        fillTechnologyIcons(inventory)
        
        // 添加功能按钮
        addFunctionButtons(inventory)
        
        player.openInventory(inventory)
    }
    
    private fun fillTechnologyIcons(inventory: Inventory) {
        val technologies = TechnologyManager.getAllTechnologies()
        
        technologies.forEachIndexed { index, tech ->
            val currentLevel = TechnologyManager.getCountryTechLevel(country, tech.id)
            val canResearch = TechnologyManager.canResearchTechnology(country, tech.id)
            
            val item = createTechnologyItem(tech, currentLevel, canResearch)
            inventory.setItem(getTechnologySlot(tech.id), item)
        }
    }
    
    private fun createTechnologyItem(tech: Technology, currentLevel: Int, canResearch: Boolean): ItemStack {
        val item = ItemStack(tech.icon)
        val meta = item.itemMeta
        
        meta.displayName = when {
            currentLevel >= tech.maxLevel -> "§a${tech.name} §7(已满级)"
            currentLevel > 0 -> "§e${tech.name} §7(等级 $currentLevel/${tech.maxLevel})"
            canResearch -> "§f${tech.name} §7(可研究)"
            else -> "§8${tech.name} §7(未解锁)"
        }
        
        val lore = mutableListOf<String>()
        lore.add("§7${tech.description}")
        lore.add("")
        
        if (currentLevel < tech.maxLevel) {
            val nextLevel = currentLevel + 1
            val cost = tech.costs[nextLevel]
            if (cost != null) {
                lore.add("§6下一级研发成本:")
                lore.add("§e  金币: ${cost.gold}")
                lore.add("§b  钻石: ${cost.diamond}")
                lore.add("§a  领土收入: ${cost.territoryIncome}小时")
                lore.add("")
            }
        }
        
        // 显示当前效果
        if (currentLevel > 0) {
            lore.add("§a当前效果:")
            tech.effects[currentLevel]?.forEach { effect ->
                lore.add("§f  ${formatEffect(effect)}")
            }
            lore.add("")
        }
        
        // 显示前置条件
        if (tech.prerequisites.isNotEmpty()) {
            lore.add("§c前置科技:")
            tech.prerequisites.forEach { prereq ->
                val prereqTech = TechnologyManager.getTechnology(prereq)
                val prereqLevel = TechnologyManager.getCountryTechLevel(country, prereq)
                val status = if (prereqLevel > 0) "§a✓" else "§c✗"
                lore.add("§f  $status ${prereqTech?.name ?: prereq}")
            }
            lore.add("")
        }
        
        when {
            currentLevel >= tech.maxLevel -> lore.add("§a§l已达到最高等级")
            canResearch -> lore.add("§e§l点击开始研究")
            else -> lore.add("§c§l条件不足，无法研究")
        }
        
        meta.lore = lore
        item.itemMeta = meta
        
        return item
    }
}
```

## 🔄 实施计划

### 第一阶段：数据模型和配置（2-3天）
1. 创建Technology相关数据类
2. 设计数据库表结构
3. 实现technology.yml配置文件
4. 创建TechnologyConfig配置加载器

### 第二阶段：核心管理器（3-4天）
1. 实现TechnologyManager核心功能
2. 科技数据的加载和缓存
3. 国家科技状态管理
4. 前置条件和资源检查

### 第三阶段：效果系统（2-3天）
1. 实现TechEffectManager
2. 药水效果和属性修改
3. 被动能力和特殊能力
4. 效果的应用和移除

### 第四阶段：GUI界面（3-4天）
1. 创建TechnologyGUI类
2. 科技树界面设计和交互
3. 点击事件处理
4. 界面更新和刷新

### 第五阶段：命令集成（1-2天）
1. 在GuozhanCommand中添加tech命令
2. 命令权限和错误处理
3. Tab补全功能

### 第六阶段：测试和优化（2-3天）
1. 单元测试编写
2. 功能测试和调试
3. 性能优化
4. 文档更新

## 📊 预期成果

### 功能特性
- 完整的科技树系统（6-8个基础科技，3-4个高级科技）
- 直观的GUI界面和交互体验
- 灵活的配置文件驱动设计
- 完整的效果系统和状态管理

### 技术指标
- 编译无错误，所有测试通过
- Folia兼容性100%
- 配置文件热重载支持
- 完整的KDoc注释覆盖

### 用户体验
- 简单易懂的科技树结构
- 友好的错误提示和帮助信息
- 实时的效果反馈和状态显示
- 平衡的研发成本和收益

---

**下一步**: 开始第一阶段的数据模型实现
