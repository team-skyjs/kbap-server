# Quickstart: 이미지 업로드용 presigned URL 발급 API (KB-145)

## 무엇을 만드나

`POST /api/v1/images/upload-url` — 인증 사용자에게 S3 업로드용 presigned PUT URL + 저장·표시용 공개 URL 을 발급. 신규 모듈 `:infra:storage`, seam·유스케이스는 `:application`, 컨트롤러는 `:app:api`. **DB·Flyway 없음.**

## 설정 (프로필별 `application-*.yml`)

```yaml
kbap:
  storage:            # :infra:storage 가 소비 — bucket 있으면 실 port 조립
    bucket: ""                      # local/test: 비움 → 발급 비활성(부팅은 정상)
    region: "ap-northeast-2"
    public-base-url: ""             # dev/prod: CloudFront/공개 베이스
  upload:             # :app:api 정책 바인딩(ImageUploadProperties)
    allowed-content-types: "image/jpeg,image/png,image/webp"
    max-bytes: 10485760             # 10MB
    upload-ttl: PT5M
```

- **local/test**: `bucket` 비움 → `UnavailablePresignedUploadPort` fallback(부팅 안전). 통합 테스트는 페이크 port 빈 주입.
- **dev/prod**: `bucket`·`region`·`public-base-url` 채움. AWS 자격증명은 `DefaultCredentialsProvider`(IAM role/인스턴스 프로파일/환경변수) — yml 에 키 하드코딩 금지.

## 빌드 배선

- `settings.gradle.kts` → `include(":infra:storage")`.
- `gradle/libs.versions.toml` → `aws-sdk` 버전 + `aws-bom`·`aws-s3`·`aws-s3-presigner`.
- `infra/storage/build.gradle.kts` → `kbap.spring-conventions` + `platform(libs.aws.bom)`·`aws-s3`·`aws-s3-presigner` + `implementation(:application)`·`implementation(:core)`.
- `app/api/build.gradle.kts` → `runtimeOnly(project(":infra:storage"))`.

## 테스트 (헌법 I — Red 먼저)

```bash
# 정책 단위(페이크 port, S3 무관) — 핵심
./gradlew :application:test --tests "com.kbap.application.upload.ImageUploadApplicationServiceTest"
# web 통합(MockMvc, 페이크 port 빈)
./gradlew :app:api:test --tests "com.kbap.app.api.upload.ImageUploadControllerTest"
# infra 경량(presigner mockk)
./gradlew :infra:storage:test
# 에러코드 형식·유일성(기존)
./gradlew :app:api:test --tests "*ErrorCodeStatusTest*"
```

각 테스트는 구현 전 **실패(Red)** 를 확인한 뒤 최소 구현으로 Green.

## 클라이언트 사용 흐름

1. `POST /api/v1/images/upload-url` `{ purpose, contentType, contentLength }` → `uploadUrl`·`requiredHeaders`·`publicUrl`.
2. `PUT uploadUrl`(body=이미지, `requiredHeaders` 그대로) → S3 직접 업로드(서버 미경유).
3. `publicUrl` 을 소비 API(프로필 수정·스캔)에 전달 → 백엔드 DB 저장. 메뉴판 스캔은 이 URL 을 vision LLM 이 fetch.

## 범위 밖

버킷/IAM/CloudFront 프로비저닝(인프라), 업로드 완료 추적·썸네일·삭제 수명주기, 소비 기능(프로필/리뷰/스캔) 연결.
