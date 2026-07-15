# Research: 메뉴판 사진 스캔 (KB-138)

Phase 0 산출물 — Technical Context 의 미확정 사항을 결정으로 고정한다. 스펙 단계에서 사용자 확정 3건(완료 API 분리·기존 스캔 대체·위험도 평가 연결)은 전제로 두고, 설계 결정만 다룬다.

## R1. 업로드 이미지의 소유 컨텍스트 — 신규 `:domain:image` 모듈

- **Decision**: 업로드 이미지 기록(`UploadedImage` 엔티티 + `ImageUploadService`)을 신규 도메인 모듈 **`:domain:image`** 에 둔다. 리프 모듈(의존은 `:core` 뿐)이며, `:domain:scan` 이 `:domain:image` 를 단방향 의존해 스캔 전 검증·소유 확인을 조회한다.
- **Rationale**: 완료 검증 API 는 사용자 확정으로 스캔 전용이 아니라 향후 리뷰 사진 등도 재사용하는 범용 창구다. `:domain:scan` 에 두면 리뷰가 스캔을 의존하는 기형이 생긴다. 기존 도메인 간 단방향 의존 체인(`avoidance ← member ← food ← scan`)에 `image ← scan` 을 추가하는 확립된 패턴이다.
- **Alternatives considered**: (a) `:domain:scan` 내부에 두기 — 재사용 시 잘못된 방향의 의존 발생, 기각. (b) `:application` 승격 — 순환이 없으므로 불필요(가드레일: 순환이 실제 발생할 때만 승격), 기각.

## R2. S3 연동 — 신규 `:infra:storage` 모듈 + `:domain:image` seam

- **Decision**: seam 인터페이스 **`StorageObjectStore`**(`head(path): StorageObjectMetadata?` · `delete(path)`)를 소비 계층인 `:domain:image` 루트에 두고, 구현 `S3StorageObjectStore`(AWS SDK v2 `S3Client` — HeadObject·DeleteObject)를 신규 **`:infra:storage`** 모듈에 둔다. 빈 조립은 `:app:api` 의 `config/StorageConfig`(부트앱 조립 원칙), 의존은 `"implementation"(project(":infra:storage"))`.
- **Rationale**: "인터페이스는 소비 계층에, 구현은 `:infra:*` 에, 조립은 부트앱 config" 기존 패턴(`:infra:auth` 의 `SocialAccountDeleter` seam 과 동일 구조). KB-145(presigned 발급)도 같은 `:infra:storage` 에 presign 구현을 얹어 재사용한다 — 모듈 신설은 이번 태스크가 담당하고 발급 seam·구현은 KB-145 범위.
- **Alternatives considered**: (a) `:infra:llm` 처럼 모듈 안 `@Configuration` — llm 은 api·batch 양쪽 소비라 예외였고 storage 는 api 단일 소비자이므로 원칙(부트앱 조립)대로, 기각. (b) S3Presigner 까지 이번에 구현 — KB-145 범위 침범, 기각.
- 테스트: local·test 프로파일에서는 빈 미조립(`@ConditionalOnProperty(kbap.storage.enabled)`) — 도메인·MockMvc 테스트는 페이크 `StorageObjectStore` 로 검증(FR-017, 헌법 I). KB-145 의 "실 스토리지는 dev/prod 만" 가정과 일치.

## R3. Vision 추출 — `:core` seam + `:infra:llm` OpenAI 구현

