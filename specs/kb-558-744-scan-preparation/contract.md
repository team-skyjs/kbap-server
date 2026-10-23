# KB-558/744 공통 실행·차감 Q0 준비

> 2026-10-19 후속: [W0 실행 확정·복구 계약](execution-delta.md)이 재조회 순서·L1~L3·미결정 게이트를 보완한다. Q1의 상세 저장/전환 결정은 해당 문서의 근거 목록을 따른다. 원안의 미정 항목을 다시 결정하지 않는다.
2026-10-16 KST. 기술 설계 초안/독립 검토 대상이며 구현된 API가 아니다. 기준 develop `173426ec3b86c1d5003ddb958b7c9c8315bae8a9`. 코드·SQL·DB 변경, 모델 호출, 기능 테스트 없음. 큐레이션 #408/정렬 #407/prod #404·B와 독립이다.

## 확정 범위와 근거

정본은 spec repo `bridge/codex/2026-10-16-pr407-and-scan-implementation-handoff.md` 및 그 §5의 확정 기획/공통 보완/여러 장 계약이다. 파일 SHA는 evidence.json. 세 모드 사용자 선택, 기본 메뉴판, 무료·리뷰·광고 해제 공유, 메뉴판 1~10장·성공1/실패0, 조건부 병합, 한 주문·모든 원본 사진은 이미 확정이다. KB-558의 오래된 본문을 제품 미결로 취급하지 않는다. 성분표에 메뉴판10장 제한을 복사하지 않는다.

소스 확인: ScanFacade의 구 경로는 reserve를 거치지 않고 scan 후 increaseScanCount, v2는 RedisScanReservationStore 예약 후 동일 증가다. ScanService.recordHistory와 회원 증가가 같은 결과 확정 트랜잭션이 아니다. 새 공통 원장은 두 경로를 함께 이관해야 한다. 이는 소스 기반 설계 결손이며 운영 초과 차감을 재현했다는 보고가 아니다. OrderService/Order.imagePath는 단일 사진 unique, OrderCreateRequest는 1~50항목과 수량1~999를 사용한다.

## 경로와 버전 제안

모드별 입력/성공 validator를 분리하고 공통 내부 operation 원장을 공유한다. 메뉴판은 아래 scan-groups, 음식 사진/성분표는 각 전용 리소스(후속 Q4/Q5 DTO 검토)로 분리한다. `/api/scans`에 mode를 추가해 구 DTO를 바꾸지 않는다. 새 전용 경로는 기존 헤더 체계의 1.0+를 기본안으로 하되 실제 ApiVersion 설정과 Swagger 검증 후 등록한다. 3.0은 대체 표시, #407의3.1은 정렬 계약이므로 새 번호를 임의 점유하지 않는다.

모든 요청 회원 인증·소유권 검증, 모든 응답 BaseResponse. Idempotency-Key는 생성/주문에서 UUID 문자열로 제안한다. 본인 완료 MENU_SCAN 객체만 manifest에 포함하고 외부 URL·타인 키·중복 photoId/objectKey는 거절한다. mode/ordered photos/object identity/lang/currency로 서버가 canonical hash를 계산한다. 회원+키 unique이며 같은 키의 다른 내용은409. 키는 성공 결과 만료 후에도 tombstone을 남겨 재실행하지 않고410을 반환한다. tombstone 삭제 시점은 보관 설계 전까지 미정이며 무기한 저장을 확정하지 않는다.

| 제안 경로 | 요청 | 정상 | 실패 |
|---|---|---|---|
| POST /api/scan-groups | Idempotency-Key; lang,currency,photos[{photoId,imagePath}] | 최초201, 동일재전송200; groupId·operationId·lifecycle·quota | 입력400, 소유404/SCAN-001 의미 대조, 내용충돌409, 잔량403 |
| PUT /api/scan-groups/{id}/photos/{photoId}/attempts/{n} | 같은 photo immutable; 첫1, 실패후다음번호 | 저장결과200, 실행중202+조회위치 | 과거/건너뛴시도409, 취소409, 만료410, 잔량403 |
| GET /api/scan-groups/{id} | lang(표시만) | 상태·사진·resultRevision·병합결과·quota 200 | 타인/없음404, 결과보관만료410; 외부 호출/차감0 |
| POST /api/scan-groups/{id}/cancel | 추가 본문 없음 | 멱등200; usageRecorded 유지 여부 명시 | 타인/없음404 |
| POST /api/scan-groups/{id}/order | Idempotency-Key; resultRevision,items[{mergedItemId,quantity}], 기존 장소 입력 | 최초201/동일재전송200 | 실행미정리409, revision409, 다른주문409, 50초과400 |

