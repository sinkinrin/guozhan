package cn.lcofficial.guozhan.util

import cn.lcofficial.guozhan.plugin
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Entity
import java.util.concurrent.TimeUnit

fun run(task: (ScheduledTask) -> Unit): ScheduledTask = plugin.server.globalRegionScheduler.run(plugin, task)

fun runLater(delay: Long, task: (ScheduledTask) -> Unit): ScheduledTask =
    plugin.server.globalRegionScheduler.runDelayed(plugin, task, maxOf(1L, delay))

fun runRepeat(delay: Long, period: Long, task: (ScheduledTask) -> Unit): ScheduledTask =
    plugin.server.globalRegionScheduler.runAtFixedRate(plugin, task, maxOf(1L, delay), maxOf(1L, period))

fun Entity.run(retired: () -> Unit = {}, task: (ScheduledTask) -> Unit): ScheduledTask? = scheduler.run(
    plugin, task
) { retired() }

fun Entity.runLater(delay: Long, retired: () -> Unit = {}, task: (ScheduledTask) -> Unit): ScheduledTask? =
    scheduler.runDelayed(plugin, task, { retired() }, maxOf(1L, delay))

fun Entity.runRepeat(
    delay: Long,
    period: Long,
    retired: () -> Unit = {},
    task: (ScheduledTask) -> Unit
): ScheduledTask? =
    scheduler.runAtFixedRate(plugin, task, { retired() }, maxOf(1L, delay), maxOf(1L, period))

fun async(task: (ScheduledTask) -> Unit): ScheduledTask = plugin.server.asyncScheduler.runNow(plugin, task)

fun asyncLater(delay: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS, task: (ScheduledTask) -> Unit): ScheduledTask =
    plugin.server.asyncScheduler.runDelayed(plugin, task, delay, timeUnit)

fun asyncRepeat(
    delay: Long,
    period: Long,
    timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    task: (ScheduledTask) -> Unit
): ScheduledTask =
    plugin.server.asyncScheduler.runAtFixedRate(plugin, task, delay, period, timeUnit)