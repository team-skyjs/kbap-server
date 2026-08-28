# Research: Alloy DAEMON → 홈 Prometheus remote_write (KB-381)

## R-1. 수집기 배치 — ECS DAEMON 서비스, host 네트워크

- **Decision**: `aws_ecs_service` `scheduling_strategy = "DAEMON"`, 태스크 정의 `network_mode = "host"`, 배치 제약 없음(api·batch 인스턴스 풀 모두). terraform 이 태스크 정의·서비스를 **완전히 소유**한다(`ignore_changes` 없음 — CI 가 건드리지 않는 서비스).
- **Rationale**: DAEMON 은 클러스터의 모든 컨테이너 인스턴스에 정확히 1개를 유지하고 인스턴스 합류 시 자동 배치한다(FR-001). host 네트워크면 (a) 컨테이너 hostname = EC2 호스트명이라 `constants.hostname` 으로 `host` 라벨이 공짜, (b) docker0 브리지 IP(172.17.x.x)의 앱 컨테이너에 직접 도달, (c) unix exporter 가 호스트 네트워크 인터페이스 지표를 그대로 본다.
- **Alternatives**: bridge 모드 + IMDS 로 인스턴스 id — IMDSv2 hop limit(`http_tokens=required`, hop 1)을 2 로 올려야 해서 기각. 사이드카 — bridge 모드에서 태스크 내 localhost 공유 없음·태스크 수만큼 중복, 기각.

## R-2. 컨테이너 발견 — `discovery.docker` + docker.sock 바인드

- **Decision**: 태스크 정의 `volumes { host { source_path = "/var/run/docker.sock" } }` → `/var/run/docker.sock` 읽기 마운트. `discovery.docker "ecs" { host = "unix:///var/run/docker.sock" }`.
- **Rationale**: ECS 에이전트가 모든 컨테이너에 도커 라벨 `com.amazonaws.ecs.container-name`·`task-arn`·`task-definition-family`·`task-definition-version`·`cluster` 를 붙인다. docker discovery 가 이를 `__meta_docker_container_label_com_amazonaws_ecs_*` 로 노출하고, 타깃 `__address__` 는 bridge 컨테이너의 `<컨테이너 IP>:<컨테이너 포트>` 다(노출 포트마다 타깃 1개). 컨테이너 교체·추가는 다음 refresh(기본 60s → 15s 로 단축)에 반영(FR-002).
- **Alternatives**: ECS task metadata endpoint — 자기 태스크만 보임. `discovery.ec2` — 호스트만 보이고 동적 포트 모름. 기각.

## R-3. relabel 규칙 (계약은 contracts/alloy-config.md)

- **Decision**:
  1. keep: `__meta_docker_container_label_com_amazonaws_ecs_container_name` =~ `api|batch` **and** `__meta_docker_port_private` == `8080`
  2. `__metrics_path__` = `/actuator/prometheus`
  3. `instance` = `<env>-<container-name>-<task-arn 끝 6자>` — source `[container_name, task_arn]`, separator `;`, regex `(.+);.*/([0-9a-f]{6})$` → replacement `${env}-$1-$2`(env 는 templatefile 로 치환)
  4. `version` = `__meta_docker_container_label_com_amazonaws_ecs_task_definition_version`
  5. `application` 은 앱이 붙인 것을 유지(`honor_labels` 불필요 — 충돌 없음). `service` 안 만듦.
- **Rationale**: spec FR-004. `instance` 를 태스크 단위로 두는 이유는 카나리 창에 한 호스트에 태스크 2개가 공존하기 때문. `version` 은 CI 가 배포마다 리비전을 만들므로 앱 변경 0 으로 배포 버전을 얻는다.

## R-4. remote_write 인증·전송

- **Decision**: `prometheus.remote_write "home" { endpoint { url = "<tunnel>/api/v1/write" headers = { "CF-Access-Client-Id" = sys.env("CF_ACCESS_CLIENT_ID"), "CF-Access-Client-Secret" = sys.env("CF_ACCESS_CLIENT_SECRET") } } external_labels = { env = "<env>", host = constants.hostname } }`. 토큰은 SSM SecureString `/kbap/<env>/CF_ACCESS_CLIENT_ID`·`/kbap/<env>/CF_ACCESS_CLIENT_SECRET` → ECS `secrets` 로 env 주입(기존 `secrets.tf` 규칙; 실행 롤 정책은 `${ssm_prefix}/*` 라 추가 IAM 없음).
- **Rationale**: Cloudflare Access 는 서비스 토큰 헤더를 엣지에서 검증 → 토큰 없는 요청은 홈 Prometheus 에 닿기 전에 거부(FR-006). 헤더 방식이라 Alloy 설정 한 줄. WAL 은 Alloy 기본(최소 5m·최대 8h 보관, 약 2h 재전송 여유) — FR-003 의 2시간 충족.
- **주의**: Access 가 거부하면 **403(4xx)** → remote_write 는 4xx 를 재시도하지 않고 버린다. 토큰 오설정은 "백로그" 가 아니라 "유실" 이다 — Alloy 로그의 `non-recoverable error` 로 즉시 드러남. 홈 Prometheus 다운(연결 실패·5xx)만 WAL 재전송 대상.
- **Alternatives**: basic auth 리버스 프록시 — Access 로 충분, 기각. mTLS — 과함.

