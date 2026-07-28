# Data Model: 스프링 모듈 구조 다이어트 (kb-244)

**변경 없음.** 이 작업은 코드의 소속 Gradle 모듈만 바꾼다.

- 엔티티·값 객체·enum: 클래스·패키지·필드 전부 불변 (파일의 소속 모듈만 이동)
- DB 스키마: Flyway 마이그레이션 추가·수정 없음 (스키마 owner=api, batch flyway off 유지)
- Hibernate 매핑: `@AutoConfigurationPackage("com.kbap")`·패키지 불변이므로 엔티티 스캔 결과 동일

검증 관점: 통합 테스트(Testcontainers MySQL + Hibernate schema-generation)가 이동 전후 동일하게
통과하면 매핑 동일성이 입증된다.
