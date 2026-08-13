# Research: 리뷰 작성 시 식당(장소) 검색·선택 저장 (kb-274)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 스펙이 plan 단계로 미룬 결정(저장 항목 구성·저장 방식·검증 수위)과 검색 API 설계(R7~R10 — 개정에서 추가)를 여기서 확정한다.

## R1. 저장 항목 구성

- **Decision**: 5개 항목 — `placeName`(식당명), `placeAddress`(주소 한 줄), `kakaoPlaceId`(카카오 장소 식별자), `latitude`·`longitude`(좌표). 전 항목 nullable, 묶음 전체도 nullable.
- **Rationale**: 카카오 키워드 검색 응답(`id`·`place_name`·`address_name`/`road_address_name`·`x`·`y`)에서 리뷰 노출(식당명·주소)과 추후 확장(지도 핀 = 좌표, 장소 동일성 판별 = place id)에 필요한 최소 집합. 주소는 도로명/지번 중 클라이언트가 고른 **한 줄**만 받는다(두 줄 다 저장할 소비처가 없다).
- **Alternatives considered**: (a) 식당명·주소 2개만 — 지도 핀·동일 장소 그룹핑 확장 시 재수집 불가라 기각. (b) 카카오 응답 전체(JSON) 보관 — 소비처 없는 필드(카테고리·전화번호·place_url)까지 저장하는 과잉이라 기각.

## R2. 저장 방식 — 컬럼 vs JSON

- **Decision**: `food_review` 에 **개별 nullable 컬럼 5개** + 엔티티는 `@Embeddable ReviewPlace` 값 객체로 묶는다.
- **Rationale**: 컬럼이면 길이·타입을 스키마가 강제하고(컨벤션 — 엔티티 길이 = Flyway 길이), 추후 place id 기반 조회가 필요해져도 인덱스만 추가하면 된다. 프로젝트에 값 객체 선례(`MemberProfile`·`Ranking`)가 있다. JSON(`image_refs` 선례)은 항목별 길이 강제가 안 되고 좌표 타입도 문자열화된다.
- **Alternatives considered**: (a) JSON 컬럼 — 위 이유로 기각. (b) 별도 테이블 — 리뷰와 생명주기가 같고 1:0..1 이라 정규화 이득이 없어 기각(스펙 가정 — 독립 생명주기 없음).

## R3. 컬럼 타입·길이

- **Decision**: `place_name varchar(100)` · `place_address varchar(200)` · `kakao_place_id varchar(30)` · `place_latitude decimal(10,7)` · `place_longitude decimal(10,7)` — 전부 NULL 허용, 인덱스 없음.
- **Rationale**: 카카오 장소명·국내 주소는 각각 100/200자로 충분. 카카오 place id 는 짧은 숫자 문자열이지만 외부 식별자라 형식 변경에 대비해 varchar(30). 좌표는 decimal(10,7)이 위경도 정밀도(±0.011m)로 충분하며 국내 좌표 범위를 커버. 식당별 조회는 스코프 밖이라 인덱스를 미리 만들지 않는다(YAGNI — 필요 시 `kakao_place_id` 인덱스 추가).
- **Alternatives considered**: `POINT` 공간 타입 — 공간 질의 계획이 없어 기각.

## R4. 검증 수위

- **Decision**: 요청 경계(web DTO)에서 **길이·범위 검증만** — `@Size`(문자열)·`@DecimalMin/Max`(위도 -90~90, 경도 -180~180). 항목 단위 결측 허용(식당명 없이 주소만도 저장). 카카오 실존 여부 검증 없음. 도메인(`Review.requireValid`)은 엔티티 길이 상수로 동일 제약을 이중 방어.
- **Rationale**: 헌법 V — 외부 입력 검증은 요청 경계 소유. 서버가 카카오를 호출하지 않으므로(스펙 가정) 실존 검증은 불가능하고 불필요. 항목 결측 허용은 스펙 FR-002 그대로.
- **Alternatives considered**: "place 를 보내면 placeName 필수" 그룹 규칙 — 스펙이 전 항목 선택값으로 고정했고, 클라이언트가 카카오 응답을 그대로 전달하므로 결측 조합을 서버가 재단할 근거가 없어 기각.

## R5. 수정 의미론

- **Decision**: 수정 요청의 place 는 **전량 교체** — 보내면 통째로 대체, 생략하면 제거. 기존 `content`·`imagePaths` 와 동일 규칙.
- **Rationale**: `ReviewUpdateRequest` 가 이미 "보낸 값으로 전량 교체" 계약(스키마 설명에 명시)이라 place 만 부분 병합(merge) 규칙을 두면 계약이 갈라진다.
- **Alternatives considered**: place 필드별 부분 수정 — 클라이언트는 항상 카카오 선택 결과를 통째로 보내므로 무의미해 기각.

## R6. 조회 노출 범위

- **Decision**: `ReviewResponse` 에 `place`(nullable 객체) 추가 — 좌표 포함 전 항목 노출. 별도 조회 API·필터 추가 없음.
- **Rationale**: `ReviewResponse.from` 이 작성·수정·음식별 목록·내 리뷰 목록의 단일 매핑 지점이라 이 한 곳 수정으로 스펙 FR-006(리뷰가 노출되는 모든 조회)이 충족된다. 음식 상세 리뷰 섹션(kb-270)도 같은 응답을 재사용한다.
- **Alternatives considered**: 식당명·주소만 노출(좌표 은닉) — 클라이언트 지도 핀 표시에 좌표가 필요하고, 공개 장소 정보라 은닉할 이유가 없어 기각.

