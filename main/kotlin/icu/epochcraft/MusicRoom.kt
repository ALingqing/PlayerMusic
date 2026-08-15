package icu.epochcraft

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 音乐房间：房主创建、成员加入、同步收听同一首音乐。
 */
class MusicRoom(
    val roomId: String,
    val creator: Player,
    var musicUrl: String,
    var description: String,
) {
    enum class RoomStatus { ACTIVE, PLAYING, CLOSING, CLOSED }

    private val membersSet = Collections.newSetFromMap(ConcurrentHashMap<Player, Boolean>())
    var lastActivityTime: Long = System.currentTimeMillis()
    var status: RoomStatus = RoomStatus.ACTIVE
    var packFileName: String? = null
    var playRequestActive: Boolean = false

    init {
        addMember(creator)
    }

    val members: Set<Player>
        get() = Collections.unmodifiableSet(membersSet)

    fun addMember(player: Player) {
        membersSet.add(player)
        lastActivityTime = System.currentTimeMillis()
    }

    fun removeMember(player: Player) {
        val removed = membersSet.remove(player)
        if (removed) {
            lastActivityTime = System.currentTimeMillis()
            if (player == creator && status != RoomStatus.CLOSING && status != RoomStatus.CLOSED) {
                val plugin = JavaPlugin.getPlugin(MusicPlayerPlugin::class.java)
                plugin.logger.info("音乐室发起者 ${creator.name} 离开了音乐室 $roomId。准备关闭...")
                status = RoomStatus.CLOSING
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { plugin.removeMusicRoom(roomId) }, 20L * 1)
            }
        }
    }

    fun isMember(player: Player): Boolean = membersSet.contains(player)
    fun isEmpty(): Boolean = membersSet.isEmpty()

    fun stopPlaybackForPlayer(player: Player) {
        val plugin = JavaPlugin.getPlugin(MusicPlayerPlugin::class.java)
        val http = plugin.httpFileServer
        if (http != null && http.isRunning) {
            val soundEventName = "${http.servePathPrefix}.room.$roomId"
            player.stopSound(soundEventName)
        }
        updateLastActivityTime()
    }

    fun stopPlaybackForAll() {
        val plugin = JavaPlugin.getPlugin(MusicPlayerPlugin::class.java)
        val http = plugin.httpFileServer
        if (http != null && http.isRunning) {
            val soundEventName = "${http.servePathPrefix}.room.$roomId"
            for (member in HashSet(membersSet)) {
                if (member.isOnline) {
                    member.stopSound(soundEventName)
                }
            }
        }
        playRequestActive = false
        status = RoomStatus.ACTIVE
        updateLastActivityTime()
    }

    fun updateLastActivityTime() {
        lastActivityTime = System.currentTimeMillis()
    }
}
