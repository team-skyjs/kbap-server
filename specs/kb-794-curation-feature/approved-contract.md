# KB-793 서울 큐레이션 계약·저장·게시 설계 검토안

작성: 2026-10-15 KST. **설계 검토용이며 미구현이다. 아래 신규 경로·필드·오류 코드·테이블은 dev Swagger에 존재하지 않는 제안이다.**

확정 범위는 서울 관광지·역·식당 3모음, 모음당 10항목, 기존 10로케일, 항목당 외부 동작 1개다. 디자인 승인은 콘텐츠·사진 권리·API·DB 적용·배포·게시 승인이 아니다. KB-794는 이 문서 검토 뒤 별도 구현 발주한다.

## 1. 결론과 근거 수준

- 새 `curation` 도메인과 공개/관리 계약이 필요하다. 현재 음식·리뷰·회원 업로드 계약을 장소 콘텐츠에 그대로 사용하지 않는다.
- 제안 저장은 **최초 공개 상태 1행 + 고정 모음 3행 + 관리자 업로드 자산**이다. 모음 행의 초안 JSON과 게시 JSON을 분리하고, 게시만 DB 트랜잭션으로 교체한다. 항목은 모음 내부 값이며 별도 장소 서비스·지도·검색·리뷰 모델을 만들지 않는다.
- 새로운 common 엔티티가 매핑할 **신규 테이블도 batch validate의 선행 조건**이다. 기존 컬럼 변경이 없어도 migration-only PR → dev/prod 적용 확인 → 동일 SQL 포함 기능 PR 순서를 적용한다.
- 재사용 가능: 관리자 JWT/권한, BaseResponse/에러 처리, 언어 코드, S3 presign/HEAD/CDN 기반 기능, 음식 초안의 버전 비교 UX. 재사용 불가: 음식 ID/초안 테이블, 회원 소유 업로드 서비스, 음식 이미지 갤러리, 리뷰 번역을 큐레이션 계약으로 간주하는 것.
- 게시 입력 미확보: 모음 대표 3장과 항목 30장의 실제 사진/이용 근거, 최종 30곳/순서, 필수 10언어 콘텐츠, 게시 직전 외부 목적지 확인. 디자인 placeholder와 조사 URL은 이 입력을 대신하지 않는다.

### 읽은 정본

