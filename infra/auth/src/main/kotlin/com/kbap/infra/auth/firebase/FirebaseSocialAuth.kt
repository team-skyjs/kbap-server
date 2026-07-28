package com.kbap.infra.auth.firebase

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.kbap.common.domain.member.SocialAccountDeleter
import com.kbap.common.application.auth.social.SocialTokenVerifier
import java.io.ByteArrayInputStream

// Firebase 자격증명이 설정된 경우에만 구현체를 만들어 준다 — 조립(빈 등록)은 부트앱 config 소관.
object FirebaseSocialAuth {
    fun tokenVerifierOrNull(credentialsJson: String, credentialsPath: String): SocialTokenVerifier? =
        firebaseAppOrNull(credentialsJson, credentialsPath)?.let { FirebaseTokenVerifier(it) }

    fun accountDeleterOrNull(credentialsJson: String, credentialsPath: String): SocialAccountDeleter? =
        firebaseAppOrNull(credentialsJson, credentialsPath)?.let { FirebaseAccountDeleter(it) }

    private fun firebaseAppOrNull(credentialsJson: String, credentialsPath: String): FirebaseApp? =
        FirebaseCredentialsSource.resolve(json = credentialsJson, path = credentialsPath)
            ?.let { firebaseApp(it) }

    private fun firebaseApp(credentials: ByteArray): FirebaseApp {
        FirebaseApp.getApps().firstOrNull()?.let { return it }
        val options = FirebaseOptions.builder()
            .setCredentials(ByteArrayInputStream(credentials).use { GoogleCredentials.fromStream(it) })
            .setHttpTransport(NetHttpTransport())
            .build()
        return FirebaseApp.initializeApp(options)
    }
}
