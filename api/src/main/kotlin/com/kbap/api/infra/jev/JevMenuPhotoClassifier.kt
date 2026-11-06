package com.kbap.api.infra.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.api.core.image.ExifOrientation
import com.kbap.api.core.image.RasterImages
import com.kbap.common.port.llm.MenuPhotoClassificationFailed
import com.kbap.common.port.llm.MenuPhotoClassifier
import com.kbap.common.port.llm.MenuPhotoVerdict
import com.kbap.common.port.storage.StorageObjectStore
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class JevMenuPhotoClassifier(
    private val storage: StorageObjectStore,
    private val apiKey: String,
    timeout: Duration,
    private val maxImageBytes: Long,
    baseUrl: String = BASE_URL,
) : MenuPhotoClassifier {
    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build()).apply { setReadTimeout(timeout) },
        )
        .build()

    override fun classify(imagePath: String): MenuPhotoVerdict {
        val original = storage.get(imagePath) ?: throw MenuPhotoClassificationFailed("image_missing")
        if (original.size > maxImageBytes) throw MenuPhotoClassificationFailed("image_too_large")
        val jpeg = try {
            prepare(original)
        } catch (e: Exception) {
            throw MenuPhotoClassificationFailed("image_unreadable", e)
        }
        val ref = UUID.randomUUID().toString()
        val body = mapOf(
            "model" to MODEL,
            "state" to mapOf("photo" to ref),
            "assets" to mapOf(ref to mapOf("type" to "image", "format" to "jpg", "data_base64" to Base64.getEncoder().encodeToString(jpeg))),
            "questions" to mapOf(QUESTION_KEY to QUESTION),
        )
        val response = try {
            restClient.post()
                .uri("/v1/systemone")
                .header("Authorization", "Bearer $apiKey")
                .header("TypeSafe-Danger-Zone-Images", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
        } catch (e: HttpStatusCodeException) {
            throw MenuPhotoClassificationFailed(statusReason(e.statusCode.value()))
        } catch (e: RuntimeException) {
            throw MenuPhotoClassificationFailed("transport", e)
        }
        return parse(response)
    }

    companion object {
        const val MODEL = "jev-1.14.0"
        const val MAX_LONG_SIDE = 1536
        const val MIN_SHORT_SIDE = 280
        private const val BASE_URL = "https://api.typesafe.ai"
        private const val QUESTION_KEY = "category"
        private const val MENU = "MENU"
        private const val JPEG_QUALITY = 0.85f
        private const val MAX_PIXELS = 50_000_000L
        private val PAD = Color(128, 128, 128)
        private val mapper = ObjectMapper()
        val LABELS = listOf(MENU, "PACKAGE_FRONT", "PACKAGE_BACK", "OTHER")

        val QUESTION: Map<String, Any> = mapOf(
            "type" to "choice",
            "instructions" to "Which scan category best matches the main subject in the photo? Choose one.",
            "criteria" to LABELS.zip(
                listOf(
                    "Restaurant menu listing dishes or drinks available to order, often with prices.",
                    "Front of a packaged food or drink showing its brand and product name.",
                    "Information face of a food package showing ingredients, nutrition facts or allergen information.",
                    "Anything else, including nonfood packaging, signs, documents or unrelated scenes.",
                ),
            ).toMap(),
        )

        fun statusReason(status: Int): String = when {
            status == 429 -> "rate_limited"
            status >= 500 -> "server_error"
            else -> "client_error"
        }

        fun parse(json: String?): MenuPhotoVerdict {
            val root: JsonNode = runCatching { mapper.readTree(json.orEmpty()) }.getOrNull() ?: throw MenuPhotoClassificationFailed("invalid_response")
            if (root.path("model").asText() != MODEL) throw MenuPhotoClassificationFailed("invalid_response")
            val answer = root.path("answers").path(QUESTION_KEY)
            val choice = answer.path("choice").takeIf { it.isTextual }?.asText()
            val probabilities = answer.path("probabilities")
            if (choice !in LABELS || !probabilities.isObject) throw MenuPhotoClassificationFailed("invalid_response")
            if (choice != MENU) return MenuPhotoVerdict.NotMenu
            val menu = probabilities.path(MENU)
            val p = if (menu.isNumber) menu.asDouble() else Double.NaN
            if (p.isNaN() || p !in 0.0..1.0) throw MenuPhotoClassificationFailed("invalid_response")
            return MenuPhotoVerdict.Menu(p)
        }

        fun prepare(original: ByteArray): ByteArray {
            val decoded = ImageIO.createImageInputStream(ByteArrayInputStream(original)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("읽을 수 없는 이미지 형식")
                try {
                    reader.input = input
                    require(reader.getWidth(0).toLong() * reader.getHeight(0) <= MAX_PIXELS) { "픽셀 수 상한 초과" }
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
            val oriented = RasterImages.oriented(decoded, ExifOrientation.of(original))
            val scale = minOf(1.0, MAX_LONG_SIDE.toDouble() / maxOf(oriented.width, oriented.height))
            val width = (oriented.width * scale).toInt().coerceAtLeast(1)
            val height = (oriented.height * scale).toInt().coerceAtLeast(1)
            val canvas = BufferedImage(maxOf(width, MIN_SHORT_SIDE), maxOf(height, MIN_SHORT_SIDE), BufferedImage.TYPE_INT_RGB)
            val g = canvas.createGraphics()
            try {
                g.color = PAD
                g.fillRect(0, 0, canvas.width, canvas.height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(oriented, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height, null)
            } finally {
                g.dispose()
            }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                val out = ByteArrayOutputStream()
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    writer.write(null, IIOImage(canvas, null, null), writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = JPEG_QUALITY })
                }
                return out.toByteArray()
            } finally {
                writer.dispose()
            }
        }
    }
}
