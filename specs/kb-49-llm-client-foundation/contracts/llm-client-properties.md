# Contract: LLM 클라이언트 설정 프로퍼티 (`meogo.llm.*`)

`:infra:llm` 의 `LlmModelProperties`(@ConfigurationProperties("meogo.llm"))가 바인딩하는 스키마. 값은 루트 `.env`(기존 `spring.config.import: optional:file:.env[.properties]`) 또는 OS 환경변수로 주입한다. **활성 플래그가 false/미설정이면 해당 모델 빈이 생성되지 않아** 키 없이도 부팅이 안전하다.

## 스키마

```
meogo:
  llm:
    openai:
      enabled: false          # true 여야 OpenAI ChatModel 빈 생성
      api-key: ${OPENAI_API_KEY:}
      base-url:                # 미설정 시 OpenAI 공식 기본값
      model: gpt-4o-mini       # 예시 — 실제 모델명은 운영에서 지정
    upstage:
      enabled: false
      api-key: ${UPSTAGE_API_KEY:}
      base-url: https://api.upstage.ai/v1   # OpenAI 호환 — base-url 만 교체해 openai 클래스 재사용
      model: solar-pro
    gemini:
      enabled: false
      api-key: ${GEMINI_API_KEY:}
      base-url:                # google-genai 기본값
      model: gemini-2.0-flash
```

## 필드 계약
| 경로 | 타입 | 기본 | 의미 |
|------|------|------|------|
| meogo.llm.<model>.enabled | Boolean | false | 빈 생성 조건(@ConditionalOnProperty). false/미설정 → 해당 모델 미탑재 |
| meogo.llm.<model>.api-key | String? | null | 벤더 API 키(.env/OS env 주입 권장) |
| meogo.llm.<model>.base-url | String? | null | 엔드포인트 override(Upstage 필수, OpenAI/Gemini 는 기본값) |
| meogo.llm.<model>.model | String? | null | 모델명 |

## 부팅 안전 계약
- 세 모델 모두 `enabled=false`(기본) → LLM 빈 0개, `spring.ai.model.*=none`(자동구성 차단)과 함께 web·batch 컨텍스트 로딩 성공(SC-003).
- 일부만 `enabled=true` → 활성 모델만 fan-out 대상(spec US2 #3).

## 프로필 운용
- `local`/`dev` 등에서 키가 있을 때만 프로필별 override 로 `enabled=true` + `api-key` 지정.
- CI/기본 프로필: 전부 비활성 유지(회귀 0).

> 잔여 검증: Gemini(google-genai)가 API 키 방식인지 GCP 자격증명(project-id/location) 방식인지 구현 시 확정 — 후자면 `gemini` 하위에 `project-id`·`location` 보강(research §11-V3).
