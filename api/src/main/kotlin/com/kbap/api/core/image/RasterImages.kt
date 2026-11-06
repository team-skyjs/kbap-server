package com.kbap.api.core.image

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

internal object RasterImages {
    fun oriented(image: BufferedImage, orientation: Int): BufferedImage {
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

    fun draw(width: Int, height: Int, source: BufferedImage, paint: (Graphics2D) -> Unit): BufferedImage {
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
