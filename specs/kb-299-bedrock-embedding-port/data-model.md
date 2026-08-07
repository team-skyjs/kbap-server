# Data Model: 임베딩 생성 포트 및 인프라 어댑터

영속 엔티티·스키마 변경 없음(Flyway 0건). 이 기능의 데이터는 메모리 내 값뿐이다.

## 값 (in-memory)

### 임베딩 벡터

- **표현**: `FloatArray` — 차원 1024 고정(Titan Text Embeddings V2 기본 출력, 정규화됨 → 코사인 유사도 적합)
- **불변식**:
  - 입력 텍스트와 1:1 대응, 대응 관계는 목록 순서로 식별(입력 i번째 ↔ 출력 i번째)
  - `size == 1024` — 어댑터가 검증하고 위반 시 예외(잘못된 벡터가 하류로 흐르지 않음)
- **수명**: 호출자에게 반환되는 즉시 이 기능의 책임 종료(저장은 KB-299 잔여 범위)

### 임베딩 설정 (`LlmModelProperties.EmbeddingProps`)

| 필드 | 타입 | 기본값 | 의미 |
|------|------|--------|------|
| `enabled` | Boolean | `false` | 빈 생성 스위치 — false/미설정이면 어댑터 빈 미생성(부팅 안전) |
| `model` | String | `amazon.titan-embed-text-v2:0` | Bedrock 모델 id |
| `region` | String | `ap-northeast-2` | AWS 리전 |
| `dimension` | Int | `1024` | 기대 차원(응답 검증용) |
| `timeout` | Duration | 기존 `callTimeout` 준용 | 호출 타임아웃 |

## 상태 전이

없음 — 무상태 변환(텍스트 → 벡터)만 존재한다.
