package com.kbap.infra.llm.food

import com.kbap.core.storage.StorageObjectMetadata
import com.kbap.core.storage.StorageObjectStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.ai.image.Image
import org.springframework.ai.image.ImageGeneration
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImagePrompt
import org.springframework.ai.image.ImageResponse
import java.util.Base64

private class StubImageModel(val b64: String?) : ImageModel {
    override fun call(request: ImagePrompt): ImageResponse =
        ImageResponse(listOf(ImageGeneration(Image(null, b64))))
}

private class InMemoryStore(val failOnPut: Boolean = false) : StorageObjectStore {
    val stored = mutableMapOf<String, ByteArray>()
    var putCount = 0
    override fun head(path: String): StorageObjectMetadata? = null
    override fun delete(path: String) {}
    override fun put(path: String, bytes: ByteArray, contentType: String) {
        if (failOnPut) throw RuntimeException("저장 실패")
        putCount++
        stored[path] = bytes
    }
}

class OpenAiFoodImageGenerationClientTest : BehaviorSpec({
    val b64 = Base64.getEncoder().encodeToString("이미지-바이트".toByteArray())

    given("정상 b64 이미지를 주는 모델과 인메모리 스토어") {
        val store = InMemoryStore()
        val client = OpenAiFoodImageGenerationClient(StubImageModel(b64), store)

        `when`("사진 생성을 호출하면") {
            val key = client.call("불고기", "food/bulgogi.png")
            then("이미지를 저장하고 storageKey 를 그대로 반환한다") {
                key shouldBe "food/bulgogi.png"
                store.stored.containsKey("food/bulgogi.png") shouldBe true
            }
        }

        `when`("같은 키로 재호출하면") {
            client.call("불고기", "food/bulgogi.png")
            client.call("불고기", "food/bulgogi.png")
            then("put 이 다시 호출되어 덮어쓴다(멱등)") {
                (store.putCount >= 2) shouldBe true
            }
        }
    }

    given("b64 데이터가 없는 응답을 주는 모델") {
        val client = OpenAiFoodImageGenerationClient(StubImageModel(null), InMemoryStore())

        `when`("사진 생성을 호출하면") {
            then("예외를 전파한다") {
                shouldThrow<IllegalArgumentException> { client.call("불고기", "food/bulgogi.png") }
            }
        }
    }

    given("저장이 실패하는 스토어") {
        val client = OpenAiFoodImageGenerationClient(StubImageModel(b64), InMemoryStore(failOnPut = true))

        `when`("사진 생성을 호출하면") {
            then("키를 반환하지 않고 예외를 전파한다") {
                shouldThrow<RuntimeException> { client.call("불고기", "food/bulgogi.png") }
            }
        }
    }
})
