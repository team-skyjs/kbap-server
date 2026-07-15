# Contract: 메뉴판 사진 스캔 API (기존 대체)

## POST /api/v1/scans

인증 필수(`@AuthMemberId`). 요청은 **검증된 이미지 경로 + 클라이언트 자체 OCR 항목**(박스 매칭용)을 함께 받는다. 응답은 기존 구조 유지 + 필드 추가(additive — FR-010a), 단 `idx` 의미가 "매칭된 클라이언트 OCR idx(nullable)" 로 재정의된다.

### Request

```json
{
  "imagePath": "scan/123/20260715-abc123.jpg",
  "items": [
    { "idx": 0, "rawMenuName": "김치찌개" },
    { "idx": 1, "rawMenuName": "6,500" }
  ]
}
```

| 필드 | 타입 | 필수 | 검증 |
|------|------|------|------|
| imagePath | string | ✔ | blank 금지, `http(s)://` 시작 금지(path only), 길이 ≤512, 완료 검증된 본인 소유 이미지 |
| items | array | ✔ | 1~100개, idx 유일 |
| items[].idx | int | ✔ | 클라이언트 OCR 항목 식별자(UI 박스 키) |
| items[].rawMenuName | string | ✔ | blank 금지. 서버가 추출 결과를 이 항목에 매칭하는 힌트 |

### 처리

1. `imagePath` 가 검증·기록된 본인 소유 이미지인지 확인(`:domain:image` 창구) — 아니면 SCAN-001.
2. vision 추출(트랜잭션 밖) — 사진 + 클라이언트 OCR 목록(idx+텍스트)을 넘겨, 메뉴명(표기/표준 한국어)·가격(KRW 정수, 축약 복원)·**대응 OCR idx(matchedIdx, 없으면 null)** 를 받는다.
3. **서버 가드**: LLM 이 요청 목록에 없는 idx 를 반환하면 null 로 처리(할루시네이션 방어).
4. 표준 한국어 이름으로 food 매칭·회피성분 위험도 판정(기존 파이프라인 — 미등록 확정 메뉴는 조사 대기 등록 포함).
5. 히스토리 저장(전 추출 항목, food_id 는 매칭 시만) + 회원 스캔 횟수 증가. (idx 는 UI 임시값이라 히스토리 미저장.)

### Response 200 (기존 구조 + price 추가)

```json
{
  "success": true,
  "payload": {
    "degraded": false,
    "results": [
      {
        "idx": 0,
        "matched": true,
        "foodId": 7,
        "riskLevel": "SAFE",
        "name": "김치찌개",
        "koreanName": "김치찌개",
        "price": 9000
      },
      {
        "idx": null,
        "matched": false,
        "foodId": null,
        "riskLevel": "UNKNOWN",
        "name": "서비스 반찬",
        "koreanName": "반찬",
        "price": null
      }
    ]
  }
}
```

| 필드 | 변경 | 의미 |
|------|------|------|
| degraded | 유지 | vision 경로는 폴백이 없어 항상 `false`(실패는 에러 응답) |
| results[].idx | **의미 재정의 + nullable** | 매칭된 클라이언트 OCR 항목의 idx. 클라이언트가 이 값으로 해당 메뉴 위 박스를 그린다. 추출됐지만 대응 OCR 이 없으면 `null` |
| results[].matched·foodId·riskLevel | 유지 | 기존과 동일 |
| results[].name·koreanName | 의미 확장 | name=사진 표기 그대로, koreanName=표준명(미매칭도 채움) |
| results[].price | **추가** | KRW 정수, 미표기 메뉴는 null. 응답에만 존재 — 저장은 스캔 히스토리 한정 |

### Errors (BaseResponse.fail)

| HTTP | code | 상황 |
|------|------|------|
| 400 | SCAN-001 | 검증되지 않았거나 접근할 수 없는 이미지 경로(미신고·타인 소유·미존재 통합 — 존재 여부 노출 방지) |
| 503 | SCAN-002 | 메뉴판 인식 실패(vision 호출 실패·응답 해석 불가) — 재시도 안내 |
| 400 | COMMON-002 | 요청 형식 위반(blank·전체 URL) |
| 401 | AUTH-* | 미인증 |

추출 항목 0개(메뉴판 아닌 사진)는 에러가 아니라 `results: []` 정상 응답(FR-011).

Swagger(`ScanApi`): 요청 교체·idx 의미·price 추가·"비회원 불가"·SCAN-001/002 문서화.
