package com.meogo.application.client.auth

import java.io.File
import java.util.Base64

object FirebaseCredentialsSource {
    fun resolve(json: String, path: String): ByteArray? {
        if (json.isNotBlank()) {
            return decode(json)
        }
        if (path.isNotBlank()) {
            val file = File(path)
            check(file.isFile) { "meogo.auth.firebase.credentials-path 가 가리키는 파일이 없습니다: $path" }
            return file.readBytes()
        }
        return null
    }

    private fun decode(json: String): ByteArray =
        runCatching { Base64.getDecoder().decode(json.trim()) }
            .getOrElse { json.toByteArray() }
}
