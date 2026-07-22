package com.kbap.infra.llm.food

import com.kbap.core.food.FoodImageGenerationClient
import com.kbap.core.storage.StorageObjectStore
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImagePrompt
import java.util.Base64

// 저장 성공이 키 반환의 전제 — 같은 키 재호출은 put 덮어쓰기로 멱등.
class OpenAiFoodImageGenerationClient(
    private val imageModel: ImageModel,
    private val storageObjectStore: StorageObjectStore,
) : FoodImageGenerationClient {

    override fun call(koreanName: String, storageKey: String): String {
        val response = imageModel.call(ImagePrompt(promptOf(koreanName)))
        val b64 = response.result?.output?.b64Json
        require(!b64.isNullOrBlank()) { "이미지 생성 응답에 b64 데이터가 없습니다: $koreanName" }

        val bytes = Base64.getDecoder().decode(b64)
        storageObjectStore.put(storageKey, bytes, "image/png")
        return storageKey
    }

    private fun promptOf(koreanName: String): String =
        "한국 음식 \"$koreanName\" 의 먹음직스러운 대표 사진. 밝은 조명, 깔끔한 배경, 사실적인 음식 사진."
}
