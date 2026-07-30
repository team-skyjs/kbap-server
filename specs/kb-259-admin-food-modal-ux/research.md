# Research: 관리자 음식 상세 모달 UX 개선

Technical Context 에 NEEDS CLARIFICATION 없음 — 아래는 설계 선택지 검토 기록이다.

## R1. 스크롤 유지 방식

- **Decision**: URL fragment(anchor) 방식. 각 행에 `id="food-<id>"`를 달고 상세 열기·닫기 링크와 저장 redirect 전부에 `#food-<id>`를 붙인다.
- **Rationale**:
  - SSR 전체 리로드 구조를 유지한 채 브라우저 네이티브 동작만으로 해결 — JS 0줄.
  - Spring MVC `RedirectView`는 redirect 문자열의 fragment 를 보존하므로 POST-redirect-GET 경로에서도 동작한다.
  - DoD 가 "anchor 또는 스크롤 복원"으로 anchor 를 명시 허용.
- **Alternatives considered**:
  - **JS `sessionStorage` 스크롤 좌표 저장/복원**: 픽셀 단위 정확하지만 스크립트 추가·복원 타이밍(렌더 후) 처리 필요. 행 단위 정확도로 충분해 기각.
  - **모달을 fetch + client-side 오픈으로 전환**: 리로드 자체를 없애는 근본 해법이나 SSR 구조 개편 — 이슈 범위(스크롤 유지) 대비 과잉. 기각.

## R2. 이미지 URL 해석

- **Decision**: `AdminFoodService`에 `kbap.storage.public-base-url` 주입 + `AdminFoodDetailView.imageUrl = ImageUrls.resolve(base, imageRef)`.
- **Rationale**: `AdminMemberQueryService.getMemberDetailOrNull` → `AdminMemberDetailView.profileImageUrl`이 동일 패턴의 기존 선례(Jira 이슈도 이 패턴을 지목). `ImageUrls.resolve`는 절대 URL 통과·base 미설정 시 ref 원문 반환을 이미 처리한다.
- **Alternatives considered**:
  - **템플릿에서 base URL 직접 조합**: 프로퍼티를 템플릿 전역으로 노출해야 하고 절대 URL 예외 처리가 중복된다. 기각.
  - **presigned URL 발급**: 관리자 확인용 공개 이미지에 불필요한 비용·만료 관리. 기각.

## R3. 이미지 로드 실패 플레이스홀더

- **Decision**: `<img>`의 `onerror` 인라인 핸들러로 이미지를 숨기고 숨겨둔 플레이스홀더 요소를 노출한다. `imageUrl == null`이면 처음부터 플레이스홀더만 렌더.
- **Rationale**: 존재하지 않는 키(로드 404)는 서버에서 미리 알 수 없다(HEAD 검사는 상세 열기마다 스토리지 왕복 추가). 클라이언트 `onerror`가 가장 싼 지점.
- **Alternatives considered**: 서버 측 존재 검사(`StorageObjectStore.head`) — 상세 조회 지연·스토리지 의존 추가 대비 이득 없음. 기각.
