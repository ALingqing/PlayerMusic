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
        val neteaseId: String? = null, // 网易云歌曲 ID（下载时走柠柚 163music 解析）
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
        val serviceDown: Boolean = false, // 柠柚搜索接口（diange）服务端故障
    )

    /** 搜索歌曲（网易云为主，跨平台）。返回结果列表 */
    fun search(query: String, page: Int = 1): List<SearchResult> = searchWithOutcome(query, page).results

    /**
     * 搜索歌曲，返回带状态的结果。
     * 优先用网易云官方搜索接口（返回与关键词相关的歌曲 ID，下载走柠柚 163music 解析）；
     * 若官方搜索不可用，回退到柠柚 diange。
     */
    fun searchWithOutcome(query: String, page: Int = 1): SearchOutcome {
        // 1) 网易云官方搜索（直接可用，结果相关，返回歌曲 ID）
        val netease = searchNetEaseOfficial(query, page)
        if (netease.isNotEmpty()) {
            return SearchOutcome(netease, serviceDown = false)
        }
        // 2) 回退柠柚 diange（能直接给 link 更好，但当前接口 502）
        val diange = searchDiange(query, page)
        return SearchOutcome(diange.results, serviceDown = diange.serviceDown)
    }

    /** 网易云官方搜索接口：/api/search/get/web?s=<词>&type=1&limit=10&offset=<页> */
    private fun searchNetEaseOfficial(query: String, page: Int): List<SearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val offset = (page - 1) * 10
            val url = URL("https://music.163.com/api/search/get/web?s=$encoded&type=1&limit=10&offset=$offset")
            val json = httpGetJson(url, extraHeaders = mapOf("Referer" to "https://music.163.com/"))
            val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return emptyList() }
            if (root.get("code")?.asInt != 200) return emptyList()
            val result = root.getAsJsonObject("result") ?: return emptyList()
            val songs = result.getAsJsonArray("songs") ?: return emptyList()
            val out = mutableListOf<SearchResult>()
            var idx = 1
            for (el in songs) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = obj.get("id")?.asLong ?: continue
                val name = obj.get("name")?.asString ?: continue
                val artist = try {
                    val a = obj.getAsJsonArray("artists")
                    if (a != null && a.size() > 0 && a[0].isJsonObject) a[0].asJsonObject.get("name")?.asString ?: "" else ""
                } catch (_: Exception) { "" }
                val albumCover = try {
                    val albumEl = obj.get("album")
                    if (albumEl != null && albumEl.isJsonObject) {
                        val p = albumEl.asJsonObject.get("picUrl")
                        if (p != null && p.isJsonPrimitive) p.asString else null
                    } else null
                } catch (_: Exception) { null }
                out.add(SearchResult(idx, name, artist, "", albumCover, null, neteaseId = id.toString()))
                idx++
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 柠柚 diange 搜索（返回带直链的结果；当前接口 502 时会标记 serviceDown） */
    private fun searchDiange(query: String, page: Int): SearchOutcome {
        val key = apiKey()
        if (key.isEmpty()) return SearchOutcome(emptyList())
        val results = java.util.Collections.synchronizedList(mutableListOf<SearchResult>())
        val downRef = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
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
                            if (code >= 500 || root.get("message")?.asString?.contains("搜索请求失败") == true) {
                                downRef.set(true)
                            }
                            return@Runnable
                        }
                        if (root.has("data") && root.get("data").isJsonObject) {
                            val data = root.getAsJsonObject("data")
                            val name = data.get("music_name")?.asString ?: ""
                            val artist = data.get("artist")?.asString ?: ""
                            val link = data.get("music_link")?.asString ?: ""
                            val cover = data.get("cover_link")?.asString
                            val lrc = data.get("lrc_content")?.asString
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
            for (f in futures) {
                try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            }
            executor.shutdownNow()
            results.sortWith(compareByDescending<SearchResult> { it.url.isNotEmpty() }.thenBy { it.index })
        } catch (e: Exception) {
        }
        return SearchOutcome(results.toList(), serviceDown = downRef.get() && results.isEmpty())
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

    private fun httpGetJson(url: URL, extraHeaders: Map<String, String> = emptyMap()): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        for ((k, v) in extraHeaders) {
            conn.setRequestProperty(k, v)
        }
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        val stream: InputStream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
