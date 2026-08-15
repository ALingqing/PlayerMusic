package icu.epochcraft;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.ChatColor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class PlayerResourceListener implements Listener {

    private final MusicPlayerPlugin plugin;
    private final MusicCommands musicCommands;

    public PlayerResourceListener(MusicPlayerPlugin plugin, MusicCommands musicCommands) {
        this.plugin = plugin;
        this.musicCommands = musicCommands;
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();

        // Paper 26.2：资源包事件携带唯一 ID。仅处理本插件最近一次发出的音乐资源包请求，
        // 忽略服务器其他资源包/过期请求，避免事件串扰。
        UUID expectedPackId = plugin.getPlayerPackRequestId(player.getUniqueId());
        if (expectedPackId == null) {
            return;
        }
        try {
            UUID eventPackId = event.getID();
            if (!expectedPackId.equals(eventPackId)) {
                return;
            }
        } catch (Throwable ignored) {
            // 极老版本 API 无 getID() 时的兼容兜底：不拦截
        }

        String pendingPackFullIdentifier = plugin.getPlayerPendingPackType(player.getUniqueId());

        if (pendingPackFullIdentifier == null) {
            return;
        }

        String[] typeParts = pendingPackFullIdentifier.split(":", 3);
        String packTypeOrSoundSource = typeParts[0];
        String tempPackFileName = null;
        String roomIdForRoomType = null;

        if (packTypeOrSoundSource.equals("singleUser") && typeParts.length >= 2) {
            tempPackFileName = typeParts[1];
        } else if (packTypeOrSoundSource.equals("room") && typeParts.length >= 3) {
            roomIdForRoomType = typeParts[1];
            tempPackFileName = typeParts[2];
        } else if (packTypeOrSoundSource.equals("preset") && typeParts.length >= 2){
            tempPackFileName = typeParts[1];
        }


        switch (status) {
            case SUCCESSFULLY_LOADED:
                plugin.sendConfigMsg(player, "messages.resourcePack.status.successfully_loaded");
                if (tempPackFileName != null) { // 只有成功加载了有效的包才设置
                    plugin.setPlayerCurrentMusicPack(player.getUniqueId(), tempPackFileName);
                }

                if (packTypeOrSoundSource.equals("singleUser") || packTypeOrSoundSource.equals("preset")) {
                    MusicPlayerPlugin.PendingOnlineSound pendingSound = plugin.getPendingSingleUserSound(player.getUniqueId());
                    if (pendingSound != null && tempPackFileName != null && tempPackFileName.equals(pendingSound.getPackFileName())) {
                        float volume = plugin.getPlayerVolume(player.getUniqueId());
                        player.playSound(player.getLocation(), pendingSound.getSoundEventName(), SoundCategory.MUSIC, volume, 1.0f);
                        plugin.getLogger().info("播放独立/预设音乐: " + pendingSound.getSoundEventName() + " for " + player.getName());

                        // 循环播放：注册声音并调度重播（按歌曲时长约 180s 重播）
                        if (plugin.isPlayerLooping(player.getUniqueId())) {
                            plugin.setPlayerLoopingSound(player.getUniqueId(), pendingSound.getSoundEventName());
                            plugin.scheduleLoopTask(player.getUniqueId(), 20L * plugin.getConfig().getInt("player.loopIntervalSeconds", 180));
                        }
                    } else if (pendingSound != null && (tempPackFileName == null || !tempPackFileName.equals(pendingSound.getPackFileName()))){
                        plugin.getLogger().warning("玩家 " + player.getName() + " 成功加载了资源包，但待播放的独立/预设音乐信息不匹配或包文件名缺失。文件名: " + tempPackFileName + ", 期望: " + (pendingSound.getPackFileName() != null ? pendingSound.getPackFileName() : "null"));
                        if (plugin.shouldUseMergedPackLogic()) {
                            plugin.sendOriginalBasePackToPlayer(player);
                        }
                    }
                } else if (packTypeOrSoundSource.equals("room") && roomIdForRoomType != null) {
                    MusicRoom room = plugin.getMusicRoom(roomIdForRoomType);
                    if (room != null && room.getPlayRequestActive() && plugin.getHttpFileServer() != null &&
                            tempPackFileName != null && Objects.equals(tempPackFileName, room.getPackFileName())) {
                        String soundEventName = plugin.getHttpFileServer().getServePathPrefix() + ".room." + room.getRoomId();
                        player.playSound(player.getLocation(), soundEventName, SoundCategory.MUSIC, 1.0f, 1.0f);
                        plugin.getLogger().info("播放房间音乐: " + soundEventName + " for " + player.getName() + " in room " + roomIdForRoomType);
                        room.updateLastActivityTime();
                        room.setStatus(MusicRoom.RoomStatus.PLAYING);
                    } else if (room != null && (!room.getPlayRequestActive() || (room.getPackFileName() != null && !Objects.equals(tempPackFileName, room.getPackFileName())))) {
                        plugin.getLogger().warning("玩家 " + player.getName() + " 加载了过时的房间资源包 " + tempPackFileName + " (房间 " + roomIdForRoomType + "). 正在恢复基础包。");
                        if (tempPackFileName != null && plugin.getResourcePackGenerator() != null && !plugin.isPrewarmedPackFile(tempPackFileName)) {
                            plugin.getResourcePackGenerator().cleanupPack(tempPackFileName);
                        }
                        plugin.clearPlayerCurrentMusicPack(player.getUniqueId());
                        if (plugin.shouldUseMergedPackLogic()) {
                            plugin.sendOriginalBasePackToPlayer(player);
                        }
                    } else if (room == null) {
                        plugin.getLogger().warning("玩家 " + player.getName() + " 加载了房间资源包，但房间 " + roomIdForRoomType + " 已不存在。");
                        if (tempPackFileName != null && plugin.getResourcePackGenerator() != null && !plugin.isPrewarmedPackFile(tempPackFileName)) {
                            plugin.getResourcePackGenerator().cleanupPack(tempPackFileName);
                        }
                        plugin.clearPlayerCurrentMusicPack(player.getUniqueId());
                        if (plugin.shouldUseMergedPackLogic()) {
                            plugin.sendOriginalBasePackToPlayer(player);
                        }
                    }
                }
                plugin.clearPlayerPendingPackType(player.getUniqueId());
                plugin.clearPlayerPackRequestId(player.getUniqueId());
                break;
            case DECLINED:
            case FAILED_DOWNLOAD:
                if (status == PlayerResourcePackStatusEvent.Status.DECLINED) {
                    plugin.sendConfigMsg(player, "messages.resourcePack.status.declined");
                } else {
                    plugin.sendConfigMsg(player, "messages.resourcePack.status.failed");
                }
                if (tempPackFileName != null && plugin.getResourcePackGenerator() != null && !plugin.isPrewarmedPackFile(tempPackFileName)) {
                    plugin.getResourcePackGenerator().cleanupPack(tempPackFileName);
                }
                if (packTypeOrSoundSource.equals("singleUser") || packTypeOrSoundSource.equals("preset")) {
                    plugin.clearPendingSingleUserSound(player.getUniqueId());
                } else if (packTypeOrSoundSource.equals("room") && roomIdForRoomType != null) {
                    MusicRoom room = plugin.getMusicRoom(roomIdForRoomType);
                    if (room != null) {
                        room.setPlayRequestActive(false);
                    }
                }
                plugin.clearPlayerPendingPackType(player.getUniqueId());
                plugin.clearPlayerCurrentMusicPack(player.getUniqueId());
                plugin.clearPlayerPackRequestId(player.getUniqueId());
                if (plugin.shouldUseMergedPackLogic()) {
                    plugin.sendOriginalBasePackToPlayer(player);
                }
                break;
            case ACCEPTED:
                plugin.sendConfigMsg(player, "messages.resourcePack.status.accepted");
                break;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryView view = event.getView();

        MusicGUI currentGui = MusicGUI.getPlayerOpenGUI(player);
        if (currentGui == null) {
            return;
        }

        // 通过 GUI 状态 + 标题前缀确认是 PlayerMusic 的 GUI，避免误拦截其他插件容器
        String guiTitleBase = plugin.getLangMessage("gui.title");
        if (guiTitleBase == null) guiTitleBase = "§9音乐播放器";
        String coloredBase = ChatColor.translateAlternateColorCodes('&', guiTitleBase);
        if (!view.getTitle().startsWith(coloredBase)) {
            return;
        }

        event.setCancelled(true);
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || !clickedItem.hasItemMeta()) return;
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.getDisplayName().isEmpty()) return;

        String itemName = meta.getDisplayName();
        String nextPageNameRaw = plugin.getLangMessage("gui.nextPageName");
        String prevPageNameRaw = plugin.getLangMessage("gui.prevPageName");
        String backRaw = plugin.getLangMessage("gui.backToAlbums");
        String allRaw = plugin.getLangMessage("gui.allMusic");
        String nextPageName = ChatColor.translateAlternateColorCodes('&', nextPageNameRaw != null ? nextPageNameRaw : "§a下一页 ->");
        String prevPageName = ChatColor.translateAlternateColorCodes('&', prevPageNameRaw != null ? prevPageNameRaw : "§c<- 上一页");
        String backName = ChatColor.translateAlternateColorCodes('&', backRaw != null ? backRaw : "§c<- 返回专辑");
        String allName = ChatColor.translateAlternateColorCodes('&', allRaw != null ? allRaw : "§a全部音乐");

        if (itemName.equals(nextPageName)) {
            currentGui.changePage(player, 1);
            return;
        } else if (itemName.equals(prevPageName)) {
            currentGui.changePage(player, -1);
            return;
        }

        // 专辑列表视图：处理"全部音乐"和专辑点击
        if (currentGui.isAlbumListView()) {
            if (itemName.equals(allName)) {
                currentGui.openAllMusic(player);
                return;
            }
            // 专辑项格式："♪ <专辑名>"
            if (itemName.startsWith("§e♪ ")) {
                String albumName = ChatColor.stripColor(itemName.substring(4));
                if (plugin.getAlbums().contains(albumName)) {
                    currentGui.openAlbum(player, albumName);
                    return;
                }
            }
            return;
        }

        // 歌曲视图：处理"返回专辑"
        if (itemName.equals(backName)) {
            currentGui.backToAlbums(player);
            return;
        }

        // 控制按钮：随机播放 / 循环 / 音量减 / 音量加
        String randomRaw = plugin.getLangMessage("gui.random");
        String loopRaw = plugin.getLangMessage("gui.loop");
        String volDownRaw = plugin.getLangMessage("gui.volumeDown");
        String volUpRaw = plugin.getLangMessage("gui.volumeUp");
        String randomName = ChatColor.translateAlternateColorCodes('&', randomRaw != null ? randomRaw : "§a随机播放");
        String loopName = ChatColor.translateAlternateColorCodes('&', loopRaw != null ? loopRaw : "§e循环");
        String volDownName = ChatColor.translateAlternateColorCodes('&', volDownRaw != null ? volDownRaw : "§c音量 -");
        String volUpName = ChatColor.translateAlternateColorCodes('&', volUpRaw != null ? volUpRaw : "§a音量 +");

        if (itemName.equals(randomName)) {
            musicCommands.playRandomSongFromAlbum(player, currentGui.getCurrentAlbum());
            return;
        }
        if (itemName.equals(loopName)) {
            boolean newState = !plugin.isPlayerLooping(player.getUniqueId());
            plugin.setPlayerLoopStatus(player.getUniqueId(), newState);
            if (!newState) {
                plugin.cancelPlayerLoop(player.getUniqueId());
            }
            plugin.sendConfigMsg(player, "messages.bf.loop.toggled",
                    "state", newState ? "§a开启" : "§c关闭");
            currentGui.openView(player);
            return;
        }
        if (itemName.equals(volDownName)) {
            float newVol = plugin.adjustPlayerVolume(player.getUniqueId(), -0.1f);
            plugin.sendConfigMsg(player, "messages.bf.volume.set", "percent", String.valueOf(Math.round(newVol * 100)));
            currentGui.openView(player);
            return;
        }
        if (itemName.equals(volUpName)) {
            float newVol = plugin.adjustPlayerVolume(player.getUniqueId(), 0.1f);
            plugin.sendConfigMsg(player, "messages.bf.volume.set", "percent", String.valueOf(Math.round(newVol * 100)));
            currentGui.openView(player);
            return;
        }

        // 歌曲点击播放
        List<PresetSong> songList = currentGui.getCurrentAlbum() == null
                ? plugin.getPresetSongs()
                : plugin.getSongsByAlbum(currentGui.getCurrentAlbum());
        PresetSong selectedSong = songList.stream()
                .filter(song -> {
                    String songDisplayItemName = ChatColor.translateAlternateColorCodes('&', song.getName());
                    return songDisplayItemName.equals(itemName);
                })
                .findFirst().orElse(null);

        if (selectedSong != null) {
            player.closeInventory();
            musicCommands.handlePlay(player, selectedSong.getUrl(), MusicCommands.PlaybackContextType.SINGLE, null, selectedSong);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        String pendingPackFullIdentifier = plugin.getPlayerPendingPackType(player.getUniqueId());
        if (pendingPackFullIdentifier != null) {
            String[] typeParts = pendingPackFullIdentifier.split(":", 3);
            String packTypeOrSoundSource = typeParts[0];
            String tempPackFileName = null;
            if (packTypeOrSoundSource.equals("singleUser") && typeParts.length >= 2) tempPackFileName = typeParts[1];
            else if (packTypeOrSoundSource.equals("room") && typeParts.length >= 3) tempPackFileName = typeParts[2];
            else if (packTypeOrSoundSource.equals("preset") && typeParts.length >= 2) tempPackFileName = typeParts[1];

            if (tempPackFileName != null && plugin.getResourcePackGenerator() != null && !plugin.isPrewarmedPackFile(tempPackFileName)) {
                plugin.getResourcePackGenerator().cleanupPack(tempPackFileName);
            }
        }
        plugin.clearPendingSingleUserSound(player.getUniqueId());
        plugin.clearPlayerPendingPackType(player.getUniqueId());
        plugin.clearPlayerPackRequestId(player.getUniqueId());
        plugin.cancelPlayerLoop(player.getUniqueId());

        String currentTempMusicFile = plugin.getPlayerCurrentMusicPackFile(player.getUniqueId());
        if (currentTempMusicFile != null && plugin.getResourcePackGenerator() != null && !plugin.isPrewarmedPackFile(currentTempMusicFile)) {
            plugin.getResourcePackGenerator().cleanupPack(currentTempMusicFile);
        }
        plugin.clearPlayerCurrentMusicPack(player.getUniqueId());

        for (MusicRoom room : new HashSet<>(plugin.getActiveMusicRoomsView())) {
            if (room.isMember(player)) {
                room.removeMember(player);
                plugin.getLogger().info("玩家 " + player.getName() + " 因断开连接离开音乐室 " + room.getRoomId());
            }
        }
        MusicGUI.removePlayerOpenGUI(player);
    }
}