실패 HTTP는 BaseResponse.fail(code,message,payload). payload에 operationId/currentRevision/retryable/reason을 의미에 맞게 제공하고 내부 claimToken/스토리지 접근정보는 숨긴다. SCAN-001~008은 현재 사용 중이며 quota403은 SCAN-004, provider503은 SCAN-006/008의 기존 의미와 대조해 재사용한다. 새 멱등/만료/revision/상태 오류의 숫자는 아직 예약하지 않았다. 구현 전 최신 develop와 열린 PR 전체 오류 패치를 재조회해야 하며 문서 예시의 DRAFT_*는 실제 코드가 아니다.

## 원장과 상태 전이

operation은 mode·manifestHash 불변, lifecycle ACTIVE/CANCELLED/EXPIRED, reservation NONE/HELD/RELEASED/COMMITTED, usageRecordedAt nullable을 가진다. 사진 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED, attemptNo와 leaseToken/leaseUntil은 서버 전용이다. 생명주기와 사진 진척을 섞지 않는다.

1. 생성 시 회원→operation→사진 ID 순서의 짧은 DB 잠금으로 활성 예약과 기존 scanCount/scanUnlocked를 검사한다. 무료 회원은 `max(0, 무료한도-scanCount-유효예약수)`가 실제 실행 가능 잔량이다. 해제 회원은 제한 없이 성공 통계만 작업당1 기록하며 무료 예약을 제한량으로 잡지 않는다.
2. 시작 시 claim을 영속 저장하고 commit 후 외부 호출한다. 사진 실행은 앱 요청 기반이며 서버 메모리에서 10장 자동 실행하는 보장을 하지 않는다. 외부 호출 중 트랜잭션/회원 잠금을 유지하지 않는다.
3. 유효 claim·lifecycle·lease를 재검증하고 결과/occurrence/이력/원장COMMITTED/회원scanCount 증가를 한 트랜잭션으로 확정한다. 최초 성공은 operation unique로 한 번만 증가. scanCount는 현 랭킹 통계이기도 하므로 해제 회원도 작업당1이며 장별 증가 금지.
4. 메뉴가 하나라도 읽혀 조회 가능한 결과가 저장되면 성공이다. 카탈로그 미매칭도 메뉴 추출 성공이며 UNKNOWN을 보존한다. 빈 결과는 새 그룹의 성공이 아니다. 구1.x의 빈결과200 wire는 보존하되 공통 경로의 사용0 정책을 명시하고 회귀 검증한다.
5. 모든 사진이 FAILED/CANCELLED이고 PENDING/RUNNING이 없을 때 미확정 예약 해제. lease 만료도 회원 잠금 아래 해당 claim을 무효화하고 집계한다. 아직 PENDING이 있으면 전체실패로 조기 해제하지 않으며 operation 기한이 최종 회수한다.
6. 해제 후 실패사진 재시도는 같은 객체·기한 안에서 잔량을 재확보한다. 이미COMMITTED면 추가차감0. 성공사진 재인식은 허용하지 않는다. 키/사진 교체는 무료재시도가 아니다.
7. 취소가 먼저 확정되면 claim 무효화·예약해제·늦은결과폐기. 성공확정이 먼저면 결과/사용1 유지, 나머지만 중단. 응답 ACK 유실은 환불 사유가 아니며 GET/동일시도로 복구한다.
8. 공급자 호출 exactly-once는 보장하지 않는다. DB확정 전 프로세스 중단은 lease와 attempt로 회복하지만 공급자 멱등 미지원이면 재호출 비용이 가능하다. 횟수 정합성과 공급자 비용을 별도 관측한다.

