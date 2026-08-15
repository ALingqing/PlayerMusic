package icu.epochcraft;

import org.bukkit.Material;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PresetSong {
    private final String name;
    private final String url;
    private final Material displayItemMaterial;
    private final List<String> lore;
    /** 所属专辑（子文件夹名）。根目录歌曲为 null */
    private final String album;

    public PresetSong(String name, String url, Material displayItemMaterial, List<String> lore) {
        this(name, url, displayItemMaterial, lore, null);
    }

    public PresetSong(String name, String url, Material displayItemMaterial, List<String> lore, @org.jetbrains.annotations.Nullable String album) {
        this.name = name;
        this.url = url;
        this.displayItemMaterial = displayItemMaterial;
        this.lore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
        this.album = album;
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public Material getDisplayItemMaterial() { return displayItemMaterial; }
    public List<String> getLore() { return Collections.unmodifiableList(lore); }

    /** 所属专辑名（子文件夹名），根目录歌曲返回 null */
    @org.jetbrains.annotations.Nullable
    public String getAlbum() { return album; }
}