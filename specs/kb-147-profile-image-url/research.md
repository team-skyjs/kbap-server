# Research: 프로필 사진 URL·맵기 선호 필드 추가 (KB-147)

## R1. 저장 방식 — JSON 컬럼 필드 vs 별도 VARCHAR 컬럼

- **Decision**: 기존 `member.profile` JSON 컬럼(`MemberProfileJson`)에 `profileImageUrl` 필드를 추가한다. **Flyway 마이그레이션 0건.**
- **Rationale**:
  - 프로필 속성(회피 성분·맵기 선호·국가·앱 언어)은 이미 전부 JSON 컬럼 단일 출처다(KB-117 스키마 통합). 사진 URL 은 같은 성격 — 조회 필터·정렬·인덱스·유니크 제약 대상이 아닌 순수 표시용 값이라 별도 컬럼의 이점이 없다.
  - `MemberProfileJson` 전 필드가 기본값을 가지므로 기존 row(키 부재 JSON)는 Jackson 역직렬화 시 자동으로 `null` = 미설정. Jira DoD 의 의도("nullable — 기존 회원·미설정 회원은 null")를 스키마 변경 없이 충족한다.
  - 스키마를 만지지 않으므로 하위 호환 리스크(마이그레이션 순서·롤백)가 0이다.
- **Alternatives considered**:
  - **별도 `profile_image_url VARCHAR(512)` 컬럼 + 마이그레이션**(Jira DoD 의 문구 그대로): `nickname` 처럼 SQL 로 직접 읽거나 검색할 값일 때 유효한 선택. 사진 URL 은 그런 용도가 없고, DoD 문구는 프로필이 JSON 컬럼 구조임을 확인하기 전 작성된 것으로 판단해 기각. (후일 URL 로 검색/통계가 필요해지면 그때 컬럼 승격 — 마이그레이션은 그 시점 1건.)

## R2. 제거(null 복귀) 표현 — 부분 수정의 "미전송=유지" 규칙과 공존

- **Decision**: 사용자 확정(스펙 Q1) — **빈 문자열 전송 = 제거**. 부분 수정 시맨틱 3분법: 미전송(null)=유지 · 값=검증 후 교체 · 빈 문자열(blank)=제거(null). 온보딩에서 빈 문자열은 미설정과 동일 취급.
- **Rationale**: 기존 `ProfileUpdateRequest` 는 Kotlin nullable 필드로 "미전송=null=유지"를 표현하므로 JSON `null` 과 필드 생략이 구분되지 않는다. 빈 문자열 센티널은 별도 API·JsonNullable 도입 없이 기존 PATCH 하나로 해결한다.
- **Alternatives considered**: 별도 DELETE 엔드포인트(API 1개 증가 — 범위 초과), `JsonNullable`/`Optional` 래퍼(전 필드 시그니처 오염 — 필드 1개 때문에 과함), v1 제거 미지원(제품상 제거는 필요 — 기각).

## R3. URL 검증 정책 — 형식 + 허용 호스트(CDN) 목록

- **Decision**: 사용자 확정(스펙 Q2 — "S3 + CloudFront CDN 서빙, 제약 가능 여부 질문"에 대한 답) — 2단 검증:
  1. **형식**: `java.net.URI` 파싱 성공 + 스킴 `https` + 호스트 존재 + 전체 길이 ≤ 512자.
  2. **허용 호스트**: 설정 프로퍼티 `kbap.member.profile-image-allowed-hosts`(호스트 목록)가 **비어 있지 않은 환경에서는 URL 호스트가 목록에 정확 일치**해야 한다. 빈 목록(기본값·로컬·테스트)이면 형식 검증만.
  - 불합격은 `BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)`(MEMBER-008, HTTP 400).
- **Rationale**: CloudFront 배포 도메인은 고정 값이므로 호스트 정확 일치 대조는 저렴하고 확실하다. 프로퍼티로 두면 도메인 값이 코드에 박히지 않고(환경별 상이·스펙 범위 밖), 로컬/테스트는 기본 빈 목록으로 자연히 열린다. prod(·staging) yml 에만 CDN 도메인을 등록한다.
- **Alternatives considered**: 형식 검증만(외부 임의 URL 허용 — 사용자가 도메인 제한 의향), 도메인 하드코딩(환경별 상이·변경 시 재배포 — 기각), 콘텐츠 존재 검증(HEAD 요청 — 외부 호출을 요청 경로에 넣는 비용·헌법 Additional Constraints 취지 위배 — 기각).

