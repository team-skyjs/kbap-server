# Contract: DocumentDB 유사 음식 검색 (벡터 저장소)

서버(`com.kbap.api.scan` 의 `DocumentDbSimilarFoodSearcher`)가 **읽기 전용으로 소비**하는 계약. 적재(임베딩 생성·문서 upsert)는 이 기능 범위 밖이며, 적재 측(kbap-langchain 또는 후속 배치)은 이 계약을 따른다.

## 도큐먼트 (collection: `foods`, database: `kbap`)

```json
{
  "foodId": 12,                  // MySQL food PK — 수록 대상은 MySQL 존재(READY) 음식으로 한정
  "name": "김치찌개",             // 참고 메타데이터 — 서버 응답에 직접 쓰지 않음(MySQL 재조회)
  "description": "...",          // 참고 메타데이터 (이름+긴 설명이 임베딩 원문)
  "imagePath": "foods/12.jpg",   // 참고 메타데이터
  "embedding": [0.01, ...]       // float 256차원 — Bedrock Titan V2 (dimensions=256), 차원 변경 = 전량 재임베딩
}
```

- 벡터 인덱스: `embedding` 필드, cosine similarity (KB-318 구축).
- **임베딩 원문 규약**: 저장 = 이름 + 긴 설명 결합 텍스트, 검색 질의 = 정제된 표준 한국어명 단독. 양쪽 모두 Titan V2 · 256차원 고정.

## 검색 (서버 → DocumentDB)

`$search` 집계(DocumentDB 벡터 검색 문법 — MongoDB Atlas 의 `$vectorSearch` 와 다름에 주의):

```json
[
  { "$search": { "vectorSearch": { "vector": [/* 256 floats */], "path": "embedding", "similarity": "cosine", "k": 1 } } },
  { "$project": { "foodId": 1, "score": { "$meta": "searchScore" } } }
]
```

- 반환: score 내림차순 top-k. 서버는 foodId·score 만 소비한다.
- 정확한 파이프라인 문법은 dev 클러스터에서 검증한다(quickstart) — 로컬 MongoDB·Testcontainers 로 재현 불가.

## 연결 (서버 설정 `kbap.vector.*`)

- `uri`: DocumentDB 연결 문자열 — TLS 필수(`tls=true` + AWS CA 번들), replicaSet 읽기 선호 설정 포함 가능.
- `enabled=false`(기본, local) 면 어댑터 빈 미생성 — 유사 폴백 no-op.

## 드리프트 방어

- 문서 메타데이터(name·description·imagePath)는 적재 시점 스냅샷이라 MySQL 과 어긋날 수 있다 — 서버는 foodId 만 신뢰하고 응답 데이터는 MySQL 재조회로 만든다(research R5).
- 소프트 삭제·비READY 전환된 foodId 가 검색되면 서버가 응답에서 제외한다(적재 측 정리 배치는 후속 과제).
