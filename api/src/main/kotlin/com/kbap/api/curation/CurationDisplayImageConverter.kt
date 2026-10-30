package com.kbap.api.curation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

@Component
class CurationDisplayImageConverter(private val maxPixels: Long = MAX_PIXELS) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gate = Semaphore(1)

    fun toDisplayWebp(original: ByteArray): ByteArray? {
        if (!gate.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("큐레이션 표시용 변형 생성 대기 초과 — 원본만 쓴다")
            return null
        }
        return try {
            convert(original)
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            log.warn("큐레이션 표시용 변형 생성 실패 — 원본만 쓴다", e)
            null
        } finally {
            gate.release()
        }
    }

    private fun convert(original: ByteArray): ByteArray =
        encodeWebp(scaleToWidth(oriented(decode(original), ExifOrientation.of(original)), MAX_WIDTH))

    internal fun decode(original: ByteArray): BufferedImage =
        ImageIO.createImageInputStream(ByteArrayInputStream(original)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("읽을 수 없는 이미지 형식")
            try {
                reader.input = input
                val pixels = reader.getWidth(0).toLong() * reader.getHeight(0)
                require(pixels <= maxPixels) { "픽셀 수 상한 초과: $pixels" }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }

    private fun oriented(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            else -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        }
        val swapped = orientation >= 5
        return draw(if (swapped) image.height else image.width, if (swapped) image.width else image.height, image) { g -> g.drawImage(image, transform, null) }
    }

    private fun scaleToWidth(image: BufferedImage, maxWidth: Int): BufferedImage {
        var current = image
        if (current.width <= maxWidth) return draw(current.width, current.height, current) { g -> g.drawImage(current, 0, 0, null) }
        while (current.width / 2 >= maxWidth) {
            val source = current
            current = draw(source.width / 2, source.height / 2, source) { g -> g.drawImage(source, 0, 0, source.width / 2, source.height / 2, null) }
        }
        val source = current
        val height = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
        return draw(maxWidth, height, source) { g -> g.drawImage(source, 0, 0, maxWidth, height, null) }
    }

    private fun draw(width: Int, height: Int, source: BufferedImage, paint: (java.awt.Graphics2D) -> Unit): BufferedImage {
        val target = BufferedImage(width, height, if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            paint(g)
        } finally {
            g.dispose()
        }
        return target
    }

    private fun encodeWebp(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByMIMEType(WEBP).asSequence().firstOrNull() ?: error("WebP 인코더를 찾지 못했다")
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionType = compressionTypes.first { it.equals("Lossy", ignoreCase = true) }
                compressionQuality = QUALITY
            }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    companion object {
        const val WEBP = "image/webp"
        const val MAX_WIDTH = 1080
        const val MAX_PIXELS = 50_000_000L
        private const val QUALITY = 0.8f
        private const val WAIT_SECONDS = 30L
    }
}

internal object ExifOrientation {
    fun of(bytes: ByteArray): Int = runCatching { read(bytes) }.getOrDefault(1)

    private fun read(b: ByteArray): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
        var i = 2
        while (i + 4 <= b.size) {
            if (u8(i) != 0xFF) return 1
            val marker = u8(i + 1)
            val length = (u8(i + 2) shl 8) or u8(i + 3)
            if (marker == 0xDA) return 1
            if (marker == 0xE1 && String(b, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") return tiffOrientation(b, i + 10)
            i += 2 + length
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, t: Int): Int {
        val little = b[t].toInt() == 'I'.code
        fun u16(i: Int) = if (little) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = if (little) u16(i) or (u16(i + 2) shl 16) else (u16(i) shl 16) or u16(i + 2)
        val ifd = t + u32(t + 4)
        repeat(u16(ifd)) { n ->
            val entry = ifd + 2 + n * 12
            if (u16(entry) == ORIENTATION_TAG) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    private const val ORIENTATION_TAG = 0x0112
}
