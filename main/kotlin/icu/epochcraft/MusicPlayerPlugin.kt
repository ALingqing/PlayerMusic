package icu.epochcraft

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.SoundCategory
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * PlayerMusic 主插件类（Kotlin 半重写，版本 1.0.0）
 */
class MusicPlayerPlugin : JavaPlugin() {

    private var musicCommands: MusicCommands? = null
    var httpFileServer: HttpFileServer? = null
        private set
    var resourcePackGenerator: ResourcePackGenerator? = null
        private set

    /** 音乐搜索/下载管理器 */
    var musicSearchManager: MusicSearchManager? = null
        private set

    /** 语言文件配置（从 lang-<lang>.yml 加载） */
    private var langConfig: FileConfiguration? = null

    /** 日志文件写入器 */
    private var logFileWriter: PrintWriter? = null
    private var logFileHandlerInstalled = false

    data class PendingOnlineSound(
        val packFileName: String,
        val soundEventName: String,
        val sha1: String,
        val targetPlayer: Player,
        val packUrl: String,
    )

    private val pendingSingleUserSounds = ConcurrentHashMap<UUID, PendingOnlineSound>()
    private val playerPendingPackType = ConcurrentHashMap<UUID, String>()
    private val playerCurrentMusicPackFile = ConcurrentHashMap<UUID, String>()
    private val playerPackRequestIds = ConcurrentHashMap<UUID, UUID>()

    // 音量 / 循环
    private val playerVolumes = ConcurrentHashMap<UUID, Float>()
    private val playerLoopStatus = ConcurrentHashMap<UUID, Boolean>()
    private val playerLoopingSound = ConcurrentHashMap<UUID, String>()
    private val playerLoopTasks = ConcurrentHashMap<UUID, BukkitTask>()

    // 玩家最近一次音乐搜索结果（下载用）
    private val playerSearchResults = ConcurrentHashMap<UUID, List<MusicSearchManager.SearchResult>>()

    private val activeMusicRooms = ConcurrentHashMap<String, MusicRoom>()
    private val presetSongsList = ArrayList<PresetSong>()
    private var roomCleanupTask: BukkitTask? = null

    private var useMergedPackLogic = false
    private var basePackFile: File? = null
    private var basePackFileNameConfig = ""
    private var basePackPromptMessage = ""
    private var originalPackPromptMessage = ""
    private var basePackSha1: String? = null

    // ===================== 生命周期 =====================

    override fun onEnable() {
        logger.info("PlayerMusic 正在启动 (版本 ${description.version})...")

        if (!dataFolder.exists()) {
            if (!dataFolder.mkdirs()) {
                logger.severe("无法创建插件数据文件夹: ${dataFolder.absolutePath} - 插件功能可能受限!")
            }
        }
        installLogFileHandler()
        saveDefaultConfig()
        loadLanguageFile()
        loadConfiguration()

        // 释放内置 ffmpeg（Linux），使 /bf download 的 MP3→OGG 转换开箱即用
        val extractedFfmpeg = NativeFfmpegLoader.extract(dataFolder)
        if (extractedFfmpeg != null) {
            logger.info("已释放内置 ffmpeg 到 $extractedFfmpeg")
        } else {
            logger.info("未找到内置 ffmpeg，将使用系统 ffmpeg（若已安装）。")
        }

        if (config.getBoolean("httpServer.enabled", false)) {
            initializeHttpServerAndGenerator()
        } else {
            logger.info("内置 HTTP 服务器已在配置中禁用。在线播放功能将不可用。")
        }

        musicCommands = MusicCommands(this)
        musicSearchManager = MusicSearchManager(this)
        server.pluginManager.registerEvents(PlayerResourceListener(this, musicCommands!!), this)
        logger.info("事件监听器已注册。")

        val bfCommand = getCommand("bf")
        if (bfCommand != null) {
            bfCommand.setExecutor(musicCommands)
            bfCommand.tabCompleter = musicCommands
        } else {
            logger.severe("无法获取指令 'bf'！")
        }

        val internalJoinRoomCmd = getCommand("internal_join_room")
        if (internalJoinRoomCmd != null) {
            internalJoinRoomCmd.setExecutor(musicCommands)
        } else {
            logger.severe("无法获取指令 'internal_join_room'！")
        }

        startRoomCleanupTask()
        logger.info("$name 已成功启用！(资源包模式: ${if (useMergedPackLogic) "合并基础包" else "独立音乐包"})")
    }

