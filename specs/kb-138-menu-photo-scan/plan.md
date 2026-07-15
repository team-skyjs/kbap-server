# Implementation Plan: 메뉴판 사진 스캔 — 업로드 완료 검증 + 메뉴명·가격 추출

**Branch**: `kb-138-menu-photo-scan` | **Date**: 2026-07-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-138-menu-photo-scan/spec.md`

## Summary

메뉴판 사진 기반 스캔으로 기존 OCR 텍스트 스캔을 대체한다. 클라이언트는 presigned 업로드(KB-145) 후 **완료 신고 API**(`POST /api/v1/images/complete`)로 실제 오브젝트의 사진 여부·신고값 일치를 검증받고(실패 시 오브젝트 삭제), 검증된 이미지의 **path 만으로 스캔**(`POST /api/v1/scans` — 요청 본문 교체)한다. 스캔은 gpt-4o-mini vision(Spring AI, 이미지 URL 직접 전달)으로 메뉴명(표기/표준 한국어)·가격(천원 축약 복원, KRW 정수)을 추출해 기존 food 매칭·회피성분 위험도 판정에 연결하고, 응답은 기존 구조에 `price` 를 얹는다(additive). 히스토리는 `scan_history` 확장(image_path·menu_name·korean_name·price, food_id nullable)으로 전 추출 항목을 기록하며 **가격은 히스토리에만 저장**된다. 신규 모듈 2개: `:domain:image`(업로드 이미지 기록·검증 창구, 리프) + `:infra:storage`(S3 HeadObject/DeleteObject 어댑터).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 toolchain (Gradle toolchain 자동 프로비저닝)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Spring AI 2.0 OpenAI 스타터(기존 `:infra:llm` — vision 은 `Media`(URI)·`responseFormat`(JSON) 사용), **AWS SDK for Java v2 `s3`(신규 — `:infra:storage`, 버전 카탈로그 등재)**, springdoc-openapi

**Storage**: MySQL(Flyway 마이그레이션 2건 — `uploaded_image` 생성, `scan_history` 확장). 가격 저장은 scan_history 한정

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + JUnit 5 플랫폼. 도메인 단위(페이크 seam)·`:infra:llm` 파서 단위·`:app:api` MockMvc 통합(MySQL Testcontainers). 실 S3·실 OpenAI 접근 없음(FR-017)

**Target Platform**: Linux 서버(`:app:api` bootJar). `:app:batch` 무관(범위 밖)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — web-service

**Performance Goals**: 스캔 응답은 vision 1회 호출 지연이 지배(프로토타입 실측 수 초). 히스토리 저장은 항목 수만큼 batch insert 1회, 매칭 조회는 기존 IN 쿼리 재사용(N+1 없음)

**Constraints**: 외부 호출(HeadObject·DeleteObject·vision)은 DB 트랜잭션 밖(헌법 Additional Constraints). 이미지 바이트는 서버를 경유하지 않음(URL 을 OpenAI 가 fetch). DB·클라이언트에는 path 만(도메인은 서버 설정)

**Scale/Scope**: 신규 모듈 2, 신규 API 1(완료 신고) + 교체 API 1(스캔), ErrorCode 5종, Flyway 2건, 신규 seam 2(`StorageObjectStore`·`MenuBoardVisionExtractor`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 모든 신규/변경 동작에 실패 테스트 선행(quickstart 테스트 매트릭스). 외부 시스템(S3·vision)은 seam 페이크로 Red→Green 검증 — tasks 단계에서 스토리별 테스트 task 가 구현 task 에 선행 |
| II. Bounded Contexts | ✅ PASS | 신규 `:domain:image` 는 리프(:core 만 의존). `image ← scan` 단방향 의존은 2026-07-14 대개편으로 확립된 도메인 간 단방향 조합 패턴(`settings.gradle.kts` 주석 "단방향 상호 의존 허용", 기존 `scan → food·member` 와 동일 — 헌법 II 원문의 ":application 조합" 문구는 대개편 이전 서술). 크로스 도메인 참조는 path/id 값 — `UploadedImage`↔`ScanHistory` 연관 없음 |
| III. Layered Dependency Direction | ✅ PASS | `:app:api` → `:domain:*` → `:core` 한 방향. 외부 시스템은 seam 으로만 — `StorageObjectStore`(소비 계층 `:domain:image` 에 인터페이스, 구현 `:infra:storage`, 조립 `app/api/config/StorageConfig`), `MenuBoardVisionExtractor`(`:core` seam, 구현 `:infra:llm`, `@ConditionalOnProperty` 조립 — 기존 `ScannedNameInterpreter` 와 동일 패턴) |
| IV. Persistence Encapsulation | ✅ PASS | `UploadedImageJpaRepository`·`ScanHistoryJpaRepository` 는 `internal`, 창구는 도메인 서비스. JPA 연관관계 0(전부 id/path 값 참조), FK 는 Flyway 스키마가 강제. (참고: "엔티티=도메인 모델" 은 대개편 이후 확립 — 기존 `ScanHistory` 와 동일하게 엔티티에 도메인 메서드 내장) |
| V. Domain Content Language | ✅ PASS(해당 최소) | 스캔 응답 표시명은 기존 스캔과 동일한 처리(현재 ko, 회원 언어 연동 TODO 유지). 가격·표기 메뉴명은 번역 대상 아님. 신규 번역 콘텐츠 없음 |
| 추가 제약: LLM·외부 호출 tx 밖 | ✅ PASS | R5 — 검증(외부)→기록(tx), 추출(외부)→저장(tx) 분리. pending/completed 상태 기계는 동기 응답이라 불채택(사유 research.md) |
| 추가 제약: 도메인 모델 API 노출 금지 | ✅ PASS | 응답은 `ScanResponse`·`ImageCompleteResponse` DTO |

**Post-Phase 1 재평가**: 설계 산출물(data-model·contracts) 기준 위반 없음 — Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-138-menu-photo-scan/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정 R1~R7
├── data-model.md        # Phase 1 — UploadedImage·ScanHistory 확장·값 타입·Flyway
├── quickstart.md        # Phase 1 — 로컬 실행·수동 검증·테스트 매트릭스
├── contracts/
│   ├── images-complete-api.md   # POST /api/v1/images/complete
│   └── scans-api.md             # POST /api/v1/scans (요청 교체·응답 additive)
└── tasks.md             # Phase 2 — /speckit-tasks 산출(이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/
├── error/ErrorCode.kt                     # [수정] IMAGE-001~003, SCAN-001~002 추가
└── scan/MenuBoardVisionExtractor.kt       # [신규] seam — extract(imagePath): List<ExtractedMenu>

domain/image/                              # [신규 모듈 — kbap.domain-conventions]
├── build.gradle.kts
└── src/main/kotlin/com/kbap/domain/image/
    ├── ImageUploadService.kt              # 창구 — completeUpload(검증·기록·멱등)·getVerifiedImage(소유 확인)
    ├── UploadedImageJpaRepository.kt      # internal
    ├── StorageObjectStore.kt              # seam — head(path)/delete(path) (+ StorageObjectMetadata)
    └── model/UploadedImage.kt             # 엔티티(=도메인 모델)

domain/scan/src/main/kotlin/com/kbap/domain/scan/
├── ScanService.kt                         # [수정] scanMenuBoardImage(memberId, imagePath) — assessMenuBoard·정제 경로 대체
├── ScanHistoryJpaRepository.kt            # [수정] findRecentReadyFoodIds 에 food_id IS NOT NULL
├── dto/ScanResult.kt                      # [수정] ItemRiskResult.price 추가·미매칭 name 채움
└── model/ScanHistory.kt                   # [수정] imagePath·menuName·koreanName·price, foodId nullable

infra/storage/                             # [신규 모듈 — kbap.spring-conventions]
├── build.gradle.kts                       # aws sdk v2 s3
└── src/main/kotlin/com/kbap/infra/storage/
    └── S3StorageObjectStore.kt            # StorageObjectStore 구현(HeadObject·DeleteObject)

infra/llm/src/main/kotlin/com/kbap/infra/llm/
├── config/LlmConfiguration.kt             # [수정] vision extractor 빈(@ConditionalOnProperty kbap.llm.vision.enabled)
├── config/LlmModelProperties.kt           # [수정] kbap.llm.vision.* (api-key·model·image-base-url)
└── menu/
    ├── OpenAiMenuBoardVisionExtractor.kt  # [신규] 구현 — Media(URI)+responseFormat(JSON), 스파이크 프롬프트 이식
    └── MenuBoardResultParser.kt           # [신규] JSON 응답 파싱(단위 테스트 대상)

app/api/src/main/kotlin/com/kbap/app/api/
├── image/                                 # [신규] ImageApi·ImageController·ImageCompleteRequest/Response
├── scan/                                  # [수정] ScanRequest(imagePath 교체)·ScanResponse(price)·ScanApi 문서
└── config/StorageConfig.kt                # [신규] S3Client·S3StorageObjectStore 조립(@ConditionalOnProperty kbap.storage.enabled)

app/api/src/main/resources/db/migration/   # [신규] uploaded_image 생성 + scan_history 확장 (timestamp 버전 2건)
settings.gradle.kts                        # [수정] :domain:image, :infra:storage 등록
gradle/libs.versions.toml                  # [수정] aws sdk v2 s3
```

