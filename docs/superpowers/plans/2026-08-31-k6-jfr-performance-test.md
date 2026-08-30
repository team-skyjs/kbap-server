# k6·JFR 성능 테스트 체계 구현 계획

> **에이전트 작업자 필수 스킬:** superpowers:subagent-driven-development 또는 superpowers:executing-plans로 항목별 실행한다. 각 단계는 체크박스로 추적한다.

**목표:** dev API의 사용자용 엔드포인트를 하나의 로컬 HTML 대시보드에서 선택·실행하고, 실행마다 k6 HTML·JSON과 두 API 태스크의 JFR을 수집·다운로드해 병목을 재현 가능하게 판정하는 체계를 만든다.

**아키텍처:** 기존 `dev` Spring profile은 그대로 두고, 현재 dev task definition을 복제한 profiling revision에 관측·최소 logging 환경변수와 JDK profiling image만 덮어쓴다. dev 전용 ECS Exec·비공개 S3를 사용해 두 태스크의 JFR을 동적으로 제어한다. localhost Python 제어 서버가 단일 k6 엔트리포인트와 campaign runner를 안전하게 호출하고, 정적 HTML/CSS/JavaScript 대시보드가 endpoint 선택, 진행 조회, 결과 비교, JFR 다운로드를 제공한다.

**기술 스택:** Kotlin 2.3.21, Java 21, Spring Boot 4.1.0, Micrometer Prometheus, Terraform 1.7+, AWS ECS EC2·S3·SSM, AWS CLI 2.36.31, k6 2.2.0, Bash, Python 3 표준 라이브러리, 의존성 없는 HTML·CSS·JavaScript

**Spec:** `docs/superpowers/specs/2026-08-31-k6-jfr-performance-test-design.md`

## 전역 제약

- 부하 대상은 `https://dev.kbap.site`이며 prod에는 부하를 보내지 않는다.
- 인증 요청은 `memberId=35`와 기존 `k6/mint-token.py`로 생성한 access token을 사용한다.
- access token, JWT secret, Firebase credential, 외부 API key, presigned URL을 파일·로그·manifest·Git에 기록하지 않는다.
- 운영 기본 Docker target은 Temurin 21 JRE를 유지하고 profiling target만 Temurin 21 JDK를 사용한다.
- Spring profile은 기존 `dev` 하나만 사용하고 새 profile 파일을 만들지 않는다.
- 최소 logging과 관측 override는 profiling task definition revision에만 환경변수로 넣는다.
- 본 측정은 `spring.jpa.show-sql=false`, root logger `WARN`으로 실행한다.
- 본 측정은 API 태스크 두 개 모두 JFR 시작에 성공한 뒤에만 실행한다.
- JFR artifact bucket은 public access를 전부 차단하고 SSE-S3와 7일 lifecycle을 적용한다.
- 모든 k6 target은 HTTP 상태와 `BaseResponse.success`를 함께 검사한다.
- 외부 비용 target은 `per-vu-iterations`로 총 요청 수를 고정한다.
- destructive endpoint는 35번 회원에 반복 실행하지 않는다.
- 생성 fixture는 `run_id`로 식별 가능해야 하며 캠페인 종료 시 정리한다.
- HTML 제어 서버는 `127.0.0.1`에만 bind하고 한 번에 campaign 하나만 직렬 실행한다.
- 브라우저에는 access token, AWS credential, secret 환경변수, S3 URL을 전달하지 않는다.
- Kotlin 소스에는 주석을 추가하지 않는다.
- 테스트를 먼저 작성하고 예상한 이유로 실패하는 것을 확인한 뒤 구현한다.
- 문서와 커밋 메시지는 한국어로 작성한다.

---

### 작업 1: 기존 dev task definition의 profiling overlay

**파일:**

- 생성: `scripts/perf/render-profile-taskdef.sh`
- 생성: `scripts/perf/test/fixtures/api-taskdef.json`
- 생성: `scripts/perf/test/profile-taskdef-overlay-test.sh`

**인터페이스:**

```bash
scripts/perf/render-profile-taskdef.sh CURRENT_TASKDEF_JSON PROFILE_IMAGE OUTPUT_JSON
```

- Consumes: `aws ecs describe-task-definition --query taskDefinition` 결과와 profiling image URI
- Produces: 기존 dev profile·secret·health check·log 설정을 보존하고 image·관측 환경변수만 바꾼 등록 가능한 task definition JSON

- [ ] **1.1 실패하는 task definition overlay 계약 작성**

fixture에는 container `api`, 현재 image, `SPRING_PROFILES_ACTIVE=dev`, DB·Redis 환경변수, secret, health check, awslogs 설정과 ECS read-only field를 넣는다. `profile-taskdef-overlay-test.sh`는 renderer를 실행한 뒤 `jq -e`로 다음을 검사한다.

```text
api image = 입력한 profiling image
SPRING_PROFILES_ACTIVE = dev
SPRING_JPA_SHOW_SQL = false
LOGGING_LEVEL_ROOT = WARN
MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS = true
SERVER_TOMCAT_MBEANREGISTRY_ENABLED = true
기존 DB·Redis 환경변수, secrets, healthCheck, logConfiguration 불변
taskDefinitionArn·revision·status·registeredAt·registeredBy 제거
JAVA_TOOL_OPTIONS에 StartFlightRecording 없음
```

- [ ] **1.2 Red 확인**

```bash
chmod +x scripts/perf/test/profile-taskdef-overlay-test.sh
scripts/perf/test/profile-taskdef-overlay-test.sh
```

예상: `render-profile-taskdef.sh`가 없어 실패.

- [ ] **1.3 최소 renderer 구현**

script는 인자 3개와 `jq` 존재를 검사하고 다음 filter를 적용한다. AWS 호출이나 배포는 하지 않는다.

```jq
def setenv($name; $value):
  .environment = ((.environment // [])
    | map(select(.name != $name))
    + [{name: $name, value: $value}]);

.containerDefinitions |= map(
  if .name == "api" then
    .image = $image
    | setenv("SPRING_PROFILES_ACTIVE"; "dev")
    | setenv("SPRING_JPA_SHOW_SQL"; "false")
    | setenv("LOGGING_LEVEL_ROOT"; "WARN")
    | setenv("MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS"; "true")
    | setenv("SERVER_TOMCAT_MBEANREGISTRY_ENABLED"; "true")
  else . end
)
| del(.taskDefinitionArn, .revision, .status, .requiresAttributes,
      .compatibilities, .registeredAt, .registeredBy, .deregisteredAt)
```

renderer는 `SPRING_PROFILES_ACTIVE`를 `dev` 이외로 받는 옵션을 제공하지 않는다. JFR 시작 옵션도 추가하지 않는다. JFR은 작업 4가 측정 구간에만 `jcmd`로 제어한다.

- [ ] **1.4 Green 확인**

```bash
bash -n scripts/perf/render-profile-taskdef.sh scripts/perf/test/profile-taskdef-overlay-test.sh
scripts/perf/test/profile-taskdef-overlay-test.sh
git diff --check
```

- [ ] **1.5 커밋**

```bash
git add scripts/perf/render-profile-taskdef.sh scripts/perf/test/fixtures/api-taskdef.json scripts/perf/test/profile-taskdef-overlay-test.sh
git commit -m "test(load): dev profiling task 정의 overlay 추가"
```

### 작업 2: 동적 JFR profiling Docker target

**파일:**

- 수정: `Dockerfile`
- 생성: `ops/jfr/kbap-profile.jfc`
- 생성: `scripts/perf/profile-image-smoke.sh`

**인터페이스:**

- Consumes: 기존 `:api:bootJar`, `public.ecr.aws/aws-cli/aws-cli:2.36.31`
- Produces: Docker target `profile-runtime`, `/app/kbap-profile.jfc`, `java`, `jcmd`, `jfr`, `aws`, `curl`

