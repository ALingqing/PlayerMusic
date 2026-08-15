package icu.epochcraft

import org.bukkit.Material

/**
 * 一首可播放的音乐（来自音乐文件夹或预设）。
 *
 * @param name 显示名称
 * @param url  音频地址（file: URI 或 http(s) URL）
 * @param displayItemMaterial GUI 中显示的物品材质
 * @param lore 物品描述
 * @param album 所属专辑（子文件夹名），根目录歌曲为 null
 */
class PresetSong(
    val name: String,
    val url: String,
    val displayItemMaterial: Material,
    lore: List<String>?,
    val album: String? = null,
) {
    val lore: List<String> = lore?.toList() ?: emptyList()
}
