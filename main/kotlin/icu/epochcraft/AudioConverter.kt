package icu.epochcraft

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MP3 → OGG Vorbis 音频转换器（基于 ffmpeg，与 URLCustomDiscs 相同方案）。
 *
 * 使用 ffmpeg：`ffmpeg -y -i input.mp3 -vn -c:a libvorbis -q:a 4 output.ogg`
 *
 * ffmpeg 来源（按优先级检测）：
 * 1. plugins/PlayerMusic/ffmpeg/ffmpeg（用户上传的 Linux 静态构建）
 * 2. 系统 PATH 中的 ffmpeg / ffmpeg.exe
 */
object AudioConverter {

    /** 插件数据目录（onEnable 时设置，用于检测 plugins/PlayerMusic/ffmpeg/ffmpeg） */
    @Volatile
    var dataFolder: File? = null

    /** 是否可用（检测到 ffmpeg）。实时检测，避免缓存过时结果 */
    val isAvailable: Boolean
        get() = findFfmpeg() != null

    /** 返回检测到的 ffmpeg 路径（供日志/排查），未检测到返回 null */
    fun detectedFfmpegPath(): String? = findFfmpeg()

    /** 检测可用的 ffmpeg 路径 */
    private fun findFfmpeg(): String? {
        // 1. 插件数据目录 plugins/PlayerMusic/ffmpeg/ffmpeg（用户上传的静态构建）
        val df = dataFolder
        if (df != null) {
            val candidates = listOf(
                File(df, "ffmpeg/ffmpeg"),
                File(df, "ffmpeg/ffmpeg.exe"),
            )
            for (c in candidates) {
                if (c.exists() && c.isFile && canRun(c.absolutePath)) return c.absolutePath
            }
        }
        // 2. 系统 PATH
        for (name in listOf("ffmpeg", "ffmpeg.exe")) {
            if (canRun(name)) return name
        }
        return null
    }

    private fun canRun(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder(cmd, "-version").redirectErrorStream(true).start()
            val ok = p.waitFor(3, TimeUnit.SECONDS)
            p.destroy()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 将 MP3 文件转换为 OGG Vorbis 文件（ffmpeg libvorbis）。
     * 命令与 URLCustomDiscs 一致：ffmpeg -y -i in.mp3 -vn -c:a libvorbis -q:a 4 out.ogg
     * @return 成功返回转换后的 .ogg File，失败返回 null
     */
    fun convertToOgg(inputFile: File, oggFile: File): File? {
        val ffmpeg = findFfmpeg() ?: return null
        return try {
            val command = listOf(
                ffmpeg, "-y", "-i", inputFile.absolutePath,
                "-vn", "-c:a", "libvorbis", "-q:a", "4",
                oggFile.absolutePath
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val outputThread = Thread {
                try { process.inputStream.bufferedReader().use { while (it.readLine() != null) {} } } catch (_: Exception) {}
            }
            outputThread.start()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            outputThread.join(2000)
            process.destroy()

            if (finished && process.exitValue() == 0 && oggFile.exists() && oggFile.length() > 100) {
                oggFile
            } else {
                try { oggFile.delete() } catch (_: Exception) {}
                null
            }
        } catch (_: Exception) {
            try { oggFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /** 兼容旧方法名 */
    fun convertMp3ToOgg(mp3File: File, oggFile: File): File? = convertToOgg(mp3File, oggFile)
}
