# Folia API 参考文档

## 概述

Folia是Paper的一个分支，为专用服务器添加了区域化多线程。这是GuoZhan项目的核心运行环境。

## 核心调度器概念

### RegionScheduler - 区域调度器
用于在特定区域（基于位置或区块）执行任务：

```java
// 按位置调度任务
@NotNull ScheduledTask run(@NotNull Plugin plugin, @NotNull Location location, @NotNull Consumer<ScheduledTask> task)

// 按区块坐标调度任务  
@NotNull ScheduledTask run(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task)

// 延迟执行任务
@NotNull ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Location location, @NotNull Consumer<ScheduledTask> task, long delayTicks)

// 重复执行任务
@NotNull ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Location location, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks)
```

### AsyncScheduler - 异步调度器
用于执行不依赖于服务器tick的异步任务：

```java
// 获取异步调度器
@NotNull public static AsyncScheduler getAsyncScheduler()

// 立即执行异步任务
ScheduledTask runNow(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task)

// 延迟执行异步任务
ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delay, @NotNull TimeUnit unit)

// 重复执行异步任务
ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelay, long period, @NotNull TimeUnit unit)
```

### EntityScheduler - 实体调度器
用于在实体所在区域执行任务：

```java
// 下一tick执行
@Nullable ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, @Nullable Runnable retired)

// 延迟执行
@Nullable ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, @Nullable Runnable retired, long delayTicks)

// 重复执行
@Nullable ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, @Nullable Runnable retired, long initialDelayTicks, long periodTicks)
```

## 重要注意事项

### 线程安全
- **异步任务不能访问Bukkit API**，必须是线程安全的
- **区域任务**在拥有该区域的线程上执行，可以安全访问Bukkit API
- **实体任务**在拥有该实体的区域线程上执行

### 迁移指南
从传统Bukkit调度器迁移到Folia：

```java
// 旧方式 (Bukkit)
Bukkit.getScheduler().runTask(plugin, () -> {
    // 同步任务
});

// 新方式 (Folia) - 需要指定位置
Bukkit.getRegionScheduler().run(plugin, location, (task) -> {
    // 区域任务
});

// 异步任务保持相似
Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
    // 异步任务
});
```

## GuoZhan项目中的应用

### 1. 领土相关操作
```java
// 在特定区块执行领土操作
Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, (task) -> {
    // 安全地访问该区块的领土数据
    TerritoryManager.updateTerritory(chunkX, chunkZ);
});
```

### 2. 玩家相关操作
```java
// 在玩家所在区域执行操作
player.getScheduler().run(plugin, (task) -> {
    // 安全地操作玩家数据
    UserManager.updatePlayer(player);
}, null);
```

### 3. 定时任务
```java
// 全局定时任务（如经济系统）
Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (task) -> {
    // 全局经济更新
    EconomyManager.updateGlobalEconomy();
}, 20L, 1200L); // 每分钟执行一次
```

### 4. 异步数据库操作
```java
// 数据库操作应该异步执行
Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
    // 异步数据库查询
    transaction {
        // Exposed数据库操作
    }
});
```

## 最佳实践

1. **明确任务类型**：确定任务是需要访问Bukkit API还是纯计算
2. **选择合适的调度器**：根据任务性质选择Region、Entity、Async或Global调度器
3. **避免跨线程访问**：不要在异步任务中访问Bukkit API
4. **合理使用延迟**：避免在同一tick内执行大量操作
5. **错误处理**：为调度任务添加适当的错误处理

## 性能考虑

- 区域化多线程可以显著提高大型服务器的性能
- 避免频繁的跨区域操作
- 合理分配任务到不同的调度器
- 监控任务执行时间，避免阻塞区域线程

---

*参考来源: Folia API 1.21 官方文档*
