---
name: tdd-test-authoring
description: "kbap-server 에서 실패하는 테스트를 먼저 작성하는 절차(TDD Red). Kotest BehaviorSpec(given/when/then 한국어), 모듈별 테스트 종류(도메인 단위·영속 H2·web MockMvc), Red 확인법을 다룬다. 테스트 작성·실패 테스트·Red 단계·테스트 보강 작업 시 반드시 사용."
---

# TDD 테스트 작성 (Red 단계)

kbap-server 의 테스트 우선 규약. 헌법 원칙 I(NON-NEGOTIABLE): 구현 전에 실패하는 테스트를 먼저 쓰고, **실제로 실패함을 확인**한 뒤에야 구현으로 넘어간다.

## 절차

1. **요구사항 수집** — 할당된 task, `specs/<feature>/spec.md`(FR·시나리오), `contracts/*.md`(API 계약), `data-model.md`(엔티티·제약)를 읽는다. 검증할 행동(behavior) 목록을 만든다.
2. **테스트 종류 결정** — 아래 "모듈별 테스트 매핑"으로 어느 소스셋에 무슨 스타일로 쓸지 정한다.
3. **테스트 작성** — BehaviorSpec 으로 행동 단위 given/when/then 을 쓴다(한국어).
4. **Red 확인** — 해당 모듈 테스트를 실행해 **의미 있는 실패**(단언 실패)를 본다. 출력을 증거로 남긴다.
5. **보고** — 작성 파일 경로 + 실행 명령 + Red 증거를 리더/implementer 에게 전달한다.

## 왜 Red 를 먼저 확인하나

테스트가 실패하는 것을 보지 않으면, 그 테스트가 무엇이든 통과시키는 빈 테스트이거나 대상을 잘못 겨냥했을 수 있다. Red 를 봐야 "이 테스트가 진짜 이 요구사항을 잡는다"가 증명된다. 단, **컴파일 에러로만** 빨간 것은 약한 Red 다 — 가능하면 타입/시그니처는 최소로 존재하게 하고 **단언에서** 실패하도록 한다.

## 테스트 스타일 (고정)

- **모든 테스트는 Kotest `BehaviorSpec`**. 다른 Spec·JUnit `@Test` 금지.
- 구조: `given("대상/전제") { \`when\`("상황") { then("기대 결과") { ... } } }`. 설명은 **한국어**.
- Kotlin 주석 금지(라인·블록·KDoc 전부). 이름과 구조로 의도를 드러낸다.
- 단언은 Kotest matcher(`shouldBe`, `shouldNotBeNull`, `shouldContainExactly`, `shouldThrow` 등).

```kotlin
class BoundingBoxTest : BehaviorSpec({
    given("BoundingBox 생성") {
        `when`("x 가 음수이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { BoundingBox(x = -1, y = 0, width = 1, height = 1) }
            }
        }
    }
})
```

## 모듈별 테스트 매핑

| 대상 | 위치 | 스타일 | 비고 |
|------|------|--------|------|
| 순수 도메인(model·port 규칙·불변·폴백) | `:kbap-api:{scan,food,...}/src/test` | 순수 BehaviorSpec(스프링 X) | 가장 빠름. 도메인 규칙·`nameFor(lang)` 폴백 등 |
| application 유스케이스·mock seam | `:kbap-api:application/src/test` | BehaviorSpec(필요 시 fake/mock port) | 컨텍스트 조합·seam(MockCyclingRiskAssessor 등) |
| 영속 어댑터(RepositoryAdapter·매핑·fetch join) | `:kbap-api:persistence/src/test` | `@SpringBootTest` + `SpringExtension`, H2 | 클래스 본문 스타일, `@Autowired lateinit` |
| web 계약(컨트롤러·BaseResponse·상태코드) | `:kbap-api:presentation/src/test` | `@SpringBootTest` + `@AutoConfigureMockMvc` | MockMvc 주입, ObjectMapper 는 `jacksonObjectMapper()` 직접 생성 |

## Spring 통합 테스트 형태 (클래스 본문 스타일)

`@SpringBootTest` 계열은 생성자-블록(`BehaviorSpec({ })`) 대신 **클래스 본문 + `init { }`** 으로 쓰고 `SpringExtension` 으로 빈을 주입한다.

```kotlin
@SpringBootTest
class FooAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FooRepositoryAdapter

    init {
        given("...") { `when`("...") { then("...") { /* ... */ } } }
    }
}
```

- web 테스트는 `@AutoConfigureMockMvc` + `@Autowired MockMvc`. (Spring Boot 4 에서 `@AutoConfigureMockMvc`·`@DataJpaTest` 는 별도 아티팩트이며 `ObjectMapper` 는 테스트에 자동 주입되지 않는다 → `jacksonObjectMapper()` 로 직접 만든다.)
- 영속 테스트는 프로필 미지정 시 임베디드 H2(`create-drop`, flyway off)로 동작한다.
- 영속 모듈 테스트에는 컴포넌트 스캔/엔티티 인식을 위한 테스트 부트 앱(`*PersistenceTestApp`)이 필요할 수 있다 — 기존 패턴(예: `FoodPersistenceTestApp`)을 따른다.

## Red 확인 명령

```bash
./gradlew :kbap-api:<module>:test --tests "<FQCN>"      # 대상 클래스만
./gradlew :kbap-api:<module>:test                        # 모듈 전체
```

실패 출력(어떤 then 이 왜 실패했는지)을 캡처해 보고한다. "통과해 버림"이면 이미 구현됐다는 뜻 — 누락 요구사항을 더 테스트하거나 리더에 보고한다.

## 커버리지 원칙

- 정상 흐름 + **경계·예외·폴백**을 spec/contracts 기준으로 모두 덮는다. 예: blank/누락 입력 → 400, 미수록 메뉴 → 400, lang 미지원/미지정 → ko 폴백, 소프트삭제 row 제외.
- 한 then 은 한 가지를 검증한다. 행동이 다르면 when/then 을 분리한다.
- 구현 세부(내부 호출 횟수 등)가 아니라 **관찰 가능한 결과**를 검증한다. 단, 영속 성능 계약(공유 재료 row 1개·N+1 회피)은 count 단언 등으로 명시 검증 가능.

## 하지 않을 것

- 구현 코드(`src/main`) 작성 — implementer 몫.
- 테스트를 통과시키려 단언을 느슨하게 쓰기 — Red 의 의미가 사라진다.
- spec 모호 시 임의 추정 — 리더에 질의한다.
