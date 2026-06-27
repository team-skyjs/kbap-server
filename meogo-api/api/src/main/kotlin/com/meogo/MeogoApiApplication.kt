package com.meogo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 컴포넌트 스캔 범위를 com.meogo 로 명시한다(멀티모듈 sibling: application/domain/infra/api 포함).
// 참고: @EntityScan/@EnableJpaRepositories 의 기본 base 도 이 진입점 패키지(com.meogo)이므로
// 도메인 모듈의 JPA 엔티티/리포지토리는 자동 탐지된다. 단 그 두 애너테이션은 JPA 타입을 import 해야 해
// api 컴파일 클래스패스로 JPA 가 새므로(캡슐화 위반) 여기 두지 않는다.
@SpringBootApplication(scanBasePackages = ["com.meogo"])
class MeogoApiApplication

fun main(args: Array<String>) {
	runApplication<MeogoApiApplication>(*args)
}
