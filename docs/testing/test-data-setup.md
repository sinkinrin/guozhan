# GuoZhan插件测试数据准备指南

## 📋 概述

本文档提供了GuoZhan插件测试所需的数据准备指南，包括测试账号、测试国家、测试配置和数据库初始化等内容。

## 🏗️ 测试环境搭建

### 1. 服务器配置

#### server.properties 推荐配置
```properties
# 基础设置
online-mode=false
difficulty=easy
gamemode=survival
spawn-protection=0
max-players=20

# 性能设置
view-distance=10
simulation-distance=8
entity-broadcast-range-percentage=100

# 测试友好设置
enable-command-block=true
op-permission-level=4
function-permission-level=4
```

#### Folia配置优化
```yaml
# config/paper-global.yml
chunk-loading:
  min-load-radius: 2
  max-concurrent-sends: 2
  autoconfig-send-distance: true

# 测试环境优化
timings:
  enabled: false
  verbose: false
```

### 2. 权限系统配置

#### LuckPerms权限配置
```yaml
# 创建测试组
/lp creategroup testowner
/lp creategroup testadmin  
/lp creategroup testmember

# 分配GuoZhan权限
/lp group testowner permission set guozhan.command.* true
/lp group testowner permission set guozhan.admin.* true

/lp group testadmin permission set guozhan.command.* true
/lp group testadmin permission set guozhan.command.disband false

/lp group testmember permission set guozhan.command.info true
/lp group testmember permission set guozhan.command.list true
/lp group testmember permission set guozhan.command.help true
```

## 👥 测试账号准备

### 测试账号清单

| 账号名 | 角色 | 权限组 | 用途 |
|--------|------|--------|------|
| TestOwner1 | 国家所有者 | testowner | 主要测试账号 |
| TestOwner2 | 国家所有者 | testowner | 多国家测试 |
| TestAdmin1 | 国家管理员 | testadmin | 权限测试 |
| TestMember1 | 普通成员 | testmember | 成员功能测试 |
| TestMember2 | 普通成员 | testmember | 多成员测试 |
| TestGuest1 | 访客 | default | 无国家测试 |

### 账号初始化脚本
```bash
# 给予测试物品
/give TestOwner1 iron_ingot 64
/give TestOwner1 gold_ingot 64
/give TestOwner1 diamond 64

/give TestOwner2 iron_ingot 64
/give TestOwner2 gold_ingot 64
/give TestOwner2 diamond 64

# 设置权限组
/lp user TestOwner1 parent set testowner
/lp user TestOwner2 parent set testowner
/lp user TestAdmin1 parent set testadmin
/lp user TestMember1 parent set testmember
/lp user TestMember2 parent set testmember

# 设置初始位置
/tp TestOwner1 0 100 0
/tp TestOwner2 1000 100 1000
/tp TestAdmin1 0 100 100
/tp TestMember1 100 100 0
/tp TestMember2 -100 100 0
/tp TestGuest1 0 100 -100
```

## 🏛️ 测试国家数据

### 基础测试国家

#### 国家1: TestEmpire
```yaml
所有者: TestOwner1
成员: [TestAdmin1, TestMember1]
首都位置: (0, 100, 0)
领土范围: (-50, -50) 到 (50, 50)
初始金币: 10000
初始钻石: 500
科技: [farming_basic, military_basic]
外交关系: 与TestKingdom结盟
```

#### 国家2: TestKingdom  
```yaml
所有者: TestOwner2
成员: [TestMember2]
首都位置: (1000, 100, 1000)
领土范围: (950, 950) 到 (1050, 1050)
初始金币: 8000
初始钻石: 300
科技: [mining_basic]
外交关系: 与TestEmpire结盟
```

