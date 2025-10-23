package cn.lcofficial.guozhan.manager

import cn.lcofficial.guozhan.Guozhan
import cn.lcofficial.guozhan.data.Country
import cn.lcofficial.guozhan.manager.UserManager
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔧 v1.3.48: 战争积分 BossBar 管理器（支持多场战争同时显示）
 */
data class WarScoreResult(
    val country1: Country,
    val country2: Country,
    val score1: Int,
    val score2: Int
) {
    val winner: Country?
        get() = when {
            score1 > score2 -> country1
            score2 > score1 -> country2
            else -> null
        }

    val isTie: Boolean
        get() = score1 == score2
}

object WarScoreBossBarManager {

    private const val WAR_DURATION = 30 * 60 * 1000L // 30分钟

    private data class WarSession(
        val country1: Country,
        val country2: Country,
        val bossBar: BossBar,
        val scores: ConcurrentHashMap<UUID, Int>,
        val startTime: Long
    ) {
        @Volatile
        var updateTask: ScheduledTask? = null
    }

    private val warSessions = ConcurrentHashMap<String, WarSession>()

    private fun warKey(country1: Country, country2: Country): String {
        val ids = listOf(country1.id.toString(), country2.id.toString()).sorted()
        return "${ids[0]}_${ids[1]}"
    }

    fun startWarScoreDisplay(
        country1: Country,
        country2: Country,
        startTime: Long = System.currentTimeMillis(),
        initialScores: Map<UUID, Int> = emptyMap()
    ) {
        val key = warKey(country1, country2)

        warSessions.remove(key)?.let { existing ->
            existing.updateTask?.cancel()
            existing.bossBar.removeAll()
        }

        val now = System.currentTimeMillis()
        val elapsed = (now - startTime).coerceAtLeast(0)
        val remaining = (WAR_DURATION - elapsed).coerceAtLeast(0)

        val initialScore1 = initialScores[country1.id] ?: 0
        val initialScore2 = initialScores[country2.id] ?: 0

        val bossBar = Bukkit.createBossBar(
            formatWarScoreTitle(country1, country2, initialScore1, initialScore2, remaining),
            BarColor.RED,
            BarStyle.SEGMENTED_10
        ).apply {
            progress = if (WAR_DURATION <= 0) 1.0 else (elapsed.toDouble() / WAR_DURATION.toDouble()).coerceIn(0.0, 1.0)
        }

        val scores = ConcurrentHashMap<UUID, Int>().apply {
            put(country1.id, initialScore1)
            put(country2.id, initialScore2)
        }

        val session = WarSession(
            country1 = country1,
            country2 = country2,
            bossBar = bossBar,
            scores = scores,
            startTime = startTime
        )

        warSessions[key] = session

        updateBossBarPlayers(session)

        if (remaining <= 0) {
            Bukkit.getGlobalRegionScheduler().run(Guozhan.instance) { _ ->
                endWarScoreDisplay(country1, country2)
            }
        } else {
            session.updateTask = startWarScoreUpdateTask(key, session)
        }

        Guozhan.instance.logger.info("🔧 [战争积分] 开始显示战争积分：${country1.name} vs ${country2.name} (startTime=$startTime)")
    }

