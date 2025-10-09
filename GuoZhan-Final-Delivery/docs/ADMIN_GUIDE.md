# GuoZhan 管理员手册

## 🛡️ 管理员权限概述

作为GuoZhan插件的管理员，您拥有完整的服务器管理权限，可以监控、调试和维护整个国战系统。

### 权限节点

```yaml
# 完整管理员权限
guozhan.admin.*: true

# 细分权限
guozhan.admin.use: true          # 基础GM命令权限
guozhan.admin.give: true         # 物品给予权限
guozhan.admin.setcountry: true   # 设置玩家国家权限
guozhan.admin.economy: true      # 经济管理权限
guozhan.admin.territory: true    # 领土管理权限
guozhan.admin.debug: true        # 调试权限
guozhan.admin.reload: true       # 重载权限
guozhan.admin.cleardata: true    # 数据清理权限
guozhan.admin.setrank: true      # 设置玩家等级权限
guozhan.admin.cleardb: true      # 数据库清空权限（危险）
```

## 🎮 GM命令系统

### 基础管理命令

**命令前缀**: `/gzgm` (GuoZhan Game Master)

```bash
/gzgm help                       # 显示所有GM命令帮助
/gzgm reload                     # 重载插件配置
/gzgm debug <on|off>            # 开关调试模式
```

### 物品管理

```bash
/gzgm give <玩家> <物品> <数量>   # 给予玩家物品
```

**支持的物品类型**:
- `iron` - 铁锭
- `gold` - 金锭  
- `diamond` - 钻石
- `wood_axe` - 木斧（用于手动占领）

**使用示例**:
```bash
/gzgm give Steve iron 64         # 给Steve 64个铁锭
/gzgm give Alex diamond 10       # 给Alex 10个钻石
```

### 国家管理

```bash
/gzgm setcountry <玩家> <国家>    # 设置玩家所属国家
/gzgm addgold <国家> <数量>       # 增加国家金币
/gzgm adddiamonds <国家> <数量>   # 增加国家钻石
/gzgm setcorehealth <国家> <血量> # 设置国家核心血量
/gzgm tp <国家>                   # 传送到国家首都
```

**使用示例**:
```bash
/gzgm setcountry Steve 大明帝国   # 将Steve设置为大明帝国成员
/gzgm addgold 大明帝国 1000       # 给大明帝国增加1000金币
/gzgm setcorehealth 大明帝国 500  # 设置大明帝国核心血量为500
```

### 领土管理

```bash
/gzgm setloyalty <X> <Z> <忠诚度> # 设置指定坐标领土的忠诚度
```

**使用示例**:
```bash
/gzgm setloyalty 100 200 50      # 设置坐标(100,200)的领土忠诚度为50
```

### 玩家管理

```bash
/gzgm setrank <玩家> <等级>       # 设置玩家权限等级
/gzgm info <国家|玩家>            # 查看详细信息
```

**等级类型**:
- `OWNER` - 君主
- `ADMIN` - 大臣  
- `DEFAULT` - 国民

### 数据管理

```bash
/gzgm cleardata <类型>           # 清理指定类型的测试数据
/gzgm cleardb confirm           # 清空整个数据库（危险操作）
```

**数据类型**:
- `countries` - 清理所有国家数据
- `territories` - 清理所有领土数据
- `users` - 清理所有用户数据
- `all` - 清理所有数据

## 📊 监控和调试

### 调试模式

**启用调试模式**:
```bash
/gzgm debug on
```

**调试模式功能**:
- 详细的操作日志
- 性能监控信息
- 错误追踪
- 实时状态显示

**调试信息查看**:
- 控制台会显示详细的调试信息
- 有权限的管理员会收到调试消息
- 日志文件记录所有调试信息

### 性能监控

**监控指标**:
- 数据库连接池状态
- 内存使用情况
- 定时任务执行时间
- 玩家操作频率

**查看方法**:
```bash
/gzgm info server               # 查看服务器状态
```

### 日志系统

**GM操作日志**:
- 位置: `plugins/GuoZhan/gm-operations.log`
- 格式: `[时间戳] 操作者(类型) 操作 目标 {详细信息}`
- 自动记录所有GM操作

**系统日志**:
- 插件启动和关闭
- 数据库连接状态
- 错误和异常信息
- 性能警告

## 🔧 故障排除

### 常见问题诊断

#### 1. 插件无法启动

**症状**: 插件显示红色或启动失败

**诊断步骤**:
1. 检查控制台错误信息
2. 验证Java版本（需要Java 21+）
3. 确认使用Folia而非Paper/Spigot
4. 检查配置文件语法

**解决方案**:
```bash
# 检查Java版本
java -version

# 验证配置文件
/gzgm reload

# 查看详细错误
/gzgm debug on
```

#### 2. 数据库连接问题

**症状**: 出现数据库连接错误

**诊断步骤**:
1. 检查数据库服务状态
2. 验证连接参数
3. 测试数据库权限
4. 检查网络连接

