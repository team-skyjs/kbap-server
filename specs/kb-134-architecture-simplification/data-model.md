# Data Model: 아키텍처 단순화 (KB-134)

DB 스키마 무변경. 이 문서는 "데이터"가 아니라 **코드 구조의 이동·삭제·전환 맵**이다.

## 1. 모듈 매핑

| 현행 모듈 | 변경 후 | 패키지 | 비고 |
|---|---|---|---|
| `:core:kernel` | `:core` | `com.meogo.core` | + BaseEntity·EntityStatus·FoodId·MemberId·IdConverter, testFixtures(컨테이너 설정) |
| `:core:food` | `:domain:food` | `com.meogo.domain.food` | + 영속 코드(internal) + FoodService |
| `:core:member` | `:domain:member` | `com.meogo.domain.member` | + 영속 코드(internal) + MemberService + RefreshTokenStore(Redis) |
| `:core:avoidance` | `:domain:avoidance` | `com.meogo.domain.avoidance` | + 영속 코드(internal) + AvoidanceSubstanceService |
| `:core:scan` | `:domain:scan` | `com.meogo.domain.scan` | + 영속 코드(internal) + ScanHistoryService |
| `:core:research` | `:domain:research` | `com.meogo.domain.research` | 코드 무변경(영속 없음) |
| `:core:review` | `:domain:review` | `com.meogo.domain.review` | placeholder 유지 |
| `:infra:persistence` | **삭제** | — | 아래 파일 이동표 |
| `:application:client`·`:infra:llm`·`:app:*`·`:common` | 유지 | 유지 | import 경로·의존 구성만 갱신 |

## 2. `:infra:persistence` 파일 이동표 (main 21파일)

| 파일 | 행선지 | 가시성 |
|---|---|---|
| `BaseEntity.kt`·`EntityStatus.kt` | `:core` `com.meogo.core.persistence` | public (전 도메인 상속) |
| `member/MemberJpaEntity.kt`·`MemberJpaRepository.kt`·`MemberStatus.kt`·`MemberProfileJson.kt` | `:domain:member` | internal |
| `member/MemberRepositoryAdapter.kt` | **폐기** → 로직은 `MemberService` 로 | — |
| `auth/RefreshTokenRedisAdapter.kt` | `:domain:member` 의 `RefreshTokenStore`(구체 클래스로 전환, 이름 승계) | public |
| `food/FoodJpaEntity.kt`·`FoodJpaRepository.kt`·`FoodAvoidanceSubstanceJpaEntity.kt` | `:domain:food` | internal |
| `food/FoodRepositoryAdapter.kt`·`FoodScoringSourceAdapter.kt` | **폐기** → 로직은 `FoodService` 로 | — |
| `avoidance/AvoidanceSubstanceJpaEntity.kt`·`AvoidanceSubstanceJpaRepository.kt`·`AvoidanceSubstanceReconstitutor.kt` | `:domain:avoidance` | internal |
| `avoidance/AvoidanceSubstanceRepositoryAdapter.kt` | **폐기** → `AvoidanceSubstanceService` | — |
| `scan/ScanHistoryJpaEntity.kt`·`ScanHistoryJpaRepository.kt` | `:domain:scan` | internal |
| `scan/ScanHistoryRepositoryAdapter.kt` | **폐기** → `ScanHistoryService` | — |

테스트(11파일): `*RepositoryAdapterTest` + `*PersistenceTestApp` → 각 도메인 모듈 `src/test` 의 `<도메인>ServiceTest` + TestApp 으로 전환. `FoodMatchKeySyncTest` 도 `:domain:food` 테스트로 이동. `RefreshTokenRedisAdapterTest` → `:domain:member` 의 `RefreshTokenStoreTest`. testFixtures(2파일): `MySqlContainerConfig`·`RedisContainerConfig` → `:core` testFixtures.

## 3. 삭제되는 port 인터페이스 (6종) → 승계 서비스

| port | 승계 public 창구 | 주 소비자 |
|---|---|---|
| `MemberRepository` | `MemberService` | application(member·auth·home·scan·food), app:api 랭킹 |
| `FoodRepository` | `FoodService` | application(food·home·scan) |
| `FoodScoringSource` | `FoodService` | app:batch 스코어링 잡 |
| `AvoidanceSubstanceRepository` | `AvoidanceSubstanceService` | application(food·home), app:batch |
| `ScanHistoryRepository` | `ScanHistoryService` | application(scan·home) |
| `RefreshTokenStore`(인터페이스) | `RefreshTokenStore`(Redis 구체 클래스) | application(auth) |

유지되는 seam(폐기 대상 아님): `ScannedNameInterpreter`(`:core` — `:infra:llm` 이 구현), application 내부 `SocialTokenVerifier`·`SocialAccountDeleter`·`AvoidedSubstanceProvider` 등 비-리포지토리 인터페이스.

## 4. 엔티티 연관관계 → id 값 클래스

**유일 대상**: `FoodJpaEntity` ↔ `FoodAvoidanceSubstanceJpaEntity`

| 항목 | 현행 | 변경 후 |
|---|---|---|
| Food → 자식 | `@OneToMany(cascade = ALL, orphanRemoval = true)` 컬렉션 | 컬렉션 제거 |
| 자식 → Food | (조인 컬럼) | `foodId: FoodId` 값 컬럼 |
| 자식 저장·삭제 | cascade·orphanRemoval 자동 | `FoodService` 가 자식 리포지토리로 명시 save/delete (교체 저장 시 기존 자식 delete → 신규 insert) |
| 애그리거트 조회 | fetch join | 부모 조회 + `foodId` 목록 일괄 조회 후 조립 |

**id 값 클래스** (`:core`, `@JvmInline`): `FoodId`·`MemberId` + `IdConverter<T>` base + `FoodIdConverter`·`MemberIdConverter`(`@Converter(autoApply = true)`). 적용 필드: `ScanHistoryJpaEntity.memberId/foodId`, `FoodAvoidanceSubstanceJpaEntity.foodId` + 대응 도메인 모델 필드(`ScanHistory`, `FoodAvoidanceSubstance`) + 서비스 메서드 시그니처. 자기 PK 는 `BaseEntity.id: Long` 유지. 주의: JPQL 파라미터 바인딩 시 값 클래스 언랩 확인(컨버터는 컬럼 매핑만 담당 — 파라미터는 통합 테스트로 검증).

**FK 제약(스키마, 무변경)**: `fk_fas_food`·`fk_fas_substance`·`fk_scan_history_member`·`fk_scan_history_food` 기존 존재 — 신규 마이그레이션 불요(research D8).

## 5. 의존 그래프 (변경 후)

```
:common  ← (모두 의존 가능 / 아무에게도 의존 안 함, 무변경)

:core (구 kernel + BaseEntity·id 값 클래스, jakarta/hibernate compileOnly)
   ▲ api
:domain:{food,member,avoidance,scan,research,review}   ← 도메인 모듈 간 상호 의존 금지(유지)
   ▲ implementation (모델·서비스·에러코드 public / 엔티티·리포지토리 internal)
:application:client (도메인 서비스 조합, @Transactional 경계 유지)
   ▲ implementation
:app:api (web bootJar, Flyway owner)      :app:batch (도메인 서비스 직접 사용, flyway off)
                                           :infra:llm ← :app:batch·:app:api (무변경)
```

runtimeOnly 조립 소멸 — 부트앱은 application(전이) 또는 직접 의존으로 도메인 모듈을 런타임에 갖는다.
