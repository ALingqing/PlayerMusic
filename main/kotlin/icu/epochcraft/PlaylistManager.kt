package icu.epochcraft

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌单管理器。
 *
 * 功能：
 * - 玩家个人歌单（/bf playlist add/remove/list/play）
 * - 服务器共享歌单（config.yml 中 playlist 配置）
 * - 音乐文件夹顺序播放（/bf playlist all）
 * - 自动连播：播完一首按歌曲时长定时切下一首
 */
class PlaylistManager(private val plugin: MusicPlayerPlugin) {

    /** 玩家个人歌单：UUID -> 歌曲 URL 列表 */
    private val playerPlaylists = ConcurrentHashMap<UUID, MutableList<String>>()

    /** 当前连播队列：UUID -> 剩余歌曲 URL 队列 */
    private val playQueues = ConcurrentHashMap<UUID, ArrayDeque<String>>()

    /** 连播循环标志：UUID -> 是否循环 */
    private val playQueueLoop = ConcurrentHashMap<UUID, Boolean>()

    /** 连播定时任务：UUID -> BukkitTask */
    private val playQueueTasks = ConcurrentHashMap<UUID, org.bukkit.scheduler.BukkitTask>()

    /** 连播进行中标志：UUID -> true */
    private val playingQueue = ConcurrentHashMap<UUID, Boolean>()

    /** 当前播放索引：UUID -> 歌曲名（用于显示"下一首"） */
    private val currentQueueSong = ConcurrentHashMap<UUID, String>()

    /** 持久化目录：plugins/PlayerMusic/playlists/（每个玩家一个 .yml 文件） */
    private val playlistDir = File(plugin.dataFolder, "playlists")

    init {
        if (!playlistDir.exists()) playlistDir.mkdirs()
        loadPlaylists()
    }

    /** 获取玩家歌单文件 */
    private fun playlistFileFor(uuid: UUID): File = File(playlistDir, "$uuid.yml")

    /** 获取玩家歌单（歌曲 URL 列表） */
    fun getPlaylist(playerId: UUID): List<String> {
        return playerPlaylists[playerId] ?: emptyList()
    }

    /** 添加歌曲到玩家歌单。返回是否新增（去重） */
    fun addToPlaylist(playerId: UUID, songUrl: String): Boolean {
        val list = playerPlaylists.computeIfAbsent(playerId) { mutableListOf() }
        if (list.contains(songUrl)) return false
        list.add(songUrl)
        savePlaylists()
        return true
    }

    /** 从歌单移除（按 URL 或序号） */
    fun removeFromPlaylist(playerId: UUID, index: Int): Boolean {
        val list = playerPlaylists[playerId] ?: return false
        if (index < 1 || index > list.size) return false
        list.removeAt(index - 1)
        savePlaylists()
        return true
    }

    /** 清空歌单 */
    fun clearPlaylist(playerId: UUID) {
        playerPlaylists.remove(playerId)
        savePlaylists()
    }

    // ===================== 连播 =====================

    /** 是否有连播进行中 */
    fun isPlayingQueue(playerId: UUID): Boolean = playingQueue[playerId] == true

    /**
     * 开始连播一个歌曲列表。
     * @param player 播放者
     * @param songUrls 歌曲 URL 列表（按顺序）
     * @param loop 是否循环
     */
    fun startQueue(player: Player, songUrls: List<String>, loop: Boolean) {
        val id = player.uniqueId
        stopQueue(id)
        playQueues[id] = ArrayDeque(songUrls)
        playQueueLoop[id] = loop
        playingQueue[id] = true
        playNextInQueue(player)
    }

    /** 停止连播 */
    fun stopQueue(playerId: UUID) {
        playingQueue[playerId] = false
        playQueues.remove(playerId)
        playQueueLoop.remove(playerId)
        currentQueueSong.remove(playerId)
        playQueueTasks.remove(playerId)?.cancel()
    }

    /** 播放队列的下一首（供自动连播和手动 next 调用） */
    fun playNextInQueue(player: Player) {
        val id = player.uniqueId
        if (playingQueue[id] != true) return
        val queue = playQueues[id] ?: return
        // 取消旧的定时任务
        playQueueTasks.remove(id)?.cancel()

        if (queue.isEmpty()) {
            if (playQueueLoop[id] == true) {
                // 循环：重置队列重新播放（从歌单重新加载）
                val playlist = playerPlaylists[id]
                if (playlist != null && playlist.isNotEmpty()) {
                    playQueues[id] = ArrayDeque(playlist)
                    playNextInQueue(player)
                } else {
                    stopQueue(id)
                }
            } else {
                stopQueue(id)
                plugin.sendConfigMsg(player, "messages.bf.playlist.finished")
            }
            return
        }

        val url = queue.removeFirst()
        // 找到 PresetSong（用于 handlePlay）；网络 URL（搜索结果添加）没有对应 PresetSong
        val song = plugin.getPresetSongs().firstOrNull { it.url == url }
        val isNetworkUrl = url.startsWith("http://") || url.startsWith("https://")
        if (song == null && !isNetworkUrl) {
            // 本地歌曲不存在，跳过到下一首
            playNextInQueue(player)
            return
        }
        currentQueueSong[id] = song?.name ?: "网络歌曲"

        // 播放
        if (plugin.musicCommands == null) {
            stopQueue(id)
            return
        }
        plugin.musicCommands!!.handlePlay(player, url, MusicCommands.PlaybackContextType.SINGLE, null, song)

        // 估算时长并定时切下一首
        val durationMs = estimateDuration(url)
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (playingQueue[id] == true) {
                playNextInQueue(player)
            }
        }, (durationMs / 50).toLong())
        playQueueTasks[id] = task
    }

    /** 估算歌曲时长（毫秒）。用 OGG 文件大小估算（约 128kbps），误差可接受 */
    private fun estimateDuration(url: String): Long {
        return try {
            if (url.startsWith("file:")) {
                val file = File(java.net.URLDecoder.decode(java.net.URL(url).path, "UTF-8"))
                if (file.exists()) {
                    // 128kbps ≈ 16000 bytes/s；Vorbis q4 ≈ 128kbps
                    val seconds = file.length() / 16000L
                    // 至少 3 秒，最多 5 分钟，防止异常
                    seconds.coerceIn(3L, 300L) * 1000L
                } else 30000L
            } else 30000L
        } catch (_: Exception) {
            30000L
        }
    }

    /** 当前队列歌曲名 */
    fun getCurrentQueueSong(playerId: UUID): String? = currentQueueSong[playerId]

    // ===================== 持久化（playlists/ 文件夹，每玩家一个 .yml） =====================

    private fun loadPlaylists() {
        try {
            if (!playlistDir.exists()) return
            playlistDir.listFiles()?.forEach { file ->
                val name = file.name
                if (!name.endsWith(".yml")) return@forEach
                val uuid = try { UUID.fromString(name.substring(0, name.length - 4)) } catch (_: Exception) { return@forEach }
                val cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
                val urls = cfg.getStringList("songs")
                if (urls.isNotEmpty()) playerPlaylists[uuid] = urls.toMutableList()
            }
        } catch (_: Exception) {
        }
    }

    private fun savePlaylists() {
        try {
            if (!playlistDir.exists()) playlistDir.mkdirs()
            for ((uuid, urls) in playerPlaylists) {
                val cfg = org.bukkit.configuration.file.YamlConfiguration()
                cfg.set("songs", urls)
                cfg.save(playlistFileFor(uuid))
            }
        } catch (_: Exception) {
        }
    }
}
