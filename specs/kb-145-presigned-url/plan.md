# Implementation Plan: 이미지 업로드용 presigned URL 발급 API

**Branch**: `kb-145-presigned-url` | **Date**: 2026-07-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-145-presigned-url/spec.md` (Jira KB-145)

## Summary

인증된 사용자에게 **S3 업로드용 presigned PUT URL**과 업로드 후 저장·표시에 쓸 **안정 공개(CDN) URL**을 함께 발급하는 단일 엔드포인트를 추가한다. 용도(purpose)별 prefix·정책으로 구분되는 범용 이미지 업로드 창구이며, 첫 소비자는 메뉴판 스캔이다.

**기술 접근**: auth 패턴을 그대로 따른다 — 무소속 유스케이스(`ImageUploadApplicationService`)와 seam 인터페이스(`PresignedUploadPort`)를 `:application`에 두고, S3 어댑터(`S3PresignedUploadPort`)는 신규 `:infra:storage` 모듈에, 컨트롤러·DTO·빈 조립은 `:app:api`에 둔다. **핵심 성질: SigV4 presign 은 로컬 서명 연산이라 발급 시 S3 를 호출하지 않는다** — DB·Flyway·엔티티·네트워크 왕복이 전부 없고, 발급은 순수 계산이다. 정책 로직(용도·Content-Type·크기 상한 검증, 객체 키 생성)은 seam 페이크로 S3 없이 단위 검증한다(헌법 I).

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web/validation), **AWS SDK for Java v2** — `software.amazon.awssdk:s3` + `s3-presigner` (신규, 버전 카탈로그 추가). Spring Boot BOM 은 AWS SDK 를 관리하지 않으므로 AWS SDK BOM 을 별도 platform 으로 고정.

**Storage**: AWS S3(오브젝트 스토리지)만 사용. **RDB·Flyway·JPA 엔티티 없음**(발급은 무상태). 읽기는 공개/CDN 경로(만료 없음).

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어) + MockMvc. 발급을 `PresignedUploadPort` seam 으로 추상화해 **페이크로 검증**(LocalStack·실 S3 불필요, 사용자 확정).

**Target Platform**: Linux 서버 — web bootJar `:app:api`.

**Project Type**: 모듈러 모놀리스 web service(멀티모듈 Gradle).

**Performance Goals**: presign 발급은 로컬 SigV4 서명(네트워크 없음) — p95 < 50ms. S3 도달성과 무관하게 발급 가능.

**Constraints**: AWS 자격증명 없이도 **부팅 안전**해야 한다(local·test) — 미구성 시 fallback port 로 조립(auth 의 `UnavailableSocialAuth` 패턴). 발급 경로에 DB 트랜잭션 없음.

**Scale/Scope**: 엔드포인트 1개, 신규 모듈 1개(`:infra:storage`), 마이그레이션 0건, 신규 의존성 1종(AWS SDK v2).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Test-First (NON-NEGOTIABLE)**: 통과. Red 우선 — `ImageUploadApplicationServiceTest`(페이크 port 로 정책·키 생성 검증) + `ImageUploadControllerTest`(MockMvc 401·400·200). 구현 전 실패 확인.
- **II. Bounded Contexts**: 통과. 도메인 소유 로직이 아닌 **무소속 유스케이스** → `:application`에 둔다(Home·Auth 와 동일 위상). 신규 도메인 모듈 없음, 도메인 간 결합 없음.
- **III. Layered Dependency Direction**: 통과. `:app:api → :application →`(seam). S3 어댑터는 `:infra:storage`에 격리하고 부트앱이 조립(외부 시스템 = seam+adapter, 폐기된 건 리포지토리 port 뿐). 의존 역전 없음.
- **IV. Persistence Encapsulation**: 해당 없음(영속 0). 엔티티·리포지토리 미생성.
- **V. Domain Content Language Policy**: 해당 없음(음식 콘텐츠·번역 무관).
- **Additional Constraints**: 통과. 외부 호출을 tx 안에 두지 않음(발급은 tx·네트워크 자체가 없음). 도메인/영속 모델을 응답으로 노출하지 않음(전용 DTO).

**위반 없음** → Complexity Tracking 비움.

## Project Structure

### Documentation (this feature)

```text
specs/kb-145-presigned-url/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정(AWS SDK·PUT vs POST·읽기 URL·부팅 안전)
├── data-model.md        # Phase 1 — 값 타입(엔티티 없음)
├── quickstart.md        # Phase 1 — 프로필 설정·클라이언트 업로드 규격·스캔 사용
├── contracts/
│   └── upload-url.md     # POST /api/v1/images/upload-url 계약
└── tasks.md             # /speckit-tasks 산출(이 명령 아님)
```

### Source Code (repository root)

auth 패턴을 미러링한다. **신규 모듈 `:infra:storage`** 하나 + 기존 3개 모듈에 파일 추가.

```text
core/
└── src/main/kotlin/com/kbap/core/error/
    └── ErrorCode.kt                       # (수정) UPLOAD-001/002/003 추가

