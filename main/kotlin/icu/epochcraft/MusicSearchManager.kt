package icu.epochcraft

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 柠柚 API 音乐搜索/解析管理器。
 *
 * 端点：
 * - 歌曲搜索: /api/v2/diange?msg=<歌名>&id=<序号>&n=<页>
 * - 网易云解析: /api/v2/163music?ids=<id>&level=<音质>&type=json
 * - QQ音乐解析: /api/v2/qqmusic?url=<url>
 * - 酷我解析:   /api/v2/kuwo?url=<url>
 */
class MusicSearchManager(private val plugin: MusicPlayerPlugin) {

    private val gson = Gson()
    private val baseUrl = "https://api.nycnm.cn/api/v2"

    data class SearchResult(
        val index: Int,
        val name: String,
        val artist: String,
        val url: String,
        val coverUrl: String? = null,
        val lyrics: String? = null,
        val neteaseId: String? = null, // 网易云歌曲 ID（热歌榜兜底时用于解析下载）
    )

    data class DownloadInfo(
        val name: String,
        val artist: String,
        val album: String? = null,
        val url: String,
        val coverUrl: String? = null,
        val lyrics: String? = null,
    )

    private fun apiKey(): String {
        return plugin.config.getString("musicApi.apiKey", "") ?: ""
    }

    /** 搜索结果（带来源标记，用于区分正常 / 服务故障） */
    data class SearchOutcome(
        val results: List<SearchResult>,
        val serviceDown: Boolean = false,   // 柠柚搜索接口（diange）服务端故障
        val usingFallback: Boolean = false, // 是否用了热歌榜兜底
    )

    /** 搜索歌曲（网易云为主，跨平台）。返回结果列表 */
    fun search(query: String, page: Int = 1): List<SearchResult> = searchWithOutcome(query, page).results