### 国家创建脚本
```bash
# 创建TestEmpire
/gzgm setcountry TestOwner1 TestEmpire
/gzgm addgold TestEmpire 10000
/gzgm adddiamonds TestEmpire 500

# 添加成员
/u invite TestAdmin1
# (TestAdmin1执行) /u accept TestOwner1
/u invite TestMember1  
# (TestMember1执行) /u accept TestOwner1

# 创建TestKingdom
/gzgm setcountry TestOwner2 TestKingdom
/gzgm addgold TestKingdom 8000
/gzgm adddiamonds TestKingdom 300

# 添加成员
/u invite TestMember2
# (TestMember2执行) /u accept TestOwner2
```

## ⚙️ 测试配置文件

### config.yml 测试配置
```yaml
# 建国成本配置（用于测试配置化）
country:
  creation:
    item_cost:
      material: "IRON_INGOT"
      amount: 64

# 护盾系统配置（用于测试长宽比限制）
shield:
  cost-per-hour: 5
  cooldown-minutes: 30
  max-duration-hours: 24
  min-duration-hours: 1
  max-aspect-ratio: 2.0
  diamond-to-gold-rate: 10

# 职业系统配置（用于测试配置化效果）
profession:
  unlock-delay-hours: 2
  upgrade-delay-hours: 24
  upgrade-cost: 50

# 税收系统配置
tax:
  base-rate: 0.1
  max-rate: 0.5
  collection-interval: 3600
  regions:
    - "spawn"
    - "inner"
    - "middle"
    - "outer"
    - "border"
    - "wilderness"

# 战争系统配置
war:
  start-time: "19:20"
  end-time: "22:00"
  day-of-week: 6
  preparation-minutes: 20
  damage-multiplier: 1.5
  kill-reward: 10

# 领土系统配置
territory:
  claim-cost: 100
  max-claims: 1000
  loyalty-decay-rate: 0.1
  loyalty-decay-interval: 3600
  adjacency-required: true
```

### technology.yml 测试配置
```yaml
technologies:
  farming_basic:
    name: "基础农业"
    description: "提高农作物产量"
    cost: 1000
    research_time: 3600
    prerequisites: []
    
  military_basic:
    name: "基础军事"
    description: "提高战斗能力"
    cost: 1500
    research_time: 7200
    prerequisites: []
    
  mining_basic:
    name: "基础采矿"
    description: "提高采矿效率"
    cost: 1200
    research_time: 5400
    prerequisites: []
```

## 🗄️ 数据库测试数据

### SQLite测试数据初始化
```sql
-- 插入测试国家
INSERT INTO gz_countries (id, name, owner, create_time, gold, diamonds) VALUES
('test-empire-001', 'TestEmpire', 'TestOwner1', 1640995200000, 10000, 500),
('test-kingdom-002', 'TestKingdom', 'TestOwner2', 1640995200000, 8000, 300);

-- 插入测试用户
INSERT INTO gz_users (id, name, country, rank, join_time) VALUES
('TestOwner1', 'TestOwner1', 'test-empire-001', 'OWNER', 1640995200000),
('TestAdmin1', 'TestAdmin1', 'test-empire-001', 'ADMIN', 1640995260000),
('TestMember1', 'TestMember1', 'test-empire-001', 'MEMBER', 1640995320000),
('TestOwner2', 'TestOwner2', 'test-kingdom-002', 'OWNER', 1640995200000),
('TestMember2', 'TestMember2', 'test-kingdom-002', 'MEMBER', 1640995380000);

-- 插入测试领土
INSERT INTO gz_territories (x, z, owner, claim_time, loyalty) VALUES
(0, 0, 'test-empire-001', 1640995200000, 100),
(1, 0, 'test-empire-001', 1640995200000, 100),
(0, 1, 'test-empire-001', 1640995200000, 100),
(1000, 1000, 'test-kingdom-002', 1640995200000, 100),
(1001, 1000, 'test-kingdom-002', 1640995200000, 100);

-- 插入测试外交关系
INSERT INTO gz_diplomatic_relations (country1, country2, relation, create_time) VALUES
('test-empire-001', 'test-kingdom-002', 'ALLY', 1640995200000);

-- 插入测试科技
INSERT INTO gz_country_technologies (country_id, technology_id, research_start_time, research_complete_time) VALUES
('test-empire-001', 'farming_basic', 1640995200000, 1640998800000),
('test-empire-001', 'military_basic', 1640998800000, 1641006000000),
('test-kingdom-002', 'mining_basic', 1640995200000, 1641000600000);
```

