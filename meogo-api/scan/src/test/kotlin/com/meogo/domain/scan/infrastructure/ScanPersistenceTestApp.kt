package com.meogo.domain.scan.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * scan 모듈 단독 @DataJpaTest 를 위한 최소 @SpringBootConfiguration.
 * (실행 진입점은 :meogo-api:api 에 있으나 도메인 모듈은 그것을 의존하지 않으므로 테스트 전용 설정을 둔다.)
 * com.meogo.domain.scan 하위를 스캔해 JPA 엔티티·리포지토리를 탐지한다.
 */
@SpringBootApplication
class ScanPersistenceTestApp
