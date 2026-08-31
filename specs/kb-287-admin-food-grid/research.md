# Research: 관리자 음식 목록 카드 그리드·상태 필터·상세 모달

Technical Context 에 NEEDS CLARIFICATION 없음. 설계 판단이 필요한 지점만 정리한다.

## R1. 상태 필터 쿼리 — 파생 쿼리 4분기

- **Decision**: `FoodJpaRepository` 에 파생 쿼리 2개 추가 — `findByContentStatus(status, pageable): Page<Food>`, `findByKoreanNameContainingAndContentStatus(keyword, status, pageable): Page<Food>`. `AdminFoodService.getFoodPage` 가 (keyword × status) 4분기로 기존 `findAll`/`findByKoreanNameContaining` 과 조합.
- **Rationale**: 조건 2개짜리 관리자 목록에 동적 쿼리 프레임워크는 과설계. Spring Data 파생 쿼리는 이미 이 리포지토리의 지배적 패턴(`findByKoreanNameContaining` 등)이고, `@SQLRestriction("status = 'ACTIVE'")` 소프트삭제 필터도 자동 적용된다. `content_status` 는 저기수 컬럼이라 인덱스 불요(페이지 쿼리는 어차피 관리자 1~2명 사용).
- **Alternatives considered**: Specification/QueryDSL(동적 조건 2개에 프레임워크 도입 — 기각), `findAll` 후 메모리 필터(totalCount 가 틀어짐 — 기각).

## R2. 알 수 없는 status 값 처리 — 관대한 파싱

- **Decision**: 컨트롤러가 `status: String?` 로 받아 `FoodContentStatus.entries.find { it.name == it }` 방식으로 파싱, 미일치 → `null`(필터 없음). `@RequestParam FoodContentStatus` 직접 바인딩은 쓰지 않는다.
- **Rationale**: FR-011(알 수 없는 값 → 오류 없이 전체 목록). enum 직접 바인딩은 `ConversionFailedException` → 400/500 이라 요구사항 위반. 기존 `page: String?` + `toIntOrNull()` 패턴과 동일한 방어 스타일.
- **Alternatives considered**: enum 바인딩 + 예외 핸들러(관리자 페이지 컨트롤러엔 전용 핸들러가 없고, 한 파라미터 때문에 만들 가치 없음 — 기각).

## R3. 상세 모달 — 네이티브 `<dialog>` + 서버 렌더 open

- **Decision**: `foodDetail != null` 일 때만 `<dialog class="food-modal">` 을 렌더하고, 인라인 스크립트 한 줄이 로드 시 `showModal()` 호출(대시보드 `report-modal` 선례 — `foods.html:140`). 열기/닫기/편집/취소는 기존 그대로 **GET 쿼리 파라미터 내비게이션**(`detail`·`edit`), 닫기 링크는 `detail` 을 떨군다. 백드롭은 `::backdrop`, ESC 닫기는 `cancel` 이벤트에서 닫기 링크로 이동.
- **Rationale**: JS 상태 관리 없이 기존 서버 렌더 흐름(read-only 오픈 → edit 토글 → 유효성 오류 시 `detail+edit+error` 재오픈)이 전부 그대로 동작한다. `<dialog open>` 속성은 백드롭·top layer 가 없어 `showModal()` 이 필요하다.
- **Alternatives considered**: div 오버레이 + CSS(포커스 트랩·ESC·백드롭 직접 구현 — 네이티브가 공짜로 제공하는 것 재구현이라 기각), 클라이언트 fetch 모달(JSON API 신설 + JS 대폭 증가 — 기각).

## R4. 목록 스크롤 위치 유지 — sessionStorage (앵커 폐기)

- **Decision**: 그리드 뷰포트(`.food-grid-viewport`, 고정 높이 + `overflow-y:auto`)의 `scrollTop` 을 인라인 JS 몇 줄로 sessionStorage 에 저장(스크롤 시)·복원(로드 시)한다. 저장(POST) 리다이렉트의 `#food-{id}` 앵커는 **제거**한다(`listRedirect` 에서 fragment 삭제).
- **Rationale**: 상세 열기·닫기·저장·삭제가 전부 풀 페이지 내비게이션이라 내부 스크롤 컨테이너 위치는 매번 초기화된다 — FR-013/017(위치 유지)을 만족하려면 복원 수단이 필요하다. 앵커는 (1) 사용자가 GET 앵커 점프를 명시 기각한 이력이 있고(KB-260), (2) 내부 스크롤 컨테이너 + sessionStorage 복원과 충돌한다(둘 다 스크롤을 조작). 단일 메커니즘으로 통일하는 쪽이 예측 가능하다. KB-259 의 "저장 후 위치 유지" 계약은 **목적(위치 유지)** 을 sessionStorage 가 대신 이행하는 것이므로 실질 위반이 아니다.
- **Alternatives considered**: `#food-{id}` 앵커 유지(GET 앵커 기각 이력 + 이중 스크롤 조작 충돌 — 기각), `scrollIntoView` JS(선택 카드 기준이라 삭제 후 대상 부재 등 엣지 많음 — 기각).

