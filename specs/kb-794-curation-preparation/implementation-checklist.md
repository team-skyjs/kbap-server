# KB-794 / C-794-R1 조건부 구현 준비

작성: 2026-10-16 KST. **문서·fixture 설계만 완료, 기능 미구현·테스트 미실행.** 최신 develop `173426ec3b86c1d5003ddb958b7c9c8315bae8a9`와 승인 인계 계약을 대조했다. 이 문서 전용 PR은 기능 PR F가 아니며 배포 게이트를 해제하지 않는다.

## 정본과 범위

- 승인 인계된 원문은 `approved-contract-reference.md`에 바이트 동일하게 보존했다. 원문의 작성 당시 ‘제안/발주 대기’ 표현은 이력이며, C-794-R1이 승인한 것은 게이트 전 준비와 게이트 후 조건부 구현이다. 실제 Swagger·배포 완료로 격상하지 않는다. 원본 경로·SHA와 코드 기준은 `source-evidence.json`.
- 기획/조사/구현 순서: spec repo의 `seoul-curation-{plan,research,implementation-order}-2026-10-15.md`. 최신 배너 채택: `bridge/ops/2026-10-16-curation-banner-adoption-check.md`.
- 서울 sights/stations/restaurants 고정 3모음×10항목, 10언어. Popular Dishes 아래 D-28 v2 B 수동 배너 → D-29 공통 목록 → 항목 외부 HTTPS 하나. 기존 음식 정렬·검색·Saved·홈 데이터 계약은 유지한다.
- PR407, prod404/B 후보는 별도 작업이다. 이 브랜치는 그 변경을 가져오지 않는다.

## 코딩 시작 게이트

| 항목 | 필요한 확인 | 현재 판단 |
|---|---|---|
| dev migration | 같은 버전/checksum1439660255/success1, 3테이블 및 초기1/3/0 | 선행 기록은 있으나 센터의 환경별 종합 확인 전달 필요 |
| prod migration | 같은 버전/checksum/success·테이블·초기 상태 | 센터가 #403 merge b740af199472735b1870fa028ce9966f6613941f와 실제 SQL 적용을 확인했다고 전달함. 이번 준비에서 직접 재검증하지 않음 |
| dev API/batch | 배포 성공·새 PRIMARY task/image·health/validate 정상 | 센터 확인 전달 필요 |
| prod API/batch | CodeDeploy 성공·API 최종 단일 배포·batch 새 revision 기동/health | 발주 당시 API13/14 healthy이나 CodeDeploy InProgress, batch13. **미완료** |
| 시작 허가 | 위 전체 근거를 센터가 확인 전달 | **대기. common 엔티티·기능 코드·실행 테스트 선행 금지** |

게이트 전후 모두 AWS 변경은 사용자 CloudShell만. main/develop 머지, 운영 DB 쓰기, 실제 콘텐츠 게시, OTA는 이 작업에서 하지 않는다. 상태가 바뀌어도 시간 경과만으로 해제를 추정하지 않는다.

## 최신 코드와 재사용/추가 작업

| 영역 | 현재 근거 | 게이트 이후 할 일 |
|---|---|---|
| 저장 | develop에 동일 migration 존재, curation 엔티티/레포/API 없음 | BaseEntity 기반 release/collection/asset, Long FK, JSON 값 타입, @Version. 실제 DDL 길이·CHECK와 정확히 매핑. 적용 SQL 수정/재생성 금지 |
| 공개 HTTP | Swagger 관측은 별도 JSON, 현재 계약 존재로 간주하지 않음 | api.curation에 index/detail 및 별도 공개 DTO allowlist. 기존 /api/home 수정 없음 |
| 관리 HTTP | 기존 api.admin 권한·JWT/활성 admin 계정 재사용 | admin 전용 컨트롤러·Api 인터페이스, 환경 고정 헤더 검증. 새 역할 없음 |
| 업로드 | PresignedUploadPort 및 S3 어댑터 존재. StorageObjectStore에는 head/delete/put/list만 있고 ETag/version fingerprint·조건부 copy 없음 | 포트/어댑터 계약 추가 및 fake 확장. staging→immutable final 조건부 복사, 트랜잭션 밖 I/O + 마지막 DB CAS |
| 기존 업로드 | 회원/installation 소유 uploaded_image, 기존 purpose 정리 | 관리자 ID를 memberId에 넣지 않음. 전용 자산·prefix, 기존 회원 endpoint/purpose에 CURATION을 노출하지 않음 |
| 오류·경계 | BaseResponse/ErrorCode/ApiErrors, language resolver, ArchUnit | curation 도메인 방향 맵 추가. curation 전용 en 전체 fallback; 기존 LocalizedText 변경 없음 |

