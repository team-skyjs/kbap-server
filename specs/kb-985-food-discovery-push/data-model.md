# Data Model: KB-985

## notification (변경)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| food_id | BIGINT NULL | FOOD_DISCOVERY 발송 음식. FK 없음. data.foodId 의 조회용 사본 |
| idx_notification_member_type_created | (member_id, type, created_at) | 90일 이력 조회 + 리더 NOT EXISTS |

## Candidate (메모리, batch)
`id: Long`, `ingredientCodes: Set<String>`, `text: Map<LanguageCode, Pair<name, description>>` — 설명이 있는 언어만.
