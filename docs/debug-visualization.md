# 数据可视化调试模块设计说明

## 目标概述

在测试环境下，为 GM 提供一套安全的「数据可视化调试命令」，用于快速拉取国家、领土、玩家、经济等核心系统的状态快照，并通过结构化、有色彩的输出辅助排查问题。本次提交完成了模块的核心框架、配置开关、命令入口，以及三类高频视图的初版实现，其余类别已在代码中登记为「规划中」状态，方便后续增量开发。

## 配置与开关

- `config.yml` 引入 `debug.data-visualization.enabled`（默认 `false`）。
- `test-server/plugins/Guozhan/config.yml` 与 `src/main/resources/test-config.yml` 中默认启用该开关，方便测试服直接使用。
- `Config.Debug.DataVisualization.enabled` 暴露只读访问器，所有命令在执行前都会检查该开关。

```yaml
debug:
  data-visualization:
    enabled: false
```

## 命令接口

- 新增 `/gz` 命令（权限 `guozhan.admin.debug`），核心子命令：
  - `/gz debug list`：查看所有可视化类别及当前状态；
  - `/gz debug <category> [参数…]`：拉取指定类别数据；
  - `/gz visualize …`：与 `debug` 同义的别名；
  - `/gz <category>`：简写（仍需开启配置）。
- 输出采用 `§` 颜色码，遵循「标题-正文-耗时-分割线」结构；敏感信息（UUID）默认脱敏至前 8 位。
- 所有执行都会通过 `GMLogger.logGMAction` 记录操作人、类别、耗时和参数，方便审计。

示例（在线玩家总览）截取：

```
§6========== [玩家系统数据 - 在线概览] ==========
§e在线玩家: §f5

§a玩家列表
§f1. §bTesterA §7(ID: 1a2b3c4d)
§7   国家: §f测试国
§7   职业: §fBERSERKER Lv.2
§7   Claim 模式: §fAUTO
§8处理耗时: §f12ms
§6====================================
```

## 核心架构

### DebugVisualizationManager

- 持有 `VisualizationDefinition` 列表，登记每个类别的 key、标题、描述、别名、是否需要额外参数，以及实现状态（`READY` / `PLANNED`）。
- 对于 `READY` 的类别，注册具体的 handler；未完成的类别会返回「规划中」提示，但仍会出现在列表中以方便排期。
- 统一提供：
  - `execute(definition, request)`：内部计时、异常兜底（记录到 `pluginLogger`）、组装输出框架，并通过 `GMLogger` 记录操作；
  - `buildCategoryList()`：生成 `/gz debug list` 使用的有色列表；
  - `isEnabled()`、`resolveDefinition(token)` 等辅助函数。
- 输出使用 `DebugVisualizationFormatter`（独立工具类）生成头尾、摘要行、警告行、UUID 脱敏等常用片段，保持视觉统一。

### 线程安全策略

- 大量查询（如国家、领土汇总）会提前在主线程复制快照数据（ConcurrentHashMap -> List），随后交由 `CompletableFuture.supplyAsync` 进行纯读计算，确保不阻塞命令线程。
- Bukkit/Folia API（如 `Bukkit.getOnlinePlayers`、`CommandSender.sendMessage`）仍在主线程/Global Scheduler 上执行；命令最终输出通过 `cn.lcofficial.guozhan.util.run { … }` 回到全局调度，兼容 Folia。
- 新增 `DataManager.getPendingAsyncTaskCount()` 读接口，为后续「数据库与缓存状态」类视图准备。

### 已实现视图

| 类别 key | 描述 | 数据来源 | 说明 |
| --- | --- | --- | --- |
| `country-overview` | 国家概览 | `CountryManager.countries`、`CountryManager.getCountryMembers`、`TerritoryManager.territories`、`UserManager.users` | 汇总国家/领土/成员/护盾/国库，默认按领土数降序展示前 50 条，支持显示地图颜色与护盾剩余时间 |
| `territory-overview` | 领土分布统计 | `TerritoryManager.territories` | 统计总数、荒野数、各国家领土占比与世界分布，默认展示前 50 条 |
| `player-online` | 在线玩家概览 | `Bukkit.getOnlinePlayers()` + `Player.user()` | 列出在线玩家所属国家、职业等级、Claim 模式，限制前 50 条并提示截断 |