- [ ] **2.1 JFR 설정 파일 생성과 민감 이벤트 비활성화 검사 작성**

Temurin 21 profile 설정을 기반으로 JFC를 생성한다.

```bash
mkdir -p ops/jfr
docker run --rm \
  -v "$PWD/ops/jfr:/out" \
  --entrypoint jfr \
  eclipse-temurin:21-jdk \
  configure \
  --input /opt/java/openjdk/lib/jfr/profile.jfc \
  --output /out/kbap-profile.jfc \
  jdk.InitialEnvironmentVariable#enabled=false \
  jdk.InitialSystemProperty#enabled=false
```

`scripts/perf/profile-image-smoke.sh`의 첫 검사는 다음 조건을 확인한다.

```bash
#!/usr/bin/env bash
set -euo pipefail

image="${PROFILE_IMAGE:-kbap-api-profile:local}"
docker run --rm --entrypoint sh "$image" -c '
  command -v java
  command -v jcmd
  command -v jfr
  command -v aws
  command -v curl
  test -f /app/kbap-profile.jfc
'
```

- [ ] **2.2 Red 확인**

```bash
chmod +x scripts/perf/profile-image-smoke.sh
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
```

예상: profiling 이미지가 아직 없어 Docker image lookup 실패.

- [ ] **2.3 Dockerfile에 profile-runtime stage 구현**

기존 build stage 뒤, 최종 JRE runtime stage 앞에 다음 stage를 둔다. 최종 stage 순서를 유지해 인자 없는 `docker build`가 계속 JRE runtime을 만든다.

```dockerfile
FROM public.ecr.aws/aws-cli/aws-cli:2.36.31 AS awscli

FROM eclipse-temurin:21-jdk AS profile-runtime
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
COPY --from=awscli /usr/local/aws-cli/ /usr/local/aws-cli/
RUN ln -s /usr/local/aws-cli/v2/current/bin/aws /usr/local/bin/aws
COPY ops/jfr/kbap-profile.jfc /app/kbap-profile.jfc
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **2.4 profiling 이미지 빌드와 도구 검사**

```bash
docker build --target profile-runtime -t kbap-api-profile:local .
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
```

- [ ] **2.5 실제 동적 녹화 smoke 추가**

`profile-image-smoke.sh` 끝에 다음 검사를 추가한다.

```bash
docker run --rm --entrypoint sh "$image" -c '
  jwebserver -p 18080 >/tmp/jwebserver.log 2>&1 &
  pid=$!
  sleep 1
  jcmd "$pid" JFR.start name=profile_smoke settings=/app/kbap-profile.jfc filename=/tmp/profile-smoke.jfr maxsize=32m
  sleep 2
  jcmd "$pid" JFR.stop name=profile_smoke
  jfr summary /tmp/profile-smoke.jfr | grep "Version:"
  test -s /tmp/profile-smoke.jfr
  kill "$pid"
'
```

- [ ] **2.6 Green 재확인**

```bash
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
docker build -t kbap-api-runtime:local .
docker run --rm --entrypoint sh kbap-api-runtime:local -c 'command -v java; ! command -v jcmd'
```

예상: profile target은 모든 검사 통과, 기본 runtime은 `java`만 확인되고 `jcmd`는 없음.

- [ ] **2.7 커밋**

```bash
git add Dockerfile ops/jfr/kbap-profile.jfc scripts/perf/profile-image-smoke.sh
git commit -m "build: JFR 프로파일링 이미지 타깃 추가"
```

### 작업 3: dev ECS Exec과 비공개 JFR artifact bucket

**파일:**

- 수정: `iac/terraform/variables.tf`
- 수정: `iac/terraform/main.tf`
- 수정: `iac/terraform/outputs.tf`
- 수정: `iac/terraform/dev.tfvars.example`
- 수정: `iac/terraform/prod.tfvars.example`
- 수정: `iac/terraform/modules/ecs-environment/variables.tf`
- 수정: `iac/terraform/modules/ecs-environment/api.tf`
- 수정: `iac/terraform/modules/ecs-environment/iam.tf`
- 수정: `iac/terraform/modules/ecs-environment/outputs.tf`
- 생성: `iac/terraform/modules/ecs-environment/performance-artifacts.tf`
- 생성: `scripts/perf/test/terraform-profile-contract.sh`

**인터페이스:**

```hcl
variable "api_execute_command_enabled" {
  type    = bool
  default = false
}

variable "performance_artifact_retention_days" {
  type    = number
  default = 7
}
```

- Consumes: `env`, dev API task role, ECS service
- Produces: dev의 `enable_execute_command=true`, `${local.name_prefix}-performance-artifacts`, output `performance_artifact_bucket_name`

- [ ] **3.1 실패하는 Terraform 계약 검사 작성**

`scripts/perf/test/terraform-profile-contract.sh`를 다음 내용으로 작성한다.

```bash
#!/usr/bin/env bash
set -euo pipefail

rg -q 'api_execute_command_enabled' iac/terraform/variables.tf
rg -q 'enable_execute_command[[:space:]]*=[[:space:]]*var.api_execute_command_enabled' iac/terraform/modules/ecs-environment/api.tf
rg -q 'performance_artifact_retention_days' iac/terraform/modules/ecs-environment/variables.tf
rg -q 'aws_s3_bucket.*performance_artifacts' iac/terraform/modules/ecs-environment/performance-artifacts.tf
rg -q 'ssmmessages:CreateControlChannel' iac/terraform/modules/ecs-environment/iam.tf
rg -q 'performance_artifact_bucket_name' iac/terraform/outputs.tf
```

- [ ] **3.2 Red 확인**

```bash
chmod +x scripts/perf/test/terraform-profile-contract.sh
scripts/perf/test/terraform-profile-contract.sh
```

예상: 첫 `api_execute_command_enabled` 검사에서 실패.

- [ ] **3.3 root와 module 변수 연결**

root 변수를 module 호출로 그대로 전달하고 환경 예시는 다음 값으로 둔다.

```hcl
# dev.tfvars.example
api_execute_command_enabled          = true
performance_artifact_retention_days = 7

