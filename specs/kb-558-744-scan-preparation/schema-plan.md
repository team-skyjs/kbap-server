# Q1 가산 SQL 선행 설계 — SQL 미생성

> 2026-10-19 후속: [W0 실행 확정·복구 계약](execution-delta.md)이 재조회 순서·L1~L3·미결정 게이트를 보완한다. Q1의 상세 저장/전환 결정은 해당 문서의 근거 목록을 따른다. 원안의 미정 항목을 다시 결정하지 않는다.
기준과 권한은 contract.md. 다음은 최소 영속 구조 제안이며 실제 DDL·마이그레이션 파일이 아니다. Q0 검토 후 api Flyway만 담은 선행 Draft PR을 만든다. 파일 버전은 실제 생성 시 로컬 timestamp. dev/prod 적용과 API/batch 기동을 확인한 뒤 동일 파일을 포함하는 기능 PR로 진행한다. 이번 큐레이션 #403 통과를 스캔 스키마 통과로 재사용하지 않는다.

모든 신규 엔티티 테이블에 BaseEntity의 id BIGINT IDENTITY, status, created_at/updated_at을 포함. 기존 members/orders/scan_history 컬럼을 바꾸거나 필수 백필하지 않고 관계 테이블을 더한다. FK는 실제 기존 테이블명/PK 타입을 DDL 작성 전에 대조, ON DELETE CASCADE 없음. 소프트 삭제 후 멱등 unique를 재사용하지 않는다.

| 테이블 제안 | 최소 필드 | 제약/조회 인덱스 |
|---|---|---|
| scan_operation | member_id, request_key, manifest_hash, mode, lifecycle, reservation_state, reservation_until, usage_recorded_at, expires_at, result_revision, version | unique(member_id,request_key); index(member_id,reservation_state,reservation_until); index(lifecycle,expires_at); FK member |
| scan_operation_photo | operation_id, photo_id, selection_index, uploaded_image_id/object_key, source_fingerprint, execution_state, attempt_no, lease_token, lease_until, error_reason | unique(operation_id,photo_id); unique(operation_id,selection_index); unique(operation_id,object_key); index(execution_state,lease_until); FK operation/upload |
| scan_attempt | photo_row_id, attempt_no, claim_token, state, provider_request_ref, started_at, finished_at, failure_kind | unique(photo_row_id,attempt_no); FK photo; 늦은 결과 claim 검증 |
| scan_operation_result | operation_id, photo_row_id, result_document(JSON), committed_attempt_no | unique(photo_row_id); FK operation/photo; 조회 가능한 성공결과와 확정시도 |
| scan_usage | operation_id, recorded_at, entitlement_at_commit | unique(operation_id); FK operation. 작업당 성공사용1의 불변 증거, 회원 scanCount는 같은 TX에서 증가 |
| scan_occurrence | operation_id, photo_row_id, occurrence_key, row_index, raw/comparison values(JSON), matched_food_id nullable, merged_item_id | unique(photo_row_id,occurrence_key); FK photo/operation/food; 미판독 상태 보존 |
| scan_merged_item | operation_id, merge_key nullable, document(JSON), stable public id | unique(operation_id,stable public id); 조건확정 merge key만 unique, 불완전행 nullable로 각각 보존 |
| scan_history_source | history_id, occurrence_id | unique(history_id), unique(occurrence_id); 기존 history 컬럼 무변으로 중복기록 차단 |
| scan_order | operation_id, order_id, request_key, request_hash, result_revision | unique(operation_id), unique(order_id); FK operation/orders; 한 그룹 한 주문 |
| order_photo | order_id, operation_photo_id, selection_index, object_key | unique(order_id,selection_index), unique(order_id,operation_photo_id); FK orders/photo; 그룹만료 후 보존 |
| order_item_source | order_item_id, occurrence_id | unique(order_item_id,occurrence_id); FK item/occurrence; 병합 출처 전체 |

operation은 공통 원장이라 메뉴판 그룹별 별도 quota 테이블을 만들지 않는다. 사진/결과 JSON은 값 DTO로 바인딩하되 자산 소유권/멱등성/주문 연결은 관계 제약으로 둔다. group의 lang/currency/환율 snapshot을 operation의 mode별 request document로 보관하는 안과 고정 컬럼의 비교는 Q0 리뷰에서 잠근다. 길이/enum/인덱스 폭을 임의 적용하지 않으며 UUID/해시 등 길이와 MySQL 실제utf8mb4 인덱스 제한은 DDL 작성 시 검증한다.

Q1 검증 계획: 격리 MySQL에 기존 migration 전체→가산 SQL, 기존 API/batch ddl-auto=validate·구 단일 스캔/주문 회귀, 최초 빈테이블 확인. 중복키/사진순서/FK/soft-delete 재사용 거절, 예약 range query EXPLAIN과 회원잠금 경합을 검증한다. out-of-order 환경에서 다른 미적용 migration 의존 금지. operation/photo/result 순환 FK는 insert 순서 문제가 없도록 occurrence→merged 방향만 두고 동일 operation 연결을 서비스 검증한다.

배포 전환: 원장 참여하는 구 경로를 먼저 전 인스턴스에 배포(신규 그룹 OFF), 참여 확인 후 그룹 ON. 활성작업 존재 중 원장을 모르는 구 코드로 롤백 금지. 새 작업 차단→기존 처리/기한회수→구 코드 호환 판단 순서. 보관/cleanup 참조 검증 전 실제 삭제 잡 활성화 금지. 이는 출시·배포 실행 승인이 아니다.
