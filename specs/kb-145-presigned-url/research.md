# Research: 이미지 업로드용 presigned URL 발급 API (KB-145)

Phase 0 — 기술 결정. 각 항목은 결정 / 근거 / 대안.

## R1. 업로드 서명 방식 — Presigned PUT vs POST policy

**결정**: **Presigned PUT**(AWS SDK v2 `S3Presigner.presignPutObject`)을 쓴다. 발급 시 `Content-Type`과 **정확한 `Content-Length`(클라이언트 신고값)**를 서명 헤더에 포함해, 클라이언트는 같은 헤더로 PUT 해야 한다.

**근거**:
- AWS SDK **v2 는 presigned POST(`createPresignedPost`, POST policy)를 기본 제공하지 않는다**(v1 에만 있던 기능, v2 미지원). POST policy 를 쓰려면 정책 JSON+HMAC 서명을 손수 구현해야 해(보안 민감·비용 큼) 이득 대비 과하다.
- PUT 서명에 **exact Content-Length** 를 포함하면 S3 가 본문 길이를 그 값으로 강제한다 → 발급 단계에서 `contentLength > maxBytes` 를 거절(UPLOAD-003)하고, S3 가 실제 업로드 크기를 신고값으로 고정. DoD 의 "크기 상한(content-length-range)" **의도(과대 파일 차단)를 충족**한다(범위 대신 정확값이라 오히려 강함). 클라이언트는 파일 바이트 수를 이미 알므로 신고 부담이 없다.
- 클라이언트 단일 PUT 이라 멀티파트 form(POST) 보다 단순.

**대안**: (a) POST policy content-length-range — 진짜 범위 강제이나 v2 미지원·수제 서명 필요로 기각. (b) contentLength 미요구 + 발급 시 검증만 — S3 강제가 없어 무의미, 기각.

**주의(구현 시 확인)**: `PutObjectRequest.contentLength()`/`.contentType()` 이 presign 서명 헤더에 포함되는지 SDK 동작 확인. 클라이언트는 발급 응답의 `requiredHeaders`(Content-Type, Content-Length) 를 **그대로** PUT 에 실어야 한다(불일치 시 S3 가 403).

## R2. 읽기(조회) URL — 공개/CDN (사용자 확정)

**결정**: 조회는 **만료 없는 안정 공개 URL** `{public-base-url}/{objectKey}`(CloudFront CDN 등) 하나로 제공. 발급 응답에 `publicUrl`(및 `objectKey`)을 담고, 클라이언트가 이를 DB 저장·표시·백엔드 전달에 쓴다. **조회용 GET 서명·재발급 엔드포인트는 두지 않는다.**

**근거**: 이미지(메뉴·프로필·리뷰)는 민감정보가 아니고 앱·LLM 이 반복/직접 표시한다. 저장한 URL 이 만료되면 이미지가 깨지므로 서명 URL 저장은 부적합. 추측 불가능한 객체 키(R4)로 보호. KB-147 이 이미 `profileImageUrl` 을 CloudFront 호스트로 저장하는 방향과 일치. (Background 의 "버킷을 공개하지 않고도"는 **쓰기 보호** 취지로 해석 — 읽기 공개는 사용자 확정.)

**대안**: presigned GET(사설 버킷) — 저장 불가·재발급 필요·LLM fetch TTL 관리 부담으로 기각(spec 논의에서 종결).

**구현 노트**: `public-base-url` 은 프로필 설정. prod=CloudFront 배포 도메인, dev=dev 버킷/배포. 실제 S3↔CloudFront 조립(OAC 로 origin 사설 + CDN 공개, vs 버킷 public-read)은 **인프라 구성 사항**이며 발급 API 는 base-url 만 알면 된다(코드 무영향).

## R3. AWS SDK — 좌표·버전

**결정**: **AWS SDK for Java v2** — `software.amazon.awssdk:s3` + `software.amazon.awssdk:s3-presigner`. 버전은 AWS SDK **BOM**(`software.amazon.awssdk:bom`)으로 고정하고 개별 좌표는 버전 생략. 버전 카탈로그에 `aws-sdk` 버전 + 3좌표 추가. 최신 2.x(예: `2.31.x` — 구현 시 최신 확인).

**근거**: Spring Boot BOM 이 AWS SDK 를 관리하지 않으므로 직접 고정 필요(jjwt·firebase 를 카탈로그에 고정한 선례와 동일). `s3-presigner` 가 SigV4 로컬 서명 제공. `infra:storage` 에서 `platform(libs.aws.bom)` 적용.

**대안**: (a) AWS SDK v1 — POST policy 는 되나 유지보수 종료 라인·구식, 기각. (b) Spring Cloud AWS — 스타터가 무겁고 Boot 4/Spring AI 라인과 정합 리스크, 단일 발급 기능에 과함, 기각.

## R4. 객체 키 규칙

