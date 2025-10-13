# GuoZhan插件测试指南

## 📋 测试概述

GuoZhan插件采用双层测试策略，包含自动化单元测试和人工集成测试，确保插件在各种环境下的稳定性和功能完整性。

## 🔧 测试环境要求

### 开发环境
- **Java**: OpenJDK 21+
- **Gradle**: 8.0+
- **IDE**: IntelliJ IDEA 或 Eclipse
- **操作系统**: Windows/Linux/macOS

### 运行环境
- **服务器**: Folia 1.21.5+
- **内存**: 最少2GB RAM
- **依赖插件**: PlaceholderAPI, ProtocolLib, squaremap

## 🧪 自动化单元测试

### 测试覆盖范围

当前单元测试覆盖以下模块：

#### 1. 配置系统测试 (`ConfigTest`)
- **文件**: `src/test/kotlin/cn/lcofficial/guozhan/test/unit/config/ConfigTest.kt`
- **覆盖**: Shield、Profession、Tax、War、Territory配置对象
- **测试点**: 默认值、类型安全、边界值、一致性检查

#### 2. ShieldManager测试 (`ShieldManagerTest`)
- **文件**: `src/test/kotlin/cn/lcofficial/guozhan/test/unit/manager/ShieldManagerTest.kt`
- **覆盖**: 长宽比计算、成本计算、冷却逻辑、资源检查
- **测试点**: 算法正确性、边界条件、错误处理

#### 3. ProfessionManager测试 (`ProfessionManagerTest`)
- **文件**: `src/test/kotlin/cn/lcofficial/guozhan/test/unit/manager/ProfessionManagerTest.kt`
- **覆盖**: 职业解锁、升级检查、效果配置、资源需求
- **测试点**: 时间逻辑、配置化效果、验证机制

#### 4. CountryManager测试 (`CountryManagerTest`)
- **文件**: `src/test/kotlin/cn/lcofficial/guozhan/test/unit/manager/CountryManagerTest.kt`
- **覆盖**: 国家删除、缓存管理、数据完整性、事务处理
- **测试点**: 数据库操作、副作用处理、错误恢复

#### 5. 解散命令测试 (`DisbandCommandTest`)
- **文件**: `src/test/kotlin/cn/lcofficial/guozhan/test/unit/command/DisbandCommandTest.kt`
- **覆盖**: 两步确认、超时处理、权限检查、数据清理
- **测试点**: 流程完整性、安全机制、通知系统

### 运行单元测试

#### 运行所有测试
```bash
./gradlew test
```

#### 运行特定测试类
```bash
./gradlew test --tests "cn.lcofficial.guozhan.test.unit.config.ConfigTest"
./gradlew test --tests "cn.lcofficial.guozhan.test.unit.manager.ShieldManagerTest"
```

#### 运行特定测试方法
```bash
./gradlew test --tests "cn.lcofficial.guozhan.test.unit.config.ConfigTest.testShieldConfigDefaults"
```

#### 生成测试报告
```bash
./gradlew test --continue
# 报告位置: build/reports/tests/test/index.html
```

### 测试结果解读

#### 成功输出示例
```
BUILD SUCCESSFUL in 9s
6 actionable tasks: 2 executed, 4 up-to-date

测试统计:
- 总测试数: 69个
- 通过: 69个
- 失败: 0个
- 跳过: 0个
```

#### 测试覆盖的功能点
- ✅ 配置系统 (9个测试)
- ✅ 护盾管理 (8个测试)
- ✅ 职业系统 (8个测试)
- ✅ 国家管理 (6个测试)
- ✅ 解散功能 (7个测试)
- ✅ 基础逻辑 (31个测试)

## 🎯 集成测试方案

### 测试文档位置
- **总体计划**: `docs/testing/integration-test-plan.md`
- **测试用例**: `docs/testing/manual-test-cases.md`
- **数据准备**: `docs/testing/test-data-setup.md`

### 关键测试场景

#### 1. 解散国家完整流程 (TC-001)
- **目标**: 验证两步确认机制和数据清理
- **时间**: 15分钟
- **参与者**: 1个国家所有者 + 2-3个成员

#### 2. 护盾系统长宽比限制 (TC-002)
- **目标**: 验证配置化的长宽比限制
- **时间**: 20分钟
- **参与者**: 1个国家所有者

#### 3. 多玩家并发操作 (TC-004)
- **目标**: 验证并发安全性
- **时间**: 30分钟
- **参与者**: 3-4个玩家

#### 4. Folia多线程兼容性 (TC-010)
- **目标**: 验证多线程环境稳定性
- **时间**: 60分钟
- **参与者**: 多个玩家同时在线

### 集成测试执行流程

#### 阶段1: 环境准备 (30分钟)
1. 部署Folia测试服务器
2. 安装GuoZhan插件和依赖
3. 配置权限系统
4. 准备测试账号和数据

#### 阶段2: 功能验证 (120分钟)
1. 执行核心功能测试用例
2. 记录测试结果和问题
3. 验证配置系统效果
4. 测试多玩家交互

#### 阶段3: 压力测试 (60分钟)
1. 多玩家并发操作
2. 长时间运行稳定性
3. 性能监控和分析

## 📊 测试质量指标

### 单元测试指标
- **测试覆盖率**: 目标 >50%，当前约45%
- **测试通过率**: 目标 100%，当前 100%
- **测试执行时间**: 目标 <30秒，当前 ~9秒
- **代码质量**: 无编译警告，无静态分析错误

### 集成测试指标
- **功能完整性**: 所有核心功能正常工作
- **性能表现**: 命令响应时间 <1秒
- **稳定性**: 连续运行24小时无崩溃
- **并发安全**: 多玩家操作无数据冲突

## 🔍 测试最佳实践

### 单元测试编写规范
1. **命名规范**: 测试方法名应清晰描述测试内容
2. **独立性**: 每个测试应该独立，不依赖其他测试
3. **完整性**: 包含正常情况、边界条件和异常情况
4. **可读性**: 使用清晰的断言和错误消息

### 集成测试执行规范
1. **环境一致性**: 使用标准化的测试环境
2. **数据准备**: 使用预定义的测试数据
3. **结果记录**: 详细记录测试步骤和结果
4. **问题跟踪**: 及时记录和跟进发现的问题

## 🚨 常见问题排查

### 单元测试问题

#### 编译错误
```bash
# 检查Java版本
java -version

# 清理并重新编译
./gradlew clean build
```

#### 测试失败
```bash
# 查看详细错误信息
./gradlew test --info

# 运行特定失败的测试
./gradlew test --tests "FailedTestClass" --info
```

### 集成测试问题

#### 插件加载失败
1. 检查Folia版本兼容性
2. 验证依赖插件是否正确安装
3. 查看服务器控制台错误日志

#### 权限问题
1. 检查权限插件配置
2. 验证权限节点设置
3. 测试权限继承关系

#### 数据库问题
1. 检查数据库连接配置
2. 验证数据库文件权限
3. 查看数据库连接池状态

## 📈 持续改进

### 测试覆盖扩展计划
1. **短期目标**: 增加更多边界条件测试
2. **中期目标**: 实现自动化集成测试
3. **长期目标**: 建立持续集成流水线

### 测试工具改进
1. **性能测试**: 集成JMH基准测试
2. **覆盖率报告**: 集成JaCoCo代码覆盖率工具
3. **自动化**: 建立GitHub Actions CI/CD

---

**注意**: 
- 运行测试前请确保开发环境配置正确
- 集成测试需要真实的Folia服务器环境
- 定期更新测试用例以覆盖新功能
- 测试发现的问题应及时修复并添加回归测试