- [발주 §1–3](../../../spec/specs/001-personalized-menu-mvp/seoul-curation-implementation-order-2026-10-15.md), [확정 기획](../../../spec/specs/001-personalized-menu-mvp/seoul-curation-plan-2026-10-15.md), [조사 후보](../../../spec/specs/001-personalized-menu-mvp/seoul-curation-research-2026-10-15.md).
- [승인 디자인 인계](../../../kbap-design/exports/D-29/FE-HANDOFF.md). 서버 검토는 그 문서의 상태/필드 요구를 읽은 범위다. Figma 클라우드 접근 성공이나 이미지 권리 확보로 간주하지 않는다.
- [KB-795 어드민 준비 §3](../../../kbap-admin/docs/seoul-curation-admin-preparation-2026-10-15.md). 커맨드센터가 검토 통과 후 전달한 요구를 반영했다. 기존 음식 갤러리는 업로더가 아니고 `translatedLangCount`는 필드 하나로도 언어 완료를 세므로, 큐레이션의 필수 10언어 검사에 쓰지 않는다.
- API 정본: [dev Swagger](https://dev.kbap.site/swagger-ui), 그 UI 설정이 지정한 `/v3/api-docs/1.0`, `/1.1`, `/2.0`, 전체 `/v3/api-docs`.
- 서버 원격 develop `a84a87d17624c0187ea09e26b35b03971d33b850`를 `git ls-remote`로 확인하고 `git show`로 읽었다. 로컬 HEAD `45b032c6`는 110커밋 뒤여서 현행 코드 근거로 사용하지 않았다.
- 어드민 환경/요청 패턴은 로컬 원격 추적 ref `d219226b7065efd0dd2edc9dde1f646cafe585fa`의 `src/lib/env.ts`, `api.ts`와 AGENTS.md를 읽었다. 어드민 배포본을 검증한 것은 아니며 KB-795의 확인과 합쳐야 한다.

### 증거 파일

- [배포 계약 관측](deployed-contract-evidence.json): 조회 시각·문서 해시·91개 경로 목록·기존 스키마 발췌·GET 실행 결과. 독립 OpenAPI 명세가 아닌 **당시 관측 자료**다.
- [dev DB 스키마 관측](dev-schema-evidence.json): `information_schema`의 테이블/컬럼 메타데이터만 조회했다. 계정·회원 데이터나 자격증명은 읽지 않았다. prod DB는 조회하지 않았다.
- 일시는 도구 실행 당시 KST 기준이다. DB 커넥터가 렌더링한 `NOW()`는 시각 기준으로 사용하지 않았다.

## 2. 실제 존재하는 계약과 재사용 범위

2026-10-15 **21:32:13~21:34:34 KST**에 HTTP 조회했다. 전체 OpenAPI는 91 paths, 그룹 1.0은 90, 1.1/2.0은 각 91 paths였다. 전체 경로 및 관련 operation/schema를 대조했으며 큐레이션·서울 모음 계약은 발견되지 않았다. 검색어에 안 걸리는 임의의 미문서화 기능까지 부재를 증명한 것은 아니다.

| 실제 계약 | 확인 내용 | 큐레이션 적용 판단 |
|---|---|---|
| `GET /api/home?lang=en` | 비인증 200. payload는 authenticated, avoidedSubstances, popularFoods, mostReviewedFoods, recentScans. 큐레이션 필드 없음 | 기존 응답은 유지. 큐레이션은 독립 GET 제안 |
| `POST /api/admin/auth/login` | id/password → accessToken. Swagger 401 `AUTH-009` | 기존 관리자 로그인 재사용. 로그인 실행은 안 함 |
| `GET /api/admin/auth/me` | 무토큰 실측 401 `AUTH-003`. Swagger는 USER 토큰 403 `AUTH-008`도 명시 | 관리자 확인 재사용. 정상 관리자/USER 토큰 실행은 안 함 |
| `POST /api/images/upload-url` | purpose, contentType, contentLength → uploadUrl, method, requiredHeaders, publicUrl, objectKey, expiresAt | 응답 모양·presign 기반 기능 재사용 후보. 관리 자산 계약 자체는 신규 |
| 기존 upload purpose | MENU_SCAN, REVIEW, PROFILE_IMAGE, COMMUNITY, FEEDBACK, ORDER_ITEM | CURATION 없음. FEEDBACK 게스트 업로드를 우회로로 사용하지 않음 |
| `POST /api/images/complete` | path, contentType, size. 발급 경로 소유·HEAD 형식/크기 검증. 응답 path | 회원/설치 ID 소유 모델. 관리자 ID를 memberId에 넣을 수 없음 |
| `GET /api/admin/foods/{foodId}/images`, `PUT .../{imageId}/primary` | 음식 갤러리와 foodVersion 경합 처리(409 FOOD-006) | 버전 재확인 UX 선례만 재사용. 장소 사진용 API 아님 |
| 음식 이미지 생성·재생성 API | `/images/generate`, `/regenerate-image` 등 음식 전용 | 사진 권리 확보나 장소 사진 업로드를 대체하지 않음 |
| `GET/PATCH /api/admin/foods/{foodId}/content-draft` | 음식 조사 결과 비교·승인/반려. draftId/foodVersion 요구 | 초안 격리·경합 처리 선례. 임의 장소 초안 CRUD 아님 |
| `POST /api/translations?lang=...` | targetType enum은 REVIEW만 | 10언어 편집 콘텐츠 생성/저장 API 아님 |
| 홈 공지/배너 | 현행 서버 Swagger에 해당 운영 계약 없음. dev DB 명칭 검색도 0건 | 기획의 공지 패턴 언급만으로 이 서버에 재사용 계약이 있다고 확정하지 않음 |

실측 GET: 정상 홈 `200/success=true`; lang 누락 `400/COMMON-002`; X-API-Version 누락 `400/COMMON-002`; 관리자 me 무토큰 `401/AUTH-003`. 다른 상태는 Swagger·소스 근거이며 실행 검증과 구분한다. 업로드·게시 등 POST/PUT/PATCH/DELETE는 **한 번도 실행하지 않았다**.

### 저장·어댑터 실측

- dev `uploaded_image`: member_id/installation_id, object_path, content_type, size_bytes, BaseEntity 필드. 관리자 소유·큐레이션 대상·사진 이용 근거·게시 참조는 없다.
- dev `food_content_draft`: food_id, outbox_id, 음식 설명/재료/매운맛/번역 JSON과 review_status. 독립 모음 편집용 테이블이 아니다.
- dev `food_image`: food_id와 image_key, source ENUM('GENERATED'), 대표/순서. 장소 사진의 의미와 다르다.
- dev `content_translation`: target_type/target_id/language/source_hash/translated_text. 현행 노출 계약은 REVIEW이고 게시본의 10언어 검증을 제공하지 않는다.
- dev 테이블명 curation/seoul/collection/banner/announcement 검색 0건. 실제 신규 저장소가 필요하다는 판단은 Swagger/소스와 함께 내렸다.
- `PresignedUploadPort.issue`와 `StorageObjectStore.head/delete/put/list`는 재사용 후보다. 현재 seam에는 파일 복사·원본 fingerprint 조건 검증이 없다. **확정 파일 생성은 어댑터 확장이 필요하다.**
- 소스 기본 업로드 정책은 JPEG/PNG/WebP, 10 MiB, presign 5분, complete 창 1일이다. 런타임 환경값/S3 CORS/IAM을 실측한 값은 아니다.
- 정리 작업은 기존 업로드 purpose/prefix와 음식·회원·리뷰 등 참조를 기준으로 판단한다. 큐레이션을 기존 purpose에 억지로 넣으면 정리의 보호 대상이 되지 않는다.

## 3. 신규 모델 제안 — 모두 미구현

패키지: 엔티티/레포 `common.domain.curation`, API 기능 `api.curation`, 관리 HTTP 조합은 기존 `api.admin` 관례. 외부 스토리지 호출은 port → api.infra.storage를 거친다. 도메인은 port/API를 참조하지 않는다. ArchUnit 허용 맵에 curation을 명시하며 다른 도메인 모델 의존은 추가하지 않는다.

### 식별자·콘텐츠

| 정보 | 제안 규칙 |
|---|---|
| collectionKey | `sights`, `stations`, `restaurants` 고정 3값. 홈 순서도 이 순서. 생성/분야 추가 API 없음 |
| entryId | 모음 안에서 유지하는 UUID. 편집/분석 식별자이며 food_id·기존 place 검색 ID가 아님. 최초 편집 때 생성, 변경·재정렬 때 유지 |
| 순서 | items 배열 순서가 단일 출처. 공개 position은 서버가 1부터 계산. position 필드의 별도 쓰기를 받지 않음 |
| placeIdentity | 내부 canonicalName + locationDiscriminator(주소/지점 또는 공식 역명·운영기관 등의 식별 근거). 역 주소가 조사 미확인이라는 이유로 가짜 주소를 만들지 않음 |
| 중복 | 같은 entryId, 정규화한 canonicalName/locationDiscriminator, 같은 정규화 외부 URL의 반복은 게시 차단. 다국어 별칭만 다른 동일 장소는 운영 확인도 필요. 자동 지오코딩/동일 장소 추론 없음 |
| 모음 | title/description의 로케일 map, cover(assetId+rights), items |
| 항목 | name/area/introduction/recommendationReason의 로케일 map, image(assetId+rights), externalUrl 하나, nearby 배열, 내부 evidence |
| nearby | 역만 2~3개. `{nearbyId, label:{locale:text}}`의 순서 있는 값 배열. 공개 응답은 문자열 배열. 관광지/식당은 빈 배열 |
| 내부 evidence | researchSources[], checkedOn, placeIdentity, externalLinkReview(URL·최종 목적지·확인일), editorialNotes, 게시 전 콘텐츠 확인 기록 |
| rights | basis(직접 촬영/허락/라이선스), sourceOrEvidenceRef, rightsHolder, permittedUse, checkedOn, attributionRequirement/text. 서버는 근거의 존재와 확인 기록을 검증할 뿐 권리의 법적 유효성을 자동 판정하지 않음 |

모든 콘텐츠 map의 키는 `ko/en/ja/zh-Hans/zh-Hant/vi/id/th/ru/es`다. 신규 HTML·Markdown 렌더링은 도입하지 않고 일반 텍스트를 저장한다. CTA 문구 `View guide`, 주변 안내 제목, 오류·분량은 앱 i18n의 책임이다. 서버가 HTML이나 추가 CTA를 내려주지 않는다. 유의 안내는 공개 소개/추천 이유에 번역해 포함하며 내부 메모로만 숨기지 않는다.

초안은 빠진 값·빈 문자열·0/9/11개 항목을 저장할 수 있다. JSON 형식, UUID 유일성, 지원하는 로케일 키, 기본 길이/크기 한계, 연결한 자산의 환경·모음 소유는 저장 때도 검사한다. 깨진 URL은 초안 텍스트로 저장 가능하되 외부 링크로 실행하지 않고 게시 검증에 실패시킨다.

기술 상한 제안: 초안 최대 50항목/본문 1 MiB, title 120자, description 400자, name 200자, area 120자, introduction 1000자, recommendationReason 600자, nearby label 200자, URL 2048자. 언어별 화면 잘림을 서버 길이 제한으로 해결하지 않는다. KB-794 DTO/Swagger에서 동일하게 고정할 검토값이다.

### 테이블 제안

모든 엔티티는 BaseEntity의 id/status/createdAt/updatedAt을 상속한다. JPA 관계 대신 Long FK를 사용한다. publication 상태는 BaseEntity.status와 구분한다. JSON은 도메인 값 타입으로 매핑하고 공개 DTO는 allowlist로 별도 생성한다.

| 테이블(가칭) | 주요 컬럼/제약 | 목적 |
|---|---|---|
| `curation_release` | region_key VARCHAR(20) UNIQUE='SEOUL', first_activated_at DATETIME(6) NULL, version BIGINT | 최초 3모음 공개 latch와 공개 변경 번호. 고정 1행 |
| `curation_collection` | release_id FK, collection_key VARCHAR(20), version BIGINT(@Version), draft_document JSON NOT NULL, published_document JSON NULL, published_revision BIGINT, published_source_version BIGINT NULL, is_published BOOLEAN, published_at/by, updated_by, unpublished_at/by | `(release_id, collection_key)` UNIQUE. 고정 3행. 초안/게시 데이터 격리. publication audit는 admin_account FK |
| `curation_asset` | collection_id FK, issued_by_admin_id FK, asset_state VARCHAR(20), staging_key VARCHAR(512) UNIQUE, final_key VARCHAR(512) NULL UNIQUE, content_type VARCHAR(100), size_bytes BIGINT, expires_at, complete_deadline, completed_at, unreferenced_since DATETIME(6) NULL, source_fingerprint VARCHAR(255) NULL | ISSUED/READY/REJECTED/EXPIRED/DELETING/DELETED, 환경별 저장. 관리자 업로드 자산. 회원 uploaded_image를 수정하지 않음 |

- 항목과 내부 근거는 모음 JSON에 함께 들어간다. 30곳의 고정 편집 문서에 검색용 장소 테이블·번역 행·범용 CMS를 추가하지 않는다. JSON을 받아 임의 객체 그대로 공개하는 방식은 금지한다.
- published_document는 게시 시 draft_document의 깊은 복사다. 사진 권리/조사 근거도 그 시점의 스냅샷으로 남기되 공개 응답에서 제거한다. 초안 메모를 고쳐도 기존 게시 근거를 바꾸지 않는다.
- published_revision은 게시 성공 때 증가한다. collection.version은 초안 저장/게시/내림 때 증가하고 모든 관리 수정 요청의 expectedVersion과 비교한다. release.version은 게시/내림 등 공개 상태 변경 때만 증가한다.
- 운영자는 draft와 마지막 published snapshot을 비교할 수 있다. 공개 내림은 is_published=false이며 기존 게시 스냅샷은 보존한다. 이번 범위에 히스토리 브라우저/임의 버전 롤백은 없다.
- `hasUnpublishedChanges`는 정규화한 초안과 마지막 게시 내용의 비교로 계산한다. 저장 후 published_revision이 증가했다고 오인하지 않는다.
- migration은 root/3모음의 **빈 초기 상태만** 만들 수 있다. 조사 후보·URL·번역·사진을 게시 데이터로 시드하지 않는다.

## 4. 게시 알고리즘·상태·동시성 제안

1. 초안 저장은 해당 collection만 잠그고 expectedVersion을 비교한다. 저장 가능성 검증 후 draft_document만 바꾼다. 공개 스냅샷/공개 상태는 변하지 않는다.
2. 게시 요청은 `curation_release → 대상 collection → 필요한 asset ID 오름차순`으로 잠근다. 다른 모음 게시/내림은 root로 직렬화한다. 초안 저장은 root를 잡지 않고 collection→asset 순서만 따른다. 역순 잠금을 만들지 않는다.
3. 트랜잭션 안에서 최신 expectedVersion, 필수 10개/10언어/사진/근거/URL 검증을 재실행한다. 별도 validate 성공을 게시 승인으로 신뢰하지 않는다. 외부 웹 GET/S3 copy/번역 호출은 트랜잭션 안에서 하지 않는다.
4. 통과한 모음 전체를 published_document로 한 번에 복사하고 is_published=true, published_revision/감사 필드를 갱신한다. validation/경합/DB 오류면 전체 롤백한다. 이전 게시본을 먼저 비우지 않는다.
5. 같은 게시 트랜잭션에서 세 고정 모음 **각각 `is_published=true AND 유효한 published_document`**임을 확인했을 때만 first_activated_at을 처음 설정한다. 공개 내림으로 보존된 스냅샷은 이 조건을 충족하지 않는다. 이 전에는 공개 홈과 상세 모두 준비된 1~2모음을 노출하지 않는다. 설정된 first_activated_at은 이후 내림으로 초기화하지 않는다.
6. 활성화 후 모음별 게시를 독립 갱신한다. 초안의 미완성은 홈을 숨기지 않는다. 하나를 공개 내리면 홈 index는 UNPUBLISHED/빈 collections가 된다. 나머지 두 모음의 게시 스냅샷은 유지한다.
7. **설계 범위 확정:** 최초 활성화 이후에는 아직 게시 중인 나머지 모음의 직접 진입을 허용한다. 공개 내린 모음은 404. 홈은 세 모음 모두 현재 게시 중일 때만 노출한다. 유효 스냅샷 결손은 정상 내림과 구분해 5xx로 처리한다.
8. 공개 조회는 `@Transactional(readOnly=true)`에서 root와 필요한 모음을 한 스냅샷으로 읽는다. 기본 REPEATABLE READ를 유지한다. 3모음 조회 실패를 부분 2카드 성공이나 UNPUBLISHED로 바꾸지 않는다.

정확한 10개·10언어·상태 불변을 이미 게시 검증에서 보장한다. 저장 손상/필수 자산 메타데이터 결손처럼 정상 미게시가 아닌 오류는 5xx다. 개별 CDN 이미지 fetch 장애는 클라이언트 fallback으로 텍스트/CTA를 유지한다.

최초 활성화 회귀 예시(A/B/C는 고정 세 모음의 별칭이며 모든 게시 스냅샷은 유효하다고 가정):

| 순서 | 현재 게시 중 | 보존 스냅샷 | 최초 활성화 latch | 홈 | 직접 상세 |
|---|---|---|---|---|---|
| A 게시 → B 게시 | A, B | A, B | false | UNPUBLISHED | 모두 404 |
| A 내림 | B | A, B | false | UNPUBLISHED | 모두 404 |
| C 게시 | B, C | A, B, C | **false** | UNPUBLISHED | 모두 404 |
| A 재게시 | A, B, C | A, B, C | true | PUBLISHED, 3카드 | 세 모음 200 |
| 활성화 후 A 내림 | B, C | A, B, C | true 유지 | UNPUBLISHED | A 404, B/C 200 |

동시 수정은 한 요청만 성공하고 나머지 409 + currentVersion으로 재확인한다. 자동 덮어쓰기·마지막 쓰기 승리·무조건 재시도 금지. 성공 응답을 잃어 재요청한 경우 409 후 관리 상세/개요를 재조회한다. 상세는 version, publishedSourceVersion(게시한 초안의 expectedVersion), publishedRevision, isPublished, publishedAt/By, updatedAt/By, unpublishedAt/By를 제공한다. 제출 내용/기대 버전과 비교해 현재 반영 상태를 보여주고 같은 게시본에 중복 게시를 강행하지 않는다. 그 뒤 다른 편집까지 발생해 과거 요청의 적용 여부를 증명할 수 없으면 '현재 서버 상태 확인 필요'로 남기며 자신의 성공/실패로 단정하지 않는다. 이번에는 별도 영구 작업 이력 API를 추가하지 않는다.

## 5. 공개 조회 계약 제안

**아래 경로는 신규 제안이고 현재 호출 가능한 계약이 아니다.** 별도 GET으로 기존 `/api/home` 장애 범위를 넓히지 않는다. 위치/로그인이 필요 없고 회원별 결과도 없다. 이 요청은 앱에서 Authorization을 보내지 않는 공개 조회로 정의한다. 인증된 관리자/회원 개인정보를 섞지 않는다.

공통: `X-API-Version` 필수. 신규 추가 경로이므로 기존 지원 버전에서 동작하는 `1.0+` 매핑을 제안한다. 3.1 같은 새 릴리스 번호를 임의로 확정하지 않는다. `lang` 필수이며 누락/빈/공백은 400 COMMON-002, 미지원·대소문자/지역 변형은 기존 LanguageCode 규칙대로 en이다.

| 메서드/경로(제안) | 성공 payload | 정상 비공개/오류 |
|---|---|---|
| `GET /api/curations/seoul?lang=...` | state, sectionVersion, requestedLang, resolvedLang, fallbackUsed, collections(0 또는 정확히 3) | 준비/일부 내림은 200 UNPUBLISHED. 장애는 5xx |
| `GET /api/curations/seoul/{collectionKey}?lang=...` | collectionKey, publishedRevision, publishedAt, 요청/해결 언어, title, description, coverImageUrl, coverImageAttribution, items(정확히 10) | 미게시/내림/잘못된 모음은 404 CURATION-001 제안 |

정상 미게시 예시(신규 제안):

```json
{"success":true,"payload":{"state":"UNPUBLISHED","sectionVersion":0,"requestedLang":"en","resolvedLang":"en","fallbackUsed":false,"collections":[]},"message":null,"code":null}
```

공개 홈 카드 shape(하나의 카드만 발췌, 실제 성공은 3개):

```json
{"collectionKey":"sights","title":"Seoul sights","description":"An edited guide to Seoul","coverImageUrl":"https://cdn.example.invalid/dev/curation-assets/example.webp","coverImageAttribution":null,"itemCount":10,"publishedRevision":1}
```

공개 항목 shape(하나만 발췌, 실제 상세는 10개이며 예시는 게시용 콘텐츠 아님):

```json
{"entryId":"e74d9863-8c04-4608-90f0-bc6b00f0dcd1","position":1,"name":"Example station","area":"Example area","introduction":"Example introduction","recommendationReason":"Example reason","imageUrl":"https://cdn.example.invalid/dev/curation-assets/example.webp","imageAttribution":"Photo: Example Organization (KOGL Type 1)","externalUrl":"https://example.invalid/guide","nearby":["Example walk","Example exhibition"]}
```

내부 주소/사진 계약 증빙/출처 목록/admin ID/staging key/초안/version은 공개 payload에 넣지 않는다. 장소별 내부 상세 URL, foodId, 위험도·별점·안전 배지, 예약/지도 URL 배열도 넣지 않는다.

### 로케일·캐시

- 게시 검증은 10로케일의 모든 필수 텍스트와 역 주변 label까지 검사한다. map에 키만 있고 값이 blank인 경우 실패한다. 고유명은 승인된 동일 표기를 여러 언어에 쓸 수 있으므로 서로 다를 것을 강제하지 않는다.
- 기존 LocalizedText는 ko 필수 + 번역 누락 ko fallback이다. 이를 그대로 적용하면 이번 제안의 en 방어 fallback과 다르므로 **큐레이션에서 별도 처리**한다. 기존 도메인의 언어 정책을 바꾸지 않는다.
- 런타임 예외적 번역 결손의 **설계 범위 확정값**: **모음 전체를 완전한 en 스냅샷 표현으로 fallback**하고 resolvedLang=en/fallbackUsed=true 및 서버 관측을 남긴다. 홈은 카드마다 다른 언어가 섞이지 않게 전체 3카드를 en으로 바꾼다. en조차 불완전하면 503, 문구 하나만 다른 언어로 조용히 채우지 않는다. 이는 게시 누락 허용이 아니다.
- 1차 구현은 HTTP `Cache-Control: no-store`, 서버 Redis/CDN 응답 캐시 없이 작고 고정된 DB 스냅샷 조회를 제안한다. ETag/304는 BaseResponse를 쓰지 않는 별도 계약이므로 이번에 추가하지 않는다.
- 앱 메모리의 마지막 성공값은 `(API host, API version, lang, collectionKey)`로 구분한다(index는 별도 key). 네트워크/5xx는 기존 값+오류 안내로 유지한다. index 200 UNPUBLISHED는 홈 index/카드 값을 비우고, detail 404는 해당 모음 상세 값을 지운다. 홈이 숨겨졌다는 이유만으로 아직 게시 중인 다른 모음의 상세 캐시까지 지우지 않는다. 오프라인에서 서버의 내림을 즉시 알 수 있다고 보장하지 않는다.
- 이미지 CDN URL은 환경별 immutable final key로 구성한다. 초안 사진 교체가 기존 URL의 bytes를 바꾸지 않는다.

### FE 구현자용 계약 묶음(P-480 준비 검증 이후 인계)

아래는 미구현 API의 설계 입력이며 배포된 Swagger 확인을 대신하지 않는다.

| 경계 | 처리 계약 |
|---|---|
| 공개 상태 | 정상 index는 `success=true` + non-null payload. `PUBLISHED`는 정확히 3카드, `UNPUBLISHED`는 빈 collections다. 최초 활성화 전 상세는 모두 404이며 활성화 후에는 현재 게시 중인 모음만 200/정확히 10항목이다. |
| 게시 버전 | index의 sectionVersion은 공개 상태 변경, 카드/상세의 publishedRevision은 해당 모음 게시 성공을 구분한다. 관리용 version/expectedVersion과 혼용하지 않는다. 내림도 sectionVersion을 바꾸므로 더 큰 publishedRevision만 기다리지 않는다. |
| 언어 | 요청 lang별로 분리하고 requestedLang/resolvedLang/fallbackUsed를 함께 해석한다. 예외적 번역 결손은 상세 전체 또는 홈 3카드 전체의 en fallback이다. 게시 시 10언어 누락 허용으로 해석하지 않는다. |
| 캐시/내림 | host/API version/lang/모음별로 분리한다. HTTP no-store. 정상 UNPUBLISHED는 index만, 상세 404는 그 상세만 제거한다. 네트워크/5xx는 마지막 성공값과 오류 안내를 유지한다. |
| null/비정상 payload | HTTP 200이어도 success=false, payload=null, 필수 필드/개수/상태 불일치는 계약 오류다. UNPUBLISHED나 빈 성공으로 치환하지 않는다. 비정상 응답으로 마지막 정상값을 덮어쓰지 않고 오류 상태를 표시한다. 기존 값이 없으면 오류 화면을 표시한다. |
| 오류 분기 | HTTP/code와 구조화 errors의 path/locale/reason을 사용한다. message 문자열로 분기하지 않는다. 상세 404와 5xx를 같은 빈 화면으로 합치지 않는다. |

## 6. 관리 계약·권한·환경 고정 제안

기존 관리자 JWT/ADMIN interceptor와 `admin_account`를 사용한다. 새 역할은 없다. 토큰의 주체는 member가 아니라 관리자 계정이며 요청 body의 adminId를 신뢰하지 않는다. curation 작업에서는 활성 관리자 계정도 확인한다. 일반 USER 토큰은 AUTH-008, 무효/만료 토큰은 기존 AUTH-003/004다.

모든 새 관리 mutation에 `X-Target-Environment: dev|prod`를 요구하고 **서버의 신뢰된 설정값**과 불일치하면 409 제안 오류로 거절한다. 이 헤더는 환경 선택/라우팅 권한이 아니다. 누락/형식 오류는 400. 관리 GET 응답에도 environment를 넣는다. local/staging은 비운영 테스트 설정에서 별도로 매핑하며 prod로 묵시 치환하지 않는다.

관리 UI는 요청 시작 시 `{env, baseURL, token, collectionKey, expectedVersion, submittedDocument}`를 함께 고정한다. 완료 시 현재 선택이 달라도 보낸 대상의 cache key만 갱신하고 실제 반영 대상을 알린다. 환경/모음 변경 시 다른 대상의 초안을 이어 쓰지 않는다. prod 게시/내림은 기존 이중 확인 UX를 따른다. 서버는 확인 다이얼로그를 보안 경계로 간주하지 않는다.

업로드는 entryId 또는 모음 cover 슬롯, 편집 세션, 파일 선택 세대도 고정한다. A→B→A 선택과 항목 재정렬을 파일명/배열 인덱스만으로 구별하지 않는다. 서버 오류의 entryId를 우선 매핑하고 path는 보조로 쓴다. 환경 변경이 전체 리로드를 일으키면 옛 콜백이 살아 있다고 가정하지 않고 재로그인 후 원래 환경의 관리 상세/전체 공개 상태를 조회한다.

| 신규 경로(모두 가칭) | 요청/동작 | 성공 |
|---|---|---|
| `GET /api/admin/curations/seoul` | environment, sectionState, sectionVersion, firstActivatedAt, 모음별 version/게시 상태/validation summary | 200, collections 정확히 3. 모음 게시본 유무와 홈 전체 공개 상태를 구분 |
| `GET /api/admin/curations/seoul/{collectionKey}` | 전체 초안, 마지막 게시본 비교, 내부 근거, version | 200, 관리자만 |
| `PUT .../{collectionKey}/draft` | `{expectedVersion, document}` 전체 교체. PATCH merge/null 삭제 의미를 새로 만들지 않음 | 200, 새 version + validationErrors + hasUnpublishedChanges |
| `POST .../{collectionKey}/validate` | `{expectedVersion}`. 저장된 초안의 게시 준비 검사, 쓰기 없음 | 200 `{valid,errors,version}`. invalid는 정상 검사 결과 |
| `POST .../{collectionKey}/publish` | `{expectedVersion}`. 별도 임의 본문을 게시하지 않고 본 버전의 저장 초안만 게시 | 200, 새 version/publishedRevision/publishedAt/sectionState |
| `POST .../{collectionKey}/unpublish` | `{expectedVersion}`. 공개 내림, 초안/스냅샷 보존 | 200, 새 version, isPublished=false/sectionState=UNPUBLISHED |
| `POST .../{collectionKey}/assets/upload-url` | `{expectedVersion, contentType, contentLength}`. 관리자·모음에 묶인 자산 예약 | 200, assetId, uploadUrl/method/requiredHeaders/contentLength/expiresAt/completeDeadline. 임시 publicUrl은 공개 콘텐츠로 쓰지 않음 |
| `POST .../{collectionKey}/assets/{assetId}/complete` | `{contentType,size}`. 발급 관리자·환경·모음/asset ID로 검증/확정 | 200, assetId/state=READY/previewUrl. 재시도는 같은 확정 자산 반환 |

업로드 예약은 collection version을 검증하되 콘텐츠 버전을 증가시키지 않는다. 업로드 완료는 발급된 자산을 완성할 뿐 초안에 자동 첨부하지 않는다. 다른 관리자가 그동안 모음을 수정했으면 최종 draft 저장이 expectedVersion 충돌로 막힌다. 완료 파일은 관리자에게 소유권을 확인해 주고, 같은 환경/모음의 다른 활성 관리자는 이후 편집에서 READY 자산을 사용할 수 있다.

불완전 초안 저장의 유효한 요청 예시:

```json
{"expectedVersion":0,"document":{"title":{},"description":{},"cover":null,"items":[]}}
```

초안에 존재하지 않는 asset, 다른 모음/환경 asset, 잘못된 타입/지원하지 않는 로케일 키 등은 저장 때부터 거절한다. 아직 안 채운 사진은 null로 저장한다. 저장 성공의 validationErrors가 비어 있지 않아도 저장 실패로 처리하지 않는다.

## 7. 게시 검증·오류 계약 제안

게시 검증 순서: 관리자/환경/대상 → expectedVersion → 문서 구조/중복 → 10개와 10로케일 → READY 자산/사진 근거 → 외부 링크/조사 확인 → 원자 교체. errors 배열은 `{path, entryId?, locale?, reason}`이며 FE가 reason으로 자체 문구를 만든다. 서버 message 문자열 매칭 금지.

| 검사 | 실패 reason 예시 |
|---|---|
| 정확히 10항목, entryId/장소 식별/외부 URL 중복 없음 | ITEM_COUNT, DUPLICATE_ENTRY, DUPLICATE_PLACE, DUPLICATE_EXTERNAL_URL |
| 모음 title/description, 항목 name/area/introduction/recommendationReason, 역 nearby 10언어 | REQUIRED_TEXT, TRANSLATION_MISSING, NEARBY_COUNT |
| 모음/항목 사진 READY, 환경·모음 일치, 필수 이용 근거·표시 방식 적합 | ASSET_NOT_READY, ASSET_TARGET_MISMATCH, PHOTO_RIGHTS_MISSING, ATTRIBUTION_UNSUPPORTED, ATTRIBUTION_TEXT_INVALID |
| 외부 URL 한 개와 실제 목적지 확인 기록 | INVALID_EXTERNAL_URL, EXTERNAL_LINK_UNVERIFIED |
| 내부 장소 식별·출처·확인일·편집 검수 | PLACE_IDENTITY_MISSING, RESEARCH_EVIDENCE_MISSING, EDITORIAL_REVIEW_MISSING |

URL은 absolute HTTPS, host 필수, userinfo·제어문자·실행 스킴·로컬/사설 IP literal 금지. 운영 출처 URL 여러 개와 externalUrl 하나를 구분한다. 입력을 임의 URL로 조립하거나 쿼리를 없애 목적지를 바꾸지 않는다. 외부 URL을 서버가 fetch하지 않으므로 자동 링크 검증/크롤러나 SSRF 진입점을 추가하지 않는다. redirect 최종 목적지·장소/지점 일치·현재 안내 유효성은 운영자가 직접 확인해 확인 기록을 남긴다. 문법 검증과 실제 목적지 검증은 다르다.

새 문서 저장 시 공개 콘텐츠/URL/사진/장소 식별이 달라지면 그 부분의 이전 확인 기록을 무효화한다. 게시 전 검수자는 같은 expectedVersion의 현재 내용을 다시 확인해야 한다. 게시 서비스는 확인된 버전과 기록을 함께 스냅샷으로 남긴다. 사진/URL은 단순 `verified:true` 한 값으로 영구 통과시키지 않는다. 이 확인은 별도 역할/승인자를 추가하는 결재 단계가 아니다.

**사진 표시 의무는 `NONE` 과 `TEXT` 만 게시를 허용한다(2026-10-20 R-96·KB-823 개정 — 종전 "NONE 만").** 이 값도 필수 이용 근거·확인 기록 및 READY 등 나머지 검증을 면제하지 않는다.

- `TEXT`: `attributionText` 가 비어 있지 않고(공백만 불가) **80자 이하**이며 제어문자(개행 포함)가 없어야 한다. 아니면 `ATTRIBUTION_TEXT_INVALID`(path `….rights.attributionText`). 문구는 **단일 문자열**이며 언어별로 번역하지 않는다(공공누리·CC BY 출처 표시는 원문 그대로 쓰는 표기다).
- `LINK`/`UNKNOWN` 과 null/누락은 계속 `ATTRIBUTION_UNSUPPORTED` 로 차단한다. 미지원 표시 의무는 초안에 기록할 수 있지만 validate와 publish에서 같은 오류를 반환한다. 규격 밖 enum 값은 초안 저장 단계의 형식 오류다.
- 공개 응답: 카드·상세의 `coverImageAttribution`, 항목의 `imageAttribution`(nullable 문자열). 표시 의무가 `TEXT` 면 그 문구, `NONE` 이면 `null` — `NONE` 사진에 남은 `attributionText` 는 내보내지 않는다. 앱은 값이 있을 때만 이미지에 작은 출처 캡션을 표시한다.
- 그 밖의 내부 권리 근거(basis·sourceOrEvidenceRef·rightsHolder·permittedUse·checkedOn·checkedAssetId)는 그대로 저장·스냅샷 보존하며 공개 응답에 내보내지 않는다.
- 출처 문구도 편집 검수 해시에 포함되므로 문구를 바꾸면 재검수가 필요하다.

| 사진 표시 의무 예시 | 게시 검증 결과(다른 조건 충족 가정) |
|---|---|
| NONE | 통과(공개 출처 필드 null) |
| TEXT + 1~80자 문구(제어문자 없음) | 통과(공개 출처 필드 = 문구) |
| TEXT + 빈 문구·공백·81자 이상·제어문자 | 차단: ATTRIBUTION_TEXT_INVALID |
| LINK / UNKNOWN | 차단: ATTRIBUTION_UNSUPPORTED |
| null / 필드 누락 | 차단: ATTRIBUTION_UNSUPPORTED |

표시 의무 오류 예시(신규 가칭 코드; 내부 증빙 내용은 반환하지 않음):

```json
{"success":false,"payload":{"errors":[{"path":"items[0].image.rights.attributionRequirement","entryId":"e74d9863-8c04-4608-90f0-bc6b00f0dcd1","reason":"ATTRIBUTION_UNSUPPORTED"}]},"message":"Publication validation failed","code":"CURATION-002"}
```

| HTTP | 코드 | 구분 |
|---|---|---|
| 400 | COMMON-002 | **기존** 요청 형식/필수 헤더/lang 오류 |
| 401/403 | AUTH-003/004, AUTH-008 | **기존** 무효/만료 토큰, 관리자 권한 없음 |
| 400 | UPLOAD-001/003/004, IMAGE-001/002/003 | **기존 코드 재사용 제안** 형식/크기/소유 경로/메타데이터/미완료 객체. 실제 관리 API는 아직 없음 |
| 404 | CURATION-001 | **신규 가칭** 모음 없음/미게시/내림/최초 비활성. 공개에서 초안 존재를 알려주지 않음 |
| 400 | CURATION-002 | **신규 가칭** 게시 검증 실패 + errors. 기존 공개본 보존 |
| 409 | CURATION-003 | **신규 가칭** expectedVersion 불일치 + currentVersion |
| 409 | CURATION-004 | **신규 가칭** 대상 환경 불일치 |
| 400 | CURATION-005 | **신규 가칭** 자산의 환경·모음·상태 불일치 |
| 503 | CURATION-006 | **신규 가칭** 공개 콘텐츠 무결성/자산 확정 의존 장애. 빈 성공으로 변환하지 않음 |
| 409 / 500 | COMMON-004 / COMMON-003 | **기존** DB 경합 / 예상하지 못한 서버 오류 |

**운영 IAM 에 따른 예외(2026-10-20, #422 결정 A)**: 업로드 없이 complete 를 호출하면(staging 객체 없음) 400 이 아니라 **503(CURATION-006)** 이다. API 태스크 롤의 `s3:ListBucket` 은 `curation/<env>/*` prefix 조건부라 없는 객체 HEAD 에 적용되지 않아 S3 가 404 대신 403 을 돌려주기 때문이다(권한 최소 유지를 위해 조건 없는 ListBucket 은 주지 않는다). 어드민은 이 503 을 "다시 시도"로 안내한다.

신규 번호는 ErrorCode enum에서 실제 충돌 확인 후 구현 PR에서 확정하고 Swagger에 등록한다. 업로드 IAM/S3 장애는 입력 오류(400)로 돌리지 않는다. public 상세의 404와 5xx는 다른 UI 상태다.

게시 실패 응답 예시(미구현):

```json
{"success":false,"payload":{"version":7,"errors":[{"path":"items","reason":"ITEM_COUNT"},{"path":"items[2].name","entryId":"e74d9863-8c04-4608-90f0-bc6b00f0dcd1","locale":"th","reason":"TRANSLATION_MISSING"}]},"message":"게시 조건을 확인해 주세요","code":"CURATION-002"}
```

경합 응답 예시(미구현):

```json
{"success":false,"payload":{"collectionKey":"sights","currentVersion":8},"message":"다른 수정이 반영되었습니다","code":"CURATION-003"}
```

## 8. 이미지 수명주기·자산 재사용 제안

1. 관리 업로드 예약은 발급 관리자와 대상 모음, 허용 MIME/정확 크기/만료를 DB에 기록하고 서버가 임시 key를 만든다. `member_id`에 관리자 ID를 넣거나 외부 URL을 그대로 자산으로 등록하지 않는다.
2. 전용 prefix 예: `{environment-prefix}/curation-staging/{collectionKey}/{assetId}/{uuid}`. 기존 `images/review/` 등 정리 대상 prefix와 분리한다. keyPrefix는 서버 환경 설정이며 클라이언트가 보내지 않는다.
3. 클라이언트는 받은 method/requiredHeaders로 S3에 업로드한다. complete는 예약된 정확한 key만 HEAD 검사하며 caller가 임의 path를 제출할 수 없다. MIME/크기/만료/주체를 검사한다. 신규 관리 purpose는 endpoint가 결정하는 CURATION_IMAGE로 한정하며 기존 UploadPurpose enum을 확장해 회원용 endpoint에 노출하지 않는다.
4. 기존 presigned PUT은 만료 전 같은 key를 다시 쓸 수 있으므로 그 key를 게시본에 직접 연결하지 않는다. **확정 시 원본 fingerprint를 검증해 새로운 final key로 복사하고 그 final key만 READY로 기록**하는 port 확장을 제안한다. final prefix에는 클라이언트 PUT 서명을 발급하지 않는다. 원본 변경 경합은 확정 실패/재시도로 처리한다. 구체 SDK 조건부 복사·권한은 KB-794에서 검증해야 할 기술 선행 항목이다.
5. S3 HEAD/복사는 트랜잭션 밖에서 한다. 마지막 짧은 DB 단계에서 자산 상태를 CAS로 READY 전환한다. 동시에 complete하면 한 final key만 채택하고 재요청은 그 값을 돌려준다. 실패·탈락한 복사 객체는 미참조 정리 대상으로 남긴다. 다른 요청이 채택한 파일을 실패 처리에서 지우지 않는다.
6. draft는 READY assetId + 사진 이용 근거를 첨부한다. 게시본은 같은 immutable final key와 근거를 스냅샷으로 보존한다. 교체는 새 asset을 만들어야 하며 기존 bytes를 덮어쓰지 않는다.
7. 게시 전 S3 파일 존재 점검이 필요하면 DB 잠금 전에 수행하고, 실제 commit에서는 버전/자산 상태를 다시 검사한다. public GET마다 HEAD를 호출하지 않는다. 게시 후 일시 CDN 장애는 UI fallback으로 처리한다.
8. 기존 업로드 정리 잡에 새 prefix를 추가하지 않는다. 큐레이션 전용 정리는 아래 보존/경합 규칙을 따른다. 범용 미디어 라이브러리는 만들지 않는다.

**파일/브라우저 계약 제안:** 기존 소스 기본과 같은 JPEG/PNG/WebP·정확 바이트수·최대 10 MiB·presign 5분, 발급 만료 후 1일까지의 completeDeadline을 예약에 고정한다. HEIC/SVG/GIF는 이번 허용값에 없다. 해상도/종횡비의 강제 업로드 제한은 추가하지 않고 화면 슬롯의 crop은 UI가 처리한다. 이 정책은 실제 새 endpoint에 아직 적용되지 않았다.

스토리지 전송은 기존 apiFetch가 아니라 별도 binary fetch를 사용하고 **ADMIN JWT/X-API-Version/X-Target-Environment를 S3에 보내지 않는다**. 새 requiredHeaders에는 브라우저가 직접 설정할 수 있는 Content-Type 등만 넣으며 Content-Length는 별도 contentLength 값으로 고정해 브라우저가 File/Blob 전송에서 생성하게 한다. 현재 port가 반환하는 Content-Length 헤더 목록을 그대로 브라우저에 복사하지 않는다. 이 계약에 맞는 서명 생성/브라우저 실측은 필수다. 새 prefix의 PUT/필요 HEAD와 필요한 서명 헤더를 실제 어드민 origin에서 허용하는 CORS, 최종 객체의 CDN 조회, 복사 권한을 KB-794에서 확인한다. 이번에는 설정을 바꾸지 않았다.

**미연결 자산 정리 제안:** (a) READY 파일은 draft 및 보존 published_document 모두의 assetId 참조를 확인한다. (b) `collection → asset` 잠금 순서로 참조를 검사해 처음 미참조로 확인된 unreferenced_since를 기록한다. 초안에 다시 연결하면 이 시각을 비운다. (c) 미참조 7일 경과 후 같은 잠금 안에서 재검사하고 DELETING으로 전환한다. 이 상태는 초안 연결/게시/complete가 거절한다. (d) 잠금을 푼 뒤 S3 삭제, 성공 후 BaseEntity 소프트삭제 및 DELETED, 실패는 DELETING을 유지해 다음 실행에서 재시도한다. 게시 보존본이 참조하면 공개 내림 상태여도 삭제하지 않는다. (e) staging/DB에 채택되지 않은 복사 결과는 발급·완료 창이 끝나고 7일 이상 지난 것만 정확한 전용 prefix에서 검사해 제거한다. complete는 마지막 DB 채택 단계에서도 deadline을 재확인해 정리 후 늦게 채택하지 못한다. 정리 후보 식별과 삭제 실행은 구분하고 기존 dry-run 관례로 검증한다. 이를 구현/실행한 것은 아니다.

현행 S3/CDN 기반 기능 재사용은 가능하지만, 실제 새 prefix IAM/CORS/CDN 노출과 다운로드/복사 조건은 이번 읽기 조사로 확인하지 않았다. 권리 미확보 33개 슬롯을 기존 음식/리뷰 이미지로 자동 채우지 않는다. 같은 이용 허락 사진을 여러 슬롯에 쓰더라도 각 사용 범위를 검증한다.

## 9. 마이그레이션·PR 분리 인계

새 common 테이블 매핑이 생기므로 **분리 필요**다. 기존 음식/member/uploaded_image 컬럼 변경·삭제·rename은 이 안에서 요구하지 않는다.

| 단계 | 작업과 통과 근거 |
|---|---|
| M: migration-only PR | `api/src/main/resources/db/migration/`의 새 SQL만. 위 신규 테이블·FK/UNIQUE/기본 상태, 필요 시 비공개 root/빈 3행. batch 트리거나 common 코드 없음 |
| M 적용 | 승인된 절차로 선행 PR 머지 후 dev와 prod 각각 flyway_schema_history의 동일 버전·checksum·success 및 information_schema 실재 확인. 두 환경 모두 완료되기 전 F를 머지하지 않음 |
| F: KB-794 기능 PR | M과 **동일 버전·내용 SQL**을 포함한 브랜치에서 common 엔티티·api 기능·테스트/ArchUnit/API 문서. M이 develop에 들어온 뒤 diff에 SQL이 안 보이는 것은 정상이며 다른 SQL을 다시 만들지 않음 |
| F 검증/후속 | MySQL Flyway+validate, API/batch 컴파일 및 관련 통합/ArchUnit, 구 앱 API 회귀, 새 dev Swagger HTTP 계약 확인. 이후 어드민/앱 구현 연결 |

두 PR 본문 맨 위에 `머지 순서: #M → dev/prod 적용 확인 → #F`를 기재하고 커맨드센터에 `짝: #M → #F`로 보고한다. 지금은 PR이 없으므로 번호를 만들지 않는다. 파일명은 **실제 SQL 생성 시점의 KST** `Vyyyy.MM.dd.HH.mm.ss__...sql`이고 이 문서 작성 시각으로 미리 확정하지 않는다.

신규 DDL은 하나의 독립 migration으로 묶어 내부 FK 생성 순서를 파일 안에서 해결한다. 다른 미적용 migration에 의존하지 않는다. 적용된 SQL은 수정/rename하지 않는다. 컬럼 삭제/타입변경을 추가로 요구하게 되면 이 순서만으로 구 코드 호환을 보장하지 못하므로 다시 검토한다.

배포 이전 코드가 새 빈 테이블을 무시하는지, 이후 batch validate가 신규 공통 엔티티를 읽는지 확인해야 한다. 서버 머지/실제 DB 적용/배포/콘텐츠 게시는 각각 별도 승인·운영 작업이며 이번 산출물에 포함되지 않는다.

## 10. KB-794/795/796 인계 및 검증 항목

| 담당 | 받을 내용 / 다음 조건 |
|---|---|
| KB-794 서버 | §3~9 검토 확정, schema M/F 분리, 관리 업로드 확정 객체 기술 검증, 아래 TDD 회귀. 실제 배포 전까지 이 문서를 Swagger 계약이라고 표현하지 않음 |
| KB-795 어드민 | 고정 3키, environment/version, document 전체 교체, 오류 path/locale/reason, assetId 업로드 완료, 초안/게시 비교. apiFetch가 payload의 구조화 errors를 버리지 않도록 API layer 보완 필요 |
| KB-796 앱 | §5 FE 계약 묶음: 공개 상태/게시 버전/로케일/캐시·내림/null payload. 독립 index/detail, entryId, 단일 externalUrl, station nearby 문자열. 기존 홈 레일·안전 색/판정 유지 |
| 커맨드센터/콘텐츠 | 최종 30곳·순서·10언어·사진 권리·링크 확인, §12 확정 기본값 적용, migration 적용/기능 발주/게시 일정 분리 |

후속에서 작성/실행할 테스트이며 **이번에 실행한 테스트가 아니다**:

- 불완전 초안 저장 후 공개 응답/사진 URL 불변. 9/11개·중복·blank 번역·역 1/4개·사진 근거 없음·실행 URL은 publish 실패, 기존 snapshot/version 불변.
- 모음 표지/항목 사진 각각 NONE·TEXT(1~80자·제어문자 없는 문구, 위반 시 ATTRIBUTION_TEXT_INVALID)만 허용하고 LINK/UNKNOWN/null/누락은 ATTRIBUTION_UNSUPPORTED(KB-823 개정). NONE이어도 이용 근거 결손은 PHOTO_RIGHTS_MISSING. validate와 publish 결과 일치, 실패 시 기존 snapshot/version 불변.
- 1/2모음 준비 상태의 index/detail 비공개. 최초 A/B 게시 → A 내림 → C 게시 시 스냅샷 3개가 있어도 latch=false/전체 비공개 → A 재게시 때만 latch=true/3모음 공개. 같은 트랜잭션의 각 isPublished와 유효 스냅샷을 모두 검사한다.
- 최초 활성화 후 한 모음 갱신·내림·재게시. 내림은 latch 유지/홈 전체 숨김/해당 상세404/나머지 게시 상세200. 보존 스냅샷을 현재 게시 상태로 오인하지 않는다.
- 기대 버전이 같은 동시 저장/게시/내림 중 하나만 성공. 첫 공개와 내림 경합에서도 부분 섹션 없음. 단순 카운트 경합 테스트를 넓히지 않고 이 게시 불변만 검증.
- 다른 환경/모음/주체/자산 ID, 발급되지 않은 key, 관리자/회원 ID 숫자 충돌을 거절. 업로드 시작 후 환경 전환에도 보낸 host/token/target 고정.
- presign 재사용·complete 중복·복사 성공 후 DB 실패·staging 변경 경합에도 이미 게시된 final 객체 불변. cleanup을 붙이면 draft/posted 참조 보호를 별도 검증.
- 게스트 공개 200, 관리자 권한 401/403, lang/헤더 400, 미게시 200 대 실제 장애 5xx, 내림 상세404. 코드/HTTP/Swagger 일치.
- 10언어 필수 텍스트, 예외적 런타임 결손 시 전체 모음/홈 카드 그룹 en fallback과 명시 필드/관측/복구, en 결손503. 오류 데이터의 false-empty 방지. JSON 내부 운영 근거가 공개 직렬화에 포함되지 않음.
- FE 계약 예시: 200/null payload·success=false·잘못된 카드 개수는 오류이고 정상 미게시와 구분한다. index UNPUBLISHED는 홈 값만 제거, 상세404는 해당 상세만 제거, 다른 게시 상세는 유지한다. 버전/환경/언어별 응답을 섞지 않는다.
- 관련 api/common/batch 테스트와 ArchUnit, `./gradlew clean build` CI. 최신 develop 기준에서 수행하고 실제 명령·SHA·결과를 보고.

## 11. 이번 수행/미수행

수행: 기획·조사·인계·기존 코드 읽기, GitHub ref 확인, Swagger 전체/그룹 GET, 홈/관리자 읽기 GET 4건, dev DB 메타데이터 SELECT, 이 설계/근거/진행 문서 작성. 구현 코드·테스트·SQL 파일은 생성/수정하지 않았다.

미수행: 사진 URL 수집/다운로드·권리 획득, 30개 외부 URL 현재 재검증, 관리자 로그인/정상 권한 호출, 이미지 업로드, 도메인·API 코드, SQL 적용/DB 쓰기, 테스트/빌드, PR 생성·머지, 배포·콘텐츠 게시, Figma 클라우드/실기 접근. 원본 조사자가 검증한 링크와 이 세션이 검증한 API를 구분한다.

## 12. 설계 범위에서 확정된 기술 기본값과 남은 입력

**새 사용자 제품 선택 때문에 막힌 항목은 없다.** 독립 검토 후 아래 기본값을 설계 범위에서 확정했다. 구현·배포·UI 승인 완료를 뜻하지 않으며 KB-794 구현은 별도 발주를 기다린다.

1. **런타임 번역 결손 방어:** 엄격한 10언어 게시 게이트를 유지한다. 예외적 런타임 데이터 결손에 한해 전체 모음/홈 3카드를 완전한 en으로 fallback하고 resolvedLang/fallbackUsed와 관측을 남긴다. en 결손은 503이다. 기존 공통 LocalizedText의 ko fallback은 변경하지 않는다.
2. **최초 활성화와 일부 내림:** 최초 latch는 같은 트랜잭션에서 세 모음 각각 isPublished=true이고 유효 게시 스냅샷이 있을 때만 설정한다. 내림 후 보존 스냅샷은 세지 않는다. 활성화 이후 일부 내림은 홈 전체 숨김 + 내린 상세404이며 나머지 현재 게시 중인 상세 접근은 허용한다.
3. **사진 attribution(2026-10-20 R-96·KB-823 개정):** 필수 근거를 갖춘 attributionRequirement=NONE 또는 TEXT(1~80자·제어문자 없는 단일 문자열)만 게시 가능하다. TEXT 문구는 공개 `coverImageAttribution`·`imageAttribution` 으로 내려가 앱이 작은 출처 캡션으로 표시한다(NONE 이면 null). LINK/UNKNOWN/null/누락은 ATTRIBUTION_UNSUPPORTED로 차단한다. 내부 근거는 보존하고 공개하지 않는다.

그 외 경로명·오류 채번·JSON/세 테이블 구조·업로드 확정 방식은 이 계약 리뷰의 기술 결정안이다. 사진/최종 번역/콘텐츠 승인은 **게시를 막는 입력**이고, 위 설계 검토를 진행하는 데 필요한 가짜 데이터로 대체하지 않는다. S3 확정 파일 복사 조건/IAM/CORS는 KB-794의 실제 구현·실행 검증 선행 항목이다.
