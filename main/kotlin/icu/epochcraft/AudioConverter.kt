package icu.epochcraft

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MP3 → OGG Vorbis 音频转换器（基于 ffmpeg）。
 *
 * ffmpeg 需安装在服务器上。插件自动检测以下位置：
 * - 系统 PATH 中的 ffmpeg/ffmpeg.exe
 * - ~/ffmpeg/bin/ffmpeg (一键安装脚本 install-ffmpeg.sh 的默认位置)
 * - plugins/PlayerMusic/ffmpeg/bin/ffmpeg
 */
object AudioConverter {

    /** 是否可用（检测到 ffmpeg）。每次调用实时检测，避免缓存过时结果 */
    val isAvailable: Boolean
        get() = findFfmpeg() != null

    private fun findFfmpeg(): String? {
        // 1. 插件内置 ffmpeg（启动时已解压到 plugins/PlayerMusic/native/ffmpeg）
        val bundled = NativeFfmpegLoader.extractedPath
        if (bundled != null && canRun(bundled)) {
            return bundled
        }
        // 2. 系统 PATH
        for (name in listOf("ffmpeg", "ffmpeg.exe")) {
            if (canRun(name)) return name
        }
        // 3. 常见安装路径
        val candidates = mutableListOf<File>()
        val home = System.getProperty("user.home")
        if (home != null) {
            candidates.add(File(home, "ffmpeg/bin/ffmpeg"))
            candidates.add(File(home, "ffmpeg/bin/ffmpeg.exe"))
        }
        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile) {
                if (canRun(candidate.absolutePath)) return candidate.absolutePath
            }
        }
        return null
    }

    private fun canRun(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder(cmd, "-version")
                .redirectErrorStream(true)
                .start()
            val ok = p.waitFor(3, TimeUnit.SECONDS)
            p.destroy()
            ok
        } catch (_: Exception) {
            false
        }
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
