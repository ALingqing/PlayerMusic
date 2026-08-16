package icu.epochcraft

import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 内置 ffmpeg 加载器。
 *
 * jar 内打包了 Linux amd64 的 ffmpeg 静态构建（gzip 压缩）：
 * - native/ffmpeg-linux-amd64.gz
 *
 * 启动时解压到 plugins/PlayerMusic/ffmpeg/ffmpeg 并设置可执行权限。
 */
object NativeFfmpegLoader {

    /** 内置 ffmpeg 在 jar 内的资源路径前缀 */
    private const val RESOURCE_PREFIX = "native/"

    /** 内置 ffmpeg 资源文件名前缀 */
    private const val RESOURCE_FILE_PREFIX = "ffmpeg-linux-"

    /** 解压目标子目录（在插件数据目录下） */
    private const val FFMPEG_DIR = "ffmpeg"

    /** 解压后的 ffmpeg 文件名 */
    private const val FFMPEG_NAME = "ffmpeg"

    /** 已解压的 ffmpeg 绝对路径（extract 成功后设置） */
    @Volatile
    var extractedPath: String? = null
        private set

    /**
     * 尝试释放内置 ffmpeg。
     * @return 成功返回解压后的 ffmpeg 可执行文件路径；失败返回 null
     */
    fun extract(dataFolder: File): String? {
        // 仅支持 Linux（服务器通常为 Linux）
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("linux")) return null

        val arch = detectArch() ?: return null
        val resourceName = "$RESOURCE_PREFIX$RESOURCE_FILE_PREFIX$arch.gz"

        val resourceStream = try {
            NativeFfmpegLoader::class.java.getResourceAsStream("/$resourceName")
        } catch (_: Exception) {
            null
        }
        if (resourceStream == null) return null

        val ffmpegDir = File(dataFolder, FFMPEG_DIR)
        if (!ffmpegDir.exists()) ffmpegDir.mkdirs()
        val target = File(ffmpegDir, FFMPEG_NAME)

        // 已解压且有效则复用（避免每次重启都重新解压）
        if (target.exists() && target.length() > 100_000) {
            target.setExecutable(true, false)
            extractedPath = target.absolutePath
            return target.absolutePath
        }

        try {
            GZIPInputStream(resourceStream).use { gzip ->
                FileOutputStream(target).use { out ->
                    gzip.copyTo(out, 64 * 1024)
                }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
            extractedPath = target.absolutePath
            return target.absolutePath
        } catch (_: Exception) {
            return null
        }
    }

    /** 检测 CPU 架构，返回内置资源对应的架构名（amd64 / arm64） */
    private fun detectArch(): String? {
        val arch = System.getProperty("os.arch", "").lowercase()
        return when {
            arch.contains("amd64") || arch.contains("x86_64") -> "amd64"
            arch.contains("aarch64") || arch.contains("arm64") || arch.contains("arm") -> "arm64"
            else -> null
        }
    }
}