# prod.tfvars.example
api_execute_command_enabled          = false
performance_artifact_retention_days = 7
```

`api.tf` 서비스에는 다음을 추가한다.

```hcl
enable_execute_command = var.api_execute_command_enabled
```

- [ ] **3.4 artifact bucket 구현**

`performance-artifacts.tf`는 `api_execute_command_enabled`가 true일 때만 다음 리소스를 만든다.

- `aws_s3_bucket.performance_artifacts`
- `aws_s3_bucket_public_access_block.performance_artifacts`
- `aws_s3_bucket_server_side_encryption_configuration.performance_artifacts`
- `aws_s3_bucket_lifecycle_configuration.performance_artifacts`

bucket 이름은 `${local.name_prefix}-performance-artifacts`로 고정한다. lifecycle은 전체 객체를 `performance_artifact_retention_days`일 후 만료시킨다. 암호화는 AES256을 사용한다.

- [ ] **3.5 API task role에 조건부 JFR 권한 추가**

`data.aws_iam_policy_document.api_task`에 `api_execute_command_enabled`가 true일 때만 두 statement를 추가한다.

```hcl
dynamic "statement" {
  for_each = var.api_execute_command_enabled ? [1] : []
  content {
    sid = "EcsExecChannel"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

dynamic "statement" {
  for_each = var.api_execute_command_enabled ? [1] : []
  content {
    sid       = "PutPerformanceArtifact"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.performance_artifacts[0].arn}/*"]
  }
}
```

- [ ] **3.6 output 구현과 정적 Green 확인**

module output은 비활성 환경에서 `null`, 활성 환경에서 bucket 이름을 반환한다. root output은 module 값을 전달한다.

```bash
scripts/perf/test/terraform-profile-contract.sh
terraform -chdir=iac/terraform fmt -recursive -check
terraform -chdir=iac/terraform init -backend=false
terraform -chdir=iac/terraform validate
```

- [ ] **3.7 dev plan 검증**

```bash
terraform -chdir=iac/terraform workspace select dev
terraform -chdir=iac/terraform plan -var-file=dev.tfvars
```

예상 변경은 dev artifact bucket·암호화·public block·lifecycle, API role policy, ECS service Exec 활성화다. RDS, Redis, ALB, desired count, task CPU·memory 변경이 있으면 적용하지 않고 원인을 수정한다.

- [ ] **3.8 커밋**

```bash
git add iac/terraform scripts/perf/test/terraform-profile-contract.sh
git commit -m "feat(infra): dev API JFR 수집 경로 추가"
```

### 작업 4: 두 ECS 태스크 JFR 제어·수집 스크립트

**파일:**

- 생성: `scripts/perf/lib.sh`
- 생성: `scripts/perf/jfr-start.sh`
- 생성: `scripts/perf/jfr-stop.sh`
- 생성: `scripts/perf/test/jfr-scripts-test.sh`

**인터페이스:**

```bash
scripts/perf/jfr-start.sh RUN_ID
scripts/perf/jfr-stop.sh RUN_ID REPORT_DIR
```

- Consumes: `AWS_PROFILE` 기본 `kbap-infra`, `AWS_REGION` 기본 `ap-northeast-2`, cluster `kbap-dev-ecs-cluster`, service `kbap-dev-ecs-api`
- Produces: 정확히 두 태스크의 recording과 `REPORT_DIR/task-<short-id>.jfr`

- [ ] **4.1 fake AWS CLI 기반 실패 테스트 작성**

`jfr-scripts-test.sh`는 임시 `PATH`에 fake `aws`를 두고 호출 인자를 파일에 기록한다. fake 응답은 두 task ARN과 artifact bucket `kbap-dev-ecs-performance-artifacts`를 반환한다.

검증 항목:

- run ID가 `^[a-zA-Z0-9._-]+$`를 벗어나면 시작 전에 종료 코드 2
- running task가 1개 또는 3개면 종료 코드 3
- start가 두 task 각각에 `JFR.start`를 한 번 호출
- 한 start가 실패하면 스크립트가 0이 아닌 코드로 종료
- stop이 두 task 각각에 `JFR.stop`, `jfr summary`, `aws s3 cp`를 호출
- stop이 로컬에서 두 JFR을 다운로드하고 크기 0 파일을 거부

- [ ] **4.2 Red 확인**

```bash
chmod +x scripts/perf/test/jfr-scripts-test.sh
scripts/perf/test/jfr-scripts-test.sh
```

예상: 제어 스크립트가 없어 실패.

- [ ] **4.3 공통 환경과 task 조회 구현**

`lib.sh`는 다음 상수를 기본값과 함께 제공한다.

```bash
PERF_AWS_PROFILE="${AWS_PROFILE:-kbap-infra}"
PERF_AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PERF_ECS_CLUSTER="${ECS_CLUSTER:-kbap-dev-ecs-cluster}"
PERF_ECS_SERVICE="${ECS_SERVICE:-kbap-dev-ecs-api}"
PERF_ECS_CONTAINER="${ECS_CONTAINER:-api}"
PERF_ARTIFACT_BUCKET="${PERFORMANCE_ARTIFACT_BUCKET:-kbap-dev-ecs-performance-artifacts}"
```

`running_task_ids`는 `aws ecs list-tasks` 결과를 short task ID 두 줄로 반환한다. `require_two_tasks`가 줄 수를 검사한다. `execute_in_task TASK_ID COMMAND`가 공통 `aws ecs execute-command --interactive` 호출을 소유한다. `summarize_jfr FILE`은 로컬 JDK 설치에 의존하지 않고 다음 명령으로 파일을 검증한다.

```bash
docker run --rm \
  -v "$(dirname "$1"):/artifacts:ro" \
  --entrypoint jfr \
  eclipse-temurin:21-jdk \
  summary "/artifacts/$(basename "$1")"
```

- [ ] **4.4 JFR 시작 구현**

각 task에 다음 명령을 실행한다.

```text
jcmd 1 JFR.start name=$RUN_ID settings=/app/kbap-profile.jfc filename=/tmp/$RUN_ID.jfr maxsize=256m
```

두 결과 모두 `Started recording`을 포함해야 성공이다. 한 task라도 실패하면 성공한 task에 `JFR.stop`을 best-effort로 실행하고 본 측정을 막는다.

- [ ] **4.5 JFR 중지·S3 반출·로컬 다운로드 구현**

각 task에서 순서대로 실행한다.

```text
jcmd 1 JFR.stop name=$RUN_ID
jfr summary /tmp/$RUN_ID.jfr
aws s3 cp /tmp/$RUN_ID.jfr s3://$BUCKET/$RUN_ID/task-$TASK_ID.jfr --sse AES256
rm -f /tmp/$RUN_ID.jfr
```

그 뒤 로컬 AWS CLI로 두 객체를 `REPORT_DIR`에 다운로드한다. 파일마다 `test -s`와 `summarize_jfr`를 실행한다.

- [ ] **4.6 Green 확인**

```bash
bash -n scripts/perf/lib.sh scripts/perf/jfr-start.sh scripts/perf/jfr-stop.sh
scripts/perf/test/jfr-scripts-test.sh
```

- [ ] **4.7 커밋**

```bash
git add scripts/perf/lib.sh scripts/perf/jfr-start.sh scripts/perf/jfr-stop.sh scripts/perf/test/jfr-scripts-test.sh
git commit -m "test(load): ECS JFR 수집 스크립트 추가"
```

### 작업 5: k6 공통 실행·HTML summary 하네스

**파일:**

- 생성: `k6/endpoint.js`
- 생성: `k6/lib/config.js`
- 생성: `k6/lib/client.js`
- 생성: `k6/lib/options.js`
- 생성: `k6/lib/summary.js`
- 생성: `k6/endpoints/index.js`
- 생성: `k6/endpoints/smoke.js`
- 생성: `k6/fixtures/dev.example.json`
- 생성: `k6/tests/mock-server.py`
- 생성: `k6/tests/harness-smoke.sh`
- 수정: `.gitignore`

**인터페이스:**

```javascript
export function requireConfig(env)
export function authenticatedParams(context, version, tags)
export function executeEndpoint(endpoint, context)
export function buildOptions(kind, env)
export function renderHtmlSummary(data, metadata)
export function renderJsonSummary(data, metadata)
export const endpoints
```

- [ ] **5.1 실패하는 하네스 smoke 작성**

`mock-server.py`는 `ThreadingHTTPServer`로 모든 요청에 다음 JSON을 200으로 반환한다.

```json
{"success":true,"payload":{"version":"1.0.0"},"message":null,"code":null}
```

`harness-smoke.sh`는 임시 디렉터리에서 mock server를 실행하고 다음 명령을 수행한다.

```bash
k6 inspect \
  -e TARGET=app-version \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e ACCESS_TOKEN=test-token \
  -e RUN_ID=harness-smoke \
  -e REPORT_DIR="$report_dir" \
  k6/endpoint.js

k6 run \
  -e TARGET=app-version \
  -e BASE_URL=http://127.0.0.1:18081 \
  -e ACCESS_TOKEN=test-token \
  -e RUN_ID=harness-smoke \
  -e REPORT_DIR="$report_dir" \
  -e PROFILE=smoke \
  k6/endpoint.js

test -s "$report_dir/report.html"
test -s "$report_dir/summary.json"
grep -q 'app-version' "$report_dir/report.html"
```

- [ ] **5.2 Red 확인**

```bash
chmod +x k6/tests/harness-smoke.sh
k6/tests/harness-smoke.sh
```

예상: `k6/endpoint.js`가 없어 실패.

- [ ] **5.3 config와 실행 profile 구현**

`requireConfig`는 `TARGET`, `BASE_URL`, `RUN_ID`, `REPORT_DIR`를 필수로 검사하고 access token은 endpoint가 인증을 요구할 때만 필수로 검사한다. 토큰 값은 반환 context에만 두고 console에 출력하지 않는다.

`buildOptions`는 다음 profile을 제공한다.

```javascript
const profiles = {
  smoke: {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    maxDuration: '30s',
  },
  read: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 5),
    timeUnit: '1s',
    duration: __ENV.DURATION || '3m',
    preAllocatedVUs: Number(__ENV.PRE_VUS || 20),
    maxVUs: Number(__ENV.MAX_VUS || 200),
  },
  write: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 1),
    timeUnit: '1s',
    duration: __ENV.DURATION || '2m',
    preAllocatedVUs: Number(__ENV.PRE_VUS || 10),
    maxVUs: Number(__ENV.MAX_VUS || 50),
  },
  external: {
    executor: 'per-vu-iterations',
    vus: Number(__ENV.VUS || 1),
    iterations: Number(__ENV.ITERATIONS || 1),
    maxDuration: __ENV.MAX_DURATION || '5m',
  },
};
```

반환 options에는 다음 summary 통계와 threshold를 함께 둔다.

```javascript
summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
thresholds: {
  checks: ['rate>0.99'],
  http_req_failed: ['rate<0.01'],
  dropped_iterations: ['count==0'],
  ...(kind === 'read'
    ? { http_req_duration: ['p(95)<300', 'p(99)<750'] }
    : kind === 'write'
      ? { http_req_duration: ['p(95)<500', 'p(99)<1000'] }
      : {}),
},
```

- [ ] **5.4 HTTP 실행과 검사 구현**

공통 params는 `run_id`, `target`, `route`, `method`, `phase` 태그를 붙인다. `executeEndpoint`는 endpoint의 method에 맞게 `http.request`를 호출하고 다음 두 check를 항상 실행한다.

```javascript
const ok = check(response, {
  [`${endpoint.key} status 200`]: (r) => r.status === 200,
  [`${endpoint.key} success true`]: (r) => {
    try {
      return r.json('success') === true;
    } catch (_) {
      return false;
    }
  },
});
```

실패하면 `endpoint_failures` Counter를 증가시키고 `code`를 파싱해 `business_code` 태그로 기록한다.

- [ ] **5.5 의존성 없는 HTML renderer 구현**

`renderHtmlSummary`는 target, run ID, 시작·종료 시각, checks, request count, failed rate, dropped iterations, 각 Trend의 avg·med·p90·p95·p99·max를 HTML table로 출력한다. 모든 동적 문자열은 `&`, `<`, `>`, `"`, `'`를 escape한다. 외부 CDN·JavaScript·CSS를 참조하지 않고 inline CSS만 사용한다.

`handleSummary`는 다음 파일을 반환한다.

```javascript
export function handleSummary(data) {
  const metadata = summaryMetadata(config);
  return {
    [`${config.reportDir}/report.html`]: renderHtmlSummary(data, metadata),
    [`${config.reportDir}/summary.json`]: renderJsonSummary(data, metadata),
  };
}
```

- [ ] **5.6 fixture 예시와 ignore 구현**

`dev.example.json`은 ID와 공개 fixture path만 담는다.

```json
{
  "memberId": 35,
  "foodId": 1,
  "foodKeyword": "김치",
  "blockedMemberId": 36,
  "reviewId": 1,
  "postId": 1,
  "commentId": 1,
  "orderId": 1,
  "scanImagePath": "test/images/scan/hansik-madang.jpg"
}
```

`.gitignore`에 다음을 추가한다.

```gitignore
k6/fixtures/dev.json
artifacts/performance/
*.jfr
```

- [ ] **5.7 Green 확인**

```bash
python3 -m py_compile k6/tests/mock-server.py
k6/tests/harness-smoke.sh
git diff --check
```

- [ ] **5.8 커밋**

```bash
git add .gitignore k6/endpoint.js k6/lib k6/endpoints/index.js k6/endpoints/smoke.js k6/fixtures/dev.example.json k6/tests
git commit -m "test(load): k6 공통 실행 하네스 추가"
```

### 작업 6: 읽기 엔드포인트와 쿼리 변형 카탈로그

**파일:**

- 생성: `k6/endpoints/app.js`
- 생성: `k6/endpoints/member.js`
- 생성: `k6/endpoints/food.js`
- 생성: `k6/endpoints/review.js`
- 생성: `k6/endpoints/community.js`
- 생성: `k6/endpoints/order.js`
- 생성: `k6/endpoints/targets.json`
- 생성: `k6/tests/catalog-contract.sh`
- 수정: `k6/endpoints/index.js`

**인터페이스:**

- Consumes: 작업 5의 endpoint object와 `k6/fixtures/dev.json`
- Produces: 모든 읽기 target의 key·label·method·route·suite·risk·기본 profile을 가진 manifest

- [ ] **6.1 endpoint manifest와 실패하는 catalog 계약 검사 작성**

`targets.json`은 다음 object schema를 사용한다. `key`는 k6 registry key와 같고 `label`은 대시보드 표시용 한국어 이름이다. `suite`는 `read|reversible-write|fixture-write|external`, `risk`는 `safe|fixture|cost`만 허용한다.

```json
{
  "targets": [
    {
      "key": "app-version",
      "label": "앱 버전",
      "method": "GET",
      "route": "/api/app-version",
      "suite": "read",
      "risk": "safe",
      "defaultProfile": "read",
      "defaultEnabled": true
    }
  ]
}
```

동일한 형식으로 다음 key를 모두 기록한다.

```json
[
  "app-version",
  "home-auth", "home-guest",
  "ingredients-ko", "ingredients-en", "ingredient-diets-ko", "ingredient-diets-en",
  "member-profile", "member-ranking", "member-blocks",
  "foods-auth", "foods-guest", "foods-next",
  "foods-search-all-ko-hit", "foods-search-all-ko-miss", "foods-search-all-en-hit", "foods-search-scanned", "foods-search-next",
  "foods-scanned", "foods-scanned-next", "food-detail-auth", "food-detail-guest",
  "bookmarks", "bookmarks-next",
  "reviews-guest-latest", "reviews-auth-latest", "reviews-rating-high", "reviews-rating-low", "reviews-food-count", "reviews-helpful", "reviews-next", "reviews-me", "reviews-me-next",
  "community-posts-guest", "community-posts-auth", "community-posts-next", "community-post-detail", "community-comments", "community-comments-next",
  "orders-10", "orders-30", "orders-next", "order-detail"
]
```

`catalog-contract.sh`는 `jq -e`로 필수 field와 enum을 검사한 뒤 `jq -r '.targets[].key'`로 모든 key를 읽어 각 key에 `k6 inspect`를 실행한다. key 중복, `index.js`에 없는 key, 안전하지 않은 target의 `defaultEnabled=true`는 실패해야 한다.

- [ ] **6.2 Red 확인**

```bash
chmod +x k6/tests/catalog-contract.sh
k6/tests/catalog-contract.sh
```

예상: 첫 미구현 key에서 unknown target 오류.

- [ ] **6.3 app·member·food 읽기 target 구현**

각 target은 실제 controller 계약의 method, path, query, `X-API-Version`을 사용한다. 인증 target만 access token을 넣는다. cursor target은 fixture의 `foodCursor`, `bookmarkCursor`, `scanCursor`를 사용하고 값이 없으면 config 단계에서 명확히 실패한다.

검색은 다음 축을 독립 요청으로 만든다.

- ALL/SCANNED
- ko/en
- hit/miss
- 첫 페이지/다음 페이지

- [ ] **6.4 review target 구현**

다음 query를 그대로 구분한다.

```text
sort=latest
sort=rating_high
sort=rating_low
sort=food_review_count
sort=helpful
```

인증·비인증 latest를 분리하고 cursor target은 `reviewCursor`를 사용한다. `lang=ko`를 기본으로 하고 fixture `foodId`가 있는 target은 `foodId`를 포함한다.

- [ ] **6.5 community·order 읽기 target 구현**

게스트 community는 첫 페이지만 호출한다. 다음 cursor는 인증 요청만 사용한다. 주문은 size 10과 최대값 30을 분리하고 list/detail을 별도 target으로 둔다.

- [ ] **6.6 mock payload 확장과 Green 확인**

mock server가 cursor와 목록 payload를 반환하게 확장한 뒤 다음을 실행한다.

```bash
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
```

- [ ] **6.7 커밋**

```bash
git add k6/endpoints k6/tests/catalog-contract.sh k6/tests/mock-server.py
git commit -m "test(load): 읽기 엔드포인트 부하 시나리오 추가"
```

### 작업 7: 쓰기·외부 API target과 fixture 정리

**파일:**

- 생성: `k6/endpoints/write.js`
- 생성: `k6/endpoints/external.js`
- 생성: `k6/fixtures/README.md`
- 생성: `k6/scripts/seed-fixtures.sql`
- 생성: `k6/scripts/cleanup-fixtures.sql`
- 수정: `k6/endpoints/index.js`
- 수정: `k6/endpoints/targets.json`
- 수정: `k6/tests/mock-server.py`
- 수정: `k6/tests/catalog-contract.sh`

**인터페이스:**

- Consumes: `RUN_ID`, 35번 access token, `dev.json` fixture IDs, 기존 scan seed image
- Produces: `kind=write|external`, cleanup SQL에서 사용하는 session variable `@run_id`

- [ ] **7.1 쓰기·외부 target metadata를 manifest에 먼저 추가**

다음 key를 작업 6의 object schema로 추가해 catalog test를 실패시킨다. 가역 쓰기는 `suite=reversible-write`, `risk=safe`, `defaultEnabled=true`; 생성 쓰기는 `suite=fixture-write`, `risk=fixture`, `defaultEnabled=false`; Places·scan은 `suite=external`, `risk=cost`, `defaultEnabled=false`로 둔다.

```text
member-profile-v1
member-profile-v11
member-block
member-unblock
bookmark-add
bookmark-remove
review-create
review-update
review-delete
review-like
review-unlike
report-create
community-post-create
community-post-update
community-post-delete
community-comment-create
community-comment-update
community-comment-delete
image-upload-url
image-complete
order-create-no-location
order-create-location
place-nearby
place-search
scan-ticket
scan-v1
scan-v2-krw
scan-v2-usd
```

- [ ] **7.2 Red 확인**

```bash
k6/tests/catalog-contract.sh
```

예상: `member-profile-v1` unknown target 실패.

- [ ] **7.3 가역 쓰기 target 구현**

profile v1/v1.1은 현재 fixture 값과 같은 유효 값으로 patch한다. block/bookmark/like는 add와 remove를 별도 target으로 둔다. 일반 부하는 fixture 배열을 `__VU % length`로 순환하고 동일 대상 경합은 `CONTENDED=true`일 때 배열 첫 값을 고정한다.

- [ ] **7.4 생성·삭제 target 구현**

생성 body의 텍스트 필드에 `[load:$RUN_ID]`를 넣는다. review create는 scan history가 있는 food ID를 사용한다. image complete와 order create는 중복이 불가능하므로 fixture 배열의 고유 object path를 iteration별로 사용하고 배열이 소진되면 요청하지 않고 `fixture_exhausted` Counter를 증가시킨다.

- [ ] **7.5 외부 비용 target 구현**

Places와 scan은 `kind=external`로 지정한다. scan v2는 매 iteration 새 ticket을 발급하고 ticket 실패 시 scan failure Counter를 증가시킨 뒤 반환한다. `scan-v2-krw`는 환율 호출을 피하고 `scan-v2-usd`는 환율 호출까지 포함한다. 기존 `SCAN_TIMEOUT` 기본 120초를 유지한다.

- [ ] **7.6 fixture seed·cleanup SQL 구현**

seed SQL은 35번 회원의 존재·ACTIVE·scan_unlocked를 검증하고 target 회원·food·review·post·comment ID를 조회하는 SELECT만 제공한다. 테스트 데이터 생성은 API setup target이 수행한다.

cleanup SQL은 실행자가 다음처럼 run ID를 명시해야 실행된다.

```sql
SET @run_id = '20260831T120000Z';
```

그 뒤 `content LIKE CONCAT('%[load:', @run_id, ']%')`인 community·review fixture와 그 하위 행만 삭제 또는 상태 정리한다. `@run_id IS NULL OR @run_id = ''`이면 `SIGNAL SQLSTATE '45000'`으로 중단한다. member 35, food master, 기존 review·post·comment는 삭제하지 않는다.

- [ ] **7.7 mock 서버와 전체 catalog Green 확인**

```bash
python3 -m py_compile k6/tests/mock-server.py
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
```

- [ ] **7.8 커밋**

```bash
git add k6/endpoints k6/fixtures/README.md k6/scripts k6/tests
git commit -m "test(load): 쓰기·외부 API 부하 시나리오 추가"
```

### 작업 8: JFR과 k6를 묶는 endpoint runner

**파일:**

- 생성: `scripts/perf/run-endpoint.sh`
- 생성: `scripts/perf/test/run-endpoint-test.sh`
- 수정: `k6/scan-burst.js`

**인터페이스:**

```bash
CAMPAIGN_ID=<UTC-id> JFR_ENABLED=true \
  scripts/perf/run-endpoint.sh TARGET PROFILE RATE_OR_VUS DURATION_OR_ITERATIONS
```

- Consumes: 작업 4 JFR scripts, 작업 5~7 k6 target, `ACCESS_TOKEN`, `k6/fixtures/dev.json`
- Produces: `artifacts/performance/$CAMPAIGN_ID/$TARGET`와 완전한 `manifest.json`

- [ ] **8.1 fake JFR·k6 기반 runner 실패 테스트 작성**

`run-endpoint-test.sh`는 임시 PATH의 fake `k6`, fake JFR scripts, fake `aws`를 사용해 다음을 검증한다.

- report directory가 `artifacts/performance/$CAMPAIGN_ID/$TARGET` 형태
- `CAMPAIGN_ID`가 없으면 UTC 시각으로 생성하고 있으면 모든 target에 같은 값을 유지
- warm-up은 JFR 시작 전에 실행
- JFR start 두 task 성공 뒤 본 k6 실행
- 본 k6 실패여도 trap이 JFR stop을 실행
- JFR start 실패 시 본 k6를 실행하지 않음
- manifest에 git SHA, target, task definition, image, 두 task ID, UTC 시작·종료 시각 기록
- manifest에 access token과 JWT secret이 없음

- [ ] **8.2 Red 확인**

```bash
chmod +x scripts/perf/test/run-endpoint-test.sh
scripts/perf/test/run-endpoint-test.sh
```

예상: runner가 없어 실패.

- [ ] **8.3 runner 구현**

campaign ID는 입력값 또는 `date -u +%Y%m%dT%H%M%SZ`, run ID는 campaign ID와 target을 조합한다. `JFR_ENABLED=false`는 오버헤드 비교 smoke에서만 허용하고 기본값은 true다. 순서는 다음과 같다.

1. 필수 env와 fixture 파일 검사
2. ECS steady state와 running task 2개 검사
3. target smoke 1회
4. 2분 warm-up
5. 두 task JFR start
6. 10초 대기
7. 본 k6 실행
8. 10초 대기
9. trap에서 두 task JFR stop·수집
10. manifest 완성

본 k6 종료 코드를 보존해 threshold 실패가 runner 성공으로 둔갑하지 않게 한다.

- [ ] **8.4 기존 scan script에 공통 식별 태그 추가**

기존 비용 상한과 check 로직은 유지하고 `run_id`, `target=scan-v2-krw`, `route=/api/scans`, `phase` 태그를 추가한다. `RUN_ID` 누락 시 실행을 거부한다. 기존 `run-50.json`, `run-145.json`은 수정하지 않는다.

- [ ] **8.5 Green 확인**

```bash
bash -n scripts/perf/run-endpoint.sh scripts/perf/test/run-endpoint-test.sh
scripts/perf/test/run-endpoint-test.sh
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
git diff --check
```

- [ ] **8.6 커밋**

```bash
git add scripts/perf/run-endpoint.sh scripts/perf/test/run-endpoint-test.sh k6/scan-burst.js
git commit -m "test(load): endpoint JFR 실행기 추가"
```

### 작업 9: localhost campaign 제어 API

**파일:**

- 생성: `tools/perf_dashboard/__init__.py`
- 생성: `tools/perf_dashboard/server.py`
- 생성: `tools/perf_dashboard/campaign.py`
- 생성: `tools/perf_dashboard/tests/__init__.py`
- 생성: `tools/perf_dashboard/tests/test_server.py`
- 생성: `tools/perf_dashboard/tests/test_campaign.py`
- 생성: `scripts/perf/dashboard.sh`

**인터페이스:**

```python
class RunStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    CANCELLING = "CANCELLING"
    PASSED = "PASSED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

def load_targets(path: Path) -> tuple[Target, ...]
def validate_run_request(payload: Mapping[str, object], targets: tuple[Target, ...]) -> RunRequest
def start_campaign(request: RunRequest) -> Campaign
def cancel_campaign(campaign_id: str) -> bool
def resolve_artifact(campaign_id: str, artifact_id: str) -> Path
```

- Consumes: 작업 6·7 target manifest, 작업 8 endpoint runner, process env의 `ACCESS_TOKEN`
- Produces: `127.0.0.1` 전용 정적 파일·JSON·SSE·artifact API와 campaign 상태 저장

- [ ] **9.1 실패하는 API·상태 테스트 작성**

`unittest`, `tempfile`, `urllib.request`만 사용해 실제 임시 HTTP server를 띄운다. fake endpoint runner는 argv와 env key 이름만 기록하고 target별 HTML·JSON·manifest·JFR 두 파일을 생성한다. 테스트는 다음을 먼저 고정한다.

- `/api/targets`가 manifest metadata를 반환하고 secret env를 반환하지 않음
- 유효한 `POST /api/runs`가 202와 `QUEUED` campaign을 반환
- 두 번째 활성 campaign은 409
- 등록되지 않은 target/profile, 음수 rate, 허용 상한 초과, 잘못된 duration은 400
- `risk=fixture|cost` target은 `allowRisk` 명시 없이 400
- `jfrEnabled` 기본값은 true이고 false는 단일 smoke profile에서만 허용
- safe-all은 `defaultEnabled=true`이면서 `risk=safe`인 target만 manifest 순서대로 포함

- [ ] **9.2 Red 확인**

```bash
python3 -m unittest discover tools/perf_dashboard/tests -v
```

예상: dashboard module이 없어 import 실패.

- [ ] **9.3 target·request·campaign store 구현**

Python 표준 라이브러리의 `dataclasses`, `enum`, `json`, `pathlib`, `threading`만 사용한다. 허용값은 다음처럼 고정한다.

```python
PROFILE_LIMITS = {
    "smoke": {"max_rate_or_vus": 1, "max_seconds_or_iterations": 1},
    "read": {"max_rate_or_vus": 40, "max_seconds_or_iterations": 300},
    "write": {"max_rate_or_vus": 10, "max_seconds_or_iterations": 120},
    "external": {"max_rate_or_vus": 10, "max_seconds_or_iterations": 10},
}
```

`artifacts/performance/<campaign-id>/campaign.json`을 상태의 영속 source로 사용한다. server 재시작 시 `RUNNING|CANCELLING`은 `FAILED`로 복구하고 `failureReason=control-server-restarted`를 기록한다. target별 상태, 시작·종료 시각, 종료 코드, summary 핵심 지표, artifact ID를 매 상태 변경마다 temp file+`os.replace`로 원자 저장한다.

- [ ] **9.4 직렬 process runner와 취소 구현**

worker thread 하나가 target을 manifest 순서대로 처리한다. subprocess는 다음 argv 배열로만 실행하고 `shell=True`를 사용하지 않는다.

```python
[
    str(endpoint_runner),
    target.key,
    request.profile,
    str(request.rate_or_vus),
    request.duration_or_iterations,
]
```

env는 현재 process env에서 시작하되 `CAMPAIGN_ID`, `JFR_ENABLED`만 server가 덮어쓴다. env 값과 전체 command line은 API·SSE·log에 기록하지 않는다. 새 process group을 만들고 취소 시 SIGINT, 10초 뒤 SIGTERM 순서로 보낸다. 현재 target 종료 뒤 남은 target은 `CANCELLED`로 표시한다. endpoint runner의 trap이 JFR을 정리할 때까지 campaign을 최종 종료하지 않는다.

- [ ] **9.5 HTTP·SSE·artifact API 구현**

`ThreadingHTTPServer`와 `BaseHTTPRequestHandler`로 spec의 API를 구현하고 `Host`가 `127.0.0.1`, `localhost`가 아니면 403으로 거절한다. bind 기본값은 `127.0.0.1:8765`이며 CLI로 port만 변경할 수 있다. 모든 응답에는 `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`를 붙이고 dashboard 문서에는 `Content-Security-Policy: default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; form-action 'self'`를 추가한다.

SSE event는 증가하는 sequence를 가지며 target·phase·status·sanitized line만 포함한다. line sanitizer는 Bearer token과 secret/token/key/password 이름 뒤 값을 `[REDACTED]`로 바꾼다. 느린 client가 worker를 막지 않도록 최근 1,000 event의 bounded buffer를 사용한다.

artifact ID는 campaign manifest에서만 해석한다. `Path.resolve()`가 campaign directory 밖이면 404로 처리한다. `report.html`은 `Content-Disposition: inline`과 `Content-Security-Policy: sandbox; default-src 'none'; style-src 'unsafe-inline'`로 제공한다. JFR과 ZIP은 attachment와 chunk 단위로 전송한다. bundle은 `.bundle.tmp`에 `zipfile`로 만든 뒤 원자 rename하고 `campaign.json`, endpoint HTML·JSON·manifest·task별 JFR만 포함한다.

- [ ] **9.6 launcher와 Green 확인**

`dashboard.sh`는 Python·k6·AWS CLI·jq·fixture·`ACCESS_TOKEN`을 검사하고 token 값을 출력하지 않은 채 server를 실행한다.

```bash
bash -n scripts/perf/dashboard.sh
python3 -m py_compile tools/perf_dashboard/server.py tools/perf_dashboard/campaign.py
python3 -m unittest discover tools/perf_dashboard/tests -v
git diff --check
```

- [ ] **9.7 커밋**

```bash
git add tools/perf_dashboard scripts/perf/dashboard.sh
git commit -m "feat(load): 성능 캠페인 제어 API 추가"
```

### 작업 10: 모든 endpoint 실행·결과·JFR을 제공하는 HTML 대시보드

**파일:**

- 생성: `tools/perf_dashboard/DESIGN.md`
- 생성: `tools/perf_dashboard/static/showcase.html`
- 생성: `tools/perf_dashboard/static/index.html`
- 생성: `tools/perf_dashboard/static/styles.css`
- 생성: `tools/perf_dashboard/static/app.js`
- 생성: `tools/perf_dashboard/tests/test_static_ui.py`
- 생성: `docs/performance/k6-jfr-runbook.md`
- 생성: `docs/performance/results/README.md`

**인터페이스:**

- Consumes: 작업 9 localhost API와 SSE
- Produces: endpoint catalog, 실행 설정, live progress, 결과 이력, HTML·JSON·manifest·JFR·ZIP 다운로드 UI

- [ ] **10.1 `DESIGN.md`와 primitive showcase를 먼저 작성**

`tools/perf_dashboard`를 독립 frontend project root로 본다. `DESIGN.md`는 Overview, Principles, Brand, Foundations, Components, Patterns, Content, Accessibility의 8개 section과 research log를 가진다. research log에는 기존 저장소의 admin 화면 token 추출, 운영 dashboard reference 2개 비교, 채택·기각 이유를 기록한다. Design Read는 “한 명의 개발자가 부하·profiling campaign을 실수 없이 운용하는 고밀도 command surface”로 고정하고 `DESIGN_VARIANCE=3`, `MOTION_INTENSITY=2`, `VISUAL_DENSITY=8`을 사용한다. 색·타입·간격·radius·상태 token, responsive behavior, fixed region과 main scroll owner도 여기서 확정한다.

화면 CSS 전에 `showcase.html`에서 button, checkbox, select, input, method badge, risk warning, status, metric, result row, download link의 normal·hover·focus·disabled·loading·error 상태를 만든다. 단일 accent와 한 radius 체계를 사용하고 성공·실패는 색과 텍스트를 함께 쓴다. 외부 font·CDN·framework·icon을 추가하지 않는다.

- [ ] **10.2 실패하는 정적 UI 계약 테스트 작성**

`test_static_ui.py`는 HTML parser로 다음 semantic 계약을 검사한다.

- skip link, `main`, 고유한 heading hierarchy
- endpoint 검색과 suite·risk filter의 명시적 label
- 안전한 전체 실행, 선택 실행, 취소 button
- 실행 설정 field의 label과 help/error region
- live status `aria-live=polite`, error `role=alert`
- 결과 표의 caption·header, artifact download link 영역
- JavaScript·CSS가 로컬 경로만 사용하고 inline event handler가 없음
- secret/token 입력 또는 localStorage 사용이 없음

- [ ] **10.3 app shell과 endpoint catalog 구현**

desktop은 `100dvb` bounded grid로 고정 header와 `list-detail` body를 만든다. catalog pane은 endpoint 검색·suite·risk filter와 선택 count를 소유하고 main pane만 세로 scroll한다. `min-block-size: 0`, `min-inline-size: 0`, `overflow-wrap: anywhere`를 적용한다. 768px 아래는 catalog/config/result가 한 열로 reflow하고 375px에서 primary content 가로 scroll이 없어야 한다.

초기 load에서 `/api/targets`, `/api/runs`를 병렬 조회한다. safe target만 기본 선택하고 fixture·cost target을 선택하면 warning과 예상 최대 호출 수를 실행 button 인접 위치에 표시한다. profile에 따라 rate/VU와 duration/iteration field를 교체하되 focus와 입력값 의미를 보존한다.

- [ ] **10.4 실행·SSE·취소 상태 구현**

`POST /api/runs` 성공 후 active run panel로 이동하고 SSE를 연결한다. target별 `QUEUED|RUNNING|PASSED|FAILED|CANCELLED`와 phase, 경과 시간을 표시한다. SSE가 끊기면 지수 backoff 최대 10초로 다시 연결하고 그 사이 `/api/runs/{id}` snapshot을 조회한다. 새로고침 시 최근 active run을 복구한다.

실행 중 선택과 설정을 disable하고 취소는 한 번만 전송한다. HTTP 409, validation 400, network error, SSE reconnect, partial artifact 상태를 문맥 안에서 설명한다. console line은 text node로만 렌더링하고 `innerHTML`을 사용하지 않는다.

- [ ] **10.5 결과·비교·artifact 다운로드 구현**

최근 campaign을 선택하면 target별 p95, p99, 실패율, dropped iterations, threshold 상태를 표로 표시한다. 동일 target의 직전 campaign이 있으면 절대값과 증감률을 함께 보여 주되 데이터가 없으면 `비교 기준 없음`으로 표시한다.

각 target에 `HTML 결과 보기`, `summary.json`, `manifest.json`, 두 task JFR download link를 만들고 campaign 상단에 `전체 artifact ZIP`을 둔다. 파일이 없는 동안은 disabled text와 수집 상태를 보여 준다. browser는 S3 URL을 알지 못하고 localhost artifact route만 사용한다.

- [ ] **10.6 런북 작성**

런북의 기본 사용 경로는 다음으로 고정한다.

```bash
export ACCESS_TOKEN="$(python3 k6/mint-token.py 35 2)"
cp k6/fixtures/dev.example.json k6/fixtures/dev.json
scripts/perf/dashboard.sh
```

브라우저에서 `http://127.0.0.1:8765`를 열고 대표 smoke, 안전한 전체 실행, 비용 target opt-in, 취소, JFR 다운로드 순서로 설명한다. CLI fallback도 같은 `CAMPAIGN_ID`로 runner를 호출하도록 기록한다. PromQL은 p95/p99, Tomcat busy, Hikari pending을 포함하고 JFR 분석은 JMC 또는 Claude에 task별 원본 두 개와 manifest를 함께 전달하도록 안내한다. JFR에 민감한 내부 정보가 남을 수 있으므로 외부 업로드 전 취급 범위를 확인하도록 경고한다.

- [ ] **10.7 실제 브라우저 visual·interaction QA**

fake runner를 지정한 localhost server를 실제로 띄우고 인앱 브라우저로 다음을 수행한다.

1. 1280px: 검색·filter·개별 선택·safe-all 실행·SSE progress·결과·JFR download
2. 768px: catalog/main reflow, keyboard Tab 순서, focus ring, 취소
3. 375px: 단일 열, 긴 route·run ID, 가로 overflow 없음
4. empty/loading/API error/SSE reconnect/failed target/partial artifact 상태
5. reduced motion과 dark/light system preference에서 대비·상태 가독성

브라우저 console error와 네트워크 4xx/5xx가 의도한 validation 외에는 없어야 한다. WCAG AA contrast, keyboard-only 실행·다운로드, 200% zoom에서 content 손실이 없음을 확인한다.

- [ ] **10.8 Green 확인과 커밋**

```bash
python3 -m unittest discover tools/perf_dashboard/tests -v
python3 -m py_compile tools/perf_dashboard/server.py tools/perf_dashboard/campaign.py
git diff --check
git add tools/perf_dashboard docs/performance scripts/perf/dashboard.sh
git commit -m "feat(load): k6 JFR HTML 대시보드 추가"
```

### 작업 11: dev 배포·대시보드 실사용 검증과 기준 결과

**파일:**

- 생성: `docs/performance/results/2026-08-31-baseline.md`

**인터페이스:**

- Consumes: profiling image ECR tag, Terraform dev plan, HTML dashboard, 35번 token
- Produces: 대표 endpoint HTML·JSON·두 JFR과 저장소에 커밋 가능한 비민감 baseline 요약

- [ ] **11.1 전체 정적 검증**

```bash
./gradlew :api:test
docker build --target profile-runtime -t kbap-api-profile:local .
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
scripts/perf/test/profile-taskdef-overlay-test.sh
scripts/perf/test/terraform-profile-contract.sh
scripts/perf/test/jfr-scripts-test.sh
scripts/perf/test/run-endpoint-test.sh
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
python3 -m unittest discover tools/perf_dashboard/tests -v
terraform -chdir=iac/terraform fmt -recursive -check
terraform -chdir=iac/terraform validate
git diff --check
```

- [ ] **11.2 dev 인프라 적용 전 plan gate**

```bash
terraform -chdir=iac/terraform workspace select dev
terraform -chdir=iac/terraform plan -var-file=dev.tfvars -out=performance.tfplan
terraform -chdir=iac/terraform show -no-color performance.tfplan
```

허용 변경만 존재할 때 apply한다. 허용 범위는 artifact bucket 4종 설정, API task role policy, ECS Exec 활성화다.

- [ ] **11.3 profiling 이미지 배포와 steady state 확인**

profiling target을 ECR에 현재 40자 git SHA 기반 tag로 push한다. 최신 dev task definition을 가져와 작업 1 renderer로 profiling revision을 만든다. Spring profile은 `dev` 그대로이며 image와 관측 환경변수 4개만 바뀌어야 한다.

```bash
PERF_TASKDEF_DIR="$(mktemp -d)"
trap 'rm -rf "$PERF_TASKDEF_DIR"' EXIT

aws ecs describe-task-definition \
  --task-definition kbap-dev-ecs-api \
  --query taskDefinition > "$PERF_TASKDEF_DIR/current.json"

scripts/perf/render-profile-taskdef.sh \
  "$PERF_TASKDEF_DIR/current.json" \
  "$PROFILE_IMAGE" \
  "$PERF_TASKDEF_DIR/profile.json"

PROFILE_TASKDEF_ARN="$(aws ecs register-task-definition \
  --cli-input-json "file://$PERF_TASKDEF_DIR/profile.json" \
  --query taskDefinition.taskDefinitionArn \
  --output text)"
```

등록 전 `jq` diff로 image, `SPRING_JPA_SHOW_SQL=false`, `LOGGING_LEVEL_ROOT=WARN`, HTTP histogram, Tomcat MBean registry 이외에 container definition 차이가 없음을 확인한다. 이 ARN을 기존 CodeDeploy canary 절차로 배포하고 완료될 때까지 기다린다. 대표 요청 후 SQL·요청 INFO가 없고 WARN·ERROR가 유지되는지 CloudWatch에서 확인한다.

```bash
aws ecs describe-services \
  --cluster kbap-dev-ecs-cluster \
  --services kbap-dev-ecs-api \
  --profile kbap-infra \
  --region ap-northeast-2 \
  --query 'services[0].[desiredCount,runningCount,enableExecuteCommand,taskDefinition]'
```

기대: desired 2, running 2, Exec true, 두 running task가 같은 profiling task definition을 사용.

- [ ] **11.4 대표 endpoint 대시보드 QA**

`scripts/perf/dashboard.sh`를 실행하고 실제 브라우저에서 다음 target을 선택 실행한다.

```text
app-version: read, 5 RPS, 3m
home-auth: read, 20 RPS, 3m
foods-search-all-ko-hit: read, 20 RPS, 3m
reviews-food-count: read, 10 RPS, 3m
```

UI와 각 report directory에서 다음을 확인한다.

- target 상태·p95·p99·실패율·dropped iterations가 summary와 일치
- `report.html`, `summary.json`, `manifest.json` 크기 0 초과
- JFR 두 개 크기 0 초과, `summarize_jfr` 성공
- manifest task ID와 JFR 파일명 일치
- 각 task JFR download와 campaign ZIP download 성공
- Prometheus endpoint bucket과 Tomcat busy metric 존재
- access token·secret 문자열 없음

- [ ] **11.5 스캔 side-load QA**

일반 API 10 RPS를 백그라운드에서 유지하고 스캔 5건을 실행한다. 스캔 단독 대비 일반 API p95, Tomcat busy, JFR SocketRead를 기록한다. 비용 상한은 정확히 5건이다.

- [ ] **11.6 baseline 문서 작성**

`2026-08-31-baseline.md`에 다음 값만 기록한다.

- git SHA, task definition, image tag
- endpoint별 RPS, p95, p99, 오류율, dropped iterations
- task별 CPU, Hikari pending, Tomcat busy 최대
- JFR 상위 CPU·SocketRead·allocation stack 이름과 비율
- RDS 상위 SQL digest와 `EXPLAIN ANALYZE` 요약
- 확인된 병목과 확인되지 않은 가설
- fixture 정리 결과와 일반 dev 이미지 복구 결과

JFR 원본, HTML 원본, token, bucket URL은 문서에 넣지 않는다.

- [ ] **11.7 dev 복구와 fixture 정리**

일반 dev 이미지를 다시 배포하고 `SPRING_PROFILES_ACTIVE=dev`로 복구한다. `cleanup-fixtures.sql`은 실제 run ID를 지정해 실행한다. API desired/running 2, health 2개, `/api/app-version` 200을 확인한다.

- [ ] **11.8 최종 커밋**

```bash
git add docs/performance/results/2026-08-31-baseline.md
git commit -m "docs(load): dev 성능 기준선 기록"
```

## 요구사항 추적

| 설계 요구사항 | 구현 작업 | 완료 증거 |
|---|---|---|
| 기존 dev profile 유지와 최소 logging·관측 overlay | 작업 1 | task definition fixture 계약, 등록 전 JSON diff |
| 운영 기본 이미지 불변과 동적 JFR | 작업 2 | JRE/JDK target smoke, 실제 JFR 파일 |
| dev 전용 Exec·비공개 7일 보관 | 작업 3 | Terraform contract·validate·dev plan |
| API 태스크 두 개 모두 JFR 수집 | 작업 4 | fake AWS test, task별 JFR 두 파일 |
| 의존성 없는 endpoint별 HTML·JSON | 작업 5 | mock server harness smoke |
| 모든 읽기 경로와 쿼리 변형 | 작업 6 | targets manifest 전체 inspect |
| 가역 쓰기·fixture 쓰기·외부 비용 상한 | 작업 7 | catalog test, seed·cleanup 계약 |
| warm-up 뒤 JFR·k6 결합과 실패 시 정리 | 작업 8 | fake runner 순서·trap test |
| localhost 직렬 campaign·SSE·안전한 artifact 제공 | 작업 9 | 실제 HTTP API·취소·redaction·path traversal test |
| 모든 target 실행·결과 조회·JFR 다운로드 HTML | 작업 10 | semantic contract, 실제 브라우저 375·768·1280 QA |
| 실제 dev 표면과 복구 확인 | 작업 11 | 대시보드 대표 네 target·스캔 side-load·baseline 문서 |

## 최종 검증

모든 작업 커밋 후 다음을 한 번 실행한다.

```bash
./gradlew clean build
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
scripts/perf/test/profile-taskdef-overlay-test.sh
scripts/perf/test/terraform-profile-contract.sh
scripts/perf/test/jfr-scripts-test.sh
scripts/perf/test/run-endpoint-test.sh
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
python3 -m unittest discover tools/perf_dashboard/tests -v
terraform -chdir=iac/terraform fmt -recursive -check
terraform -chdir=iac/terraform validate
git diff --check
```

완료 증거는 다음을 모두 포함한다.

- 전체 Gradle build 성공
- 기본 runtime에 `jcmd` 없음, profiling runtime에 `jcmd`·`jfr`·`aws` 존재
- Terraform dev plan이 허용한 profiling 리소스만 변경
- 모든 endpoint target `k6 inspect` 성공
- mock server 대상 HTML·JSON 생성 성공
- localhost 대시보드에서 safe-all·개별 opt-in·취소·새로고침 복구 성공
- 실제 브라우저 375·768·1280에서 endpoint 선택, 결과 보기, task별 JFR·ZIP 다운로드 성공
- 실제 dev 대표 네 target의 HTML·JSON·두 JFR 생성 성공
- 35번 회원과 dev 서비스 정상 복구
- JFR·token·secret·실제 fixture 파일이 Git status에 나타나지 않음