    override fun onDisable() {
        logger.info("$name 正在禁用...")
        if (httpFileServer != null && httpFileServer!!.isRunning) {
            httpFileServer!!.stop()
        }
        roomCleanupTask?.cancel()

        // 取消所有玩家循环任务
        playerLoopTasks.values.forEach { it.cancel() }
        playerLoopTasks.clear()
        playerLoopingSound.clear()
        playerLoopStatus.clear()
        playerVolumes.clear()

        activeMusicRooms.values.forEach { room ->
            if (room.packFileName != null && resourcePackGenerator != null && !isPrewarmedPackFile(room.packFileName)) {
                resourcePackGenerator!!.cleanupPack(room.packFileName)
            }
        }
        activeMusicRooms.clear()

        pendingSingleUserSounds.values.forEach { sound ->
            if (sound.packFileName != null && resourcePackGenerator != null && !isPrewarmedPackFile(sound.packFileName)) {
                resourcePackGenerator!!.cleanupPack(sound.packFileName)
            }
        }
        pendingSingleUserSounds.clear()

        playerCurrentMusicPackFile.values.forEach { tempPackFile ->
            if (resourcePackGenerator != null && !isPrewarmedPackFile(tempPackFile)) {
                resourcePackGenerator!!.cleanupPack(tempPackFile)
            }
        }
        playerCurrentMusicPackFile.clear()
        playerPendingPackType.clear()
        playerPackRequestIds.clear()

        // 关闭日志文件写入器
        synchronized(this) {
            logFileWriter?.flush()
            logFileWriter?.close()
            logFileWriter = null
        }
        logFileHandlerInstalled = false

        logger.info("$name 已被禁用。")
    }

    fun reloadPluginConfiguration() {
        if (httpFileServer != null && httpFileServer!!.isRunning) {
            httpFileServer!!.stop()
        }
        httpFileServer = null
        resourcePackGenerator = null

        loadLanguageFile()
        loadConfiguration()

        if (config.getBoolean("httpServer.enabled", false)) {
            initializeHttpServerAndGenerator()
        } else {
            logger.info("HTTP 服务器在重载后仍为禁用状态。")
        }

        roomCleanupTask?.cancel()
        startRoomCleanupTask()
        logger.info("$name 的配置已重载。(资源包模式: ${if (useMergedPackLogic) "合并基础包" else "独立音乐包"})")
    }

    // ===================== 日志文件 =====================