## R5. 버튼 공통 규격 — `.btn` 베이스 + 역할 변형 4종

- **Decision**: `admin.css` 에 `.btn` 베이스(패딩·radius·폰트·고정 min-width)와 변형 `.btn-primary`(저장 — navy), `.btn-neutral`(편집·취소 — 외곽선), `.btn-danger`(삭제 — `--error-text` 계열)를 추가하고 모달 푸터 버튼 4종에 적용한다. 기존 `.page-btn`·`.btn-primary` 는 다른 화면이 쓰므로 건드리지 않는다.
- **Rationale**: FR-018/019(공통 규격 + 역할 색 + 삭제 경고색). 기존 디자인 토큰(`--navy`·`--error-*`·`--neutral-*`)만 조합하면 되고, 전 화면 버튼 리디자인은 티켓 범위 밖이다.
- **Alternatives considered**: 기존 `.page-btn` 전면 개편(다른 화면 회귀 위험, 범위 초과 — 기각).

## R6. JSON syntax highlighting — 구현하지 않음

- **Decision**: 티켓의 JSON syntax highlighting 항목을 **범위에서 제외**한다. JSON 3종은 기존 `<textarea class="json-input">`(읽기 시 `disabled`) 표시를 유지한다.
- **Rationale**: 애초 이 항목을 받쳤던 방어 명분("저장된 값이 깨진 JSON이어도 원문이 보여야 한다")이 성립하지 않는다 — `name_translations`·`avoidance_substances` 는 MySQL `JSON` 타입 컬럼이라 무효 값이 저장 단계에서 거부되고, 화면에 오는 문자열은 Jackson 이 파싱된 객체를 다시 직렬화한 값이라 항상 유효 JSON 아니면 빈 문자열이다. 남는 이득은 색상 표시뿐인데, 그걸 위해 읽기/편집 마크업 분기 + 인라인 토크나이저 + 토큰 색 CSS 를 유지할 값어치가 없다.
- **Alternatives considered**: 읽기 전용 `<pre>` + 인라인 정규식 토크나이저(초안에서 구현했다가 위 근거로 철회), 외부 하이라이터 CDN(의존성 제약 위반 — 기각).

## R7. 카드 그리드 레이아웃 — CSS Grid + aspect-ratio

- **Decision**: `.food-grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap }`, 카드 썸네일은 `aspect-ratio: 1; object-fit: cover`, 음식명은 한 줄 말줄임(`text-overflow: ellipsis`). 뷰포트는 `.food-grid-viewport { height: calc(100vh - <헤더·페이지네이션 여백>); overflow-y: auto }` — 검색·필터 폼과 페이지네이션은 뷰포트 밖. 이미지는 `AdminFoodSummaryView.imageUrl`(신규 — `ImageUrls.resolve`, 이미 주입된 `imagePublicBaseUrl` 재사용) + `loading="lazy"` + `onerror` 플레이스홀더 폴백(상세 패널의 기존 패턴 재사용).
- **Rationale**: 페이지당 200건 `<img>` 를 lazy 로딩 없이 다 받으면 낭비. auto-fill 그리드는 기존 `.stat-grid` 선례와 같은 접근. 상태 배지는 기존 `.badge-*` 재사용하되 미사용이던 `badge-progress`(PENDING_*)·`badge-warn`(REVIEW_REJECTED) 을 배정해 6종 상태를 구분한다.
- **Alternatives considered**: flex-wrap(정사각 균등 폭에 grid 가 더 단순 — 기각), 서버 페이지 크기 축소(스크롤 요구가 내부 스크롤로 해결되므로 불필요 — 기각).

## R8. 검색·필터 UI — 단일 GET 폼

- **Decision**: 기존 `form.food-search` 를 확장해 `q` 인풋 + `status` `<select>`(전체 + 6종, 템플릿의 `T(...FoodContentStatus).values()` 열거 재사용) + 검색 버튼의 단일 GET 폼으로 만든다. 페이지네이션·상세보기·모달 내 hidden 필드 전부에 `status` 를 스레딩한다.
- **Rationale**: SSR GET 폼이 기존 패턴. select 는 상태 6종 + 전체를 한 컨트롤에 담는 가장 작은 UI(FR-007, SC-002 조작 2회 이하).
- **Alternatives considered**: 상태 칩(체크박스) 다중 선택(티켓 요구는 단일 상태 필터 — 기각).
