package com.kbap.api.processedfood

import com.kbap.common.port.llm.MenuPhotoClassifier
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.time.Duration

class MenuFastPathTest : BehaviorSpec({
    given("메뉴판 바로가기 — Jev 키가 없어 분류기 빈이 없을 때") {
        `when`("플래그·실험 대상·신호가 모두 맞아도") {
            then("Jev 를 부르지 않고 기존 판독으로 보낸다") {
                val noClassifier = StaticListableBeanFactory().getBeanProvider(MenuPhotoClassifier::class.java)
                val fastPath = MenuFastPath(noClassifier, SimpleMeterRegistry(), true, listOf(1L), 0.98, Duration.ofSeconds(1), 4)
                fastPath.tryMenu(1L, "dev/images/packaged-scans/a.jpg", requested = true) shouldBe null
                fastPath.shutdown()
            }
        }
    }
})
