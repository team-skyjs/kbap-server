# Data Model: Alloy DAEMON → 홈 Prometheus (KB-381)

영속 데이터 변경 없음(엔티티·Flyway 무관). 런타임/설정 모델만.

## 라벨 스키마 (홈 Prometheus 에 저장되는 시계열의 식별자)

| 라벨 | 값 | 출처 | 앱 메트릭 | 호스트 메트릭 |
|---|---|---|---|---|
| `env` | `dev` \| `prod` | remote_write `external_labels`(templatefile) | ✓ | ✓ |
| `host` | EC2 호스트명(`ip-10-0-1-23`) | `constants.hostname`(host 네트워크) | ✓ | ✓ |
| `application` | `kbap-api` \| `kbap-batch` | 앱(Micrometer 공통 태그, KB-380) | ✓ | — |
| `instance` | `<env>-<container>-<task id 6자>` | relabel(도커 라벨 task-arn) | ✓ | 호스트명(exporter 기본) |
| `version` | ECS 태스크 정의 리비전(`42`) | relabel(도커 라벨 task-definition-version) | ✓ | — |
| `job` | `ecs-apps` \| `host` | scrape 컴포넌트 이름 | ✓ | ✓ |

카디널리티: 배포 1회 = 태스크당 새 `instance`·`version` 조합 → 시계열 세트 하나 추가(태스크당 ~1.5k). 15일 보존 × 하루 수 회 배포 = 수만 시리즈 — 홈 Prometheus 에 부담 없음.

## 설정·시크릿

| 이름 | 위치 | 환경별 | 비밀 |
|---|---|---|---|
| Alloy 설정 본문 | terraform `alloy.config.alloy.tftpl` → 컨테이너 env `ALLOY_CONFIG` | env·URL 치환 | 아니오 |
| `CF_ACCESS_CLIENT_ID` / `CF_ACCESS_CLIENT_SECRET` | SSM SecureString `/kbap/<env>/…` → ECS secrets | dev·prod 각 1쌍 | 예 |
| `home_prometheus_remote_write_url` | terraform 변수(tfvars) | 공통(호스트 하나) | 아니오 |
| `alloy_image` | terraform 변수 | 공통 | 아니오 |

## 상태 전이 (수집기 관점)

```
인스턴스 합류 ─▶ DAEMON 태스크 배치 ─▶ 컨테이너 발견(15s) ─▶ scrape(15s) ─▶ WAL ─▶ remote_write
                                                                     │            └─ 홈 단절: WAL 보관(≤ ~2h) → 복구 시 재전송
                                                                     │            └─ 4xx(토큰 거부): 즉시 폐기 + 로그
                                                                     └─ 컨테이너 교체: 다음 발견 주기에 타깃 교체, 옛 시계열 5분 후 stale
```
