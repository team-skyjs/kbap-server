package com.kbap.batch

import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 컴포넌트 스캔은 배치 자신 + LLM 어댑터 설정만 — 도메인 서비스(@Service)는 타 도메인/외부 seam 을
// 조합하므로 배치 컨텍스트에 올리지 않는다(배치는 레포지토리를 직접 조립). 엔티티/레포지토리 스캔은
// @AutoConfigurationPackage(com.kbap) 이 담당.
@SpringBootApplication(scanBasePackages = ["com.kbap.batch", "com.kbap.common.infra.llm"])
@AutoConfigurationPackage(basePackages = ["com.kbap"])
class KbapBatchApplication

fun main(args: Array<String>) {
    runApplication<KbapBatchApplication>(*args)
}