## R-5. 호스트 메트릭 — `prometheus.exporter.unix`

- **Decision**: `/proc`·`/sys`·`/` 를 각각 `/host/proc`·`/host/sys`·`/host/root` 로 **읽기전용** 바인드(ECS `volumes` host path + `mountPoints readOnly=true`). `prometheus.exporter.unix "host" { procfs_path = "/host/proc" sysfs_path = "/host/sys" rootfs_path = "/host/root" }` + `filesystem` 컬렉터의 `mount_points_exclude` 로 컨테이너 오버레이 제외. 타깃의 `instance` 는 Alloy 가 `constants.hostname` 으로 채움.
- **Rationale**: KB-383 에서 이관(CW Agent 미설치). 호스트마다 뜨는 Alloy 에 블록 하나.

## R-6. 설정 전달 — terraform `templatefile` → 컨테이너 env → 파일

- **Decision**: `iac/terraform/modules/ecs-environment/alloy.config.alloy.tftpl` 을 `templatefile()` 로 렌더해 컨테이너 env `ALLOY_CONFIG` 에 넣고, 컨테이너 `command` 를 `["sh","-c","printf '%s' \"$ALLOY_CONFIG\" > /etc/alloy/config.alloy && exec alloy run --server.http.listen-addr=127.0.0.1:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config.alloy"]` 로 둔다. 커스텀 이미지·SSM 파라미터 없음. 설정 변경 = terraform apply(태스크 정의 새 리비전 → DAEMON 롤링).
- **Rationale**: 설정 본문은 비밀이 아니고(토큰은 `sys.env` 참조), terraform 이 이미 env/템플릿을 가진다. spec FR-008 "앱 재배포 없이 수집기만 재기동" 충족. Jira 에 적었던 "SSM Parameter 주입" 보다 한 단계 적다.
- **주의**: env 값에 줄바꿈 포함 — ECS 는 허용. `printf '%s'` 로 이스케이프 문제 회피. Alloy UI 포트 12345 는 localhost 바인드(host 네트워크라 외부 노출 방지).

## R-7. 리소스·배치 여유

- **Decision**: `cpu = 128`, `memoryReservation = 128`, `memory = 384`(hard). 이미지 `grafana/alloy` 태그는 변수 `alloy_image` (기본값은 구현 시점 최신 v1.x 로 고정 — `latest` 금지).
- **Rationale**: README 기준 t3.medium 가용 ≈ 3.6 GiB, api 1536 × 2(카나리 공존) = 3072 → 여유 ~500 MiB. Alloy 예약 128 이면 카나리가 계속 들어간다. hard 384 는 unix exporter + WAL 스파이크 대비. README 의 "태스크 메모리 올리면 카나리 멈춤" 주의에 Alloy 예약분을 덧붙인다.

## R-8. 홈서버 측 변경

- **Decision**: (1) Prometheus 컨테이너 args 에 `--web.enable-remote-write-receiver` 추가, `/prometheus` 볼륨 영속 확인. (2) Cloudflare Tunnel 공개 호스트 `prom-write.<도메인>` → `http://prometheus:9090`(도커 네트워크 내부 이름). (3) Cloudflare Access 셀프호스트 앱(경로 `/api/v1/write`)에 **Service Auth** 정책 + 서비스 토큰 2쌍(dev·prod). Prometheus UI 경로는 공개 호스트에 안 넣는다.
- **Rationale**: spec Assumptions. 환경별 토큰 분리는 한쪽 폐기가 다른 쪽에 영향 없게(Key Entities).

## R-9. 검증 전략 — 자동화 테스트 없음

- **Decision**: KB-380 과 같은 결정(사용자). 검증은 quickstart 의 dev 시나리오 5개(조회·배포·호스트 교체·단절·토큰) 실행 결과.
- **Rationale**: 전부 인프라·설정. 코드 단위 테스트 대상이 없다. Alloy 설정 문법만 `alloy fmt`/`alloy validate`(로컬 도커로) 로 사전 점검.