application/
└── src/main/kotlin/com/kbap/application/upload/
    ├── ImageUploadApplicationService.kt   # (신규) 정책 소유 — 검증·객체 키 생성·seam 위임
    ├── PresignedUploadPort.kt          # (신규) seam 인터페이스
    ├── UnavailablePresignedUploadPort.kt # (신규) 미구성 fallback(부팅 안전)
    ├── ImageUploadProperties.kt           # (신규) 정책값(허용 타입·max bytes·TTL·purpose prefix·public-base-url)
    └── dto/
        ├── UploadPurpose.kt               # (신규) enum — MENU_SCAN(초기), (REVIEW placeholder)
        ├── PresignUploadCommand.kt        # (신규) 서비스 입력
        └── PresignedUpload.kt             # (신규) 서비스 결과(uploadUrl·publicUrl·objectKey·expiresAt·requiredHeaders)
└── src/test/kotlin/com/kbap/application/upload/
    └── ImageUploadApplicationServiceTest.kt  # (신규 Red) 페이크 port

infra/storage/                              # ── 신규 모듈 ──
├── build.gradle.kts                        # kbap.spring-conventions + aws bom·s3·s3-presigner + :application·:core
└── src/main/kotlin/com/kbap/infra/storage/
    ├── S3PresignedUploadPort.kt         # (신규) S3Presigner 로 PUT presign + public URL 조립
    └── config/StorageConfiguration.kt      # (신규) @ConditionalOnProperty("kbap.storage.bucket") 실 port 빈
└── src/test/kotlin/com/kbap/infra/storage/
    └── S3PresignedUploadPortTest.kt     # (신규 Red, 경량) public URL 조립·presigner 호출 인자

app/api/
├── src/main/kotlin/com/kbap/app/api/upload/
│   ├── ImageUploadController.kt            # (신규) POST /api/v1/images/upload-url
│   ├── ImageUploadApi.kt                   # (신규) swagger 문서 인터페이스
│   ├── UploadUrlRequest.kt                 # (신규) { purpose, contentType, contentLength }
│   └── UploadUrlResponse.kt                # (신규) 발급 결과 DTO
├── src/main/kotlin/com/kbap/app/api/config/
│   └── StorageConfig.kt                    # (신규) ImageUploadProperties 빈 + fallback port(@ConditionalOnMissingBean)
├── src/main/resources/application*.yml     # (수정) kbap.storage.* / kbap.upload.* 프로필 값
├── build.gradle.kts                        # (수정) runtimeOnly(:infra:storage)
└── src/test/kotlin/com/kbap/app/api/upload/
    ├── ImageUploadControllerTest.kt        # (신규 Red) MockMvc
    └── FakePresignedUploadPortConfig.kt # (신규) 테스트 port 빈(scan 의 Unavailable* 테스트 config 패턴)

settings.gradle.kts                          # (수정) include(":infra:storage")
gradle/libs.versions.toml                    # (수정) aws-sdk 버전 + aws-bom·aws-s3·aws-s3-presigner
```

**Structure Decision**: 외부 시스템(S3) 어댑터는 기존 관례대로 전용 `:infra:*` 모듈(`:infra:storage`)로 격리하고, seam 인터페이스·유스케이스는 `:application`(Auth 와 동일 위상), 컨트롤러·DTO·프로필 설정은 `:app:api`에 둔다. `:app:api`는 `runtimeOnly(:infra:storage)`로 조립해 AWS SDK 타입이 web 컴파일 클래스패스에 새지 않게 한다(infra:llm 조립 방식과 동일). 도메인 모듈·배치는 무관(범위 밖).

## Complexity Tracking

> 위반 없음 — 비움.
