package icu.epochcraft

import biniu.ogg.Packet
import biniu.ogg.Page
import biniu.ogg.StreamState
import biniu.vorbis.Block
import biniu.vorbis.Comment
import biniu.vorbis.DspState
import biniu.vorbis.Info
import biniu.vorbis.VorbisEnc
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * MP3 → OGG Vorbis 音频转换器（纯 Java，无 ffmpeg）。
 *
 * - MP3 解码：mp3spi (soundlibs 封装 JLayer)，纯 Java
 * - OGG 编码：jVorbisEnc (biniu.vorbis)，纯 Java (Xiph libvorbis 移植)
 *
 * 所有代码内嵌于 jar，不依赖任何外部二进制，跨平台 (Linux/Windows)。
 */
object AudioConverter {

    /** 是否可用：纯 Java 实现，永远可用 */
    val isAvailable: Boolean
        get() = true

    /**
     * 将 MP3 文件转换为 OGG Vorbis 文件（纯 Java）。
     * @return 成功返回转换后的 .ogg File，失败返回 null
     */
    fun convertToOgg(inputFile: File, oggFile: File): File? {
        return try {
            decodeMp3ToPcm(inputFile)?.let { pcm ->
                encodePcmToOgg(pcm, oggFile)
            }
        } catch (_: Exception) {
            try { oggFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /** 兼容旧方法名 */
    fun convertMp3ToOgg(mp3File: File, oggFile: File): File? = convertToOgg(mp3File, oggFile)

    /**
     * 解码后的 PCM 数据。
     * @param samples 交错立体声 short[] PCM (16-bit 有符号小端)
     * @param sampleRate 采样率 (Hz)
     * @param channels 声道数 (1/2)
     */
    data class PcmData(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /**
     * 使用 mp3spi 解码 MP3 → PCM（16-bit 交错 short[]）。
     */
    private fun decodeMp3ToPcm(mp3File: File): PcmData? {
        var inStream: AudioInputStream? = null
        try {
            val source = AudioSystem.getAudioInputStream(mp3File)
            val fmt = source.format
            val sampleRate = fmt.sampleRate.toInt()
            val channels = fmt.channels
            if (sampleRate <= 0 || channels <= 0) return null

            // 转换为 16-bit 有符号 PCM（小端，交错）
            val pcmFmt = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), 16, channels,
                channels * 2, sampleRate.toFloat(), false
            )
            inStream = AudioSystem.getAudioInputStream(pcmFmt, source)
            val bytes = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val n = inStream.read(buf)
                if (n <= 0) break
                bytes.write(buf, 0, n)
            }
            val pcmBytes = bytes.toByteArray()
            if (pcmBytes.size < 4) return null

            val samples = ShortArray(pcmBytes.size / 2)
            for (i in samples.indices) {
                samples[i] = ((pcmBytes[i * 2].toInt() and 0xff) or (pcmBytes[i * 2 + 1].toInt() shl 8)).toShort()
            }
            return PcmData(samples, sampleRate, channels)
        } catch (_: Exception) {
            return null
        } finally {
            try { inStream?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 使用 jVorbisEnc 将 PCM 编码为 OGG Vorbis 文件。
     * 参考 EncodeExample.java (Xiph libvorbis Java 移植)。
     */
    private fun encodePcmToOgg(pcm: PcmData, oggFile: File): File? {
        var result: File? = null
        var out: BufferedOutputStream? = null
        var state: StreamState? = null
        var block: Block? = null
        var dsp: DspState? = null
        var comment: Comment? = null
        var info: Info? = null
        try {
            val channels = pcm.channels
            val sampleRate = pcm.sampleRate

            info = Info()
            comment = Comment()
            dsp = DspState()
            block = Block(dsp)
            val encoder = VorbisEnc()
            val stateLocal = StreamState()
            state = stateLocal
            val page = Page()
            val packet = Packet()

            info.init()
            // 质量模式: 0.4 ≈ 128kbps (与 ffmpeg -q:a 4 接近)
            val ret = encoder.initVBR(info, channels, sampleRate, 0.4f)
            if (ret != 0) return null

            comment.init()
            comment.addTag("ENCODER", "PlayerMusic (jVorbisEnc)")

            dsp.analysisInit(info)
            block.blockInit(dsp)

            // 固定种子保证可复现
            val rand = Random(0x20260815L)
            stateLocal.init(rand.nextInt())

            // 写入 3 个 Vorbis 头
            val header = Packet()
            val headerComm = Packet()
            val headerCode = Packet()
            dsp.analysisHeaderOut(comment, header, headerComm, headerCode)
            stateLocal.packetIn(header)
            stateLocal.packetIn(headerComm)
            stateLocal.packetIn(headerCode)

            out = BufferedOutputStream(FileOutputStream(oggFile))

            // flush 头所在页
            var eos = false
            while (!eos) {
                val flushed = stateLocal.flush(page)
                if (!flushed) break
                out.write(page.header_base, page.header, page.header_len)
                out.write(page.body_base, page.body, page.body_len)
            }

            // 逐块提交 PCM 并编码
            val READ = 1024
            val samples = pcm.samples
            var pos = 0
            val totalFrames = samples.size / channels

            while (pos < totalFrames) {
                val n = minOf(READ, totalFrames - pos)
                val buffer = dsp.analysisBuffer(n)
                var l = dsp.pcm_current
                for (i in 0 until n) {
                    val base = (pos + i) * channels
                    for (ch in 0 until channels) {
                        val sample = samples[base + ch].toInt()
                        buffer[ch][l] = sample / 32768.0f
                    }
                    l++
                }
                pos += n
                dsp.analysisWrote(n)

                while (block.analysisBlockOut()) {
                    block.analysis(null)
                    block.bitrateAddBlock()
                    while (dsp.bitrateFlushPacket(packet)) {
                        stateLocal.packetIn(packet)
                        while (!eos) {
                            val got = stateLocal.pageOut(page)
                            if (!got) break
                            out.write(page.header_base, page.header, page.header_len)
                            out.write(page.body_base, page.body, page.body_len)
                            if (page.eos()) eos = true
                        }
                    }
                }
            }

            // 结束流，flush 剩余页
            dsp.analysisWrote(0)
            while (block.analysisBlockOut()) {
                block.analysis(null)
                block.bitrateAddBlock()
                while (dsp.bitrateFlushPacket(packet)) {
                    stateLocal.packetIn(packet)
                    while (!eos) {
                        val got = stateLocal.pageOut(page)
                        if (!got) break
                        out.write(page.header_base, page.header, page.header_len)
                        out.write(page.body_base, page.body, page.body_len)
                        if (page.eos()) eos = true
                    }
                }
            }
            out.flush()
            out.close()

            if (oggFile.exists() && oggFile.length() > 100) {
                result = oggFile
            } else {
                try { oggFile.delete() } catch (_: Exception) {}
                result = null
            }
        } catch (_: Exception) {
            try { oggFile.delete() } catch (_: Exception) {}
            result = null
        } finally {
            try { out?.close() } catch (_: Exception) {}
            try { state?.clear() } catch (_: Exception) {}
            try { block?.clear() } catch (_: Exception) {}
            try { dsp?.clear() } catch (_: Exception) {}
            try { comment?.clear() } catch (_: Exception) {}
            try { info?.clear() } catch (_: Exception) {}
        }
        return result
    }
}
