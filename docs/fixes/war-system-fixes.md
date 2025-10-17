# 战争系统修复报告

## 修复日期
2025-10-16

## 修复的问题

### ✅ 问题 1：WarListener.kt 与 WarManager.kt 之间的递归调用导致重复广播和数据覆盖

**问题描述**：
- `WarListener.onDiplomaticRelationChange()` 监听外交关系变化事件
- 当关系变为 WAR 时，调用 `WarManager.startWar()`
- `WarManager.startWar()` 内部又调用 `DiplomacyManager.updateRelation()` 设置为 WAR
- 这会再次触发 `onDiplomaticRelationChange` 事件，形成递归
- 结果：每次宣战产生两次广播，`warStartTimes` 被重置两次
- `WarManager.endWar()` 也有类似问题：递归调用时传入 `null` winner，覆盖原有的胜利者信息

**修复方案**：
采用方案A - 在 WarManager 方法中添加状态检查

**修改文件**：
- `src/main/kotlin/cn/lcofficial/guozhan/manager/WarManager.kt`

**修改内容**：
1. `startWar()` 方法开始时检查：如果两国关系已经是 WAR，直接返回
2. `endWar()` 方法开始时检查：如果两国关系已经不是 WAR，直接返回
3. 添加日志记录跳过重复处理的情况

**代码示例**：
```kotlin
fun startWar(country1: Country, country2: Country) {
    // 🔧 修复问题1：检查是否已经处于战争状态，避免递归调用
    val currentRelation = DiplomacyManager.getRelation(country1, country2)
    if (currentRelation.relationType == RelationType.WAR) {
        Guozhan.instance.logger.info("[战争系统] ${country1.name} 与 ${country2.name} 已经处于战争状态，跳过重复处理")
        return
    }
    // ... 继续处理
}
```

**验证方法**：
- 执行宣战命令
- 确认只有一次战争开始广播
- 检查 `warStartTimes` 只被设置一次
- 战争结束时确认胜利者信息正确，在线玩家获得胜利效果

---

### ✅ 问题 2：服务器重启后战争开始时间被重置

**问题描述**：
- 服务器启动时调用 `loadWarStates()` 加载进行中的战争
- 对每个战争，使用 `System.currentTimeMillis()` 作为开始时间
- 这会将所有战争的开始时间重置为服务器启动时间
- 结果：战争超时检查失效，战争持续时间被人为延长

**修复方案**：
在数据库中添加字段存储战争开始时间

**修改文件**：
1. `src/main/kotlin/cn/lcofficial/guozhan/data/DiplomaticRelation.kt`
2. `src/main/kotlin/cn/lcofficial/guozhan/manager/WarManager.kt`
3. `sql/migrate_war_start_time.sql` (新建)

**数据库修改**：
- 表名：`gz_diplomatic_relations`
- 新增字段：`war_start_time BIGINT NULL` - 战争开始时间戳（毫秒）
- 索引：`idx_war_start_time` 在 `war_start_time` 字段上

**代码修改**：
1. `DiplomaticRelations` 表对象添加 `warStartTime` 字段
2. `DiplomaticRelation` 类添加 `warStartTime` 属性
3. `save()` 方法保存 `warStartTime`
4. `create()` 方法在创建战争关系时设置 `warStartTime`
5. `WarManager.loadWarStates()` 从数据库读取战争开始时间
6. `WarManager.startWar()` 设置战争开始时间到数据库
7. `WarManager.endWar()` 清除战争开始时间
8. `WarManager.startWarGM()` 和 `endWarGM()` 同样处理

**代码示例**：
```kotlin
// DiplomaticRelations 表定义
object DiplomaticRelations : Table("gz_diplomatic_relations") {
    // ... 其他字段
    val warStartTime = long("war_start_time").nullable()
}

// WarManager.startWar()
fun startWar(country1: Country, country2: Country) {
    // ...
    val warStartTime = System.currentTimeMillis()
    warStartTimes[warId] = warStartTime
    currentRelation.warStartTime = warStartTime
    // ...
}

// WarManager.loadWarStates()
private fun loadWarStates() {
    // ...
    val warStartTime = relation.warStartTime ?: System.currentTimeMillis()
    warStartTimes[warId] = warStartTime
    // ...
}
```

**数据库迁移**：
运行 `sql/migrate_war_start_time.sql` 脚本：
```sql
ALTER TABLE `gz_diplomatic_relations` 
ADD COLUMN `war_start_time` BIGINT NULL COMMENT '战争开始时间戳（毫秒）' AFTER `updated_at`;

UPDATE `gz_diplomatic_relations` 
SET `war_start_time` = `updated_at` 
WHERE `relation_type` = 'WAR' AND `war_start_time` IS NULL;
```

**验证方法**：
- 启动两国战争
- 记录战争开始时间
- 重启服务器
- 检查战争开始时间是否保持不变
- 确认战争超时检查使用正确的开始时间

---

### ✅ 问题 3：战争击杀奖励未持久化

