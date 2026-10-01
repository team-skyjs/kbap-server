---
name: kbap-tdd-implementation
description: "kbap-server 에서 tasks.md 작업 묶음을 자기 워크트리에서 Red→Green→Refactor→커밋까지 처리하는 구현 절차. implementer 에이전트가 시작 시 반드시 호출한다. 워크트리 확인, BehaviorSpec 테스트 작성, Red/Green 판정 기준, 모듈 테스트 실행, kbap 규약(모듈 경계·트랜잭션·응답 봉투·주석 금지), 커밋·보고 형식을 담는다. 팀 구현·워크트리 구현·task 묶음 구현·'할당된 task 구현해' 요청 시 사용. 리뷰는 kbap-code-review, 단독 직접 구현은 speckit-implement 가 담당한다."
---

# kbap TDD 구현 절차 (워크트리 구현자용)

리더가 준 task 묶음을 전용 워크트리에서 끝까지 구현하고 커밋하는 절차다. 순서를 지키는 이유는 하나다 — **다른 워크트리의 동료와 리더가 당신의 결과를 그대로 머지할 수 있어야** 한다.

## 0. 워크트리 확인 (첫 도구 호출)

```bash
pwd && git rev-parse --abbrev-ref HEAD && git status --short | head
```

- `pwd` 가 메인 체크아웃이 아니라 워크트리여야 한다. 메인 체크아웃이면 편집을 멈추고 리더에게 알린다 — 메인에서 편집하면 다른 구현자와 충돌한다.
- 브랜치명을 기록한다. 최종 보고에 적어야 리더가 머지한다.
- 워크트리에는 `.env` 가 없다. 테스트(Testcontainers)는 `.env` 없이 돈다. `bootRun` 은 이 절차에서 쓰지 않는다.

## 1. 컨텍스트 로드

프롬프트의 feature 디렉터리에서 `tasks.md`(할당 task 본문), `plan.md`(구조·제약), 있으면 `research.md`·`data-model.md` 를 읽는다. 인터페이스 계약은 프롬프트에 적힌 것이 최종이다 — data-model 과 다르면 프롬프트를 따르고 리더에게 알린다.

## 2. task 순서대로 Red → Green → Refactor

task 를 tasks.md 의 ID 순으로 처리한다. 병렬 `[P]` 표시는 리더가 묶음을 나눌 때 쓴 것이고, 한 구현자 안에서는 순차다.

### 2-1. Red 판정 기준

| task 성격 | Red 는 무엇인가 | 확인 방법 |
|-----------|----------------|-----------|
| 새 동작(엔드포인트·서비스 메서드·검증 규칙) | 그 동작을 검증하는 BehaviorSpec 이 **단언에서** 실패 | 테스트 작성 → 모듈 테스트 실행 → 실패 원문 확인 |
| 동작 변경 없는 리팩터(구조 이동·시그니처 정리) | 기존 테스트가 리팩터 **전에** 그린 | 편집 전 모듈 테스트 실행 → BUILD SUCCESSFUL 확인 |

Red 가 컴파일 에러로만 나면 의미 있는 실패가 아니다. 대상 타입·시그니처를 빈 껍데기로 두어 단언에서 실패하게 만든다. 이미 통과해 버리면 그 사실을 보고에 적고 빠진 요구를 추가로 테스트한다.

### 2-2. 테스트 작성 규약

- 전부 Kotest `BehaviorSpec`. `given("대상/전제") > \`when\`("상황") > then("기대 결과")`, 설명은 한국어.
- 통합 테스트 헤더는 합성 애너테이션 하나뿐이다: api 는 `@IntegrationTest`, batch 는 `@BatchIntegrationTest`. `@SpringBootTest(...)`·`@AutoConfigureMockMvc`·`@Import`·`properties` 를 직접 쓰면 Spring 이 새 컨텍스트(= 새 MySQL 컨테이너)를 띄워 전체가 느려진다.
- 한 컨텍스트가 DB 를 공유한다. 시드는 자기 클래스가 만들고 정리는 `TestTables.clearAll(dataSource)` 로 한다. 부분 `DELETE` 는 다른 클래스의 자식 행 FK 에 막힌다.
- tasks.md 가 "무수정"이라 명시한 테스트 파일은 열지 않는다. 회귀 안전망을 고치면 안전망이 아니다.

### 2-3. Green — 최소 구현