**결정**: `{purpose-prefix}/{yyyy}/{MM}/{dd}/{memberId}/{UUID}.{ext}`. 예: `menu-scan/2026/07/15/1024/3f2a....jpg`. prefix 는 `UploadPurpose` 별 매핑(MENU_SCAN→`menu-scan`). 확장자는 Content-Type 에서 도출(image/jpeg→jpg, image/png→png).

**근거**: purpose prefix 로 용도 구분(정책·수명주기·권한 분리 기반), 날짜 세그먼트로 운영 탐색성, `memberId` 로 소유 추적, `UUID` 로 **동시·연속 발급 충돌 불가**(FR-007). 추측 불가능(R2 공개 읽기의 보호 근거).

**대안**: 순번/타임스탬프만 — 충돌·추측 가능성으로 기각. 시각 기반 랜덤은 `Date.now` 제약과 무관(런타임 서비스 코드는 `Instant.now`/`UUID.randomUUID` 정상 사용 — 워크플로 스크립트 제약과 별개).

## R5. 부팅 안전(자격증명 미설정)

**결정**: auth 의 `UnavailableSocialAuth` 패턴을 미러링. `:infra:storage` 의 `StorageConfiguration` 이 `@ConditionalOnProperty("kbap.storage.bucket")` 로 **실 port** 를 조립하고, `:app:api` 의 `StorageConfig` 가 `@ConditionalOnMissingBean` 로 `UnavailablePresignedUploadPort`(호출 시 `BusinessException(INTERNAL_SERVER_ERROR)` 또는 명시적 비활성 에러) 를 fallback 으로 둔다. local·test 는 자격증명·버킷 없이 부팅되고, 컨트롤러 테스트는 페이크 port 빈으로 대체.

**근거**: LLM/Firebase 가 키 없으면 caller/verifier 빈을 안전 대체하는 기존 관례와 동일. `:app:api` 는 `:application` 의 Unavailable 타입만 참조(AWS SDK 무참조 유지).

**대안**: 항상 실 빈 생성 — local 부팅 실패로 기각.

## R6. 에러 코드

**결정**: `:core` `ErrorCode` 에 신규 접두 `UPLOAD` 추가:
- `UPLOAD-001`(400) — 지원하지 않는 이미지 형식(Content-Type).
- `UPLOAD-002`(400) — 지원하지 않는 업로드 용도(purpose).
- `UPLOAD-003`(400) — 파일이 허용 크기를 초과.
- (미구성 발급 불가 시 `COMMON-003` 재사용 — 신규 5xx 코드 불필요.)

**근거**: 도메인 접두 + 3자리 채번 규약. `KB-` 금지. 형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 강제(잘 지으면 통과).

## R7. 엔드포인트·계약

**결정**: `POST /api/v1/images/upload-url`(JWT 필수, `@AuthMemberId`). 요청 `{ purpose, contentType, contentLength }`, 응답 `BaseResponse<UploadUrlResponse>` = `{ uploadUrl, method:"PUT", requiredHeaders, publicUrl, objectKey, expiresAt }`. 경로는 `ApiPaths.V1 + "/images/upload-url"`.

**근거**: `/api/v` 규약·`BaseResponse` 봉투 준수. `requiredHeaders` 로 클라이언트가 PUT 시 실을 헤더를 명시(R1). 계약 상세는 [contracts/upload-url.md](./contracts/upload-url.md).

## R8. 테스트 전략 (헌법 I)

**결정**:
1. `ImageUploadApplicationServiceTest`(`:application`, 페이크 port) — **핵심 Red**: purpose/Content-Type/크기 검증 거절, 객체 키 포맷·연속 발급 유일성, port 위임 command 정확성, 결과 매핑. S3 무관.
2. `ImageUploadControllerTest`(`:app:api`, MockMvc BehaviorSpec) — 401(토큰 없음)·400(형식/용도/초과)·200(happy), 봉투 형태. 페이크 port 빈 주입(`FakePresignedUploadPortConfig`, scan 의 `UnavailableScannedNameInterpreterConfig` 선례).
3. `S3PresignedUploadPortTest`(`:infra:storage`, 경량) — public URL 조립(`base+key`)과 presigner 호출 인자 검증(실 S3Presigner + 더미 정적 자격증명 — 로컬 서명이라 네트워크 없음). LocalStack·실 S3 없음.
4. `ErrorCodeStatusTest` — 기존 테스트가 UPLOAD-* 형식·유일성 자동 커버.

**근거**: 발급 정책 전부를 seam 페이크로 S3 없이 검증(사용자 확정 R2/R5). 각 테스트는 구현 전 실패 확인.

## 미해결·후속(범위 밖)

- 업로드 완료 서버 추적·후처리(썸네일·재인코딩), 이미지 삭제/수명주기(S3 lifecycle) — 소비 기능·인프라 태스크.
- S3 버킷·IAM·CloudFront(OAC) 실제 프로비저닝 — 인프라 작업(코드 무영향, base-url·bucket 프로퍼티만 소비).
- 메뉴판 스캔이 발급 URL 을 실제로 소비하는 연결 — 스캔 태스크(KB-138 계열).