## 🧪 测试场景数据

### 场景1: 解散国家测试数据
```yaml
国家: TestDisbandCountry
所有者: TestOwner1
成员: [TestMember1, TestMember2]
领土: 9块（3x3区域）
外交关系: 与TestKingdom结盟
活跃战争: 无
冷却状态: 无
```

### 场景2: 护盾系统测试数据
```yaml
国家: TestShieldCountry
领土形状: 可变（用于测试不同长宽比）
- 正方形: 5x5
- 矩形: 10x5 (长宽比2.0)
- 长条: 15x5 (长宽比3.0)
资源: 足够的金币和钻石
护盾状态: 无活跃护盾
```

### 场景3: 多玩家并发测试数据
```yaml
同时在线玩家: 6个
操作类型: 
- 同时邀请同一玩家
- 同时声明相邻领土
- 同时执行经济交易
- 同时修改国家设置
```

## 🔧 测试工具和脚本

### 快速重置脚本
```bash
#!/bin/bash
# reset-test-data.sh

echo "重置测试数据..."

# 清理数据库
sqlite3 plugins/Guozhan/guozhan.db "DELETE FROM gz_countries;"
sqlite3 plugins/Guozhan/guozhan.db "DELETE FROM gz_users;"
sqlite3 plugins/Guozhan/guozhan.db "DELETE FROM gz_territories;"
sqlite3 plugins/Guozhan/guozhan.db "DELETE FROM gz_diplomatic_relations;"
sqlite3 plugins/Guozhan/guozhan.db "DELETE FROM gz_country_technologies;"

# 重新加载插件
echo "reload" | nc localhost 25575

echo "测试数据重置完成"
```

### 性能监控脚本
```bash
#!/bin/bash
# monitor-performance.sh

echo "开始性能监控..."

while true; do
    echo "$(date): 内存使用情况"
    ps aux | grep java
    
    echo "$(date): 数据库连接数"
    sqlite3 plugins/Guozhan/guozhan.db ".databases"
    
    sleep 60
done
```

## 📊 测试数据验证

### 数据完整性检查
```sql
-- 检查国家数据完整性
SELECT COUNT(*) as country_count FROM gz_countries;

-- 检查用户-国家关联
SELECT u.name, u.country, c.name as country_name 
FROM gz_users u 
LEFT JOIN gz_countries c ON u.country = c.id;

-- 检查领土分布
SELECT owner, COUNT(*) as territory_count 
FROM gz_territories 
GROUP BY owner;

-- 检查外交关系
SELECT c1.name, c2.name, dr.relation 
FROM gz_diplomatic_relations dr
JOIN gz_countries c1 ON dr.country1 = c1.id
JOIN gz_countries c2 ON dr.country2 = c2.id;
```

### 配置验证脚本
```bash
#!/bin/bash
# validate-config.sh

echo "验证配置文件..."

# 检查YAML语法
python3 -c "import yaml; yaml.safe_load(open('plugins/Guozhan/config.yml'))" && echo "config.yml 语法正确"
python3 -c "import yaml; yaml.safe_load(open('plugins/Guozhan/technology.yml'))" && echo "technology.yml 语法正确"
python3 -c "import yaml; yaml.safe_load(open('plugins/Guozhan/message.yml'))" && echo "message.yml 语法正确"

echo "配置文件验证完成"
```

---

**注意**: 
1. 测试前请备份现有数据
2. 测试完成后及时清理测试数据
3. 定期验证测试数据的完整性
4. 根据测试需要调整数据规模