## R4. 검증 로직 위치와 설정 주입

- **Decision**: `MemberService` 의 기존 `validated~` private 관례(`validatedNickname`·`validatedCountry` 등)에 `validatedImageUrl` 을 추가하고, 허용 호스트 목록은 `@Value("\${kbap.member.profile-image-allowed-hosts:}")` 생성자 주입(콤마 구분 → 리스트, 기본 빈 값).
- **Rationale**: 검증은 도메인 규칙이므로 도메인 서비스 소유(컨트롤러에 두면 창구 우회 시 뚫림). 도메인 모듈은 이미 Spring 관리(`@Service`·`@Transactional`)라 `@Value` 주입이 새 의존을 만들지 않는다. 필드 1개용 `@ConfigurationProperties` 클래스는 과함.
- **Alternatives considered**: `@ConfigurationProperties` 전용 클래스(설정이 늘면 그때 승격), Bean Validation `@Pattern`(요청 DTO 검증 — 허용 호스트가 환경 설정이라 애너테이션으로 표현 불가, 도메인 규칙 소유권도 어긋남 — 기각).

## R5. 에러 코드 채번

- **Decision**: `INVALID_PROFILE_IMAGE_URL("MEMBER-008", 400, "프로필 사진 URL 형식이 올바르지 않습니다")`·`INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400, "맵기 선호는 0~10 사이여야 합니다")` — MEMBER 접두 다음 번호(007 까지 사용 중).
- **Rationale**: ErrorCode enum 단일 출처·도메인 접두 3자리 채번 규약. 사진 URL 은 형식 불합격과 허용 도메인 밖을 코드 하나로 묶는다 — 클라이언트 분기가 갈릴 이유가 없다(둘 다 "올바른 사진 URL 아님").

## R6. 맵기 선호 개방 방식 (추가 요청 2026-07-15)

- **Decision**: 도메인·저장은 무변경(이미 `MemberProfile.spicinessPreference` 0~10·기본 5, JSON 저장) — **입출력 경로만 개방**한다. 온보딩·부분 수정 입력에 `spicinessPreference: Int? = null`(미전송=기존 값 유지 — 신규 회원은 기본 5), 조회 응답에 포함. 범위 검증은 `MemberService.validatedSpiciness`(0~10 밖 → MEMBER-009, 400)로 서비스 계층에서 선행한다.
- **Rationale**:
  - 현재 `completeOnboarding`·`updateProfile` 이 `member.profile.spicinessPreference` 를 항상 그대로 복사해 입력 경로가 죽어 있고, `MyProfileResult` 에도 빠져 있다 — DTO 필드 추가 + 병합 한 줄이면 열린다.
  - 범위 검증을 서비스에 두는 이유: `MemberProfile` init 의 `require` 는 IllegalArgumentException(→ 500)이라 API 계약(400 + 안정 코드)에 못 쓴다. 기존 `validated~` 관례(닉네임·국가·언어)와 동일하게 `BusinessException` 으로 선행 거절한다. `require` 는 최후 방어선으로 유지.
  - 미전송=유지 시맨틱은 부분 수정의 기존 규칙과 동일하고, 온보딩에서도 같은 시맨틱(미전송 → 기본 5 유지)이라 요청 DTO 가 일관된다. 맵기는 non-null 속성이라 사진과 달리 제거(빈 값) 개념이 없다.
- **Alternatives considered**: 온보딩 필수 입력(기존 클라이언트 온보딩이 즉시 깨짐 — 하위 호환 SC-002 위배, 기각), Bean Validation `@Min/@Max`(범위는 표현 가능하나 검증 소유권이 도메인 규칙 관례와 어긋나고 에러 코드도 COMMON 계열로 빠짐 — 기존 `validated~` 관례 유지).
