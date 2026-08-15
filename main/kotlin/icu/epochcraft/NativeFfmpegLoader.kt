package icu.epochcraft

import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 内置 ffmpeg 加载器。
 *
 * 插件 jar 内打包了两个 Linux 架构的 ffmpeg 二进制（gzip 压缩）：
 * - native/ffmpeg-linux-amd64.gz
 * - native/ffmpeg-linux-arm64.gz
 *
 * 启动时根据服务器 OS + 架构解压对应的二进制到
 * plugins/PlayerMusic/native/ffmpeg 并设置可执行权限。
 */
object NativeFfmpegLoader {

    /** 内置 ffmpeg 在 jar 内的资源路径前缀 */
    private const val RESOURCE_PREFIX = "native/"

    /** 内置 ffmpeg 资源文件名前缀（实际文件如 ffmpeg-linux-amd64.gz） */
    private const val RESOURCE_FILE_PREFIX = "ffmpeg-linux-"

    /** 插件数据目录下的解压目标子目录 */
    private const val NATIVE_DIR = "native"

    /** 解压后的 ffmpeg 文件名 */
    private const val FFMPEG_NAME = "ffmpeg"

    /**
     * 尝试释放内置 ffmpeg。
     * @return 成功返回解压后的 ffmpeg 可执行文件路径；失败返回 null
     */
    fun extract(dataFolder: File): String? {
        // 仅支持 Linux（服务器通常为 Linux）。其他系统返回 null 走系统 PATH。
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("linux")) return null

        val arch = detectArch() ?: return null
        val resourceName = "$RESOURCE_PREFIX$RESOURCE_FILE_PREFIX$arch.gz"

        // 检查 jar 内是否真的打包了该资源
        val resourceStream = try {
            NativeFfmpegLoader::class.java.getResourceAsStream("/$resourceName")
        } catch (_: Exception) {
            null
        }
        if (resourceStream == null) return null

        val nativeDir = File(dataFolder, NATIVE_DIR)
        if (!nativeDir.exists()) nativeDir.mkdirs()
        val target = File(nativeDir, FFMPEG_NAME)

        try {
            GZIPInputStream(resourceStream).use { gzip ->
                FileOutputStream(target).use { out ->
                    gzip.copyTo(out, 64 * 1024)
                }
            }
            // 设置可执行权限
            target.setExecutable(true, false)
            target.setReadable(true, false)
            return target.absolutePath
        } catch (_: Exception) {
            return null
        }
    }

    /** 检测 CPU 架构，返回内置资源的文件名（如 ffmpeg-linux-amd64.gz → "amd64"） */
    private fun detectArch(): String? {
        val arch = System.getProperty("os.arch", "").lowercase()
        return when {
            arch.contains("amd64") || arch.contains("x86_64") -> "amd64"
            arch.contains("aarch64") || arch.contains("arm64") || arch.contains("arm") -> "arm64"
            else -> null
        }
    }
}