**解决方案**:
```bash
# 测试数据库连接（在数据库服务器上）
mysql -u username -p -h localhost

# 检查GuoZhan数据库
USE guozhan;
SHOW TABLES;

# 重新加载配置
/gzgm reload
```

#### 3. 权限系统问题

**症状**: 玩家无法执行命令

**诊断步骤**:
1. 检查权限插件状态
2. 验证权限节点配置
3. 测试权限继承
4. 检查玩家组分配

**解决方案**:
```bash
# 检查玩家权限
/lp user <玩家名> permission check guozhan.command.create

# 临时给予权限测试
/gzgm setrank <玩家名> OWNER

# 查看玩家详细信息
/gzgm info <玩家名>
```

#### 4. 性能问题

**症状**: 服务器卡顿或延迟

**诊断步骤**:
1. 启用调试模式监控
2. 检查数据库查询性能
3. 监控内存使用
4. 分析定时任务执行时间

**解决方案**:
```bash
# 启用性能监控
/gzgm debug on

# 检查数据库连接池
/gzgm info database

# 清理缓存
/gzgm cleardata cache
```

### 数据恢复

#### 备份策略

**自动备份**:
- 数据库定期备份
- 配置文件版本控制
- 玩家数据快照

**手动备份**:
```bash
# 备份数据库
mysqldump -u username -p guozhan > backup_$(date +%Y%m%d).sql

# 备份配置文件
cp -r plugins/GuoZhan/config/ backup/config_$(date +%Y%m%d)/
```

#### 数据恢复流程

1. **停止服务器**
2. **恢复数据库**:
   ```bash
   mysql -u username -p guozhan < backup_20251009.sql
   ```
3. **恢复配置文件**
4. **启动服务器并验证**

### 紧急处理

#### 服务器崩溃处理

1. **立即响应**:
   - 检查崩溃日志
   - 识别崩溃原因
   - 评估数据损失

2. **快速恢复**:
   ```bash
   # 禁用GuoZhan插件
   mv plugins/GuoZhan.jar plugins/GuoZhan.jar.disabled
   
   # 启动服务器
   # 检查其他插件是否正常
   
   # 逐步恢复
   mv plugins/GuoZhan.jar.disabled plugins/GuoZhan.jar
   ```

3. **数据验证**:
   ```bash
   /gzgm debug on
   /gzgm info server
   ```

## 📈 维护任务

### 日常维护

**每日任务**:
- [ ] 检查服务器日志
- [ ] 监控数据库性能
- [ ] 验证备份完整性
- [ ] 检查玩家反馈

**每周任务**:
- [ ] 清理过期数据
- [ ] 更新配置优化
- [ ] 性能分析报告
- [ ] 安全检查

**每月任务**:
- [ ] 数据库优化
- [ ] 插件版本更新
- [ ] 服务器硬件检查
- [ ] 备份策略评估

### 数据库维护

**优化查询**:
```sql
-- 分析表性能
ANALYZE TABLE gz_countries, gz_users, gz_territory_blocks;

-- 优化表结构
OPTIMIZE TABLE gz_countries, gz_users, gz_territory_blocks;

-- 检查索引使用
SHOW INDEX FROM gz_countries;
```

**清理过期数据**:
```bash
/gzgm cleardata expired        # 清理过期的临时数据
```

### 配置优化

**性能调优**:
```yaml
# config.yml 优化建议
database:
  pool-size: 15                # 根据服务器负载调整
  connection-timeout: 30000
  idle-timeout: 600000

bossbar:
  update-interval: 40          # 降低更新频率减少性能消耗
  cache-duration: 60           # 增加缓存时间

random-spawn:
  max-attempts: 30             # 减少尝试次数避免卡顿
```

## 🚨 安全管理

### 权限安全

**最小权限原则**:
- 只给予必要的权限
- 定期审查权限分配
- 监控权限使用情况

**敏感操作保护**:
```yaml
# 限制危险命令的使用
guozhan.admin.cleardb: false    # 禁用数据库清空命令
guozhan.admin.setrank: false    # 限制等级设置权限
```

### 数据安全

**访问控制**:
- 数据库用户权限最小化
- 定期更换数据库密码
- 启用数据库访问日志

**数据加密**:
- 敏感配置信息加密
- 数据库连接SSL加密
- 备份文件加密存储

## 📞 技术支持

### 问题报告

**收集信息**:
1. 服务器版本和配置
2. 错误日志和堆栈跟踪
3. 重现步骤
4. 影响范围评估

**联系渠道**:
- 查看 `docs/FAQ.md` 获取常见问题解答
- 检查插件文档获取详细信息
- 使用调试模式收集详细日志

### 更新和升级

**版本更新流程**:
1. 备份当前数据
2. 测试环境验证
3. 生产环境部署
4. 功能验证测试

**配置迁移**:
- 检查配置文件兼容性
- 运行数据库迁移脚本
- 验证新功能正常工作

---

**管理员手册完成。如有其他问题，请参考相关文档或启用调试模式获取更多信息。**