    fun endWarScoreDisplay(country1: Country, country2: Country): WarScoreResult? {
        val key = warKey(country1, country2)
        val session = warSessions.remove(key) ?: return null

        session.updateTask?.cancel()

        val score1 = session.scores[country1.id] ?: 0
        val score2 = session.scores[country2.id] ?: 0
        val result = WarScoreResult(country1, country2, score1, score2)

        val finalTitle = result.winner?.let {
            "§6战争结束! §f${it.name} §6胜利! §e${result.score1} : ${result.score2}"
        } ?: "§6战争结束! §e平局! §e${result.score1} : ${result.score2}"

        session.bossBar.apply {
            setTitle(finalTitle)
            color = BarColor.GREEN
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(Guozhan.instance, { _ ->
            session.bossBar.removeAll()
        }, 100L)

        Guozhan.instance.logger.info("🔧 [战争积分] 结束战争积分显示：${country1.name} vs ${country2.name}，最终比分 ${result.score1}:${result.score2}")

        return result
    }

    fun updateWarScore(country: Country, opponent: Country, scoreIncrease: Int) {
        val key = warKey(country, opponent)
        val session = warSessions[key] ?: return

        session.scores.merge(country.id, scoreIncrease) { a, b -> a + b }
        Guozhan.instance.logger.info("🔧 [战争积分] ${country.name} 对 ${opponent.name} 积分更新：+${scoreIncrease} (总计: ${session.scores[country.id] ?: 0})")
    }

    fun getWarScoreResult(country1: Country, country2: Country): WarScoreResult? {
        val key = warKey(country1, country2)
        val session = warSessions[key] ?: return null
        val score1 = session.scores[country1.id] ?: 0
        val score2 = session.scores[country2.id] ?: 0
        return WarScoreResult(country1, country2, score1, score2)
    }

    private fun startWarScoreUpdateTask(key: String, session: WarSession): ScheduledTask {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(Guozhan.instance, { _ ->
            val currentSession = warSessions[key] ?: return@runAtFixedRate

            val elapsed = System.currentTimeMillis() - currentSession.startTime
            val remaining = WAR_DURATION - elapsed

            if (remaining <= 0) {
                endWarScoreDisplay(currentSession.country1, currentSession.country2)
                return@runAtFixedRate
            }

            val score1 = currentSession.scores[currentSession.country1.id] ?: 0
            val score2 = currentSession.scores[currentSession.country2.id] ?: 0

            currentSession.bossBar.setTitle(
                formatWarScoreTitle(currentSession.country1, currentSession.country2, score1, score2, remaining)
            )

            currentSession.bossBar.progress = (elapsed.toDouble() / WAR_DURATION.toDouble()).coerceIn(0.0, 1.0)

            currentSession.bossBar.color = when {
                score1 > score2 -> BarColor.BLUE
                score2 > score1 -> BarColor.YELLOW
                else -> BarColor.WHITE
            }

            updateBossBarPlayers(currentSession)
        }, 20L, 20L)
    }

    private fun formatWarScoreTitle(
        country1: Country,
        country2: Country,
        score1: Int,
        score2: Int,
        remainingTime: Long
    ): String {
        val minutes = remainingTime / 60000
        val seconds = (remainingTime % 60000) / 1000
        val leader = when {
            score1 > score2 -> "§b${country1.name} 领先"
            score2 > score1 -> "§e${country2.name} 领先"
            else -> "§f平局"
        }
        return "§c战争积分 §f${country1.name} §e${score1} : ${score2} §f${country2.name} §7| $leader §7| §a${minutes}:${seconds.toString().padStart(2, '0')}"
    }

    private fun updateBossBarPlayers(session: WarSession) {
        val bossBar = session.bossBar
        val targetPlayers = mutableSetOf<Player>()

        Bukkit.getOnlinePlayers().forEach { player ->
            val user = UserManager.getUser(player.uniqueId)
            val playerCountry = user?.country
            if (playerCountry != null &&
                (playerCountry.id == session.country1.id || playerCountry.id == session.country2.id)
            ) {
                targetPlayers.add(player)
            }
        }

        bossBar.players.toList().forEach { player ->
            if (player !in targetPlayers) {
                bossBar.removePlayer(player)
            }
        }

        targetPlayers.forEach { player ->
            if (!bossBar.players.contains(player)) {
                bossBar.addPlayer(player)
            }
        }
    }

    fun hasActiveWarDisplay(): Boolean = warSessions.isNotEmpty()

    fun cleanup() {
        warSessions.values.forEach { session ->
            session.updateTask?.cancel()
            session.bossBar.removeAll()
        }
        warSessions.clear()
        Guozhan.instance.logger.info("🔧 [战争积分] 清理所有战争积分显示")
    }
}
