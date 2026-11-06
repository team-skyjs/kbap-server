package com.kbap.api.processedfood

import com.kbap.common.port.llm.MenuPhotoClassifier
import com.kbap.common.port.llm.MenuPhotoVerdict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.core.io.FileSystemResource
import java.time.Duration

class MenuFastPathTest : BehaviorSpec({
    val path = "prod/images/packaged-scans/a.jpg"
    fun fastPath(classifier: MenuPhotoClassifier?, memberIds: List<Long> = emptyList(), rolloutPercent: Int = 0): MenuFastPath {
        val beans = StaticListableBeanFactory().apply { classifier?.let { addBean("classifier", it) } }
        return MenuFastPath(beans.getBeanProvider(MenuPhotoClassifier::class.java), SimpleMeterRegistry(), true, memberIds, 0.98, Duration.ofSeconds(1), 4, rolloutPercent)
    }
    fun menuClassifier() = FakeMenuPhotoClassifier().apply { program(path) { MenuPhotoVerdict.Menu(0.99) } }

    given("메뉴판 바로가기 — Jev 키가 없어 분류기 빈이 없을 때") {
        `when`("플래그·실험 대상·신호·전체 비율이 모두 맞아도") {
            then("Jev 를 부르지 않고 기존 판독으로 보낸다") {
                val fastPath = fastPath(null, listOf(1L), rolloutPercent = 100)
                fastPath.tryMenu(1L, path, requested = true) shouldBe null
                fastPath.shutdown()
            }
        }
    }

    given("메뉴판 바로가기 대상 범위(S-976)") {
        `when`("비율이 0 이고 명단에 없는 회원이면") {
            then("Jev 를 부르지 않는다") {
                val classifier = menuClassifier()
                val fastPath = fastPath(classifier, listOf(1L))
                fastPath.tryMenu(2L, path, requested = true) shouldBe null
                classifier.calls.get() shouldBe 0
                fastPath.shutdown()
            }
        }
        `when`("명단에 있는 회원이면") {
            then("비율과 무관하게 대상이다") {
                val fastPath = fastPath(menuClassifier(), listOf(1L))
                fastPath.tryMenu(1L, path, requested = true) shouldNotBe null
                fastPath.shutdown()
            }
        }
        `when`("비율이 회원 버킷과 같거나 크면") {
            then("버킷 < 비율인 회원만 대상이고, 버킷은 같은 회원에 늘 같다") {
                val member = (1L..1000L).first { MenuFastPath.bucketOf(it) in 1..98 }
                val bucket = MenuFastPath.bucketOf(member)
                MenuFastPath.bucketOf(member) shouldBe bucket
                val atBucket = menuClassifier()
                fastPath(atBucket, rolloutPercent = bucket).run { tryMenu(member, path, requested = true) shouldBe null; shutdown() }
                atBucket.calls.get() shouldBe 0
                fastPath(menuClassifier(), rolloutPercent = bucket + 1).run { tryMenu(member, path, requested = true) shouldNotBe null; shutdown() }
                fastPath(menuClassifier(), rolloutPercent = 100).run { tryMenu(member, path, requested = true) shouldNotBe null; shutdown() }
            }
        }
        `when`("버킷 분포를 보면") {
            then("연속 회원 id 1만 명이 0~99 에 고르게 퍼진다") {
                val counts = (1L..10_000L).groupingBy { MenuFastPath.bucketOf(it) }.eachCount()
                counts.keys shouldBe (0..99).toSet()
                (counts.values.max() < 150) shouldBe true
            }
        }
        `when`("비율이 0~100 밖이면") {
            then("기동에 실패한다") {
                shouldThrow<IllegalArgumentException> { fastPath(null, rolloutPercent = 101) }
                shouldThrow<IllegalArgumentException> { fastPath(null, rolloutPercent = -1) }
            }
        }
    }

    given("메뉴판 바로가기 설정의 프로필별 기본값(S-976)") {
        fun raw(file: String, key: String): String? =
            YamlPropertiesFactoryBean().apply { setResources(FileSystemResource("src/main/resources/$file")) }.`object`!!.getProperty(key)
        fun defaultOf(file: String, key: String): String? = raw(file, key)?.let { Regex("""^\$\{[^:}]+:([^}]*)}$""").find(it)?.groupValues?.get(1) ?: it }
        val prefix = "kbap.processed-food.vision.menu-fast-path"

        `when`("환경변수 없이 뜨면") {
            then("기본 설정에 바인딩돼 있고 꺼짐·키 없음·명단 없음·비율 0 이며, 다른 프로필은 덮지 않는다") {
                defaultOf("application.yml", "$prefix.enabled") shouldBe "false"
                defaultOf("application.yml", "$prefix.api-key") shouldBe ""
                defaultOf("application.yml", "$prefix.member-ids") shouldBe ""
                defaultOf("application.yml", "$prefix.rollout-percent") shouldBe "0"
                raw("application.yml", "$prefix.enabled") shouldBe "\${PROCESSED_FOOD_MENU_FAST_PATH_ENABLED:false}"
                raw("application.yml", "$prefix.api-key") shouldBe "\${JEV_API_KEY:}"
                raw("application.yml", "$prefix.member-ids") shouldBe "\${PROCESSED_FOOD_MENU_FAST_PATH_MEMBER_IDS:}"
                raw("application.yml", "$prefix.rollout-percent") shouldBe "\${PROCESSED_FOOD_MENU_FAST_PATH_ROLLOUT_PERCENT:0}"
                listOf("application-prod.yml", "application-dev.yml", "application-staging.yml", "application-local.yml").forEach { file ->
                    listOf("enabled", "api-key", "member-ids", "rollout-percent").forEach { raw(file, "$prefix.$it") shouldBe null }
                }
            }
        }
    }
})