구1.x는 안정 키가 없으므로 요청당 내부 UUID만 부여하며 클라이언트 재전송 멱등을 소급 보장하지 않는다. v2 ticket jti는 member와 결합해 operation을 식별하되 기존 DUPLICATE_SCAN_REQUEST wire와 저장결과 재조회 지원을 혼동하지 않는다. 신규 계약만 저장 결과 재전송을 약속한다. 모든 구·신 인스턴스가 DB예약에 참여한 이후 새 그룹 진입을 활성화한다. Redis TTL만으로 원장을 대체하지 않는다. 리뷰/광고 해제의 회원 갱신도 같은 잠금 순서로 정합성을 검증한다.

## 병합·주문·사진

원문 이름은 NFC와 양끝 공백 정리만 적용한다. 내부 공백/괄호/크기/단위 삭제 금지. 가격은 판독된 원금액(decimal)+명시/확정 통화로 비교하며 3,000/3000만 숫자 동치; 환산가격으로 합치지 않는다. 크기/옵션은 READ/NOT_PRESENT/UNREADABLE로 구분하고 READ 값은 같은 보수적 정규화로 비교한다. 이름/가격 미판독·미표기 가격 또는 옵션 UNREADABLE은 병합하지 않는다. 둘 다 NOT_PRESENT인 크기/옵션만 동치로 본다. foodId/번역명은 병합 키가 아니다.

occurrenceId=(photoId,저장된행ID)는 불변. 첫 병합행의 mergedItemId를 유지하고 늦은 동일 occurrence는 출처만 추가한다. 표시순서는 선택순서/행순서, 카트수량은 사용자값으로 유지한다. 불일치 위험근거는 임의SAFE를 선택하지 않는다. 신규 그룹은 정확매칭 우선·유사후보 참고와 원문UNKNOWN을 분리하며 구2.0/3.0 응답 의미는 회귀 대상으로 둔다.

주문은 사진 실행이 모두 정리되고 유효 결과가 있을 때만 현재 resultRevision을 잠가 저장한다. PENDING은 취소로 정리한 후 주문할 수 있다. 그룹당 주문1개 unique이며 이후 재시도/늦은 결과로 주문 스냅샷을 바꾸지 않고 두 번째 주문을 생성하지 않는다. 이미 주문된 그룹의 신규 인식은409로 막는다. 멱등 키가 달라도 group unique로 중복 주문을 방어한다. 주문항목은 서버 mergedItemId로 이름/가격/foodId를 해석한다. 미매칭 메뉴의 기존 incomplete food 등록과 연결 가능성을 검증하고 삭제된 참조는 명시적으로 거절한다. 기존50항목 한도 유지, 자동절단 없음.

업로드 완료된 manifest 전부를 선택 순서로 order_photo에 연결한다. 인식 실패 사진도 포함한다. 주문 참조는 그룹 처리기한/결과만료와 독립 보존하며 정리 작업에서 제외한다. orders.imagePath는 첫 원본으로 구앱 호환을 유지하고 전체 사진 정본은 관계 테이블이다. 다른 그룹이 같은 원본을 재사용해 기존 unique와 충돌하면 ORDER_ALREADY_PLACED 의미로 거절한다. 서명URL 대신 객체참조를 저장한다.

## 후속 모드와 남은 기술 검증

음식 사진은 유효·공개가능 후보1~3개 저장 시 사용1, 후보0/지원밖0, 선택·거절 추가0의 validator안을 사용한다. 참고 카탈로그와 실물 재료 판단을 분리하고 score/확률 미노출. 성분표는 원문span·번역segment·coverage가 연결된 유효partial만 성공 후보이며 preview는0. 유효partial 판정은 평가 fixture 검토 후 잠그고 임의 문자수/신뢰도 임계값을 만들지 않는다. 번역만 실패하면 원문을 보존해 번역만 재시도하고 OCR·사용 추가0. 여러 면 추가 revision은 별도 Q5 계약으로 구체화하며 같은 제품 추가 촬영 요구를 삭제하지 않는다.

파일당 현재10MiB JPEG/PNG/WebP는 코드 설정 근거일 뿐 100MiB 묶음 허용을 뜻하지 않는다. 전체 bytes/pixels, 실행동시성, lease/시도/결과보관 기한은 격리 성능·복구 측정 뒤 기술 설정으로 고정한다. 지금 유료 모델/실제 사진/운영 변경 없이 Q0 문서·fixture 검토 가능. Q1 SQL 생성·검증과 Q2 common 매핑은 별도 단계다.