- **Decision**: seam **`MenuBoardVisionExtractor`** 를 `:core`(`com.kbap.core.scan`) 에 두고(`extract(imagePath): List<ExtractedMenu>` — `ExtractedMenu(name, koreanName, priceKrw: Int?)`), 구현 `OpenAiMenuBoardVisionExtractor` 를 `:infra:llm` 에 둔다. 스파이크에서 검증한 시스템 프롬프트(JSON 포맷 `{"results":[{idx,name,koreanName,price}]}` + 천원 축약 가격 복원 지침)를 이식하고, 응답 파싱은 `MenuBoardResultParser` 로 분리해 단위 테스트한다.
- **Rationale**: 기존 `ScannedNameInterpreter`(`:core` seam → `:infra:llm` Upstage 구현, `:app:api` runtimeOnly + `@ConditionalOnProperty`) 와 완전히 동일한 패턴. 소비자는 `:domain:scan`.
- **Spring AI 확인 사항**: OpenAI 모델은 `UserMessage` 의 `Media`(URI) 로 **이미지 URL 을 직접 전달**할 수 있고(OpenAI 가 fetch — 이미지 바이트가 서버를 거치지 않는 전제 유지), `OpenAiChatOptions.responseFormat`(JSON) 으로 json_object 강제를 지원한다. ([Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/1.0/api/chat/openai-chat.html), [Multimodality API](https://docs.spring.io/spring-ai/reference/api/multimodality.html))
- **전체 URL 조합**: seam 입력은 path — 구현이 `kbap.llm.vision.image-base-url`(CDN 도메인) 프로퍼티와 조합해 전체 URL 을 만든다(FR-012, "URL 조합은 서버 책임"의 실현 지점. 외부 접근 URL 이 필요한 건 vision 호출이라는 인프라 세부이므로 도메인은 path 만 안다).
- **구성**: 기존 `kbap.llm.openai.*`(배치 채점용) 와 분리된 **`kbap.llm.vision.*`**(enabled·api-key·model=`gpt-4o-mini`·image-base-url) 프로퍼티 그룹 + `@ConditionalOnProperty` — 배치 채점 모델과 웹 vision 모델이 서로 결박되지 않는다. 미구성 시 빈 미생성으로 부팅 안전(기존 관례).
- **Alternatives considered**: (a) `LlmChatRequest` 에 imageUrl 필드 추가해 `LlmModelCaller` 재사용 — fan-out·과금 계측용 추상화에 vision 단일 호출을 욱여넣는 확장, 전용 구현이 더 단순해 기각(재사용 필요가 생기면 후속 승격). (b) 이미지 detail(low/high) 옵션 — 프로토타입은 auto 로 검증됐고 Spring AI 옵션 노출이 불확실하므로 이번엔 미사용(비용 이슈 시 후속).

## R4. 스캔 히스토리 스키마 — 단일 테이블 확장 (컬럼 추가)

- **Decision**: `scan_history` 단일 테이블을 유지하고 컬럼을 추가한다 — `image_path VARCHAR(512) NOT NULL`, `menu_name VARCHAR(100) NOT NULL`(표기 그대로), `korean_name VARCHAR(100) NOT NULL`, `price INT NULL`(KRW), `food_id` 는 **NULL 허용으로 완화**(미매칭 항목도 기록). 추출 항목 1건 = 1 row, 같은 스캔의 row 들은 image_path 를 공유한다.
- **Rationale**: 조회 소비자는 홈의 "최근 스캔 음식"(`findRecentReadyFoodIds`) 하나뿐 — 스캔 회차를 묶어 보는 요구가 없어 회차 테이블 정규화는 선행 비용만 생긴다. 컬럼 추가는 additive 라 기존 인덱스·FK 유지.
- **파생 변경**: `findRecentReadyFoodIds` 쿼리에 `food_id IS NOT NULL` 조건 추가(기존엔 매칭 항목만 저장해서 불필요했음). FK `fk_scan_history_food` 는 NULL 허용과 공존 가능(MySQL FK 는 NULL 을 검사하지 않음).
- **Alternatives considered**: `scan` + `scan_item` 2테이블 정규화 — 스캔 회차 조회 기능이 생기면 그때 마이그레이션(후속 승격 경로 명확), 지금은 기각.

## R5. 트랜잭션 경계 — vision 호출은 트랜잭션 밖

- **Decision**: 스캔 유스케이스는 (1) 이미지 검증·소유 확인(짧은 읽기 tx) → (2) vision 호출(**무트랜잭션**) → (3) food 매칭·히스토리 저장·스캔 횟수 증가(쓰기 tx) 의 3단으로 나눈다. public 진입 메서드는 트랜잭션을 선언하지 않고(사유 주석), 저장 단계만 `@Transactional` 로 감싼다.
- **Rationale**: 헌법 Additional Constraints — "외부 LLM 호출을 DB 트랜잭션 안에서 길게 잡지 않는다". 완료 검증 API 의 HeadObject/DeleteObject 도 외부 시스템 호출이므로 같은 원칙: 검증(외부) 후 기록만 tx.
- **Alternatives considered**: pending 저장 → 호출 → completed 전환(헌법 예시의 상태 기계) — 스캔 결과를 비동기로 소비하는 요구가 없어(동기 응답) 상태 컬럼만 늘어난다, 기각.

## R6. 기존 스캔 계약 대체 범위

- **Decision**: `POST /api/v1/scans` 요청 본문을 `{imagePath}` 로 교체하고 `ScanRequest.items`(rawMenuName)·`ScanInput`·`ScanItemInput`·정제(refinement) 경로를 제거한다. 응답은 기존 구조 유지 + 항목별 `price` 추가(FR-010a), `idx` 는 서버가 부여하는 추출 순번으로 의미 재정의, 미매칭 항목도 vision 의 name/koreanName 을 채워 반환한다. `degraded` 필드는 유지하되 vision 경로에선 폴백이 없으므로 항상 `false`(vision 실패는 에러 응답 — FR-011).
- **Rationale**: 사용자 확정(대체·공존 안 함) + additive 응답 규칙. `ScannedNameInterpreter` seam 은 vision 이 koreanName 을 직접 주므로 스캔 경로에서 호출이 사라진다 — seam·Upstage 구현은 다른 소비자가 없으면 제거 후보지만, 삭제는 별도 확인 후(이번 범위는 "스캔 경로에서 미사용"까지).
- **에러 코드 신설**: `IMAGE-001`(허용되지 않는 파일 형식, 400) · `IMAGE-002`(신고값과 실제 오브젝트 불일치, 400) · `IMAGE-003`(업로드된 오브젝트 없음, 400) · `SCAN-001`(검증되지 않았거나 접근할 수 없는 이미지, 400) · `SCAN-002`(메뉴판 인식 실패, 503). 형식·유일성은 `ErrorCodeStatusTest` 가 강제.

## R7. 완료 API 계약 — 경로·멱등

- **Decision**: `POST /api/v1/images/complete` — 요청 `{path, contentType, size}`, 성공 시 등록된 이미지 정보(path) 반환. 같은 path 재신고는 **멱등**(이미 검증·기록된 이미지면 재검증 없이 성공 응답). 인증 필수(`@AuthMemberId`), path 는 `@AuthMemberId` 회원 소유(KB-145 키 설계의 회원 식별 prefix)와 대조한다.
- **Rationale**: 리소스는 "업로드 이미지"이고 스캔 전용이 아니므로 `/images` 아래에 둔다(용도 확장 시 같은 창구). 멱등은 스펙 Edge Case 확정 사항.
- **Alternatives considered**: `PUT /api/v1/images/{path}` — path 에 슬래시가 포함돼 경로 변수로 부적합, 기각.
