package com.meogo.batch

import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 배치 앱. com.meogo 전체를 컴포넌트 스캔해 application/도메인/infra 빈을 조립한다.
// web 컨트롤러가 있는 :meogo-api:api 는 의존하지 않으므로 batch 클래스패스에 없고 스캔에도 잡히지 않는다.
//
// @AutoConfigurationPackage(com.meogo): 진입점이 com.meogo.batch 라 auto-config 기본 base 가
// com.meogo.batch 로 좁아진다. 이를 com.meogo 로 넓혀 도메인 모듈의 Spring Data JPA 리포지토리·
// JPA 엔티티(com.meogo.domain.*) 가 스캔되게 한다(api 앱은 진입점이 com.meogo 라 기본값으로 충족).
// @EntityScan/@EnableJpaRepositories 와 달리 JPA 타입을 import 하지 않아 batch 컴파일 클래스패스로 JPA 가 새지 않는다.
@SpringBootApplication(scanBasePackages = ["com.meogo"])
@AutoConfigurationPackage(basePackages = ["com.meogo"])
class MeogoBatchApplication

fun main(args: Array<String>) {
    runApplication<MeogoBatchApplication>(*args)
}
