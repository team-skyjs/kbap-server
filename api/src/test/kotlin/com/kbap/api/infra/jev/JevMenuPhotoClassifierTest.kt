package com.kbap.api.infra.jev

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.llm.MenuPhotoClassificationFailed
import com.kbap.common.port.llm.MenuPhotoVerdict
import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

class JevMenuPhotoClassifierTest : BehaviorSpec({
    val mapper = jacksonObjectMapper()
    fun response(choice: Any?, menu: Any?, model: String = "jev-1.14.0"): String = mapper.writeValueAsString(
        mapOf("model" to model, "answers" to mapOf("category" to mapOf("type" to "choice", "choice" to choice, "probabilities" to mapOf("MENU" to menu, "OTHER" to 0.0)))),
    )
    fun jpeg(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
    fun sizeOf(bytes: ByteArray): Pair<Int, Int> = ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }

    given("Jev 응답 해석") {
        `when`("choice=MENU 이고 probabilities.MENU 가 0~1 이면") {
            then("그 확률을 준다(최상위 confidence 가 아니라 MENU 확률)") {
                JevMenuPhotoClassifier.parse(response("MENU", 0.98)) shouldBe MenuPhotoVerdict.Menu(0.98)
            }
        }
        `when`("다른 선택지를 고르면") {
            then("NotMenu 다") {
                JevMenuPhotoClassifier.parse(response("PACKAGE_BACK", 0.1)) shouldBe MenuPhotoVerdict.NotMenu
            }
        }
        `when`("모델 불일치·choice 누락·모르는 선택지·MENU 확률 누락·범위 밖·JSON 아님이면") {
            then("invalid_response 로 실패한다(기존 판독으로 보낸다)") {
                listOf(
                    response("MENU", 0.99, model = "jev-2.0"),
                    response(null, 0.99),
                    response("PRICE_TAG", 0.99),
                    response("MENU", null),
                    response("MENU", 1.2),
                    response("MENU", "0.99"),
                    "not json",
                ).forEach { body ->
                    shouldThrow<MenuPhotoClassificationFailed> { JevMenuPhotoClassifier.parse(body) }.reason shouldBe "invalid_response"
                }
            }
        }
    }

    given("Jev 로 보낼 사진 전처리") {
        `when`("짧은 변이 280px 보다 작으면") {
            then("여백을 더해 짧은 변을 280px 로 맞춘 JPEG 다(280 미만은 실제 400)") {
                sizeOf(JevMenuPhotoClassifier.prepare(jpeg(400, 100))) shouldBe (400 to 280)
            }
        }
        `when`("긴 변이 1536px 보다 크면") {
            then("긴 변 1536px 로 줄인다") {
                sizeOf(JevMenuPhotoClassifier.prepare(jpeg(3072, 2048))) shouldBe (1536 to 1024)
            }
        }
    }

    given("Jev HTTP 호출") {
        val received = AtomicReference<Map<String, Any?>>()
        val headers = AtomicReference<Map<String, String?>>()
        val status = AtomicReference(200)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/systemone") { exchange ->
                headers.set(mapOf("auth" to exchange.requestHeaders.getFirst("Authorization"), "danger" to exchange.requestHeaders.getFirst("TypeSafe-Danger-Zone-Images")))
                received.set(mapper.readValue(exchange.requestBody.readBytes(), Map::class.java) as Map<String, Any?>)
                val body = response("MENU", 0.99).toByteArray()
                exchange.sendResponseHeaders(status.get(), body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val photo = jpeg(600, 800)
        val storage = object : StorageObjectStore {
            override fun head(path: String): StorageObjectMetadata? = null
            override fun delete(path: String) = Unit
            override fun get(path: String): ByteArray? = photo.takeIf { path == "dev/images/packaged-scans/a.jpg" }
            override fun put(path: String, bytes: ByteArray, contentType: String) = Unit
            override fun list(prefix: String, afterPath: String?, limit: Int): List<StoredObject> = emptyList()
        }
        val classifier = JevMenuPhotoClassifier(storage, "test-key", Duration.ofSeconds(2), 2_097_152, "http://127.0.0.1:${server.address.port}")
        afterSpec { server.stop(0) }

        `when`("정상 응답이면") {
            then("모델·질문·이미지 헤더를 붙여 보내고 MENU 확률을 준다") {
                status.set(200)
                classifier.classify("dev/images/packaged-scans/a.jpg") shouldBe MenuPhotoVerdict.Menu(0.99)
                headers.get() shouldBe mapOf("auth" to "Bearer test-key", "danger" to "true")
                val body = received.get()
                body["model"] shouldBe "jev-1.14.0"
                (body["questions"] as Map<*, *>)["category"] shouldBe mapper.readValue(mapper.writeValueAsString(JevMenuPhotoClassifier.QUESTION), Map::class.java)
                val ref = (body["state"] as Map<*, *>)["photo"]
                val asset = (body["assets"] as Map<*, *>)[ref] as Map<*, *>
                asset["format"] shouldBe "jpg"
                sizeOf(Base64.getDecoder().decode(asset["data_base64"] as String)) shouldBe (600 to 800)
            }
        }
        `when`("400·429·503 이면") {
            then("각각 client_error·rate_limited·server_error 로 실패한다") {
                mapOf(400 to "client_error", 429 to "rate_limited", 503 to "server_error").forEach { (code, reason) ->
                    status.set(code)
                    shouldThrow<MenuPhotoClassificationFailed> { classifier.classify("dev/images/packaged-scans/a.jpg") }.reason shouldBe reason
                }
            }
        }
        `when`("저장소에 사진이 없으면") {
            then("외부 호출 없이 image_missing 으로 실패한다") {
                received.set(null)
                shouldThrow<MenuPhotoClassificationFailed> { classifier.classify("dev/images/packaged-scans/none.jpg") }.reason shouldBe "image_missing"
                received.get() shouldBe null
            }
        }
    }
})
