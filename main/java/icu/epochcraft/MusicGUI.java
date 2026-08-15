package icu.epochcraft;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class MusicGUI {

    private final MusicPlayerPlugin plugin;
    private int currentPage = 0;
    public static final int ITEMS_PER_PAGE = 45;

    /** 当前浏览的专辑名；null 表示专辑列表/全部音乐视图 */
    private String currentAlbum = null;
    /** 专辑视图（浏览子文件夹分类） */
    private boolean albumListView = true;

    /** 最近一次打开此 GUI 的玩家（用于显示音量/循环状态） */
    private Player lastViewer = null;

    private static final Map<UUID, MusicGUI> openGUIs = new HashMap<>();

    public MusicGUI(MusicPlayerPlugin plugin) {
        this.plugin = plugin;
    }

    /** 打开 GUI：有子文件夹专辑时先显示专辑列表，否则直接显示全部音乐 */
    public void open(Player player) {
        List<String> albums = plugin.getAlbums();
        if (albums.isEmpty()) {
            albumListView = false;
            currentAlbum = null;
        } else {
            albumListView = true;
            currentAlbum = null;
        }
        openView(player);
    }

    /** 重新渲染当前视图（公开给监听器在音量/循环变化后刷新） */
    public void openView(Player player) {
        this.lastViewer = player;
        String baseTitle = plugin.getLangMessage("gui.title");
        if (baseTitle == null) baseTitle = "§9音乐播放器";

        if (albumListView) {
            // 专辑列表视图：显示所有专辑 + 全部音乐
            List<String> albums = plugin.getAlbums();
            int totalPages = (int) Math.ceil((double) (albums.size() + 1) / (double) ITEMS_PER_PAGE);
            if (totalPages == 0) totalPages = 1;

            String finalTitle = baseTitle + " §7(专辑)";
            if (totalPages > 1) {
                finalTitle += " §7- 第 " + (currentPage + 1) + "/" + totalPages + " 页";
            }
            Inventory dynamicInv = Bukkit.createInventory(null, 54, ChatColor.translateAlternateColorCodes('&', finalTitle));
            populateAlbumItems(dynamicInv, albums, totalPages);
            player.openInventory(dynamicInv);
        } else {
            // 歌曲列表视图（含"全部音乐"或指定专辑）
            List<PresetSong> songs = currentAlbum == null ? plugin.getPresetSongs() : plugin.getSongsByAlbum(currentAlbum);
            int totalPages = (int) Math.ceil((double) songs.size() / (double) ITEMS_PER_PAGE);
            if (totalPages == 0) totalPages = 1;

            String finalTitle = baseTitle;
            if (currentAlbum != null) {
                finalTitle += " §7- " + currentAlbum;
            }
            if (totalPages > 1) {
                finalTitle += " §7(第 " + (currentPage + 1) + "/" + totalPages + " 页)";
            }
            Inventory dynamicInv = Bukkit.createInventory(null, 54, ChatColor.translateAlternateColorCodes('&', finalTitle));
            populateSongItems(dynamicInv, songs, totalPages);
            player.openInventory(dynamicInv);
        }
        openGUIs.put(player.getUniqueId(), this);
    }

    /** 专辑列表视图的物品 */
    private void populateAlbumItems(Inventory currentInventory, List<String> albums, int totalPages) {
        currentInventory.clear();

        if (albums.isEmpty()) {
            String noPresetsMessage = ChatColor.translateAlternateColorCodes('&', plugin.getLangMessage("gui.noPresets"));
            if (noPresetsMessage == null || noPresetsMessage.equals("null")) noPresetsMessage = "§c没有可用的音乐。";
            ItemStack noSongsItem = new ItemStack(Material.BARRIER);
            ItemMeta meta = noSongsItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(noPresetsMessage);
                noSongsItem.setItemMeta(meta);
            }
            currentInventory.setItem(22, noSongsItem);
            return;
        }

        // 第一个槽位放"全部音乐"（含根目录歌曲）
        String allName = ChatColor.translateAlternateColorCodes('&', plugin.getLangMessage("gui.allMusic"));
        if (allName == null || allName.equals("null")) allName = "§a全部音乐";
        ItemStack allItem = new ItemStack(Material.MUSIC_DISC_CAT);
        ItemMeta allMeta = allItem.getItemMeta();
        if (allMeta != null) {
            allMeta.setDisplayName(allName);
            allMeta.setLore(List.of("§7查看所有音乐"));
            allItem.setItemMeta(allMeta);
        }
        currentInventory.setItem(0, allItem);

        // 专辑列表（从槽位 1 开始）
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, albums.size());
        int slot = 1;
        for (int i = startIndex; i < endIndex; i++) {
            String album = albums.get(i);
            int count = plugin.getSongsByAlbum(album).size();
            ItemStack albumItem = new ItemStack(Material.MUSIC_DISC_STAL);
            ItemMeta albumMeta = albumItem.getItemMeta();
            if (albumMeta != null) {
                albumMeta.setDisplayName(ChatColor.YELLOW + "♪ " + album);
                albumMeta.setLore(List.of("§7专辑 · " + count + " 首"));
                albumItem.setItemMeta(albumMeta);
            }
            currentInventory.setItem(slot++, albumItem);
        }

        if (currentPage > 0) {
            String prevName = plugin.getLangMessage("gui.prevPageName");
            if (prevName == null) prevName = "§c<- 上一页";
            currentInventory.setItem(45, createNavItem(prevName, plugin.getConfig().getString("gui.prevPageItem", "ARROW")));
        }
        if (currentPage < totalPages - 1) {
            String nextName = plugin.getLangMessage("gui.nextPageName");
            if (nextName == null) nextName = "§a下一页 ->";
            currentInventory.setItem(53, createNavItem(nextName, plugin.getConfig().getString("gui.nextPageItem", "ARROW")));
        }
    }

    /** 歌曲列表视图的物品 */
    private void populateSongItems(Inventory currentInventory, List<PresetSong> presetSongs, int totalPages) {
        currentInventory.clear();

        // 专辑视图下提供"返回专辑列表"按钮
        if (!plugin.getAlbums().isEmpty()) {
            String backName = ChatColor.translateAlternateColorCodes('&', plugin.getLangMessage("gui.backToAlbums"));
            if (backName == null || backName.equals("null")) backName = "§c<- 返回专辑";
            currentInventory.setItem(49, createNavItem(backName, plugin.getConfig().getString("gui.backItem", "BARRIER")));
        }

        if (presetSongs.isEmpty()) {
            String noPresetsMessage = ChatColor.translateAlternateColorCodes('&', plugin.getLangMessage("gui.noPresets"));
            if (noPresetsMessage == null || noPresetsMessage.equals("null")) noPresetsMessage = "§c没有可用的音乐。";
            ItemStack noSongsItem = new ItemStack(Material.BARRIER);
            ItemMeta meta = noSongsItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(noPresetsMessage);
                noSongsItem.setItemMeta(meta);
            }
            currentInventory.setItem(22, noSongsItem);
        } else {
            int startIndex = currentPage * ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, presetSongs.size());

            for (int i = startIndex; i < endIndex; i++) {
                PresetSong song = presetSongs.get(i);
                ItemStack songItem = new ItemStack(song.getDisplayItemMaterial());
                ItemMeta songMeta = songItem.getItemMeta();
                if (songMeta != null) {
                    songMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', song.getName()));
                    List<String> lore = song.getLore().stream()
                            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                            .collect(Collectors.toList());
                    songMeta.setLore(lore);
                    songItem.setItemMeta(songMeta);
                }
                currentInventory.setItem(i - startIndex, songItem);
            }
        }

        if (currentPage > 0) {
            String prevName = plugin.getLangMessage("gui.prevPageName");
            if (prevName == null) prevName = "§c<- 上一页";
            currentInventory.setItem(45, createNavItem(prevName, plugin.getConfig().getString("gui.prevPageItem", "ARROW")));
        }
        if (currentPage < totalPages - 1) {
            String nextName = plugin.getLangMessage("gui.nextPageName");
            if (nextName == null) nextName = "§a下一页 ->";
            currentInventory.setItem(53, createNavItem(nextName, plugin.getConfig().getString("gui.nextPageItem", "ARROW")));
        }

        populateControlButtons(currentInventory);
    }

    /** 底部控制按钮：随机播放 / 循环开关 / 音量减 / 音量加 */
    private void populateControlButtons(Inventory currentInventory) {
        String randomName = ChatColor.translateAlternateColorCodes('&', plugin.getLangMessage("gui.random"));
        if (randomName == null || randomName.equals("null")) randomName = "§a随机播放";
        currentInventory.setItem(47, createControlItem(randomName, Material.HOPPER, "§7从当前列表随机播放一首"));

        String loopNameRaw = plugin.getLangMessage("gui.loop");
        if (loopNameRaw == null || loopNameRaw.equals("null")) loopNameRaw = "§e循环";
        String loopStateDesc = (lastViewer != null && plugin.isPlayerLooping(lastViewer.getUniqueId())) ? "§a已开启" : "§c已关闭";
        currentInventory.setItem(48, createControlItem(loopNameRaw, Material.REPEATER, "§7当前: " + loopStateDesc));

        String volDownRaw = plugin.getLangMessage("gui.volumeDown");
        String volUpRaw = plugin.getLangMessage("gui.volumeUp");
        if (volDownRaw == null || volDownRaw.equals("null")) volDownRaw = "§c音量 -";
        if (volUpRaw == null || volUpRaw.equals("null")) volUpRaw = "§a音量 +";
        int currentVolPercent = lastViewer != null ? Math.round(plugin.getPlayerVolume(lastViewer.getUniqueId()) * 100) : 100;
        currentInventory.setItem(50, createControlItem(volDownRaw, Material.STONE_BUTTON, "§7当前音量: " + currentVolPercent + "%", "§7降低音量"));
        currentInventory.setItem(52, createControlItem(volUpRaw, Material.OAK_BUTTON, "§7当前音量: " + currentVolPercent + "%", "§7提高音量"));
    }

    private ItemStack createControlItem(String name, Material material, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 进入指定专辑 */
    public void openAlbum(Player player, String album) {
        this.currentAlbum = album;
        this.albumListView = false;
        this.currentPage = 0;
        openView(player);
    }

    /** 返回专辑列表 */
    public void backToAlbums(Player player) {
        this.currentAlbum = null;
        this.albumListView = true;
        this.currentPage = 0;
        openView(player);
    }

    /** 查看全部音乐（根目录 + 所有专辑） */
    public void openAllMusic(Player player) {
        this.currentAlbum = null;
        this.albumListView = false;
        this.currentPage = 0;
        openView(player);
    }

    public boolean isAlbumListView() { return albumListView; }
    public String getCurrentAlbum() { return currentAlbum; }

    private ItemStack createNavItem(String name, String materialName) {
        Material itemMaterial;
        try {
            itemMaterial = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            itemMaterial = Material.ARROW;
            plugin.getLogger().warning("GUI导航物品材质 '" + materialName + "' 无效，将使用默认的 ARROW。");
        }
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }

    public void changePage(Player player, int direction) {
        int totalPages;
        if (albumListView) {
            totalPages = (int) Math.ceil((double) (plugin.getAlbums().size() + 1) / (double) ITEMS_PER_PAGE);
        } else {
            List<PresetSong> songs = currentAlbum == null ? plugin.getPresetSongs() : plugin.getSongsByAlbum(currentAlbum);
            totalPages = (int) Math.ceil((double) songs.size() / (double) ITEMS_PER_PAGE);
        }
        if (totalPages == 0) totalPages = 1;

        int newPage = currentPage + direction;
        if (newPage >= 0 && newPage < totalPages) {
            currentPage = newPage;
            openView(player);
        }
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public static MusicGUI getPlayerOpenGUI(Player player) {
        return openGUIs.get(player.getUniqueId());
    }

    public static void removePlayerOpenGUI(Player player) {
        openGUIs.remove(player.getUniqueId());
    }
}