## 구현 순서와 완료 판단

1. 게이트 증거 고정 → 원본 migration SHA 일치 확인 → 최신 develop과 열린 PR 코드 채번 재조회. 기능 PR 본문에 `#402(dev)/#403(prod) → 양 환경 적용·API/batch 확인 → 기능 PR`을 명시한다.
2. 도메인 값 타입/게시 검증 BehaviorSpec Red → 모델/검증 Green. 초안 0~50항목/1MiB 및 길이 상한은 승인 계약 표와 동일하게 DTO에 고정한다. 저장 형식 검증과 게시 검증을 분리한다.
3. 초안 저장·validate·publish·unpublish 트랜잭션. root→collection→asset ID 순서, 초안은 collection→asset. isolation 지정 없이 기본 REPEATABLE READ. 게시 시 검증을 다시 수행하고 deep snapshot·감사·version을 원자 갱신한다.
4. 공개 GET readOnly snapshot, 최초 활성화 latch와 현재 isPublished를 별도로 판단한다. index 0/3과 detail10 불변을 검사한다. 저장 손상을 정상 UNPUBLISHED로 숨기지 않는다.
5. ADMIN 자산 예약/확정. 발급 주체·환경·모음·deadline·MIME·size 검증, 조건부 copy와 DB CAS. 기존 presigned URL로 final 객체를 덮을 수 없도록 한다.
6. 합성 IntegrationTest/MySQL Flyway+validate 및 fake storage로 HTTP/rollback/CAS 회귀. API/batch 컴파일·ArchUnit 및 변경 모듈 검증. 게시 불변의 치명 경합만 동시 실행 검증한다.
7. kbap-code-review 정적 검토와 kbap-pr-verify 절차에 따른 CI·격리 로컬 HTTP·Swagger 검증. 실제 dev 배포 후 Swagger와 예제 응답을 별도 채취해 어드민/앱에 정확한 SHA로 인계한다. 로컬 mock을 실제 dev 계약으로 내보내지 않는다.

## HTTP 검증 체크리스트 — 아래는 미실행 계획

모든 API는 X-API-Version과 BaseResponse. 신규 경로는 기존 지원 버전의 1.0+ 매핑 안을 따른다. 새 릴리스 버전을 임의 생성하지 않는다. mutation에는 X-Target-Environment 필수이며 서버 설정과 대조한다. 본문/URL에서 adminId·환경·스토리지 key를 신뢰하지 않는다.