**问题描述**：
- 玩家在战争中击杀敌对国家成员时，增加击杀者国家的经济点数
- 代码：`killerCountry.economyPoints += reward`
- 但未调用 `killerCountry.save()` 保存到数据库
- 结果：服务器重启后战争击杀奖励丢失

**修复方案**：
在增加经济点数后立即调用 `save()` 持久化

**修改文件**：
- `src/main/kotlin/cn/lcofficial/guozhan/manager/WarManager.kt`

**修改内容**：
在 `handlePlayerDeath()` 方法中，增加经济点数后立即调用 `killerCountry.save()`

**代码示例**：
```kotlin
// 在战争中的击杀，给予击杀者国家经济奖励
val reward = DiplomacyConfig.getWarKillReward()
killerCountry.economyPoints += reward

// 🔧 修复问题3：持久化经济点数到数据库
try {
    killerCountry.save()
    Guozhan.instance.logger.info("[战争击杀] ${killerCountry.name} 击杀 ${killedCountry.name} 成员，获得 ${reward} 经济点数（已持久化）")
} catch (e: Exception) {
    Guozhan.instance.logger.severe("[战争击杀] 保存经济点数失败: ${e.message}")
    e.printStackTrace()
}
```

**验证方法**：
- 在战争中击杀敌对国家成员
- 检查数据库 `gz_countries` 表的 `economy_points` 字段是否更新
- 重启服务器后确认经济点数未回滚
- 查看日志确认击杀奖励记录

---

## 修复优先级

1. **最高优先级**：问题 3（战争击杀奖励持久化）- 最简单，影响游戏公平性 ✅
2. **高优先级**：问题 1（递归调用）- 影响战争系统稳定性和玩家体验 ✅
3. **高优先级**：问题 2（战争时间持久化）- 需要数据库修改，影响战争超时机制 ✅

---

## 编译结果

所有修复已通过编译测试：
```
BUILD SUCCESSFUL in 9s
1 actionable task: 1 executed
```

只有一些非关键的警告（已存在的弃用警告）。

---

## 部署步骤

### 1. 数据库迁移（仅MySQL用户需要）

如果使用 MySQL 数据库，需要运行迁移脚本：

```bash
mysql -u root -p guozhan < sql/migrate_war_start_time.sql
```

如果使用 SQLite，插件会在启动时自动创建新字段（Exposed ORM 自动处理）。

### 2. 重新构建插件

```bash
.\gradlew.bat shadowJar
```

### 3. 替换插件文件

```bash
copy build\libs\Guozhan-1.0-SNAPSHOT.jar test-server\plugins\Guozhan-1.0-SNAPSHOT.jar
```

### 4. 重启服务器

停止当前服务器，然后重新启动。

---

## 验证清单

- [ ] 宣战时只有一次广播，无重复消息
- [ ] 战争结束时胜利者信息正确，在线玩家获得效果
- [ ] 服务器重启后战争开始时间保持不变
- [ ] 战争击杀奖励正确持久化到数据库
- [ ] 所有修改添加了适当的日志记录
- [ ] 编译通过，无新增错误

---

## 相关文件

### 修改的文件
- `src/main/kotlin/cn/lcofficial/guozhan/manager/WarManager.kt`
- `src/main/kotlin/cn/lcofficial/guozhan/data/DiplomaticRelation.kt`

### 新增的文件
- `sql/migrate_war_start_time.sql`
- `docs/fixes/war-system-fixes.md` (本文件)

### 未修改的文件
- `src/main/kotlin/cn/lcofficial/guozhan/listener/WarListener.kt` (问题1通过修改WarManager解决)

---

## 技术细节

### 问题1的技术原因
事件驱动架构中的循环依赖：
```
WarListener.onDiplomaticRelationChange() 
  → WarManager.startWar() 
    → DiplomacyManager.updateRelation() 
      → 触发 DiplomaticRelationChangeEvent 
        → WarListener.onDiplomaticRelationChange() (递归)
```

### 问题2的技术原因
内存缓存 `warStartTimes` 未持久化，服务器重启后丢失：
```kotlin
// 错误的做法
warStartTimes[warId] = System.currentTimeMillis() // 仅在内存中

// 正确的做法
val warStartTime = System.currentTimeMillis()
warStartTimes[warId] = warStartTime
relation.warStartTime = warStartTime // 持久化到数据库
relation.save()
```

### 问题3的技术原因
ORM 对象修改后未调用 `save()` 方法：
```kotlin
// 错误的做法
killerCountry.economyPoints += reward // 仅修改内存对象

// 正确的做法
killerCountry.economyPoints += reward
killerCountry.save() // 持久化到数据库
```

---

## 后续建议

1. **添加单元测试**：为战争系统添加单元测试，特别是递归调用和持久化逻辑
2. **监控日志**：关注 `[战争系统]` 和 `[战争击杀]` 日志，确认修复生效
3. **性能优化**：如果战争频繁，考虑批量保存而非每次击杀都保存
4. **数据一致性检查**：定期检查 `warStartTimes` 缓存与数据库的一致性

---

## 版本信息

- **修复版本**：v1.3.20
- **修复日期**：2025-10-16
- **修复者**：Augment Agent
- **测试状态**：编译通过，待运行时验证