    /**
     * 搜索歌曲，返回带状态的结果。
     * 优先走 diange 关键词搜索；若该接口整体 502（服务端故障），
     * 自动改用 163ncm 热歌榜兜底并标记 usingFallback，同时 serviceDown=true 以便提示。
     */
    fun searchWithOutcome(query: String, page: Int = 1): SearchOutcome {
        val key = apiKey()
        if (key.isEmpty()) return SearchOutcome(emptyList())
        val results = java.util.Collections.synchronizedList(mutableListOf<SearchResult>())
        val downRef = java.util.concurrent.atomic.AtomicBoolean(false)
        val successRef = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            // diange 接口的 id 参数是"选择序号"：id=1,2,3... 每首返回不同的歌。
            // 并行请求 id=1..8 收集多首（显著加快搜索速度，总耗时≈单次请求）。
            val maxResults = 8
            val executor = java.util.concurrent.Executors.newFixedThreadPool(maxResults)
            val futures = ArrayList<java.util.concurrent.Future<*>>()
            for (i in 1..maxResults) {
                futures.add(executor.submit(Runnable {
                    try {
                        val url = URL("$baseUrl/diange?msg=$encoded&id=$i&n=$page&apikey=$key")
                        val json = httpGetJson(url)
                        val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return@Runnable }
                        val code = root.get("code")?.asInt ?: -1
                        if (code != 200) {
                            // 502 等错误码 = 服务端故障（diange 上游 502）
                            if (code >= 500 || root.get("message")?.asString?.contains("搜索请求失败") == true) {
                                downRef.set(true)
                            }
                            return@Runnable
                        }
                        successRef.set(true)
                        if (root.has("data") && root.get("data").isJsonObject) {
                            val data = root.getAsJsonObject("data")
                            val name = data.get("music_name")?.asString ?: ""
                            val artist = data.get("artist")?.asString ?: ""
                            val link = data.get("music_link")?.asString ?: ""
                            val cover = data.get("cover_link")?.asString
                            val lrc = data.get("lrc_content")?.asString
                            // 只要有歌名就显示（link 可能为空=该曲源暂不可用，但用户能看到歌）
                            if (name.isNotEmpty()) {
                                synchronized(results) {
                                    if (results.none { it.name == name && it.artist == artist }) {
                                        results.add(SearchResult(i, name, artist, link, cover, lrc))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }))
            }
            // 等待所有请求完成（总超时 20 秒）
            for (f in futures) {
                try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            }
            executor.shutdownNow()
            // 排序：有下载链接的优先，其余按序号
            results.sortWith(compareByDescending<SearchResult> { it.url.isNotEmpty() }.thenBy { it.index })
        } catch (e: Exception) {
            // 静默：搜索失败不打印控制台
        }
        // diange 服务端故障且没有任何结果 → 用热歌榜兜底（仍是柠柚 API，可正常下载）
        if (downRef.get() && results.isEmpty()) {
            val fallback = fetchHotRankSongs(key, 8)
            if (fallback.isNotEmpty()) {
                return SearchOutcome(fallback, serviceDown = true, usingFallback = true)
            }
        }
        return SearchOutcome(results.toList(), serviceDown = downRef.get() && results.isEmpty(), usingFallback = false)
    }

    /**
     * 网易云热歌榜兜底（163ncm action=detail id=3778678）。
     * 返回带网易云 id 的结果（url 留空，下载时通过 resolveNetease(id) 解析）。
     */
    private fun fetchHotRankSongs(key: String, size: Int): List<SearchResult> {
        return try {
            val url = URL("$baseUrl/163ncm?action=detail&id=3778678&size=$size&encoding=json&apikey=$key")
            val json = httpGetJson(url)
            val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return emptyList() }
            if (root.get("code")?.asInt != 200) return emptyList()
            val arr = root.getAsJsonArray("data") ?: return emptyList()
            val out = mutableListOf<SearchResult>()
            var idx = 1
            for (el in arr) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = obj.get("id")?.asLong ?: continue
                val name = obj.get("title")?.asString ?: continue
                val artist = try {
                    val a = obj.getAsJsonArray("artist")
                    if (a != null && a.size() > 0 && a[0].isJsonObject) a[0].asJsonObject.get("name")?.asString ?: "" else ""
                } catch (_: Exception) { "" }
                val cover = try {
                    val albumEl = obj.get("album")
                    if (albumEl != null && albumEl.isJsonObject) {
                        val c = albumEl.asJsonObject.get("cover")
                        if (c != null && c.isJsonPrimitive) c.asString else null
                    } else null
                } catch (_: Exception) { null }
                out.add(SearchResult(idx, name, artist, "", cover, null, neteaseId = id.toString()))
                idx++
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 网易云解析：根据歌曲 ID 获取下载信息 */
    fun resolveNetease(songId: String, level: String = "standard"): DownloadInfo? {
        val key = apiKey()
        if (key.isEmpty()) return null
        try {
            val url = URL("$baseUrl/163music?ids=$songId&level=$level&type=json&apikey=$key")
            val json = httpGetJson(url)
            val root = JsonParser.parseString(json).asJsonObject
            val status = root.get("status")?.asInt ?: root.get("code")?.asInt ?: 0
            if (status != 200) return null

            val name = root.get("name")?.asString ?: ""
            val artist = root.get("ar_name")?.asString ?: ""
            val album = root.get("al_name")?.asString
            val link = root.get("url")?.asString ?: ""
            val cover = root.get("pic")?.asString
            val lrc = root.get("lyric")?.asString
            if (name.isEmpty() || link.isEmpty()) return null
            return DownloadInfo(name, artist, album, link, cover, lrc)
        } catch (e: Exception) {
            // 静默：解析失败不打印控制台
            return null
        }
    }

    /** 下载 mp3 到目标文件 */
    fun downloadMp3(url: String, target: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PlayerMusic/" + plugin.description.version)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            val code = conn.responseCode
            if (code != 200) {
                return false
            }
            conn.inputStream.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.exists() && target.length() > 0
        } catch (e: Exception) {
            // 静默：下载失败不打印控制台
            false
        }
    }

    /** 下载并转换 MP3 为 OGG，返回 OGG 文件 */
    fun downloadAndConvertToOgg(url: String, targetOgg: File): File? {
        val tmpMp3 = File(targetOgg.parentFile, targetOgg.nameWithoutExtension + ".tmp.mp3")
        return try {
            if (!downloadMp3(url, tmpMp3)) {
                tmpMp3.delete()
                return null
            }
            val ogg = AudioConverter.convertMp3ToOgg(tmpMp3, targetOgg)
            tmpMp3.delete()
            ogg
        } catch (e: Exception) {
            tmpMp3.delete()
            // 静默：转换失败不打印控制台
            null
        }
    }

    private fun httpGetJson(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "PlayerMusic/" + plugin.description.version)
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        val stream: InputStream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
