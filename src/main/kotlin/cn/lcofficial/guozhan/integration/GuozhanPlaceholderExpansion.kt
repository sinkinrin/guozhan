package cn.lcofficial.guozhan.integration

import cn.lcofficial.guozhan.manager.UserManager
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class GuozhanPlaceholderExpansion : PlaceholderExpansion() {
    
    override fun getIdentifier(): String = "guozhan"
    
    override fun getAuthor(): String = "LCOfficial"
    
    override fun getVersion(): String = "1.0.0"
    
    override fun persist(): Boolean = true
    
    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return null
        
        val user = UserManager.getUser(player.uniqueId) ?: return "流民"
        
        return when (params.lowercase()) {
            "country" -> user.country?.name ?: "流民"
            "title" -> user.title ?: "国民"
            "rank" -> user.rank.name
            "profession" -> user.profession?.name ?: "无职业"
            "profession_level" -> user.professionLevel.toString()
            else -> null
        }
    }
}