task 본문이 적은 파일·시그니처·본문 요지를 그대로 옮긴다. 다음은 넣지 않는다: task 에 없는 헬퍼·추상화·설정값·방어 분기·로그. Green 에 필요한 것만 쓴다. 이유는 간단하다 — 리더가 여러 묶음을 머지하므로 여분 코드는 충돌과 리뷰 되돌림의 원인이 된다.

### 2-4. Refactor

중복 제거·이름 정리 뒤 테스트를 다시 돌려 그린을 확인한다. 리팩터 범위도 소유 파일 안이다.

## 3. kbap 규약 (어기면 리뷰에서 되돌아온다)

- **모듈·패키지**: `:common`(영속·seam)·`:api`(`com.kbap.api.<feature>`)·`:batch`. api 는 `com.kbap.domain`·`com.kbap.application` 패키지를 쓰지 않는다. 어댑터 구현 직접 참조는 조립 config 와 어댑터 자신에서만.
- **서비스 순환 금지**: 새 의존을 넣기 전 상대 서비스가 이쪽을 이미 의존하는지 `grep -n "import com.kbap.api" <상대 파일>` 로 본다. 순환이면 리포지토리를 직접 주입한다(단순 영속 접근은 허용).
- **트랜잭션**: DB 를 만지는 서비스 public 메서드는 전부 명시 `@Transactional`(읽기는 `readOnly = true`). `isolation` 지정 금지. private 메서드에는 달지 않는다(프록시가 안 탄다).
- **네이밍**: 조회 `get~`(단건 없으면 예외, null 정상값만 `get~OrNull`), 목록 `get~s`, 페이지 `get~Page: ~Page`, 생성/수정/삭제 `create~/update~/delete~`, 도메인 행위는 업무 동사 그대로.
- **응답**: 컨트롤러는 `ResponseEntity<BaseResponse<T>>`, 경로는 `ApiPaths.API + "/<리소스>"`, 버전은 `X-API-Version` 헤더. 새 경로는 `WebConfig` 의 JWT 보호 경로에 등록한다 — 빠뜨리면 전 시나리오가 401 이다.
- **엔티티**: `BaseEntity` 상속, 크로스 도메인 참조는 `Long` id, 소프트 삭제는 `delete()`. 컬럼 길이는 Flyway 와 일치. Flyway 파일명은 `Vyyyy.MM.dd.HH.mm.ss__desc.sql`(생성 시각).
- **LIKE**: `LikeWildcards.escape` + 쿼리 ESCAPE 절.
- **Kotlin 주석 금지**: `.kt` 에 `//`·`/* */`·KDoc 을 새로 쓰지 않는다. 제약·근거는 커밋 메시지에 적는다.

## 4. 테스트 실행

```bash
./gradlew :api:test          # 변경 모듈. Kotest 는 --tests 필터를 무시하므로 항상 모듈 전체가 돈다
```

- Docker 가 떠 있어야 한다(Testcontainers). 못 돌리면 리더에게 알리고 커밋하지 않는다.
- 다른 워크트리도 같은 시각에 Gradle 을 돌린다. 데몬은 공유돼도 안전하다. 느리면 기다리지 `--offline`·`-x test` 로 우회하지 않는다.
- 실패 원문은 `api/build/test-results/test/*.xml` 또는 `api/build/reports/tests/test/index.html` 에서 본다.

## 5. 커밋과 보고

묶음이 끝나면 워크트리 브랜치에 한 번 커밋한다(태스크 여럿이면 논리 단위별 여러 커밋도 좋다).

```bash
git add -A && git commit -m "<type>(<scope>): <한국어 제목>

<왜 이렇게 했는지 — 제약·근거는 여기 적는다(Kotlin 주석 대신)>

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

tasks.md 의 `[ ]` 는 고치지 않는다 — 리더가 머지 후 한 번에 체크한다(여러 워크트리가 같은 파일을 고치면 충돌한다).

최종 보고는 implementer 에이전트 정의의 "구현 보고" 형식으로 돌려준다. 브랜치명·커밋 SHA·테스트 명령과 결과가 빠지면 리더가 머지할 수 없다.

## 6. 계약 변경·차단 시

- 프롬프트의 인터페이스 시그니처를 바꿔야 하면 **바꾸기 전에** 영향받는 구현자와 `main` 에 SendMessage 를 보낸다. 첫 줄은 "계약 변경: `<이전>` → `<이후>`" 로 시작한다.
- 소유 파일 밖을 고쳐야 하면 고치지 말고 `main` 에 알린다.
- 같은 실패를 두 번 고쳐도 안 풀리면 에러 원문과 시도한 것을 `main` 에 보낸다.
