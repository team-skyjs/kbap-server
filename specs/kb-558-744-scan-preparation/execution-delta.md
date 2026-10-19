# W0 실행 확정·복구 후속 계약

2026-10-19 KST. #409의 Q0 고정본 `abafa3783b01526dc45d403ecf11f28d761ba8cf`에 대한 후속 계약이다. 문서·설계 fixture 보완이며 API·SQL·원장 구현 또는 실행 시험 완료가 아니다. 기존 계약 전체를 다시 작성하지 않는다.

## 근거와 우선순위

spec 저장소의 다음 문서를 참조한다. 정확한 파일 hash는 `w0-evidence.json`에 기록한다.

- `bridge/codex/2026-10-16-scan-q1-technical-decisions.md`: Q1 저장 형식·공통 잠금 순서·구 경로 전환·Q5 revision/번역 경계.
- `bridge/codex/2026-10-16-scan-execution-delta-and-test-matrix.md` §2~4: 재조회 우선순위·불명확 COMMIT·혜택 writer와 D01~D15.
- `bridge/codex/2026-10-17-scan-implementation-start-handoff.md` L1~L3·W0~W5: 결과 receipt 구분·복구 직렬화·혜택 획득 시 예약 해제.

이 파일은 Q0의 취소/만료 오류표와 결과 보존 사이의 적용 순서를 명확히 한다. Q1의 UUIDv7·고정 컬럼·별도 처리/결과 기한·control row·예약 epoch 설계는 해당 기술 결정 문서를 따른다. 초기 한도는 운영 승인·실측 통과값으로 승격하지 않는다. 홈·정렬·리뷰봇 변경을 포함하지 않는다.

## 재조회와 새 실행 분기

모든 분기에서 먼저 인증·소유권과 요청 identity/hash를 검증한다. 다른 요청 내용을 기존 성공으로 반환하지 않는다. 결과 보관이 끝났으면 410이며 새 인식을 시작하지 않는다.

| 저장 상태 / 요청 | 반환 또는 수행 | 외부 호출·사용 |
|---|---|---|
| 같은 unit·attempt·대상 manifest revision의 보관 중인 성공 재전송 | 저장 결과 200. 이후 취소나 처리기한 만료는 성공 재조회를 막지 않음 | 추가 호출 0, 추가 사용 0 |
| 실패한 현재 attempt n 재전송 | 저장된 실패 재응답. n+1 암묵 생성 금지 | 추가 호출 0, 추가 사용 0 |
| 현재보다 과거 번호 또는 건너뛴 번호 | 기존 409 규칙. 동일 성공 재전송과 구별 | 추가 호출 0 |
| 같은 진행 중 attempt 요청 | 현재 실행 202와 조회 위치 | 추가 호출 0 |
| 실패 후 명시적 다음 attempt n+1 | lifecycle·처리기한·주문 여부·claim/lease·capacity·필요한 예약 검사 후 새 claim | 동시 두 요청 중 하나만 실행 |
| 성공한 unit에 새로운 attempt 요청 | 재인식 거절 | 추가 호출 0 |
| COMMIT 결과 불명확 | 아래 writer 직렬화 복구를 수행. 확인 불가하면 미확정 유지 | 성급한 실패 확정·예약 해제·재호출 금지 |

새 계약의 미확정 응답은 구성 가능한 경우 202와 같은 operation/조회 경로를 제공한다. DB 장애로 안전한 상태 응답을 만들 수 없으면 재시도 가능한 오류와 동일 키 유지 지침을 사용한다. 구 1.x의 안정 키 부재·기존 v2 duplicate wire는 그대로 보존하며 새 응답 약속을 소급하지 않는다.

취소는 미완료 실행의 결과 확정 권한을 없앤다. 이미 확정한 성공과 사용은 유지하며 응답에 해당 revision·usageRecorded를 포함한다. 화면 이탈·HTTP abort·취소 버튼을 누른 시각을 DB 취소 확정으로 간주하지 않는다. 생성 응답 유실도 동일 생성 키로 복구한다.

## L1 — 현재 실행 결과와 작업 사용을 각각 확인

복구 receipt의 논리 식별자는 `(operation, 실행 unit, attempt, 대상 manifest revision)`이다. 메뉴판은 사진 결과의 committed attempt를, 성분표는 해당 revision/번역의 확정 결과를 확인한다. receipt는 기존 결과의 식별성을 뜻하며 새 테이블이나 컬럼 추가를 이 문서로 요구하지 않는다.

- 사진 A 성공으로 usage=1인 상태에서 B 확정 TX가 롤백되면 A만 성공이다. B 결과가 없으면 B 성공을 만들어 반환하지 않는다. 성분표 r 성공 뒤 r+1 실패도 같은 원칙이다.
- 최초 성공 결과가 있는데 usage가 없으면 정합성 오류다. 성공 응답이나 조회 중 카운터 보충으로 숨기지 않는다.
- `recordUsageOnce`와 `commitValidatedResult`의 책임을 분리한다. usage가 이미 있어도 유효한 후속 결과는 저장한다. 추가 차감이 없다는 이유로 claim·lease·lifecycle·revision 검사를 생략하지 않는다.
- 메뉴판 사진의 유효 메뉴 결과와 성분표의 현재 revision aggregate validator를 혼용하지 않는다. 성분표 preview만으로 최초 사용을 확정하지 않는다.