## R7. 검색 제공 주체 — 서버 프록시 (개정)

- **Decision**: 장소 검색은 **서버가 별도 컨트롤러로 제공**하고, 서버가 카카오 로컬 키워드 검색 REST API 를 대신 호출한다. 클라이언트 직접 호출 전제(구 가정)를 폐기한다.
- **Rationale**: 카카오 REST 키를 앱에 심으면 추출·도용에 노출되고 쿼터 통제가 불가능하다. 서버 경유면 키가 서버 프로퍼티에만 존재하고, 응답을 서비스 필요 항목(R1 의 5개)으로 축약해 클라이언트-서버 계약도 안정된다.
- **Alternatives considered**: 클라이언트 직접 호출(카카오 SDK/JS 키) — 네이티브 앱의 REST 검색엔 키 노출 문제가 그대로라 기각.

## R8. 검색 클라이언트 배치 — seam + 신규 어댑터 모듈

- **Decision**: 계약 `common.port.place.PlaceSearchClient`(+ 결과 값 타입 `FoundPlace`, Spring-free) / 구현 `:infra:place` 의 `KakaoPlaceSearchClient`(Spring `RestClient`) / 조립 api config(`PlaceConfig`). api 는 `:infra:place` 를 `implementation` 의존.
- **Rationale**: 헌법 III — 외부 시스템 클라이언트는 seam 인터페이스로만 사용하고 인터페이스 `:common`·구현 `:infra:*`·조립 부트앱 config. 기존 어댑터 4종(llm·auth·redis·storage)과 동일 패턴. seam 덕에 통합 테스트가 페이크로 외부 호출 없이 돌아간다(LLM `LlmModelCaller` 선례). 모듈명은 기존처럼 capability 기준(`place`), 벤더(카카오)는 구현 클래스명에만 둔다 — **제공자 교체 가능성이 이 배치의 핵심**: 추후 Google Places 전환·국가/언어별 제공자 혼합 시 `:infra:place` 에 구현체(예: `GooglePlaceSearchClient`, 라우팅 구현체)를 추가하고 api 조립 config 의 빈만 바꾸면 컨트롤러·DTO·테스트는 무수정이다. 계약 시그니처(`search(query, page)`)와 값 타입(`FoundPlace`)에 카카오 고유 개념을 싣지 않는 것도 같은 이유(단, `kakaoPlaceId` 는 저장 데이터 식별 목적의 외부 id 라 값 타입에 유지 — 제공자 중립 필드명 `externalPlaceId` 대신 출처가 명확한 이름을 택했고, 제공자 추가 시점에 일반화한다).
- **Alternatives considered**: api 모듈 안에 RestClient 직접 구현 — seam 규약 위반(계층 역전 금지 조항)이라 기각. 기존 인프라 모듈에 얹기 — llm/auth/redis/storage 어디에도 capability 가 안 맞아 기각.

## R9. 검색 엔드포인트 형태·오류 계약

- **Decision**: `GET /api/v1/places?query=<키워드>&page=<선택, 기본 1>` — 인증 필수(`JwtAuthenticationFilter` 보호 경로 등록 + `@AuthMemberId`). 응답은 결과 목록(items: 식당명·주소·kakaoPlaceId·좌표) + `hasNext`(카카오 `meta.is_end` 반전). 빈 `query` 는 400(기존 validation 공통 처리), 카카오 호출 실패는 신규 `ErrorCode` **`PLACE-001`**(외부 장소 검색 실패, HTTP 502) 로 반환.
- **Rationale**: 리뷰 작성 화면의 선택 UX 에는 키워드+페이지면 충분(카카오 페이지당 15건). 검색 실패를 전용 코드로 분리해야 클라이언트가 "검색만 실패, 작성은 가능" 분기를 할 수 있다(스펙 FR-003). 카테고리·반경·좌표 기반 검색은 소비처가 없어 뺀다.
- **Alternatives considered**: POST + body 검색 — 조회 의미론에 안 맞아 기각. 페이지 없이 상위 15건 고정 — 동명 식당 다수(프랜차이즈) 케이스에 부족해 페이지 유지.

## R10. 카카오 키 구성·부팅 안전

- **Decision**: REST 키는 `kbap.kakao.rest-api-key` 프로퍼티(프로필별 yml/환경변수). 빈은 항상 조립하되 키 미설정 상태의 실호출은 `PLACE-001` 실패로 처리한다. 통합 테스트는 seam 페이크를 `@TestConfiguration` 으로 대체하므로 키가 필요 없다.
- **Rationale**: LLM 의 `@ConditionalOnProperty` 빈 미생성 방식은 "호출자가 없을 수 있는 배치" 전제다 — api 는 컨트롤러가 항상 주입받아야 하므로 조건부 빈이면 부팅이 깨진다. 키 없는 로컬 부팅은 되고, 검색 호출만 명확히 실패하는 쪽이 낫다.
- **Alternatives considered**: 키 필수(미설정 시 부팅 실패) — 로컬·CI 마찰이 커서 기각.