其余 17 个类别已全部登记在 `definitions` 中，状态为 `PLANNED`，命令会提示「规划中」，并在 `/gz debug list` 中展示描述和参数要求（如 `<countryId|name>`）。

## 扩展指引

1. **新增类别**：在 `DebugVisualizationManager` 的 `definitions` 中追加条目，指定 `key`、描述、别名、参数提示等。如同现有实现，给 `handler` 指派一个返回 `CompletableFuture<DebugVisualizationFrame>` 的函数即可。
2. **数据访问**：优先读取各 Manager 的内存缓存（`CountryManager.countries`、`TerritoryManager.territories`、`TechnologyManager` 等），避免在异步线程直接访问数据库。若必须查询数据库，请在主线程打快照或确认 Exposed 事务可以在背景线程执行。
3. **分页/限制**：默认输出最多 50 条结果，超过部分应在 `warnings` 中提示截断；必要时可在 `DebugVisualizationRequest.args` 中解析分页参数（预留字段已存在）。
4. **日志**：无需额外调用 `GMLogger`，`execute` 已集中处理。若 handler 内部需要补充 debug 信息，可复用 `pluginLogger` 或现有的 `GMDebugManager`。
5. **敏感信息**：使用 `DebugVisualizationFormatter.maskUuid` 脱敏 UUID，或在输出中仅展示首 8 位。内含时间戳统一通过 `DebugVisualizationFormatter.formatTimestamp` 处理。

## 使用说明 & 建议流程

1. 在 `config.yml` 启用 `debug.data-visualization.enabled: true`，或在测试服保持默认配置。
2. 执行 `/gz debug list` 了解当前可用与规划中的类别。
3. 运行 `/gz debug country-overview`、`/gz debug territory-overview`、`/gz debug player-online` 验证功能。
4. 查看 `plugins/Guozhan/gm-operations.log` 获取命令操作历史。
5. 若需要新增类别，可按照表格中列出的「数据来源」快速定位 Manager：

| 模块 | 主要 Manager / 数据结构 | 备注 |
| --- | --- | --- |
| 国家系统 | `CountryManager`, `Country`, `DiplomacyManager` | 成员缓存 `memberCache`，外交关系 `DiplomaticRelations` |
| 领土系统 | `TerritoryManager`, `ClaimManager`, `ClaimProgress` | 领土缓存 `territories`，坐标索引 `territoryByCoords` |
| 玩家系统 | `UserManager`, `ProfessionManager`, `TechEffectManager` | 玩家缓存 `users`，职业效果映射 `playerProfessionEffects` |
| 经济系统 | `EconomyManager`, `EconomyTasks`, `RegionalTaxSystem` | 税收区域、自动化任务调度 |
| 战争系统 | `WarManager`, `WarEventScheduler`, `WarScoreBossBarManager` | 战争状态、积分榜、历史记录 |
| 科技系统 | `TechnologyManager`, `TechEffectManager` | 研究进度缓存 `researchingTechnologies` |
| 职业系统 | `ProfessionManager` | 升级冷却计算、职业效果缓存 |
| 护盾系统 | `ShieldManager` | 护盾时长、冷却、成员列表 |
| 数据库 / 缓存 | `DataManager`, 各 Manager 缓存 | `pendingAsyncTasks` 计数、HikariCP 状态 |
| 调度器 | `EconomyTasks`, `WarEventScheduler`, `LoyaltySystem` 等 | Folia `ScheduledTask` 句柄、下次执行时间 |

## 后续工作建议

- 为「规划中」分类填充 handler，可按优先级（经济 → 战争 → 科技 → 调度器）逐步完成。
- 如果需要分页输出，可在 `DebugVisualizationRequest` 中解析 `--page` 等参数，并扩展 `DebugVisualizationFormatter` 以渲染分页信息。
- 结合 `DataManager.getPendingAsyncTaskCount()` 和 HikariCP，完善数据库/缓存巡检视图，并加上潜在警告阈值。
- 视需求编写自动化测试（例如使用 Mock Manager 数据）验证格式化输出。

---

如需进一步扩展，可参考 `DebugVisualizationManager` 内的 `ImplementationStatus.PLANNED` 条目，逐项补充 handler 并改为 `READY` 即可。
