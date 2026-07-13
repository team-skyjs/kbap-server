package com.kbap.application.auth

import java.io.File
import java.util.Base64

object FirebaseCredentialsSource {
    fun resolve(json: String, path: String): ByteArray? {
        if (json.isNotBlank()) {
            return decode(json.trim())
        }
        if (path.isNotBlank()) {
            val file = File(path)
            check(file.isFile) { "kbap.auth.firebase.credentials-path 가 가리키는 파일이 없습니다: $path" }
            return file.readBytes()
        }
        return null
    }

    private fun decode(value: String): ByteArray {
        if (value.startsWith("{")) {
            return value.toByteArray()
        }
        return runCatching { Base64.getDecoder().decode(value) }
            .getOrElse {
                error(
                    "kbap.auth.firebase.credentials-json 이 base64 도 JSON 도 아닙니다. " +
                        "복사 과정에서 개행·따옴표·쉘 프롬프트 기호(%)가 섞이지 않았는지 확인하세요",
                )
            }
    }
}
