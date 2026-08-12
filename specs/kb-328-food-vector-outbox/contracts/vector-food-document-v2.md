# Contract: 음식 벡터 문서 v2 (kbap.foods)

KB-319 의 `vector-food-document.md` 계약을 대체(상위호환 확장)한다. 소비자: 쓰기 = `:batch` `foodVectorSyncJob`, 읽기 = `:api` `DocumentDbSimilarFoodSearcher`(kbap-langchain 초기 적재분과 공존).

## 문서

| 필드 | 타입 | 필수 | 비고 |
|------|------|------|------|
| foodId | long | ✔ | unique index. 서버가 신뢰하는 유일 값 — 나머지는 스냅샷 |
| name | string | ✔ | MySQL `food.korean_name` (질의는 정제된 표준 한국어명 단독 — KB-319 규약 유지) |
| longDescription | string | ✔ | MySQL `food.long_description` |
| imageRef | string | — | MySQL `food.image_ref` 스냅샷 |
| embedding | float[256] | ✔ | cosine 벡터 인덱스 대상 (기존 인덱스 그대로) |
| embeddingHash | string | ✔ | `sha256:<hex>` — SHA-256(`{model}|{dimension}|{name}\n{longDescription}`) |
| embeddingModel | string | ✔ | 예: `amazon.titan-embed-text-v2:0` |
| embeddingDimension | int | ✔ | 256 |
| indexedAt | ISO-8601 UTC | ✔ | 적재 시각 |

## 규약

- **임베딩 원문**: `name + "\n" + longDescription`. hash 입력에 모델·차원을 포함해 모델 교체 시 자동 재임베딩된다.
- **upsert 는 foodId 기준 문서 전체 교체(replace)** — 부분 갱신 없음. 단 hash 동일 시엔 임베딩 재계산 없이 메타데이터 필드(imageRef·indexedAt 등)만 갱신할 수 있다.
- **delete 는 foodId 기준 제거** — 대상 부재는 성공(멱등).
- **구 스키마 문서**(embeddingHash 없음, kbap-langchain 초기 적재분)는 hash 비교에서 항상 불일치 → 첫 UPSERT 처리 때 v2 로 교체된다. 별도 이관 작업 없음.
- 읽기 경로는 `foodId`·`embedding`(·score)만 소비하므로 필드 추가는 검색과 무호환 없음. 표시 데이터는 항상 MySQL 재조회(단일 진실).
