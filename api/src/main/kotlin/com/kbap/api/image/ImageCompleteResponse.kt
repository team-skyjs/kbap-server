package com.kbap.api.image

import com.kbap.domain.image.model.UploadedImage
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "업로드 완료 신고 결과 — 검증되어 기록된 이미지의 경로")
data class ImageCompleteResponse(
    @field:Schema(description = "검증된 이미지의 오브젝트 경로. 이후 스캔 요청에 이 값을 그대로 넘긴다.", example = "scan/123/20260715-abc123.jpg")
    val path: String,
) {
    companion object {
        fun from(image: UploadedImage): ImageCompleteResponse = ImageCompleteResponse(path = image.path)
    }
}