**Structure Decision**: 기존 모듈러 모놀리스 컨벤션 유지. 신규 도메인 `:domain:image`(리프, `image ← scan` 단방향)과 신규 어댑터 `:infra:storage`(KB-145 presign 도 향후 이 모듈에 얹음). 테스트는 각 모듈 `src/test` 미러링 — `ImageUploadServiceTest`·`ScanServiceTest`(도메인, 페이크 seam), `MenuBoardResultParserTest`(파서 단위), `ImageControllerTest`·`ScanControllerTest`(MockMvc 통합, `@TestConfiguration` 페이크 빈).

## 설계 핵심 (요약 — 상세는 research.md)

- **R1** 업로드 이미지 소유 컨텍스트 = 신규 `:domain:image`(범용 — 리뷰 사진 재사용 대비, 스캔에 넣으면 역방향 의존 발생).
- **R2** S3 seam `StorageObjectStore` 는 `:domain:image` 에, 구현은 `:infra:storage`, 조립은 부트앱 config. presign 은 KB-145 가 같은 모듈에 추가.
- **R3** vision seam `MenuBoardVisionExtractor` 는 `:core`, 구현은 `:infra:llm`(전용 프로퍼티 `kbap.llm.vision.*`, 배치 채점 모델과 독립). 이미지는 URL 로 전달(서버 무경유), 전체 URL 조합은 구현이 `image-base-url` 로 수행.
- **R4** `scan_history` 단일 테이블 확장(회차 테이블은 소비자가 생기면 후속). `findRecentReadyFoodIds` 에 `food_id IS NOT NULL`.
- **R5** 외부 호출은 전부 tx 밖 — 검증→기록, 추출→저장의 짧은 tx 2회.
- **R6** 스캔 계약 대체: 요청 `{imagePath}`, 응답 additive(`price` 추가, `idx` 의미 재정의, 미매칭도 이름 채움, `degraded` 상수 false). 정제(`ScannedNameInterpreter`) 호출은 스캔 경로에서 제거(seam 자체 삭제는 별도 확인).
- **R7** 완료 API `POST /api/v1/images/complete`, 멱등, 소유 대조.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
