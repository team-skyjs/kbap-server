package com.kbap.api.curation

import com.kbap.api.core.image.ExifOrientation
import com.kbap.api.core.image.RasterImages
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
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
        encodeWebp(scaleToWidth(RasterImages.oriented(decode(original), ExifOrientation.of(original)), MAX_WIDTH))

    internal fun decode(original: ByteArray): BufferedImage =
        ImageIO.createImageInputStream(ByteArrayInputStream(original)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("읽을 수 없는 이미지 형식")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val pixels = width.toLong() * height
                require(pixels <= maxPixels) { "픽셀 수 상한 초과: $pixels" }
                val displayWidth = if (ExifOrientation.of(original) >= 5) height else width
                val step = (displayWidth / MAX_WIDTH).coerceAtLeast(1)
                reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) })
            } finally {
                reader.dispose()
            }
        }

    private fun scaleToWidth(image: BufferedImage, maxWidth: Int): BufferedImage {
        var current = image
        if (current.width <= maxWidth) return RasterImages.draw(current.width, current.height, current) { g -> g.drawImage(current, 0, 0, null) }
        while (current.width / 2 >= maxWidth) {
            val source = current
            current = RasterImages.draw(source.width / 2, source.height / 2, source) { g -> g.drawImage(source, 0, 0, source.width / 2, source.height / 2, null) }
        }
        val source = current
        val height = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
        return RasterImages.draw(maxWidth, height, source) { g -> g.drawImage(source, 0, 0, maxWidth, height, null) }
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
