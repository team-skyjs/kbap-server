# KB-803 음식 목록 이름 정렬 계약

상태: 구현·검증 중. dev/prod 배포 전이며 기존 운영 릴리스 후보와 독립이다.

## 범위와 호환

- `GET /api/foods`, `GET /api/bookmarks`에 `X-API-Version: 3.1` 이상 계약을 추가한다. 기존 1.x/2.0/3.0 응답은 그대로 숫자 nextCursor다.
- 새 계약의 `sort`: `default`(생략 기본), `name_asc`, `name_desc`. 기본순은 기존 foodId DESC / bookmarkId DESC를 보존한다. 미정의 값은 400 COMMON-002.
- 새 계약은 모든 정렬에서 문자열 nextCursor를 반환한다. FE는 해석·숫자 변환 없이 그대로 전달한다. 이전 숫자 커서는 새 계약에서 400 FOOD-002다.
- FE 연결은 이 두 목록 요청의 버전 선택을 명시한다. 앱 공통 헤더를 일괄 3.1로 올리는 작업은 이 PR 범위가 아니다. 기존 서버에서 scans는 2.0 이상 별도 계약, 3.0 이상 substitution 노출이므로 다른 요청의 버전도 함께 바꾸면 해당 계약 검증이 필요하다.
- lang 필수, 미지원 값은 기존 LanguageCode 규칙에 따라 en. 위험도 CSV와 인증 규칙은 기존과 동일하다. 홈·검색·스캔 내역 정렬은 변경하지 않는다.

## 비교 규칙

- 실제 Food.displayName(lang)과 같은 resolver를 쓴다. ko는 displayName(공백이면 koreanName), 타 언어는 비어 있지 않은 정확한 언어 번역, 없으면 한국어 표시명이다.
- ICU4J 77.1 / CLDR 47, SECONDARY(대소문자 동률·악센트 구분), canonical decomposition, numeric collation 활성, 공백·기호 무시 안 함. 중국어 간체 pinyin, 번체 stroke를 명시한다. 나머지는 해당 언어의 standard collation.
- `(로케일 비교키, foodId)`로 전순서를 만든다. 2와 02, 대소문자 등 비교상 동률은 foodId ASC. 역순은 두 기준을 모두 반전한다. Saved에서도 동률 기준은 foodId다.
- 일본어 한자 독음을 추정하거나 표시하지 않는 번역·로마자 이름을 기준으로 쓰지 않는다.

## 페이지·변경 감지

- 같은 읽기 전용 REPEATABLE READ 트랜잭션 안에서 이름 projection, 회원 범위·회피 성분, 페이지 엔티티를 읽는다. 격리 수준을 변경하지 않는다.
- 커서는 형식/비교기 버전, 요청 fingerprint, 목록 revision, 마지막 탐색 위치를 가진다. 언어·방향·전체/Saved·회원·위험도 불일치는 400 FOOD-002.
- 표시 이름·공개 대상·저장 목록·회피 성분 등 revision 변경은 409 FOOD-022. 클라이언트는 커서를 버리고 첫 페이지부터 다시 요청한다. 서버 프로세스별 캐시나 세션에 커서를 종속시키지 않는다.
- 최대 20건을 반환한다. 위험도 필터는 기존처럼 최대 500 후보를 탐색한다. 상한에 도달하면 0건이어도 hasNext=true, 마지막 탐색 후보 이후의 커서를 반환한다. 21번째 적격 후보를 발견하면 마지막 반환 후보를 경계로 사용해 다음 페이지에서 빠지지 않도록 한다.
- FE는 언어·정렬·위험도·계정·전체/Saved 변경 시 페이지를 초기화하고 요청 세대가 지난 응답을 합치지 않는다.

## 조회 비용과 저장 전략

MySQL의 기존 utf8mb4 콜레이션은 문자열 내 숫자를 자연수순으로 비교하지 않는다. JSON 번역에서 선택한 이름을 로케일별로 정렬하는 현재 인덱스도 없다. 새 스키마 없이 우선 가벼운 이름 projection을 읽고 서버에서 비교하며, 상세 엔티티는 페이지 후보만 읽는다. 전체 엔티티 적재나 클라이언트 일부 페이지 정렬을 하지 않는다.

이 방식은 요청당 카탈로그 크기에 비례하는 metadata 읽기·정렬 비용이 있다. 성능 측정 결과와 현재 한계를 PR에 공개한다. 대규모 카탈로그 전환 시 persisted sort-key projection과 원자 revision 교체를 별도 설계해야 하며, 지금 O(1) 인덱스 조회인 것처럼 주장하지 않는다.

참고: ICU numeric collation https://unicode-org.github.io/icu/userguide/collation/customization/ ; ICU 77.1 https://github.com/unicode-org/icu/releases/tag/release-77-1
