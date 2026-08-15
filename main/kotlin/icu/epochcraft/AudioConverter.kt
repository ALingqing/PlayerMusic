package icu.epochcraft

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MP3 → OGG Vorbis 音频转换器（基于 ffmpeg）。
 *
 * ffmpeg 需安装在服务器上（`apt install ffmpeg` 或面板安装）。
 * 插件会自动检测系统 PATH 中的 ffmpeg/ffmpeg.exe。
 */
object AudioConverter {

    /** 是否可用（检测到 ffmpeg） */
    val isAvailable: Boolean by lazy { findFfmpeg() != null }

    private fun findFfmpeg(): String? {
        for (name in listOf("ffmpeg", "ffmpeg.exe")) {
            try {
                val p = ProcessBuilder(name, "-version")
                    .redirectErrorStream(true)
                    .start()
                p.waitFor(3, TimeUnit.SECONDS)
                p.destroy()
                return name
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * 将任意音频文件（MP3/WAV/FLAC/OGG）转换为 OGG Vorbis 文件。
     * 使用 ffmpeg：`ffmpeg -i input.mp3 -c:a libvorbis -q:a 4 -y output.ogg`
     * @return 成功返回转换后的 .ogg File，失败返回 null
     */
    fun convertToOgg(inputFile: File, oggFile: File): File? {
        val ffmpeg = findFfmpeg() ?: return null
        return try {
            val command = listOf(
                ffmpeg,
                "-y", "-i", inputFile.absolutePath,
                "-c:a", "libvorbis",
                "-q:a", "4",
                oggFile.absolutePath
            )
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            // 读取输出，避免缓冲区阻塞
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
        } catch (e: Exception) {
            try { oggFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /** 兼容旧方法名 */
    fun convertMp3ToOgg(mp3File: File, oggFile: File): File? = convertToOgg(mp3File, oggFile)
}
