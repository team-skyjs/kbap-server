# Quickstart — KB-302 로컬 확인

## 설정

`api/src/main/resources/application-local.yml`

```yaml
kbap:
  food-content-outbox:
    recollect-max: 500                  # 일괄 재수집 1회 상한
```

**발행은 이번 범위 밖**이라 큐 설정이 없다. 아웃박스 행은 쌓이기만 하고 `PENDING` 으로 남는다 — 정상이다.

## 흐름 확인

### 1. 스캔 미보유 음식 → 아웃박스 적재

메뉴판 스캔을 한 번 태운 뒤:

```sql
select f.id, f.display_name, f.content_status, r.outbox_status
from food_content_outbox r join food f on f.id = r.food_id
order by r.id desc limit 10;
```

새 음식이 `FAILED` 로 등록되고 `PENDING` 요청이 함께 생겼는지 본다.

### 2. 관리자 일괄 재수집

`/admin/foods` 에서 검색어·상태로 좁힌 뒤 "재수집" → 확인 다이얼로그의 대상 건수를 확인하고 실행. 같은 쿼리로 `PENDING` 행이 생겼는지 본다. 이미 대기 중이던 음식은 중복 생성되지 않아야 한다.

### 3. 적재 API 직접 호출

```bash
curl -X POST localhost:8080/api/v1/admin/foods/contents \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{
    "foodId": 1,
    "passed": true,
    "description": "들깨를 곱게 갈아 넣어 고소한 칼국수",
    "spiciness": 2,
    "nameTranslations": {"en":"Perilla Kalguksu","ja":"えごまカルグクス","zh-Hans":"紫苏刀削面","zh-Hant":"紫蘇刀削麵","vi":"Mì Kalguksu tía tô","id":"Kalguksu Perilla","th":"คัลกุกซูงาขี้ม้อน","ru":"Кальгуксу с периллой","es":"Kalguksu de perilla"},
    "descriptionTranslations": {"en":"...","ja":"...","zh-Hans":"...","zh-Hant":"...","vi":"...","id":"...","th":"...","ru":"...","es":"..."},
    "ingredients": [{"code":"SESAME","inclusion_percent":100}]
  }'
```

확인 포인트:

| 사전 상태 | 기대 결과 |
|---|---|
| `READY` + 사진 있음 | 텍스트만 바뀌고 `content_status='READY'`, `image_ref` 불변 |
| `FAILED` + 사진 있음 | `PENDING_REVIEW` 로 이동, `image_ref` 불변 |
| `FAILED` + 사진 없음 | `PENDING_IMAGE` |
| 삭제된 `foodId` | 400 `FOOD-001` |
| 번역 8키만 전송 | 400 `COMMON-002`, DB 변화 없음 |

이미지 재생성이 안 걸리는지는 이 쿼리로 확인한다 — 결과가 비어야 한다:

```sql
select id from food where content_status = 'PENDING_IMAGE' and image_ref is not null;
```

### 4. 실패 결과

```bash
curl -X POST localhost:8080/api/v1/admin/foods/contents \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{"foodId":1,"passed":false,"failureKind":"INGREDIENT_GUARD","reason":"기피성분 62점 < 임계값 80: 견과 교차오염 확인 필요"}'
```

`READY` 였다면 상태가 그대로고 `content_failure_kind`·사유만 기록된다. 관리자 화면에서 유형·사유가 보이는지 확인한다.

## 테스트

```bash
./gradlew :common:test --tests "*FoodContent*"     # 엔티티 상태 규칙
./gradlew :api:test --tests "*ContentIngest*"      # 적재 API
./gradlew :api:test --tests "*ContentOutbox*"     # 아웃박스 적재·일괄 재수집
./gradlew build                                    # 전체(마이그레이션·ArchUnit 포함)
```