    /**
     * 播放过程日志写入 player.log（不经过 java.util.logging，
     * 避免触发 Paper SysoutCatcher 的 System.out → Logger 无限递归）。
     */
    fun logPlayback(msg: String) {
        synchronized(this) {
            try {
                if (logFileWriter == null) {
                    val logFile = File(dataFolder, "player.log")
                    logFileWriter = PrintWriter(FileWriter(logFile, true), true)
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                logFileWriter?.println("[$time] $msg")
                logFileWriter?.flush()
            } catch (e: Exception) {
                logger.log(Level.WARNING, "写入播放日志失败", e)
            }
        }
    }

    private fun installLogFileHandler() {
        if (logFileHandlerInstalled) return
        // 仅初始化日志文件写入器（懒加载），不再接管控制台输出，避免递归
        logFileWriter?.flush()
        logFileHandlerInstalled = true
        logger.info("播放日志将写入 ${File(dataFolder, "player.log").path}")
    }

    private fun isPlaybackLogMessage(msg: String): Boolean {
        return msg.contains("播放独立") || msg.contains("播放房间") || msg.contains("播放预设")
                || msg.contains("已创建独立的资源包") || msg.contains("已创建合并的资源包")
                || msg.contains("已清理临时资源包") || msg.contains("自动识别音乐文件")
                || msg.contains("已从音乐文件夹") || msg.contains("播放音乐")
    }

    // ===================== 语言文件 =====================

    private fun loadLanguageFile() {
        val language = config.getString("language", "zh")
        val langFileName = "lang-$language.yml"
        val langFile = File(dataFolder, langFileName)
        if (!langFile.exists()) {
            saveResource(langFileName, false)
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile)
        logger.info("已加载语言文件: $langFileName")
    }

    fun getLangMessage(key: String): String? {
        langConfig?.getString(key)?.let { return it }
        return config.getString(key)
    }

    // ===================== HTTP 服务器与资源包 =====================

    private fun initializeHttpServerAndGenerator() {
        val configuredPublicAddress = config.getString("httpServer.publicAddress", "")!!
        val httpPort = config.getInt("httpServer.port", 8123)
        val servePath = config.getString("httpServer.servePath", "musicpacks")!!

        if (configuredPublicAddress.isEmpty() && config.getBoolean("httpServer.enabled")) {
            logger.warning("HTTP 服务器的 'publicAddress' 未在 config.yml 中配置! 音乐资源包可能无法从外部访问。")
        }

        val tempPackStorageDir = File(dataFolder, config.getString("httpServer.tempDirectory", "temp_packs"))
        if (!tempPackStorageDir.exists()) {
            if (!tempPackStorageDir.mkdirs()) {
                logger.severe("无法创建 HTTP 服务器的临时目录: ${tempPackStorageDir.absolutePath}")
                httpFileServer = null
                resourcePackGenerator = null
                return
            }
        }

        resourcePackGenerator = ResourcePackGenerator(this, tempPackStorageDir,
                config.getLong("httpServer.maxDownloadSizeBytes", 0),
                if (useMergedPackLogic) basePackFile else null)
        httpFileServer = HttpFileServer(this, servePath, tempPackStorageDir)
        httpFileServer!!.start(httpPort)

        if (!httpFileServer!!.isRunning && config.getBoolean("httpServer.enabled")) {
            logger.severe("内置 HTTP 服务器未能启动！在线播放功能将不可用。")
            httpFileServer = null
        } else if (httpFileServer != null && httpFileServer!!.isRunning) {
            logger.info("HTTP 服务器已启动。")
        }
    }

    // ===================== 配置加载 =====================

    private fun loadConfiguration() {
        reloadConfig()
        val config = config

        val mergingEnabledByConfig = config.getBoolean("baseResourcePack.enableMerging", false)
        basePackFileNameConfig = config.getString("baseResourcePack.fileName", "base_pack.zip")!!
        basePackFile = File(dataFolder, basePackFileNameConfig)

        if (mergingEnabledByConfig && basePackFile!!.exists()) {
            try {
                FileInputStream(basePackFile).use { fis ->
                    basePackSha1 = DigestUtils.sha1Hex(fis)
                }
                useMergedPackLogic = true
                logger.info("基础资源包 '$basePackFileNameConfig' 加载成功。SHA-1: $basePackSha1. 将使用合并模式。")
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "无法读取基础资源包 '$basePackFileNameConfig' 或计算其SHA-1值。将回退到独立音乐包模式。", e)
                basePackFile = null
                basePackSha1 = null
                useMergedPackLogic = false
            }
        } else {
            if (mergingEnabledByConfig && !basePackFile!!.exists()) {
                sendConfigMsg(server.consoleSender, "messages.general.basePackMissing")
            }
            useMergedPackLogic = false
            basePackFile = null
            basePackSha1 = null
        }

        basePackPromptMessage = ChatColor.translateAlternateColorCodes('&', config.getString("baseResourcePack.promptMessage", "§6加载音乐资源包...")!!)
        originalPackPromptMessage = ChatColor.translateAlternateColorCodes('&', config.getString("baseResourcePack.originalPackPromptMessage", "§6恢复服务器默认资源包...")!!)

        loadMusicFolderSongs(config)
        logger.info("已加载 ${presetSongsList.size} 首音乐。")
    }