| 표면 | 정상 대조군 | 오류/불변 |
|---|---|---|
| GET /api/curations/seoul | 게스트·회원 모두 공개, 활성화 후 정확히3카드, no-store | 초기/일부내림 200 UNPUBLISHED+[], 장애503; null payload/부분2카드 성공 금지 |
| GET /api/curations/seoul/{key} | 현재 게시 모음10항목, entryId/position/외부URL1개, 역 nearby2~3 | 최초 latch 전 모두404; 활성화 후 내린 모음404/나머지200; 미지 key404 |
| 공개 lang/헤더 | 10언어, 미지원코드→en | lang/헤더 누락·blank400; 번역 결손은 전체모음 또는3카드 그룹en, en 결손503; 내부증빙·adminId·staging key 유출0 |
| 관리 GET 개요/상세 | 활성ADMIN, environment·version·초안/게시본 비교 | 무토큰401/만료401/USER403/비활성ADMIN403; 환경별 결과 분리 |
| PUT /{key}/draft | {expectedVersion,document} 불완전초안 저장200, validationErrors 가능 | 잘못된UUID/중복ID/미지원locale/자산소유/본문상한400; version409; 게시본·공개사진URL 불변 |
| POST /{key}/validate | 같은 version의 저장초안, valid=false도200 | stale409·환경409; DB 변경0, publish와 동일한 errors/path/entryId/locale/reason |
| POST /{key}/publish | 정확히10×10언어·READY·NONE·근거·URL확인, snapshot원자교체 | 9/11/중복/빈번역/권리/URL400+errors; 이전 snapshot/version 유지; 최초3모음 원자활성 |
| POST /{key}/unpublish | version 증가·isPublished=false·sectionVersion 변경 | snapshot·latch 보존; stale409; 응답유실 후 GET으로 currentVersion/publishedSourceVersion 대조 |
| POST /{key}/assets/upload-url | 활성ADMIN·대상환경·version·JPEG/PNG/WebP≤10MiB, 예약200 | 잘못된형식/크기/환경/소유/버전; collection 콘텐츠 version 불변; 브라우저 금지 Content-Length는 requiredHeaders에서 제외 |
| POST /{key}/assets/{assetId}/complete | 예약주체/모음일치, HEAD·fingerprint확인·copy·CAS, READY 재시도동일결과 | 없는객체/형식크기/만료/다른주체/환경/모음 거절; copy/IAM장애503·DB경합409/500; 이미채택된final삭제 금지 |

공개 GET은 Authorization 불필요, 서버 회원별 개인정보를 섞지 않는다. 관리 PUT/POST의 정상 대조군은 로컬 fixture에서만 실행한다. 실제 dev/prod 쓰기 검증은 별도 승인·환경 확인 전 실행하지 않는다.

## 게시/CAS·사진·언어 fixture 설계

세 모음 각각 10개의 합성 UUID와 example.invalid HTTPS를 사용한다. 각 필수 필드의 10로케일 map, READY asset metadata, NONE 권리/현재내용 확인 레코드를 fixture builder 입력으로 둔다. 이는 테스트용 사실이며 실제 사진 권리·30곳·번역·외부 URL 승인이 아니다. 실제 저장 JSON 세부 필드 binding은 게이트 후 타입 정의와 함께 확정한다. fixture-matrix.json은 실행 데이터가 아니라 시나리오 명세다.

- A/B 게시→A내림→C게시: 보존 snapshot3개여도 latch=false, index UNPUBLISHED/detail전부404. A재게시가 성공한 동일 트랜잭션에서만 latch=true.
- 최초 활성화 후 A내림: index숨김, A404, B/C200; 재게시 복원. draft 변경/실패가 posted snapshot과 final bytes를 바꾸지 않음.
- 같은 expectedVersion의 저장/게시/내림 경합: 한 요청만 성공, 다른 요청409/currentVersion; root 잠금으로 최초활성화/내림 경합의 부분섹션 방지. 응답 유실 때 무조건 재시도·자동덮어쓰기 금지.
- cover와 item 각각 NONE만 게시 통과. TEXT/LINK/UNKNOWN/null/누락은 ATTRIBUTION_UNSUPPORTED. NONE라도 근거 없으면 PHOTO_RIGHTS_MISSING. validate/publish 동일 위치 오류.
- 9/11개·중복ID/장소/URL·10언어 키만존재/공백·역nearby1/4개·unsupportedlocale를 분리한다. 문서 변경 시 해당 사진/URL/콘텐츠의 과거 확인 기록을 무효화한다.
- URL은 HTTPS/host/userinfo/control/local·private IP literal 검사. query를 제거해 목적지를 바꾸지 않음. 서버 외부GET0. redirect·장소일치의 사람 확인은 별도이며 연결 실패/복귀는 앱 검증과 구분한다.
- staging 재PUT·조건부copy 실패·동시complete·copy성공후DB실패·deadline 직전/후 CAS를 fake로 재현한다. final key 불변과 losing copy 객체만 미참조가 되는지 검사한다.
- 삭제 잡은 별도 기술 입력 확인 후 전용prefix/dry-run부터 검증한다. 내려간 published snapshot 참조도 보호하고 DELETING 중 첨부/게시/complete를 금지한다. 실제 삭제 실행은 이번 준비 범위 밖이다.

