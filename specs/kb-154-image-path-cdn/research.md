# Research: KB-154 이미지 경로 저장 + CDN 조합

## R1. CDN 베이스 프로퍼티 — 어느 것을 쓰나

- **Decision**: `kbap.storage.public-base-url`(환경변수 `IMAGE_PUBLIC_BASE_URL`, 기본 빈 문자열) 재사용.
- **Rationale**: KB-145 presigned 발급 응답의 `publicUrl` 이 이미 이 값으로 조합된다 — 업로드 시 발급되는 공개 URL 과 조회 응답이 조합하는 URL 의 출처가 같아야 한다. dev·staging·prod yml 에 이미 환경별 주입이 있다. spec 초안의 `kbap.image-upload.public-base-url` 표기는 오기 — 실제 키는 `kbap.storage.public-base-url`(`ImageUploadConfig` 가 `ImageUploadProperties.publicBaseUrl` 로 바인딩).
- **Alternatives considered**: 신규 `kbap.cdn.base-url` 프로퍼티 — 같은 값을 두 키로 관리하게 되어 기각.

## R2. 조합 로직의 위치

- **Decision**: `:core` 에 Spring-free 순수 헬퍼 `ImageUrls.resolve(base: String, ref: String?): String?` 하나. 서비스 3곳(MemberService·FoodService·HomeApplicationService)이 `@Value("\${kbap.storage.public-base-url:}")` 로 베이스를 주입받아 호출.
- **Rationale**: member·food·application 세 계층이 공유하는 최소 공배수 모듈이 `:core`(헌법 II — 공유 vocabulary/유틸은 :core). `:core` 는 Spring-free 라 빈 등록 불가 — `@Value` 직주입은 MemberService 의 기존 선례(`profile-image-allowed-hosts`)와 동일 패턴이고, 기본값 `:`(빈 문자열) 덕에 배치·테스트·local 등 미설정 컨텍스트에서도 부팅 안전. 배치는 애초에 MemberService/FoodService 를 탑재하지 않음(`@Import` 는 FoodScoringSource·AvoidanceCatalogService 만) — 확인 완료.
- **Alternatives considered**:
  - `ImageUrlResolver` 빈(:application) 주입 — 도메인 모듈이 :application 을 의존할 수 없어(역방향) 기각.
  - 컨트롤러(Response DTO)에서 조합 — 티켓이 "서비스 레이어에서 조합" 을 명시, HomeResponse 등 조립 지점이 흩어져 기각.
  - `ImageUploadProperties` 재사용 주입 — :application 소속이라 도메인 모듈에서 역방향 의존. 기각.

## R3. 프로필 사진 입력 검증 — 무엇으로 대체하나

- **Decision**: `validatedImagePath` — trim 후 빈 문자열→null(제거 센티널, KB-124 3분법 유지), 512자 초과 거부, `http://`·`https://` 시작(대소문자 무시) 거부. 에러코드는 기존 `MEMBER-008`(`INVALID_PROFILE_IMAGE_URL`) 재사용, 메시지만 경로 문구로.
- **Rationale**: 저장값이 경로가 되므로 "https + 허용 호스트" 검증은 존재 이유가 사라진다. 전체 URL 거부 패턴은 `ScanRequest` 의 `^(?!https?://)` 선례와 동일 계약. 에러코드 신설 대신 재사용 — 클라이언트 분기 코드 불변.
- **Alternatives considered**: 허용 호스트 검증을 경로에도 유지 — 경로엔 호스트가 없어 무의미. objectKey 형식(purpose-prefix 정규식) 강검증 — presign 발급이 이미 키 형식을 통제하므로 과잉(YAGNI).

## R4. 레거시 절대 URL 행 처리

- **Decision**: 데이터 마이그레이션 없음. `resolve` 가 `http(s)://` 시작 값을 그대로 반환(이중 도메인 방지). 신규 입력은 전체 URL 거부라 레거시는 자연 감소.
- **Rationale**: dev DB 에 실회원 데이터가 있고(KB-163 재기준선으로 보존) 절대 URL 행이 존재할 수 있다. UPDATE 마이그레이션은 CDN 도메인별 prefix 매칭이 필요해 위험 대비 이득이 없다 — 조립 시 통과가 회귀 0 을 보장.
- **Alternatives considered**: Flyway 로 도메인 strip — 도메인 목록을 SQL 에 하드코딩해야 해 기각.

## R5. 저장 키·API 필드 이름

- **Decision**: DB(JSON 키 `profileImageUrl`·컬럼 `image_ref`)와 API 필드(`profileImageUrl`·`imageRef`) 모두 이름 유지. 값 의미만 변경(입력=경로, 출력=완전 URL).
- **Rationale**: JSON 키 리네임은 기존 행 전체 백필 마이그레이션을 요구(KB-158 에서 확인된 구조). API 필드 리네임은 클라이언트 파괴 변경. 이름-의미 불일치는 Swagger 문구(MemberApi)로 해소.
- **Alternatives considered**: `profileImagePath` 리네임 — 마이그레이션·클라 파괴 비용 > 이름 정합 이득. 기각.

## R6. 음식 이미지 조립 방식

- **Decision**: `FoodSummaryView.from(food, lang, codes, imageUrl)` 로 resolved 값을 파라미터로 받는다(기본값 없음 — 호출부 3곳이 명시 전달). `GetFoodDetailResult.imageRef` 는 FoodService.getDetail 이 resolve 해 채운다.
- **Rationale**: `from` 내부에서 resolve 하려면 DTO 가 베이스 문자열을 알아야 해 시그니처 오염이 같다 — resolved 값 전달이 가장 단순. 호출부는 FoodService.foodPage·HomeApplicationService 2곳 + getDetail.
- **Alternatives considered**: Food 엔티티에 resolve 메서드 — 엔티티가 설정값을 알게 되어 기각.