    // ===================== 音乐文件夹扫描 =====================

    private fun loadMusicFolderSongs(config: FileConfiguration) {
        presetSongsList.removeIf { it.url != null && it.url.startsWith("file://") }

        if (!config.getBoolean("musicFolder.enabled", true)) return

        val folderPath = config.getString("musicFolder.path", "music")!!
        val recursive = config.getBoolean("musicFolder.recursive", true)
        val itemMaterialName = config.getString("musicFolder.item", "MUSIC_DISC_CAT")!!.uppercase()
        var material = Material.getMaterial(itemMaterialName)
        if (material == null) {
            logger.warning("musicFolder.item '$itemMaterialName' 无效。将使用默认的 MUSIC_DISC_CAT。")
            material = Material.MUSIC_DISC_CAT
        }
        val lore = config.getStringList("musicFolder.lore")

        val musicFolder = File(dataFolder, folderPath)
        if (!musicFolder.exists()) {
            if (musicFolder.mkdirs()) {
                logger.info("音乐文件夹不存在，已自动创建: ${musicFolder.absolutePath}")
            } else {
                logger.warning("无法创建音乐文件夹: ${musicFolder.absolutePath}")
            }
            return
        }
        if (!musicFolder.isDirectory) {
            logger.warning("musicFolder.path 指向的不是文件夹: ${musicFolder.absolutePath}")
            return
        }

        val oggFiles = ArrayList<File>()
        collectOggFiles(musicFolder, oggFiles, recursive)
        oggFiles.sortBy { it.name }

        var added = 0
        for (oggFile in oggFiles) {
            val fileName = oggFile.name
            val songName = fileName.substring(0, fileName.length - 4).replace('_', ' ')
            val fileUri = oggFile.toURI().toString()
            // 专辑名（子文件夹相对路径），根目录为 null
            val album: String?
            val parentDir = oggFile.parentFile
            if (parentDir != null && parentDir != musicFolder) {
                var rawAlbum = musicFolder.toURI().relativize(parentDir.toURI()).path
                while (rawAlbum.endsWith("/") || rawAlbum.endsWith("\\")) {
                    rawAlbum = rawAlbum.substring(0, rawAlbum.length - 1)
                }
                album = rawAlbum
            } else {
                album = null
            }
            val albumLabel = album ?: "默认"
            val songLore = lore.map { line ->
                line.replace("<name>", fileName).replace("<url>", fileUri).replace("<album>", albumLabel)
            }
            presetSongsList.add(PresetSong(songName, fileUri, material, songLore, album))
            added++
            logPlayback("自动识别音乐文件: ${oggFile.absolutePath} -> 歌曲名: '$songName'" + (if (album != null) " (专辑: $album)" else ""))
        }
        logPlayback("已从音乐文件夹 (${musicFolder.absolutePath}) 自动识别 $added 首 .ogg 音乐文件。")
    }

    fun getAlbums(): List<String> {
        return presetSongsList.mapNotNull { it.album }.distinct().sorted()
    }

    fun getSongsByAlbum(album: String?): List<PresetSong> {
        return presetSongsList.filter { it.album == album }
    }

    // ===================== 音乐搜索/下载支持 =====================