## 오류 코드 예약 후보 — 미등록

2026-10-16 열린 PR407/406/404/393/367/272/189의 변경파일·patch와 최신 develop ErrorCode를 읽었다. CURATION 접두 신규 코드 충돌은 발견되지 않았다. PR407의 FOOD-022와 겹치지 않는다. `source-evidence.json`에 관측 범위를 기록했다. 이는 enum 등록/전역 잠금이 아니며 기능 구현 직전 다시 확인한다.

| 후보 enum / 코드 | HTTP | payload/의미 |
|---|---:|---|
| CURATION_NOT_FOUND / CURATION-001 | 404 | 미게시·내림·최초비활성·없는key. 초안 존재 노출 금지 |
| CURATION_PUBLICATION_INVALID / CURATION-002 | 400 | errors[{path,entryId?,locale?,reason}], version. 게시본 보존 |
| CURATION_VERSION_CONFLICT / CURATION-003 | 409 | collectionKey,currentVersion |
| CURATION_ENVIRONMENT_MISMATCH / CURATION-004 | 409 | 신뢰된 서버환경과 target 불일치 |
| CURATION_ASSET_INVALID / CURATION-005 | 400 | 환경·모음·자산 상태 불일치 |
| CURATION_UNAVAILABLE / CURATION-006 | 503 | 공개 무결성·확정 스토리지 의존 장애. 입력오류로 축소 금지 |

기존 COMMON-002/003/004, AUTH-003/004/008, UPLOAD-001/003/004, IMAGE-001/002/003은 기존 의미에 맞을 때만 재사용한다. 관리자 소유 위반을 회원 ‘본인 업로드’ 문구에 억지로 끼우지 말고 관리 DTO/Swagger에서 실제 의미를 확인한다. 새 오류를 임의로 추가 등록하지 않았다.

## 남은 입력과 인계 형식

- 코딩 블록: 센터의 dev/prod migration+API/batch 기동 게이트 해제 증거.
- 기술 검증 입력: 신뢰된 서버 environment 설정 매핑(local/staging 포함), S3 원본 ETag/version fingerprint와 조건부 copy 지원, 새 prefix IAM/CORS/CDN 실제 허용. 설정 변경·스토리지 유료 호출을 준비 승인에 포함하지 않는다.
- 게시 블록: 최종30곳/순서, 사진33슬롯 권리 및 NONE, 필수10언어 콘텐츠, 외부목적지 확인. 코딩에 실제 콘텐츠가 필요하다고 주장하지 않으며 synthetic fixture와 분리한다.
- 후행 인계는 코드SHA/DB migration 버전·checksum/대상host·API버전/Swagger URL·SHA/정상·실패 원시응답/실측시각/미검증 표를 포함한다. 지금 실제 새 Swagger·예제응답이 준비됐다는 주장을 하지 않는다.

## 실제 dev Swagger 관측 한계

2026-10-16 12:43 KST swagger-config는 200으로 1.0/1.1/2.0 그룹을 반환했다. 이어진 `/v3/api-docs/1.1` 조회는 15초 read timeout으로 실패했고 반복하지 않았다. `dev-swagger-observation.json`의 config `pathCount=0`/`curationPaths=[]`는 설정 문서에 paths가 없다는 뜻이며, 배포된 API 부재의 증거가 아니다. 최신 operation 명세·실제 큐레이션 응답은 미확인이다. 소스에 큐레이션 API가 없다는 판단은 위 develop SHA의 코드 조사에만 근거한다.
