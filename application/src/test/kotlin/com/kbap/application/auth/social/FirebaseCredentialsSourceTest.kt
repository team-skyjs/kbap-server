package com.kbap.application.auth.social

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Base64

class FirebaseCredentialsSourceTest : BehaviorSpec({

    val serviceAccountJson = """{"type":"service_account","project_id":"kbap"}"""

    given("base64 로 인코딩한 키 내용") {
        `when`("소스를 해석하면") {
            then("디코딩한 JSON 바이트를 돌려준다") {
                val encoded = Base64.getEncoder().encodeToString(serviceAccountJson.toByteArray())

                val bytes = FirebaseCredentialsSource.resolve(json = encoded, path = "")

                bytes.shouldNotBeNull()
                String(bytes) shouldBe serviceAccountJson
            }
        }
    }

    given("인코딩하지 않은 원본 JSON") {
        `when`("소스를 해석하면") {
            then("그대로 바이트로 돌려준다") {
                val bytes = FirebaseCredentialsSource.resolve(json = serviceAccountJson, path = "")

                bytes.shouldNotBeNull()
                String(bytes) shouldBe serviceAccountJson
            }
        }
    }

    given("base64 도 JSON 도 아닌 값") {
        `when`("소스를 해석하면") {
            then("원문 JSON 으로 오인하지 않고 명확한 예외를 던진다") {
                val encoded = Base64.getEncoder().encodeToString(serviceAccountJson.toByteArray())

                val e = shouldThrow<IllegalStateException> {
                    FirebaseCredentialsSource.resolve(json = encoded + "%", path = "")
                }

                e.message.shouldNotBeNull()
            }
        }
    }

    given("키 내용과 파일 경로가 모두 없을 때") {
        `when`("소스를 해석하면") {
            then("null 을 돌려준다(검증기 비활성)") {
                FirebaseCredentialsSource.resolve(json = "", path = "").shouldBeNull()
            }
        }
    }

    given("존재하지 않는 파일 경로") {
        `when`("소스를 해석하면") {
            then("예외를 던져 잘못된 설정을 즉시 드러낸다") {
                shouldThrow<IllegalStateException> {
                    FirebaseCredentialsSource.resolve(json = "", path = "/no/such/firebase.json")
                }
            }
        }
    }

    given("키 내용과 파일 경로가 모두 있을 때") {
        `when`("소스를 해석하면") {
            then("키 내용을 우선한다") {
                val bytes = FirebaseCredentialsSource.resolve(json = serviceAccountJson, path = "/no/such/firebase.json")

                String(bytes!!) shouldBe serviceAccountJson
            }
        }
    }
})