    /** 音乐文件夹（下载的音乐存放处） */
    fun getMusicFolder(): File? {
        val folderPath = config.getString("musicFolder.path", "music") ?: "music"
        val folder = File(dataFolder, folderPath)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun setPlayerSearchResults(playerId: UUID, results: List<MusicSearchManager.SearchResult>) {
        playerSearchResults[playerId] = results
    }

    fun getPlayerSearchResults(playerId: UUID): List<MusicSearchManager.SearchResult> {
        return playerSearchResults[playerId] ?: emptyList()
    }

    private fun collectOggFiles(folder: File, result: MutableList<File>, recursive: Boolean) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (recursive) collectOggFiles(file, result, true)
            } else if (file.name.lowercase().endsWith(".ogg")) {
                result.add(file)
            }
        }
    }

    fun rescanMusicFolder(): Int {
        val before = presetSongsList.size
        loadMusicFolderSongs(config)
        return presetSongsList.size - before
    }

    // ===================== 访问器 =====================

    fun getPresetSongs(): List<PresetSong> = java.util.Collections.unmodifiableList(presetSongsList)
    fun shouldUseMergedPackLogic(): Boolean = useMergedPackLogic
    fun getBasePackFile(): File? = if (useMergedPackLogic) basePackFile else null
    fun getMusicPackPromptMessage(): String = basePackPromptMessage
    fun getOriginalPackPromptMessage(): String = if (useMergedPackLogic) originalPackPromptMessage else ""
    fun isPrewarmedPackFile(packFileName: String?): Boolean = false // 预热已移除

    fun createStableIdentifier(input: String): String = DigestUtils.sha1Hex(input).substring(0, 16)

    // ===================== 音量 =====================

    fun getPlayerVolume(playerId: UUID): Float = playerVolumes[playerId] ?: 1.0f

    fun setPlayerVolume(playerId: UUID, volume: Float): Float {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        playerVolumes[playerId] = clamped
        return clamped
    }

    fun adjustPlayerVolume(playerId: UUID, delta: Float): Float =
            setPlayerVolume(playerId, getPlayerVolume(playerId) + delta)

    // ===================== 循环 =====================

    fun isPlayerLooping(playerId: UUID): Boolean = playerLoopStatus[playerId] ?: false

    fun setPlayerLoopStatus(playerId: UUID, looping: Boolean) {
        playerLoopStatus[playerId] = looping
    }

    fun setPlayerLoopingSound(playerId: UUID, soundEventName: String) {
        playerLoopingSound[playerId] = soundEventName
    }

    fun getPlayerLoopingSound(playerId: UUID): String? = playerLoopingSound[playerId]

    fun cancelPlayerLoop(playerId: UUID) {
        playerLoopTasks.remove(playerId)?.cancel()
        playerLoopingSound.remove(playerId)
    }

    fun scheduleLoopTask(playerId: UUID, intervalTicks: Long) {
        cancelPlayerLoop(playerId)
        if (!isPlayerLooping(playerId)) return
        val task = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            val p = Bukkit.getPlayer(playerId)
            val sound = getPlayerLoopingSound(playerId)
            if (p != null && p.isOnline && sound != null) {
                p.stopSound(sound, SoundCategory.MUSIC)
                p.playSound(p.location, sound, SoundCategory.MUSIC, getPlayerVolume(playerId), 1.0f)
            }
        }, intervalTicks, intervalTicks)
        playerLoopTasks[playerId] = task
    }

    // ===================== 待播放声音 =====================

    fun addPendingSingleUserSound(playerId: UUID, soundInfo: PendingOnlineSound) {
        pendingSingleUserSounds[playerId] = soundInfo
        playerPendingPackType[playerId] = "singleUser:${soundInfo.packFileName}"
    }

    fun getPendingSingleUserSound(playerId: UUID): PendingOnlineSound? = pendingSingleUserSounds[playerId]

    fun clearPendingSingleUserSound(playerId: UUID) {
        pendingSingleUserSounds.remove(playerId)
    }

    fun markPlayerPendingRoomPack(playerId: UUID, roomId: String, tempPackFileName: String) {
        playerPendingPackType[playerId] = "room:$roomId:$tempPackFileName"
    }

    fun getPlayerPendingPackType(playerId: UUID): String? = playerPendingPackType[playerId]

    fun clearPlayerPendingPackType(playerId: UUID) {
        playerPendingPackType.remove(playerId)
    }

    fun setPlayerCurrentMusicPack(playerId: UUID, tempPackFileName: String?) {
        if (tempPackFileName == null) playerCurrentMusicPackFile.remove(playerId)
        else playerCurrentMusicPackFile[playerId] = tempPackFileName
    }

    fun getPlayerCurrentMusicPackFile(playerId: UUID): String? = playerCurrentMusicPackFile[playerId]

    fun clearPlayerCurrentMusicPack(playerId: UUID) {
        playerCurrentMusicPackFile.remove(playerId)
    }

    // ===================== 音乐房间 =====================

    fun createMusicRoom(creator: Player, musicUrl: String, description: String): MusicRoom {
        val roomId = "mroom_" + UUID.randomUUID().toString().substring(0, 6)
        val coloredDescription = ChatColor.translateAlternateColorCodes('&', description)
        val room = MusicRoom(roomId, creator, musicUrl, coloredDescription)
        activeMusicRooms[roomId] = room
        logger.info("音乐室已创建: $roomId 由 ${creator.name} 创建，URL: $musicUrl")
        return room
    }

    fun getMusicRoom(roomId: String): MusicRoom? = activeMusicRooms[roomId]

    fun findMusicRoomByCreatorName(creatorName: String): MusicRoom? {
        return activeMusicRooms.values.firstOrNull { it.creator.name.equals(creatorName, ignoreCase = true) }
    }

    fun removeMusicRoom(roomId: String) {
        val room = activeMusicRooms.remove(roomId) ?: return
        logger.info("音乐室 $roomId (${room.description}§r) 已被移除。")
        val roomSpecificTempPack = room.packFileName

        for (member in HashSet(room.members)) {
            if (member.isOnline) {
                if (member != room.creator) {
                    sendConfigMsg(member, "messages.bf.disbandroom.notifyMemberRoomDisbanded", "room_description", room.description)
                }
                val playerCurrentTempPack = getPlayerCurrentMusicPackFile(member.uniqueId)
                if (roomSpecificTempPack != null && roomSpecificTempPack == playerCurrentTempPack) {
                    clearPlayerCurrentMusicPack(member.uniqueId)
                    if (shouldUseMergedPackLogic()) sendOriginalBasePackToPlayer(member)
                } else if (getPlayerPendingPackType(member.uniqueId)?.endsWith(":$roomSpecificTempPack") == true) {
                    clearPlayerPendingPackType(member.uniqueId)
                    if (shouldUseMergedPackLogic()) sendOriginalBasePackToPlayer(member)
                }
            }
        }
        if (roomSpecificTempPack != null && resourcePackGenerator != null && !isPrewarmedPackFile(roomSpecificTempPack)) {
            resourcePackGenerator!!.cleanupPack(roomSpecificTempPack)
        }
        playerPendingPackType.entries.removeIf { it.value.endsWith(":$roomSpecificTempPack") }
    }

    fun getActiveMusicRoomsView(): Collection<MusicRoom> = java.util.Collections.unmodifiableCollection(activeMusicRooms.values)

    private fun startRoomCleanupTask() {
        val interval = 20L * 60
        val inactiveCleanupDelayMillis = config.getLong("httpServer.musicRoomInactiveCleanupDelaySeconds", 600) * 1000L
        roomCleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            val currentTime = System.currentTimeMillis()
            val roomsToClose = ArrayList<String>()
            activeMusicRooms.forEach { (id, room) ->
                if (room.status != MusicRoom.RoomStatus.CLOSING && room.status != MusicRoom.RoomStatus.CLOSED) {
                    if (room.isEmpty() && (currentTime - room.lastActivityTime > inactiveCleanupDelayMillis)) {
                        logger.info("音乐室 ${room.roomId} (${room.description}§r) 空闲且不活跃，准备关闭。")
                        roomsToClose.add(id)
                    }
                }
            }
            if (roomsToClose.isNotEmpty()) {
                Bukkit.getScheduler().runTask(this, Runnable {
                    for (roomIdToClose in roomsToClose) {
                        val currentRoom = getMusicRoom(roomIdToClose)
                        if (currentRoom != null && currentRoom.status != MusicRoom.RoomStatus.CLOSING && currentRoom.status != MusicRoom.RoomStatus.CLOSED) {
                            currentRoom.status = MusicRoom.RoomStatus.CLOSING
                            sendConfigMsg(server.consoleSender, "musicRoomClosedMessage", "description", currentRoom.description)
                            Bukkit.getScheduler().runTaskLater(this, Runnable { removeMusicRoom(currentRoom.roomId) }, 20L)
                        }
                    }
                })
            }
        }, interval, interval)
    }

    // ===================== 资源包发送 =====================

    fun sendOriginalBasePackToPlayer(player: Player) {
        if (!useMergedPackLogic || basePackFile == null || !basePackFile!!.exists() || basePackSha1 == null) {
            if (useMergedPackLogic) {
                logger.warning("尝试为 ${player.name} 发送原始基础资源包，但文件或SHA-1未准备好/未启用合并模式。")
                sendConfigMsg(player, "messages.general.basePackReapplyFailed")
            }
            return
        }

        val servedBasePack = File(httpFileServer!!.serveDirectory, basePackFileNameConfig)
        if (!servedBasePack.exists() || !compareFileSha1(servedBasePack, basePackSha1!!)) {
            try {
                Files.copy(basePackFile!!.toPath(), servedBasePack.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "无法将原始基础包复制到服务目录以供发送。", e)
                sendConfigMsg(player, "messages.general.basePackReapplyFailed")
                return
            }
        }
        val packUrl = httpFileServer!!.getFileUrl(config.getString("httpServer.publicAddress"), config.getInt("httpServer.port"), basePackFileNameConfig)
        try {
            val sha1Bytes = Hex.decodeHex(basePackSha1!!)
            val packId = UUID.nameUUIDFromBytes(("playermusic-base-$basePackFileNameConfig").toByteArray(StandardCharsets.UTF_8))
            setPlayerPackRequestId(player.uniqueId, packId)
            player.setResourcePack(packId, packUrl, sha1Bytes, legacyToComponent(getOriginalPackPromptMessage()), true)
            logger.info("正在向玩家 ${player.name} 发送原始基础资源包: $packUrl")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "无法解码原始基础资源包的SHA-1哈希值: $basePackSha1", e)
            sendConfigMsg(player, "messages.general.basePackReapplyFailed")
        }
    }

    fun setPlayerPackRequestId(playerId: UUID, packRequestId: UUID) {
        playerPackRequestIds[playerId] = packRequestId
    }

    fun getPlayerPackRequestId(playerId: UUID): UUID? = playerPackRequestIds[playerId]

    fun clearPlayerPackRequestId(playerId: UUID) {
        playerPackRequestIds.remove(playerId)
    }

    fun legacyToComponent(legacyText: String?): Component {
        if (legacyText == null) return Component.empty()
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText)
    }

    private fun compareFileSha1(file: File, expectedSha1: String): Boolean {
        if (!file.exists() || expectedSha1.isEmpty()) return false
        return try {
            FileInputStream(file).use { fis ->
                DigestUtils.sha1Hex(fis).equals(expectedSha1, ignoreCase = true)
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "比较文件SHA1时出错: ${file.name}", e)
            false
        }
    }

    // ===================== 消息发送 =====================

    private fun replacePlaceholders(template: String, vararg substitutions: String): String {
        if (substitutions.size % 2 != 0) {
            logger.warning("替换占位符时提供的替换项数量无效。必须是键值对。模板: $template")
            return template
        }
        var result = template
        var i = 0
        while (i < substitutions.size) {
            val key = "<${substitutions[i]}>"
            val value = substitutions[i + 1] ?: ""
            result = result.replace(key, value)
            i += 2
        }
        return result
    }

    fun sendLegacyMsg(sender: CommandSender, template: String, vararg substitutions: String) {
        if (template.isEmpty()) {
            sender.sendMessage(ChatColor.RED.toString() + "错误: 插件尝试发送空消息模板。")
            logger.warning("尝试向 ${sender.name} 发送空消息模板。")
            return
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', replacePlaceholders(template, *substitutions)))
    }

    fun sendConfigMsg(sender: CommandSender, configKey: String, vararg substitutions: String) {
        val template = getLangMessage(configKey)
        if (template != null && template.isNotEmpty()) {
            sendLegacyMsg(sender, template, *substitutions)
        } else {
            sendLegacyMsg(sender, ChatColor.RED.toString() + "错误: 缺少配置消息，键: $configKey")
            logger.warning("配置中缺少消息键: $configKey")
        }
    }
}