## L2 — 불명확 COMMIT은 writer에서 직렬화한 뒤 복구

실패한 TX/rollback-only TX 밖에서 새 writer TX를 연다. Q1의 공통 잠금 순서 `control → member → operation → photo(오름차순) → attempt/관련 결과`로 이전 확정/취소/회수와 직렬화하고, 잠금 취득 후 current read로 L1의 결과와 사용 증거를 다시 확인한다. 기본 REPEATABLE READ를 변경하지 않는다.

일반 SELECT의 결과 부재나 reader의 부재는 이전 COMMIT의 롤백 증거가 아니다. 이전 TX가 진행 중이면 잠금 취득까지 기다리며, lock timeout/DB 장애 시 미확정을 유지한다. 잠금을 얻고 해당 결과가 없어도 현재 claim을 종료/fence하기 전에는 새 공급자 호출을 허용하지 않는다. 이후 재실행도 명시 attempt·lease·예약 조건을 통과해야 한다.

unique 충돌의 패자도 rollback-only TX를 벗어난 뒤 같은 방식으로 승자 상태를 읽는다. 이미 커밋된 결과의 HTTP ACK 유실과 DB COMMIT 자체의 불명확성을 별도 시험한다. 공급자 raw 응답을 영속 성공으로 먼저 반환하지 않는다. 보장 대상은 DB 사용 최대 1이며 공급자 실행 exactly-once는 아니다.

## L3 — 무제한 혜택 획득은 정상 claim을 취소하지 않음

성공 확정은 먼저 현재 claim·lease·lifecycle·대상 revision을 검증한다. 최초 사용 전이면 같은 member 잠금 아래에서 유효한 무료 예약 또는 현재 무제한 혜택을 검증한다. 무료 예약 경로에서는 현재 예약 epoch도 검사한다.

리뷰/광고로 무제한을 얻어 예약 점유를 해제하는 전이는 만료/취소로 인한 fencing과 구분한다. 혜택 획득에 따른 해제만으로 정상 claim을 무효화하지 않는다. 반대로 현재 무제한이라는 이유로 이미 취소·만료·fence된 claim을 살리지 않는다. 작업에 이미 usage=1이면 후속 결과에 새 무료 예약을 요구하지 않지만 실행 유효성 검사는 유지한다.

관리자의 무제한 혜택 취소는 반대 방향의 별도 제품 정책이다. 이를 현재 무제한이 아니므로 자동 실패라는 구현으로 확정하지 않는다.

## 상태 버전과 시험 배정

기존 operation version을 외부 `stateVersion`으로 사용하고, 자식 attempt를 포함한 모든 외부 가시 변경에서 증가시킨다. 상태·usage·version을 같은 snapshot으로 반환한다. resultRevision은 결과 변경 번호이므로 취소/진행 응답 순서를 대신하지 않는다. FE는 같은 operation의 오래된 stateVersion을 버리며 회원 전체 잔량은 별도로 갱신한다.

`w0-fixtures.json`은 D03·D07·D10·D11의 추가 assertion이다. 기존 24개 fixture와 D01~D15를 대체하지 않는다. 모든 시나리오는 executed=false이며 runtime 합격 판정은 없다. 후속 W2/W3 시험에서는 독립 DB 연결과 barrier/commit 관측으로 순서를 고정하고 결과 receipt·usage 수·scanCount·예약·provider 호출을 각각 기록한다.

## 남은 결정과 다음 단계

| 항목 | 담당 / 막는 범위 |
|---|---|
| D11: 무제한 admission 뒤 관리자 reset이 먼저 확정된 실행의 권리 | 예진/제품, 커맨드센터가 결정 연결. 두 순서 기대값은 미정이며 해당 경합이 가능한 원장 전환을 막음. W1·독립 코어 준비는 가능 |
| 음식 후보/성분표의 새 리뷰 작성 자격·음식별 이력 부여 | 예진/제품. 별도 결정 전 새 권한을 부여하지 않음. 공통 usage 구현은 분리 가능 |
| 성분표 장수·총량·추가 면 원가, TTL/실행 한도 운영값 | 서버 기술 검증 후 커맨드센터를 통해 운영 적용 판단. 해당 모드 활성화 조건 |
| 인식 기권/오인식·유효 partial 품질 | 모드 구현/품질 담당. DB 정합성 검증으로 품질 통과를 대신하지 않음 |

후속 W1은 api/Flyway만 포함하는 가산 SQL 단위다. W2 common 매핑의 병합·배포는 해당 SQL의 dev/prod 실제 적용·기동 확인 뒤 진행한다. 기능 PR에는 동일 버전·내용의 SQL을 포함한다. 이 W0에서는 SQL 생성·PR 생성·DB 변경·원장 전환을 수행하지 않는다. W1/W2 산출물과 운영 게이트는 별도로 추적한다.
