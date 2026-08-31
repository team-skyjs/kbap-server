# Contract: 원격 잡 실행 스크립트 `iac/scripts/batch-job.sh`

젠킨스·운영자가 호출하는 유일한 진입점. 내부적으로 `aws ecs execute-command` 로 배치 컨테이너 안에서 `curl localhost:8080` 을 실행한다.

## 사용법

```
iac/scripts/batch-job.sh run    <env> <jobName>      [aws-profile]
iac/scripts/batch-job.sh status <env> <executionId>  [aws-profile]
```

- `env`: `dev` | `prod` → 클러스터 `kbap-<env>-ecs-cluster`, 서비스 `kbap-<env>-ecs-batch`, 컨테이너 `batch`
- `aws-profile`: 생략 시 `$AWS_PROFILE`, 그것도 없으면 `kbap-<env>-batch-operator`

## 전제 (미충족 시 즉시 비0 종료 + 안내 메시지)

- AWS CLI v2, **Session Manager plugin**(`session-manager-plugin` 실행 파일) 설치
- 해당 env 의 운영 자격증명 프로필
- 배치 태스크가 RUNNING 이고 `ExecuteCommandAgent` 가 RUNNING (아니면 "배치 미기동 또는 Exec 미적용 — 재배포 필요" 안내)

## 출력·종료 코드

| 상황 | stdout | exit |
|---|---|---|
| `run` 접수 | 배치 응답 JSON(`jobName·executionId·status·exitCode·message`) 원문 | 0 (HTTP 202) |
| `run` — 알 수 없는 잡 | 배치 응답 원문(`status: NOT_FOUND`, `message` 에 실행 가능 잡 목록) | 1 (HTTP 404) |
| `run` — 이미 실행 중 | 배치 응답 원문(`status: ALREADY_RUNNING`, 진행 중 `executionId`) | 2 (HTTP 409) |
| `status` 조회 | 배치 응답 원문(`status: STARTED/COMPLETED/FAILED …`) | 0 (HTTP 200) |
| `status` — 없는 실행 | 배치 응답 원문(`status: NOT_FOUND`) | 1 (HTTP 404) |
| 권한 없음(교차 환경·타 컨테이너) | AWS `AccessDeniedException` 원문 | ≥ 100 (aws cli 종료 코드 전달) |
| 태스크 없음 / Exec 미적용 | 안내 메시지 | 3 |

- 배치 응답 본문은 **가공 없이 그대로** 전달한다(FR-007). 스크립트는 HTTP 상태코드만 종료 코드로 번역한다.
- `run` 은 잡 완료를 기다리지 않는다(202 접수 즉시 반환). 완료 확인은 `status` 를 폴링한다 — 젠킨스 파이프라인 예: `run` → executionId 파싱 → `status` 를 30초 간격으로 COMPLETED/FAILED 까지.

## 내부 동작 (참고)

```bash
TASK=$(aws ecs list-tasks --cluster $CLUSTER --service-name $SERVICE --desired-status RUNNING --query 'taskArns[0]' --output text)
aws ecs execute-command --cluster $CLUSTER --task $TASK --container batch --interactive \
  --command "curl -s -w '\n%{http_code}' -X POST 'http://localhost:8080/internal/batch/jobs?jobName=$JOB'"
```

`--interactive` 는 execute-command 의 필수 플래그(비대화 명령이어도). 출력 마지막 줄의 상태코드를 떼어 종료 코드로 쓴다.

## 원격 경로에서의 배치 트리거 계약 (무변경 확인)

배치 앱 `BatchJobTriggerController` 의 응답을 그대로 노출한다 — 202 접수 / 404 잡 없음 / 409 중복 실행 / 200 조회 / 404 실행 없음. 인증은 이 경로에 추가하지 않는다: 접근 통제는 IAM(운영 사용자 정책)이 담당하고, 컨테이너 밖에서 8080 으로 직접 도달하는 경로는 여전히 없다.
