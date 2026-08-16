package icu.epochcraft

import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.SoundCategory
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.logging.Level

/**
 * 命令处理：/bf 主命令及补全（Kotlin 半重写）
 */
class MusicCommands(private val plugin: MusicPlayerPlugin) : CommandExecutor, TabCompleter {

    enum class PlaybackContextType { SINGLE, ROOM }

    private fun canExecute(sender: CommandSender, permissionKey: String, playerOnly: Boolean): Boolean {
        if (playerOnly && sender !is Player) {
            plugin.sendConfigMsg(sender, "messages.general.playerOnly")
            return false
        }
        val isConsole = sender is org.bukkit.command.ConsoleCommandSender
        if (!sender.hasPermission(permissionKey) &&
            !(isConsole && (permissionKey == "playermusic.reload" || permissionKey == "playermusic.info"))) {
            plugin.sendConfigMsg(sender, "messages.general.noPermission")
            return false
        }
        return true
    }

    private fun handleLeavePreviousRoom(player: Player, newRoomToJoin: MusicRoom?) {
        plugin.getActiveMusicRoomsView().firstOrNull { r -> r.isMember(player) && (newRoomToJoin == null || r != newRoomToJoin) }?.let { otherRoom ->
                otherRoom.removeMember(player)
                plugin.sendConfigMsg(player, "messages.bf.join.leftOtherRoom", "other_room_description", otherRoom.description)
                val playerCurrentTempPack = plugin.getPlayerCurrentMusicPackFile(player.uniqueId)
                if (otherRoom.packFileName != null && otherRoom.packFileName == playerCurrentTempPack && !plugin.isPrewarmedPackFile(playerCurrentTempPack)) {
                    plugin.clearPlayerCurrentMusicPack(player.uniqueId)
                    if (plugin.shouldUseMergedPackLogic()) plugin.sendOriginalBasePackToPlayer(player)
                }
            }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("bf", ignoreCase = true)) {
            if (args.isEmpty()) {
                plugin.sendConfigMsg(sender, "messages.bf.usage")
                return true
            }
            when (args[0].lowercase()) {
                "play" -> {
                    if (!canExecute(sender, "playermusic.play", true)) return true
                    val playerForPlay = sender as? Player ?: return true
                    if (args.size < 2) {
                        plugin.sendConfigMsg(playerForPlay, "messages.bf.play.usage")
                        return true
                    }
                    val songIdentifier = args[1]
                    val songToPlay = findFolderSong(songIdentifier)
                    if (songToPlay != null) {
                        handlePlay(playerForPlay, songToPlay.url, PlaybackContextType.SINGLE, null, songToPlay)
                    } else {
                        plugin.sendConfigMsg(playerForPlay, "messages.bf.play.notFound", "song", songIdentifier)
                    }
                    return true
                }

                "random" -> {
                    if (!canExecute(sender, "playermusic.play", true)) return true
                    val playerForRandom = sender as? Player ?: return true
                    playRandomSong(playerForRandom)
                    return true
                }

                "loop" -> {
                    if (!canExecute(sender, "playermusic.play", true)) return true
                    val playerForLoop = sender as? Player ?: return true
                    val newLoopState = !plugin.isPlayerLooping(playerForLoop.uniqueId)
                    plugin.setPlayerLoopStatus(playerForLoop.uniqueId, newLoopState)
                    if (!newLoopState) {
                        plugin.cancelPlayerLoop(playerForLoop.uniqueId)
                    }
                    plugin.sendConfigMsg(playerForLoop, "messages.bf.loop.toggled", "state", if (newLoopState) "§a开启" else "§c关闭")
                    return true
                }

                "volume" -> {
                    if (!canExecute(sender, "playermusic.play", true)) return true
                    val playerForVolume = sender as? Player ?: return true
                    if (args.size < 2) {
                        plugin.sendConfigMsg(playerForVolume, "messages.bf.volume.usage")
                        return true
                    }
                    try {
                        val vol = args[1].toInt()
                        val newVol = plugin.setPlayerVolume(playerForVolume.uniqueId, vol / 100f)
                        plugin.sendConfigMsg(playerForVolume, "messages.bf.volume.set", "percent", Math.round(newVol * 100).toString())
                    } catch (e: NumberFormatException) {
                        plugin.sendConfigMsg(playerForVolume, "messages.bf.volume.invalid")
                    }
                    return true
                }

                "search" -> {
                    if (!canExecute(sender, "playermusic.search", true)) return true
                    val playerForSearch = sender as? Player ?: return true
                    if (args.size < 2) {
                        plugin.sendConfigMsg(playerForSearch, "messages.bf.search.usage")
                        return true
                    }
                    val query = args.drop(1).joinToString(" ")
                    doSearch(playerForSearch, query)
                    return true
                }

                "download" -> {
                    if (!canExecute(sender, "playermusic.download", true)) return true
                    val playerForDownload = sender as? Player ?: return true
                    if (args.size < 2) {
                        plugin.sendConfigMsg(playerForDownload, "messages.bf.download.usage")
                        return true
                    }
                    val input = args[1]
                    doDownload(playerForDownload, input)
                    return true
                }

                "stop" -> {
                    if (!canExecute(sender, "playermusic.stop", true)) return true
                    val playerForStop = sender as? Player ?: return true
                    val roomPlayerIsIn = plugin.getActiveMusicRoomsView().firstOrNull { it.isMember(playerForStop) }
                    var stoppedSomething = false

                    if (roomPlayerIsIn != null) {
                        val roomSoundEventBase = plugin.httpFileServer!!.servePathPrefix + ".room." + roomPlayerIsIn.roomId
                        if (roomPlayerIsIn.creator == playerForStop) {
                            roomPlayerIsIn.stopPlaybackForAll()
                            plugin.sendConfigMsg(playerForStop, "messages.bf.stop.roomStopped", "room_description", roomPlayerIsIn.description)
                            val roomTempPack = roomPlayerIsIn.packFileName
                            if (roomTempPack != null && !plugin.isPrewarmedPackFile(roomTempPack)) {
                                for (member in HashSet(roomPlayerIsIn.members)) {
                                    if (member.isOnline) {
                                        val memberCurrentPack = plugin.getPlayerCurrentMusicPackFile(member.uniqueId)
                                        if (roomTempPack == memberCurrentPack) {
                                            plugin.clearPlayerCurrentMusicPack(member.uniqueId)
                                            if (plugin.shouldUseMergedPackLogic()) plugin.sendOriginalBasePackToPlayer(member)
                                        }
                                    }
                                }
                            }
                            stoppedSomething = true
                        } else {
                            playerForStop.stopSound(roomSoundEventBase, SoundCategory.MUSIC)
                            val memberCurrentPack = plugin.getPlayerCurrentMusicPackFile(playerForStop.uniqueId)
                            if (roomPlayerIsIn.packFileName != null && roomPlayerIsIn.packFileName == memberCurrentPack && !plugin.isPrewarmedPackFile(memberCurrentPack)) {
                                plugin.clearPlayerCurrentMusicPack(playerForStop.uniqueId)
                                if (plugin.shouldUseMergedPackLogic()) plugin.sendOriginalBasePackToPlayer(playerForStop)
                            }
                            plugin.sendConfigMsg(playerForStop, "messages.bf.stop.stoppedForSelfInRoom", "room_description", roomPlayerIsIn.description)
                            stoppedSomething = true
                        }
                    } else {
                        val pendingSoundInfo = plugin.getPendingSingleUserSound(playerForStop.uniqueId)
                        val currentTempPack = plugin.getPlayerCurrentMusicPackFile(playerForStop.uniqueId)

                        if (pendingSoundInfo != null) {
                            playerForStop.stopSound(pendingSoundInfo.soundEventName, SoundCategory.MUSIC)
                            plugin.logger.info("停止独立音乐: ${pendingSoundInfo.soundEventName} for ${playerForStop.name}")
                            if (pendingSoundInfo.packFileName != null && plugin.resourcePackGenerator != null && !plugin.isPrewarmedPackFile(pendingSoundInfo.packFileName)) {
                                plugin.resourcePackGenerator!!.cleanupPack(pendingSoundInfo.packFileName)
                            }
                            plugin.clearPendingSingleUserSound(playerForStop.uniqueId)
                            stoppedSomething = true
                        }
                        if (currentTempPack != null && !plugin.isPrewarmedPackFile(currentTempPack)) {
                            val isRoomPack = plugin.getActiveMusicRoomsView().any { currentTempPack == it.packFileName }
                            if (!isRoomPack) {
                                plugin.resourcePackGenerator?.cleanupPack(currentTempPack)
                                plugin.logger.info("清理当前玩家独立音乐包: $currentTempPack for ${playerForStop.name}")
                                if (!stoppedSomething) stoppedSomething = true
                            }
                        }

                        if (stoppedSomething) {
                            plugin.clearPlayerCurrentMusicPack(playerForStop.uniqueId)
                            plugin.clearPlayerPendingPackType(playerForStop.uniqueId)
                            plugin.cancelPlayerLoop(playerForStop.uniqueId)
                            if (plugin.shouldUseMergedPackLogic()) plugin.sendOriginalBasePackToPlayer(playerForStop)
                            plugin.sendConfigMsg(playerForStop, "messages.bf.stop.stoppedForSelf")
                        }
                    }

                    if (!stoppedSomething) {
                        plugin.sendConfigMsg(playerForStop, "messages.bf.stop.notPlaying")
                    }
                    return true
                }

                "gui" -> {
                    if (!canExecute(sender, "playermusic.gui", true)) return true
                    val playerForGui = sender as? Player ?: return true
                    MusicGUI(plugin).open(playerForGui)
                    return true
                }

                "createroom" -> {
                    if (!canExecute(sender, "playermusic.createroom", true)) return true
                    val creator = sender as? Player ?: return true
                    if (args.size < 3) {
                        plugin.sendConfigMsg(creator, "messages.bf.createroom.usage")
                        return true
                    }
                    val roomSongIdentifier = args[1]
                    val roomSong = findFolderSong(roomSongIdentifier)
                    if (roomSong == null) {
                        plugin.sendConfigMsg(creator, "messages.bf.play.notFound", "song", roomSongIdentifier)
                        return true
                    }
                    val roomMusicUrl = roomSong.url
                    val descriptionInput = args.drop(2).joinToString(" ").trim()
                    if (descriptionInput.isEmpty()) {
                        plugin.sendConfigMsg(creator, "messages.bf.createroom.noDescription")
                        return true
                    }
                    val existingRoomByCreator = plugin.getActiveMusicRoomsView().firstOrNull { it.creator == creator }
                    if (existingRoomByCreator != null) {
                        plugin.sendConfigMsg(creator, "messages.bf.createroom.alreadyCreated", "room_description", existingRoomByCreator.description)
                        return true
                    }
                    val newRoom = plugin.createMusicRoom(creator, roomMusicUrl, descriptionInput)
                    plugin.sendConfigMsg(creator, "messages.bf.createroom.successWithStartHint", "description", newRoom.description, "url", roomSong.name)
                    for (onlinePlayer in Bukkit.getOnlinePlayers()) {
                        if (onlinePlayer != creator) {
                            plugin.sendConfigMsg(onlinePlayer, "messages.bf.createroom.broadcast", "creator_name", creator.name, "description", newRoom.description)
                        }
                    }
                    return true
                }

                "join" -> {
                    if (!canExecute(sender, "playermusic.joinroom", true)) return true
                    val joiner = sender as? Player ?: return true
                    if (args.size < 2) {
                        plugin.sendConfigMsg(joiner, "messages.bf.join.usage")
                        return true
                    }
                    val creatorName = args[1]
                    val targetRoom = plugin.findMusicRoomByCreatorName(creatorName)
                    if (targetRoom == null) {
                        plugin.sendConfigMsg(joiner, "messages.bf.join.roomNotFound", "creator", creatorName)
                        return true
                    }
                    if (targetRoom.creator == joiner) {
                        plugin.sendConfigMsg(joiner, "messages.bf.join.alreadyCreator", "room_description", targetRoom.description)
                        return true
                    }
                    if (targetRoom.isMember(joiner)) {
                        plugin.sendConfigMsg(joiner, "messages.bf.join.alreadyMember", "room_description", targetRoom.description)
                        return true
                    }
                    handleLeavePreviousRoom(joiner, targetRoom)
                    targetRoom.addMember(joiner)
                    plugin.sendConfigMsg(joiner, "messages.bf.join.successNoAutoPlay", "room_description", targetRoom.description)
                    if (targetRoom.status == MusicRoom.RoomStatus.PLAYING && targetRoom.packFileName != null) {
                        handlePlay(joiner, targetRoom.musicUrl, PlaybackContextType.ROOM, targetRoom, null)
                    }
                    return true
                }

                "start" -> {
                    if (!canExecute(sender, "playermusic.room.start", true)) return true
                    val roomStarter = sender as? Player ?: return true
                    val roomToStart = plugin.getActiveMusicRoomsView().firstOrNull { it.creator == roomStarter }
                    if (roomToStart == null) {
                        plugin.sendConfigMsg(roomStarter, "messages.bf.room.start.notRoomCreator")
                        return true
                    }
                    if (roomToStart.musicUrl.isEmpty()) {
                        plugin.sendConfigMsg(roomStarter, "messages.bf.room.start.noMusicUrl", "room_description", roomToStart.description)
                        return true
                    }
                    roomToStart.playRequestActive = true
                    val memberNotification = plugin.getLangMessage("messages.bf.room.start.memberStartNotification")
                    for (member in ArrayList(roomToStart.members)) {
                        if (member.isOnline) {
                            handlePlay(member, roomToStart.musicUrl, PlaybackContextType.ROOM, roomToStart, null)
                            if (member != roomStarter && memberNotification != null && memberNotification.isNotEmpty()) {
                                plugin.sendLegacyMsg(member, memberNotification, "room_description", roomToStart.description, "creator_name", roomToStart.creator.name)
                            }
                        }
                    }
                    plugin.sendConfigMsg(roomStarter, "messages.bf.room.start.started", "room_description", roomToStart.description)
                    return true
                }

                "roomplay" -> {
                    if (!canExecute(sender, "playermusic.room.roomplay", true)) return true
                    val roomPlayRequester = sender as? Player ?: return true
                    val ownRoom = plugin.getActiveMusicRoomsView().firstOrNull { it.creator == roomPlayRequester }
                    if (ownRoom == null) {
                        plugin.sendConfigMsg(roomPlayRequester, "messages.bf.room.play.notRoomCreator")
                        return true
                    }
                    if (args.size < 2) {
                        plugin.sendConfigMsg(roomPlayRequester, "messages.bf.room.play.usage")
                        return true
                    }
                    val newRoomSong = findFolderSong(args[1])
                    if (newRoomSong == null) {
                        plugin.sendConfigMsg(roomPlayRequester, "messages.bf.play.notFound", "song", args[1])
                        return true
                    }
                    val newMusicUrl = newRoomSong.url
                    if (newMusicUrl.equals(ownRoom.musicUrl, ignoreCase = true)) {
                        plugin.sendConfigMsg(roomPlayRequester, "messages.bf.room.play.urlSame", "room_description", ownRoom.description)
                        return true
                    }
                    if (ownRoom.status == MusicRoom.RoomStatus.PLAYING || ownRoom.playRequestActive) {
                        ownRoom.stopPlaybackForAll()
                        val oldRoomTempPack = ownRoom.packFileName
                        if (oldRoomTempPack != null && !plugin.isPrewarmedPackFile(oldRoomTempPack)) {
                            for (member in HashSet(ownRoom.members)) {
                                if (member.isOnline) {
                                    val memberCurrentPack = plugin.getPlayerCurrentMusicPackFile(member.uniqueId)
                                    if (oldRoomTempPack == memberCurrentPack) {
                                        plugin.clearPlayerCurrentMusicPack(member.uniqueId)
                                        if (plugin.shouldUseMergedPackLogic()) plugin.sendOriginalBasePackToPlayer(member)
                                    }
                                }
                            }
                            plugin.resourcePackGenerator?.cleanupPack(oldRoomTempPack)
                        }
                        ownRoom.packFileName = null
                    }
                    ownRoom.musicUrl = newMusicUrl
                    plugin.sendConfigMsg(roomPlayRequester, "messages.bf.room.play.urlSet", "room_description", ownRoom.description, "url", newRoomSong.name)
                    plugin.sendConfigMsg(roomPlayRequester, "messages.bf.room.play.startHint")
                    return true
                }

                "disbandroom" -> {
                    if (!canExecute(sender, "playermusic.disbandroom", true)) return true
                    val disbandRequester = sender as? Player ?: return true
                    val roomToDisband = plugin.getActiveMusicRoomsView().firstOrNull { it.creator == disbandRequester }
                    if (roomToDisband == null) {
                        plugin.sendConfigMsg(disbandRequester, "messages.bf.disbandroom.notCreatorOrNoRoom")
                        return true
                    }
                    val disbandedRoomDesc = roomToDisband.description
                    val roomSoundEventToStop = plugin.httpFileServer!!.servePathPrefix + ".room." + roomToDisband.roomId
                    for (member in HashSet(roomToDisband.members)) {
                        if (member.isOnline) member.stopSound(roomSoundEventToStop, SoundCategory.MUSIC)
                    }
                    plugin.removeMusicRoom(roomToDisband.roomId)
                    plugin.sendConfigMsg(disbandRequester, "messages.bf.disbandroom.success", "room_description", disbandedRoomDesc)
                    return true
                }

                "reload" -> {
                    if (!canExecute(sender, "playermusic.reload", false)) return true
                    plugin.reloadPluginConfiguration()
                    plugin.sendConfigMsg(sender, "messages.bf.reload.success")
                    return true
                }

                "rescan" -> {
                    if (!canExecute(sender, "playermusic.reload", false)) return true
                    val addedSongs = plugin.rescanMusicFolder()
                    plugin.sendConfigMsg(sender, "messages.bf.rescan.success", "count", addedSongs.toString())
                    return true
                }

                "playlist" -> {
                    if (!canExecute(sender, "playermusic.playlist", true)) return true
                    handlePlaylistCommand(sender as Player, args)
                    return true
                }

                "next" -> {
                    if (!canExecute(sender, "playermusic.play", true)) return true
                    val p = sender as Player
                    val pm = plugin.playlistManager
                    if (pm != null && pm.isPlayingQueue(p.uniqueId)) {
                        pm.playNextInQueue(p)
                    } else {
                        plugin.sendConfigMsg(p, "messages.bf.playlist.notPlaying")
                    }
                    return true
                }

                "stopqueue" -> {
                    if (!canExecute(sender, "playermusic.play", true)) return true
                    val p = sender as Player
                    plugin.playlistManager?.stopQueue(p.uniqueId)
                    plugin.sendConfigMsg(p, "messages.bf.playlist.stopped")
                    return true
                }

                "info" -> {
                    if (!canExecute(sender, "playermusic.info", false)) return true
                    val pdf = plugin.description
                    sender.sendMessage(ChatColor.GOLD.toString() + "--- [" + ChatColor.YELLOW.toString() + "PlayerMusic 信息" + ChatColor.GOLD + "] ---")
                    sender.sendMessage(ChatColor.AQUA.toString() + "作者: " + ChatColor.WHITE.toString() + pdf.authors.joinToString(", "))
                    sender.sendMessage(ChatColor.AQUA.toString() + "版本: " + ChatColor.WHITE.toString() + pdf.version)
                    sender.sendMessage(ChatColor.AQUA.toString() + "描述: " + ChatColor.WHITE.toString() + (pdf.description ?: "N/A"))
                    sender.sendMessage(ChatColor.GOLD.toString() + "-----------------------------")
                    return true
                }

                else -> {
                    plugin.sendConfigMsg(sender, "messages.bf.unknownCommand")
                    return true
                }
            }
        } else if (command.name.equals("internal_join_room", ignoreCase = true)) {
            val playerToJoin = sender as? Player ?: return true
            if (args.isEmpty()) return true
            val roomId = args[0]
            val roomToJoin = plugin.getMusicRoom(roomId)
            if (roomToJoin != null) {
                if (!roomToJoin.isMember(playerToJoin) && roomToJoin.creator != playerToJoin) {
                    handleLeavePreviousRoom(playerToJoin, roomToJoin)
                    roomToJoin.addMember(playerToJoin)
                    plugin.sendConfigMsg(playerToJoin, "messages.bf.join.successNoAutoPlay", "room_description", roomToJoin.description)
                    if (roomToJoin.status == MusicRoom.RoomStatus.PLAYING && roomToJoin.packFileName != null) {
                        handlePlay(playerToJoin, roomToJoin.musicUrl, PlaybackContextType.ROOM, roomToJoin, null)
                    }
                } else {
                    plugin.sendConfigMsg(playerToJoin, "messages.bf.join.alreadyMember", "room_description", roomToJoin.description)
                }
            } else {
                plugin.sendConfigMsg(playerToJoin, "messages.bf.join.internalRoomNotFound")
            }
            return true
        }
        return false
    }

    /**
     * 歌单命令：/bf playlist <add|remove|list|play|loop|clear|all|server>
     */
    private fun handlePlaylistCommand(player: Player, args: Array<out String>) {
        val pm = plugin.playlistManager ?: run {
            plugin.sendConfigMsg(player, "messages.general.httpDisabled")
            return
        }
        val sub = if (args.size >= 2) args[1].lowercase() else "help"

        when (sub) {
            "add" -> {
                if (args.size < 3) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.addUsage")
                    return
                }
                var song: PresetSong? = null
                var displayName = args[2]
                // 1. 若输入是数字，尝试从最近一次搜索结果按序号添加（精确匹配作者）
                val num = args[2].toIntOrNull()
                if (num != null) {
                    val results = plugin.getPlayerSearchResults(player.uniqueId)
                    val hit = results.firstOrNull { it.index == num }
                    if (hit != null && hit.url.isNotEmpty()) {
                        displayName = if (hit.artist.isNotEmpty()) "${hit.artist} - ${hit.name}" else hit.name
                        // 添加搜索结果（URL 可能是网络链接，直接存 URL；播放时用 handlePlay 网络播放）
                        val added = pm.addToPlaylist(player.uniqueId, hit.url)
                        if (added) {
                            plugin.sendConfigMsg(player, "messages.bf.playlist.added", "song", displayName)
                        } else {
                            plugin.sendConfigMsg(player, "messages.bf.playlist.alreadyIn", "song", displayName)
                        }
                        return
                    }
                }
                // 2. 按文件夹歌曲名/序号查找
                song = findFolderSong(args[2])
                if (song == null) {
                    plugin.sendConfigMsg(player, "messages.bf.play.notFound", "song", args[2])
                    return
                }
                displayName = song.name
                val added = pm.addToPlaylist(player.uniqueId, song.url)
                if (added) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.added", "song", displayName)
                } else {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.alreadyIn", "song", displayName)
                }
            }

            "remove" -> {
                if (args.size < 3) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.removeUsage")
                    return
                }
                val idx = args[2].toIntOrNull()
                if (idx == null || !pm.removeFromPlaylist(player.uniqueId, idx)) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.removeFailed")
                    return
                }
                plugin.sendConfigMsg(player, "messages.bf.playlist.removed", "index", args[2])
            }

            "list" -> {
                val list = pm.getPlaylist(player.uniqueId)
                if (list.isEmpty()) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.empty")
                    return
                }
                player.sendMessage(ChatColor.GOLD.toString() + "===== " + ChatColor.YELLOW.toString() + "我的歌单" + ChatColor.GOLD.toString() + " =====")
                list.forEachIndexed { idx, url ->
                    val song = plugin.getPresetSongs().firstOrNull { it.url == url }
                    val name = song?.name ?: "(未知)"
                    player.sendMessage("${ChatColor.GRAY}[${idx + 1}] ${ChatColor.WHITE}$name")
                }
                player.sendMessage(ChatColor.GRAY.toString() + "使用 /bf playlist play 开始播放")
            }

            "clear" -> {
                pm.clearPlaylist(player.uniqueId)
                plugin.sendConfigMsg(player, "messages.bf.playlist.cleared")
            }

            "play" -> {
                val list = pm.getPlaylist(player.uniqueId)
                if (list.isEmpty()) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.empty")
                    return
                }
                pm.startQueue(player, list, false)
                plugin.sendConfigMsg(player, "messages.bf.playlist.playing", "count", list.size.toString())
            }

            "loop" -> {
                val list = pm.getPlaylist(player.uniqueId)
                if (list.isEmpty()) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.empty")
                    return
                }
                pm.startQueue(player, list, true)
                plugin.sendConfigMsg(player, "messages.bf.playlist.looping", "count", list.size.toString())
            }

            "all" -> {
                // 音乐文件夹所有歌顺序播放
                val songs = plugin.getPresetSongs()
                if (songs.isEmpty()) {
                    plugin.sendConfigMsg(player, "messages.general.noSongs")
                    return
                }
                val urls = songs.map { it.url }
                pm.startQueue(player, urls, false)
                plugin.sendConfigMsg(player, "messages.bf.playlist.playingAll", "count", urls.size.toString())
            }

            "server" -> {
                // 服务器共享歌单（config.yml 中 playlist.serverSongs）
                val serverSongs = plugin.config.getStringList("playlist.serverSongs")
                if (serverSongs.isEmpty()) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.serverEmpty")
                    return
                }
                // 解析配置的歌名 → 找到歌曲 URL
                val urls = ArrayList<String>()
                for (name in serverSongs) {
                    val song = findFolderSong(name)
                    if (song != null) urls.add(song.url)
                }
                if (urls.isEmpty()) {
                    plugin.sendConfigMsg(player, "messages.bf.playlist.serverEmpty")
                    return
                }
                pm.startQueue(player, urls, false)
                plugin.sendConfigMsg(player, "messages.bf.playlist.playingServer", "count", urls.size.toString())
            }

            else -> {
                player.sendMessage(ChatColor.GOLD.toString() + "===== " + ChatColor.YELLOW.toString() + "歌单" + ChatColor.GOLD.toString() + " =====")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist add <歌曲名或序号> ${ChatColor.GRAY}- 添加")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist remove <序号> ${ChatColor.GRAY}- 移除")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist list ${ChatColor.GRAY}- 查看歌单")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist play ${ChatColor.GRAY}- 顺序播放歌单")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist loop ${ChatColor.GRAY}- 循环播放歌单")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist all ${ChatColor.GRAY}- 播放整个音乐文件夹")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist server ${ChatColor.GRAY}- 播放服务器共享歌单")
                player.sendMessage("${ChatColor.YELLOW}/bf playlist clear ${ChatColor.GRAY}- 清空歌单")
                player.sendMessage("${ChatColor.GRAY}播放中可用 ${ChatColor.YELLOW}/bf next${ChatColor.GRAY} 切下一首，${ChatColor.YELLOW}/bf stopqueue${ChatColor.GRAY} 停止")
            }
        }
    }

    /** 从音乐文件夹的歌曲列表中按名称或序号查找歌曲 */
    private fun findFolderSong(identifier: String): PresetSong? {
        val songs = plugin.getPresetSongs()
        return songs.firstOrNull { s ->
            val strippedName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s.name))
            strippedName.equals(identifier, ignoreCase = true) ||
                identifier.equals((songs.indexOf(s) + 1).toString(), ignoreCase = true)
        }
    }

    /** 随机播放一首歌曲 */
    private fun playRandomSong(player: Player) {
        val songs = plugin.getPresetSongs()
        if (songs.isEmpty()) {
            plugin.sendConfigMsg(player, "messages.general.noSongs")
            return
        }
        val randomSong = songs[(Math.random() * songs.size).toInt()]
        handlePlay(player, randomSong.url, PlaybackContextType.SINGLE, null, randomSong)
        plugin.sendConfigMsg(player, "messages.bf.play.preparing", "song_name", ChatColor.translateAlternateColorCodes('&', randomSong.name))
    }

    /** 在当前浏览的专辑中随机播放（GUI 用） */
    fun playRandomSongFromAlbum(player: Player, album: String?) {
        val songs = if (album == null) plugin.getPresetSongs() else plugin.getSongsByAlbum(album)
        if (songs.isEmpty()) {
            plugin.sendConfigMsg(player, "messages.general.noSongs")
            return
        }
        val randomSong = songs[(Math.random() * songs.size).toInt()]
        handlePlay(player, randomSong.url, PlaybackContextType.SINGLE, null, randomSong)
        plugin.sendConfigMsg(player, "messages.bf.play.preparing", "song_name", ChatColor.translateAlternateColorCodes('&', randomSong.name))
    }

    // ===================== 音乐搜索 / 下载 =====================

    /** 搜索歌曲（异步），结果存到玩家临时搜索结果并提示用 /bf download <序号> 下载 */
    private fun doSearch(player: Player, query: String) {
        val manager = plugin.musicSearchManager ?: run {
            plugin.sendConfigMsg(player, "messages.general.httpDisabled")
            return
        }
        plugin.sendConfigMsg(player, "messages.bf.search.searching", "query", query)
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val outcome = manager.searchWithOutcome(query, 1)
            val results = outcome.results
            org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                if (results.isEmpty()) {
                    if (outcome.serviceDown) {
                        plugin.sendConfigMsg(player, "messages.bf.search.serviceDown", "query", query)
                    } else {
                        plugin.sendConfigMsg(player, "messages.bf.search.noResult", "query", query)
                    }
                    return@Runnable
                }
                // 存结果到插件（每个玩家最多保留最近一次搜索）
                plugin.setPlayerSearchResults(player.uniqueId, results)
                player.sendMessage("§6===== §e音乐搜索结果: §f$query §6=====")
                results.take(10).forEach { r ->
                    val srcTag = if (r.url.isNotEmpty()) "" else " §c(源不可用)"
                    player.sendMessage("§7[${r.index}] §f${r.name} §7- §b${r.artist}$srcTag")
                }
                player.sendMessage("§7使用 §e/bf download <序号> §7下载并添加到音乐库")
            })
        })
    }

    /** 按序号下载搜索结果（网易云解析 → MP3 → OGG → 放入音乐文件夹） */
    private fun doDownload(player: Player, input: String) {
        val manager = plugin.musicSearchManager ?: run {
            plugin.sendConfigMsg(player, "messages.general.httpDisabled")
            return
        }
        val index = input.toIntOrNull()
        if (index == null) {
            // 直接输入网易云歌曲 ID 解析
            plugin.sendConfigMsg(player, "messages.bf.download.resolving", "id", input)
            doDownloadById(player, input)
            return
        }
        val results = plugin.getPlayerSearchResults(player.uniqueId)
        val result = results.firstOrNull { it.index == index }
        if (result == null) {
            plugin.sendConfigMsg(player, "messages.bf.download.noSearch", "index", input)
            return
        }
        if (result.url.isEmpty()) {
            plugin.sendConfigMsg(player, "messages.bf.download.sourceUnavailable", "name", result.name)
            return
        }
        plugin.sendConfigMsg(player, "messages.bf.download.resolving", "id", result.name)
        doDownloadByUrl(player, result.name, result.url)
    }

    /** 按网易云歌曲 ID 解析下载 */
    private fun doDownloadById(player: Player, songId: String) {
        val manager = plugin.musicSearchManager ?: return
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val info = manager.resolveNetease(songId, "standard")
            org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                if (info == null) {
                    plugin.sendConfigMsg(player, "messages.bf.download.resolveFailed", "id", songId)
                    return@Runnable
                }
                downloadAndAddToLibrary(player, info)
            })
        })
    }

    /** 按 URL 下载（搜索结果直接给 mp3 链接） */
    private fun doDownloadByUrl(player: Player, name: String, url: String) {
        val info = MusicSearchManager.DownloadInfo(name, "", null, url)
        downloadAndAddToLibrary(player, info)
    }

    /** 下载 MP3 → 存入音乐文件夹（播放时自动转 OGG）→ 重新扫描入列 */
    private fun downloadAndAddToLibrary(player: Player, info: MusicSearchManager.DownloadInfo) {
        plugin.sendConfigMsg(player, "messages.bf.download.downloading", "name", info.name)
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val manager = plugin.musicSearchManager ?: return@Runnable
            // 目标文件名：歌手 - 歌名.mp3
            val safeName = (if (info.artist.isNotEmpty()) "${info.artist} - ${info.name}" else info.name)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val musicFolder = plugin.getMusicFolder()
            if (musicFolder == null) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable { plugin.sendConfigMsg(player, "messages.bf.download.failed", "name", info.name) })
                return@Runnable
            }
            val targetMp3 = java.io.File(musicFolder, "$safeName.mp3")
            val ok = manager.downloadMp3(info.url, targetMp3)
            org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                if (ok) {
                    plugin.sendConfigMsg(player, "messages.bf.download.success", "name", safeName)
                    // 异步转换 MP3 → OGG 缓存并重扫，完成后自动播放
                    org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                        plugin.convertMp3InFolderAndRescan(targetMp3)
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                            val song = findFolderSong(safeName)
                            if (song != null) {
                                handlePlay(player, song.url, PlaybackContextType.SINGLE, null, song)
                            }
                        })
                    })
                } else {
                    plugin.sendConfigMsg(player, "messages.bf.download.failed", "name", info.name)
                }
            })
        })
    }

    fun handlePlay(player: Player, url: String, contextType: PlaybackContextType, roomContext: MusicRoom?, presetContext: PresetSong?) {
        if (plugin.resourcePackGenerator == null || plugin.httpFileServer == null || !plugin.httpFileServer!!.isRunning) {
            plugin.sendConfigMsg(player, "messages.general.httpDisabled")
            return
        }
        if (url.isEmpty()) {
            plugin.sendConfigMsg(player, "messages.general.invalidUrl")
            return
        }
        if (plugin.shouldUseMergedPackLogic() && (plugin.getBasePackFile() == null || !plugin.getBasePackFile()!!.exists())) {
            plugin.sendConfigMsg(player, "messages.general.basePackMissing")
            return
        }

        // 播放新歌时取消旧循环，等待新歌加载后按需重建
        if (contextType == PlaybackContextType.SINGLE) {
            plugin.cancelPlayerLoop(player.uniqueId)
        }

        val currentRoomPlayerIsIn = plugin.getActiveMusicRoomsView().firstOrNull { it.isMember(player) }
        if (contextType != PlaybackContextType.ROOM || roomContext == null || roomContext != currentRoomPlayerIsIn) {
            val existingSingleSound = plugin.getPendingSingleUserSound(player.uniqueId)
            if (existingSingleSound != null) {
                player.stopSound(existingSingleSound.soundEventName, SoundCategory.MUSIC)
                if (existingSingleSound.packFileName != null && plugin.resourcePackGenerator != null && !plugin.isPrewarmedPackFile(existingSingleSound.packFileName)) {
                    plugin.resourcePackGenerator!!.cleanupPack(existingSingleSound.packFileName)
                }
                plugin.clearPendingSingleUserSound(player.uniqueId)
                plugin.clearPlayerCurrentMusicPack(player.uniqueId)
            } else {
                val currentPack = plugin.getPlayerCurrentMusicPackFile(player.uniqueId)
                if (currentPack != null && !plugin.isPrewarmedPackFile(currentPack) &&
                    (currentRoomPlayerIsIn == null || currentPack != currentRoomPlayerIsIn.packFileName)) {
                    player.stopSound(SoundCategory.MUSIC)
                    plugin.resourcePackGenerator?.cleanupPack(currentPack)
                    plugin.clearPlayerCurrentMusicPack(player.uniqueId)
                }
            }
        }

        val soundEventName: String
        if (contextType == PlaybackContextType.SINGLE && presetContext != null) {
            soundEventName = plugin.httpFileServer!!.servePathPrefix + ".preset." + plugin.createStableIdentifier(presetContext.url)
            plugin.sendConfigMsg(player, "messages.bf.play.preparing", "song_name", ChatColor.translateAlternateColorCodes('&', presetContext.name))
        } else if (contextType == PlaybackContextType.ROOM && roomContext != null) {
            soundEventName = plugin.httpFileServer!!.servePathPrefix + ".room." + roomContext.roomId
            plugin.sendConfigMsg(player, "messages.bf.room.start.startingMusic", "room_description", roomContext.description)
        } else {
            val randomId = UUID.randomUUID().toString().substring(0, 4)
            val uniquePlayerIdPart = player.uniqueId.toString().substring(0, 8)
            soundEventName = plugin.httpFileServer!!.servePathPrefix + ".single." + uniquePlayerIdPart + "." + randomId
            plugin.sendConfigMsg(player, "messages.bf.play.preparing", "song_name", "音乐")
        }

        plugin.resourcePackGenerator!!.generateAndServePack(player, url, soundEventName, contextType == PlaybackContextType.ROOM, roomContext)
            .thenAccept { packInfo ->
                if (packInfo != null) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        val promptMessage = plugin.getMusicPackPromptMessage()
                        val sha1Bytes: ByteArray
                        try {
                            sha1Bytes = Hex.decodeHex(packInfo.sha1())
                        } catch (e: DecoderException) {
                            plugin.logger.log(Level.SEVERE, "无效的SHA-1哈希值: ${packInfo.sha1()}", e)
                            plugin.sendConfigMsg(player, "messages.playurl.error")
                            roomContext?.playRequestActive = false
                            if (!plugin.isPrewarmedPackFile(packInfo.packFileName())) {
                                plugin.resourcePackGenerator!!.cleanupPack(packInfo.packFileName())
                            }
                            if (plugin.shouldUseMergedPackLogic()) plugin.sendOriginalBasePackToPlayer(player)
                            return@Runnable
                        }
                        val packId = UUID.nameUUIDFromBytes(("playermusic-$soundEventName").toByteArray(StandardCharsets.UTF_8))
                        plugin.setPlayerPackRequestId(player.uniqueId, packId)
                        player.setResourcePack(packId, packInfo.packUrl(), sha1Bytes, plugin.legacyToComponent(promptMessage), true)

                        if (contextType == PlaybackContextType.ROOM && roomContext != null) {
                            plugin.markPlayerPendingRoomPack(player.uniqueId, roomContext.roomId, packInfo.packFileName())
                        } else {
                            plugin.addPendingSingleUserSound(player.uniqueId,
                                MusicPlayerPlugin.PendingOnlineSound(packInfo.packFileName(), soundEventName, packInfo.sha1(), player, packInfo.packUrl()))
                        }
                    })
                } else {
                    plugin.sendConfigMsg(player, "messages.playurl.packCreationFailed")
                    roomContext?.playRequestActive = false
                }
            }
            .exceptionally { ex ->
                plugin.logger.log(Level.WARNING, "处理播放请求时出错 for ${player.name} (URL: $url): ${ex.message}", ex)
                plugin.sendConfigMsg(player, "messages.playurl.error")
                roomContext?.playRequestActive = false
                null
            }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        val completions = ArrayList<String>()
        if (command.name.equals("bf", ignoreCase = true)) {
            when {
                args.size == 1 -> {
                    val input = args[0].lowercase()
                    val subCommands = ArrayList(listOf("play", "search", "download", "random", "loop", "volume", "stop", "gui", "createroom", "join", "start", "roomplay", "disbandroom", "reload", "rescan", "info", "playlist", "next", "stopqueue"))
                    if (sender is Player) {
                        subCommands.removeIf { cmd ->
                            var perm = "playermusic.$cmd"
                            if (cmd == "start" || cmd == "roomplay") perm = "playermusic.room.$cmd"
                            if (cmd == "playlist") perm = "playermusic.playlist"
                            !sender.hasPermission(perm)
                        }
                    } else {
                        subCommands.removeIf { it != "reload" && it != "info" }
                    }
                    addMatchingCompletions(completions, input, *subCommands.toTypedArray())
                }

                args.size == 2 && args[0].equals("playlist", ignoreCase = true) && sender.hasPermission("playermusic.playlist") -> {
                    val input = args[1].lowercase()
                    val listSubs = arrayOf("add", "remove", "list", "play", "loop", "all", "server", "clear")
                    addMatchingCompletions(completions, input, *listSubs)
                }

                args.size == 3 && args[0].equals("playlist", ignoreCase = true) && sender.hasPermission("playermusic.playlist")
                        && (args[1].equals("add", ignoreCase = true) || args[1].equals("remove", ignoreCase = true)) -> {
                    addSongCompletions(completions, args[2].lowercase())
                }

                args.size == 2 -> {
                    val subCommand = args[0].lowercase()
                    val input = args[1].lowercase()
                    when {
                        subCommand == "play" && sender.hasPermission("playermusic.play") -> addSongCompletions(completions, input)
                        subCommand == "join" && sender.hasPermission("playermusic.joinroom") ->
                            plugin.getActiveMusicRoomsView().map { it.creator.name }
                                .filter { it.lowercase().startsWith(input) }.distinct().forEach { completions.add(it) }
                        subCommand == "roomplay" && sender.hasPermission("playermusic.room.roomplay") -> addSongCompletions(completions, input)
                        subCommand == "createroom" && sender.hasPermission("playermusic.createroom") -> addSongCompletions(completions, input)
                    }
                }

                args.size == 3 && args[0].equals("createroom", ignoreCase = true) && sender.hasPermission("playermusic.createroom") ->
                    completions.add("<房间描述>")
            }
        }
        return completions.distinct()
    }

    private fun addSongCompletions(completions: MutableList<String>, input: String) {
        plugin.getPresetSongs().forEachIndexed { index, song ->
            val songIndexStr = (index + 1).toString()
            if (songIndexStr.startsWith(input)) completions.add(songIndexStr)
            val cleanName = (ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', song.name)) ?: "").replace(" ", "_")
            if (cleanName.lowercase().startsWith(input)) completions.add(cleanName)
        }
    }

    private fun addMatchingCompletions(completions: MutableList<String>, input: String, vararg options: String) {
        for (option in options) {
            if (option.lowercase().startsWith(input.lowercase())) completions.add(option)
        }
    }
}
