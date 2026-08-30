# k6·JFR 성능 테스트 체계 구현 계획

> **에이전트 작업자 필수 스킬:** superpowers:subagent-driven-development 또는 superpowers:executing-plans로 항목별 실행한다. 각 단계는 체크박스로 추적한다.

**목표:** dev API의 사용자용 엔드포인트를 독립적으로 부하 측정하고, 실행마다 k6 HTML·JSON과 두 API 태스크의 JFR을 수집해 병목을 재현 가능하게 판정하는 체계를 만든다.

**아키텍처:** `dev,load` Spring profile이 prod와 같은 SQL 로깅 조건과 HTTP/Tomcat 관측을 제공한다. 기존 bootJar를 Temurin 21 JDK로 실행하는 profiling Docker target과 dev 전용 ECS Exec·비공개 S3를 사용해 두 태스크의 JFR을 동적으로 제어하고, 단일 k6 엔트리포인트가 target registry에서 엔드포인트별 시나리오를 골라 HTML·JSON·manifest를 생성한다.

**기술 스택:** Kotlin 2.3.21, Java 21, Spring Boot 4.1.0, Micrometer Prometheus, Terraform 1.7+, AWS ECS EC2·S3·SSM, AWS CLI 2.36.31, k6 2.2.0, Bash, Python 3 표준 라이브러리

**Spec:** `docs/superpowers/specs/2026-08-31-k6-jfr-performance-test-design.md`

## 전역 제약

- 부하 대상은 `https://dev.kbap.site`이며 prod에는 부하를 보내지 않는다.
- 인증 요청은 `memberId=35`와 기존 `k6/mint-token.py`로 생성한 access token을 사용한다.
- access token, JWT secret, Firebase credential, 외부 API key, presigned URL을 파일·로그·manifest·Git에 기록하지 않는다.
- 운영 기본 Docker target은 Temurin 21 JRE를 유지하고 profiling target만 Temurin 21 JDK를 사용한다.
- `load` profile은 `SPRING_PROFILES_ACTIVE=dev,load`에서만 활성화한다.
- 본 측정은 `spring.jpa.show-sql=false`로 실행한다.
- 본 측정은 API 태스크 두 개 모두 JFR 시작에 성공한 뒤에만 실행한다.
- JFR artifact bucket은 public access를 전부 차단하고 SSE-S3와 7일 lifecycle을 적용한다.
- 모든 k6 target은 HTTP 상태와 `BaseResponse.success`를 함께 검사한다.
- 외부 비용 target은 `per-vu-iterations`로 총 요청 수를 고정한다.
- destructive endpoint는 35번 회원에 반복 실행하지 않는다.
- 생성 fixture는 `run_id`로 식별 가능해야 하며 캠페인 종료 시 정리한다.
- Kotlin 소스에는 주석을 추가하지 않는다.
- 테스트를 먼저 작성하고 예상한 이유로 실패하는 것을 확인한 뒤 구현한다.
- 문서와 커밋 메시지는 한국어로 작성한다.

---

### 작업 1: prod와 동등한 load 관측 profile

**파일:**

- 생성: `api/src/main/resources/application-load.yml`
- 생성: `api/src/test/kotlin/com/kbap/api/core/config/LoadProfileConfigurationTest.kt`

**인터페이스:**

- Consumes: Spring profile `dev,load`
- Produces: `spring.jpa.show-sql=false`, HTTP histogram 활성화, Tomcat MBean registry 활성화

- [ ] **1.1 실패하는 profile 설정 테스트 작성**

`LoadProfileConfigurationTest.kt`를 다음 구조로 작성한다.

```kotlin
package com.kbap.api.core.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class LoadProfileConfigurationTest : BehaviorSpec({
    given("부하 테스트 전용 설정") {
        val source = YamlPropertySourceLoader()
            .load("load", ClassPathResource("application-load.yml"))
            .single()

        then("SQL 콘솔 출력과 HTTP·Tomcat 관측 값이 고정된다") {
            source.getProperty("spring.jpa.show-sql") shouldBe false
            source.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests") shouldBe true
            source.getProperty("server.tomcat.mbeanregistry.enabled") shouldBe true
        }
    }
})
```

- [ ] **1.2 Red 확인**

```bash
./gradlew :api:test --tests '*LoadProfileConfigurationTest'
```

예상: `application-load.yml`이 없어 `FileNotFoundException`으로 실패.

- [ ] **1.3 최소 load profile 구현**

`application-load.yml`을 다음 내용으로 작성한다.

```yaml
spring:
  jpa:
    show-sql: false

management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true

server:
  tomcat:
    mbeanregistry:
      enabled: true
```

- [ ] **1.4 Green 확인**

```bash
./gradlew :api:test --tests '*LoadProfileConfigurationTest'
```

- [ ] **1.5 커밋**

```bash
git add api/src/main/resources/application-load.yml api/src/test/kotlin/com/kbap/api/core/config/LoadProfileConfigurationTest.kt
git commit -m "chore(api): 부하 테스트 관측 프로필 추가"
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
- Produces: 모든 읽기 target key와 `kind=read`

- [ ] **6.1 endpoint manifest와 실패하는 catalog 계약 검사 작성**

`targets.json`에 다음 key를 기록한다.

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

`catalog-contract.sh`는 `jq -r '.[]'`로 모든 key를 읽어 각 key에 `k6 inspect`를 실행한다. `index.js`에 없는 key는 inspect가 실패해야 한다.

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

- [ ] **7.1 쓰기·외부 target key를 manifest에 먼저 추가**

다음 key를 추가해 catalog test를 실패시킨다.

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

### 작업 8: JFR과 k6를 묶는 dev 캠페인 runner·런북

**파일:**

- 생성: `scripts/perf/run-endpoint.sh`
- 생성: `scripts/perf/test/run-endpoint-test.sh`
- 생성: `docs/performance/k6-jfr-runbook.md`
- 생성: `docs/performance/results/README.md`
- 수정: `k6/scan-burst.js`

**인터페이스:**

```bash
scripts/perf/run-endpoint.sh TARGET PROFILE RATE_OR_VUS DURATION_OR_ITERATIONS
```

- Consumes: 작업 4 JFR scripts, 작업 5~7 k6 target, `ACCESS_TOKEN`, `k6/fixtures/dev.json`
- Produces: `artifacts/performance/$RUN_ID/$TARGET`와 완전한 `manifest.json`

- [ ] **8.1 fake JFR·k6 기반 runner 실패 테스트 작성**

`run-endpoint-test.sh`는 임시 PATH의 fake `k6`, fake JFR scripts, fake `aws`를 사용해 다음을 검증한다.

- report directory가 `artifacts/performance/$RUN_ID/$TARGET` 형태
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

run ID는 `date -u +%Y%m%dT%H%M%SZ`와 target을 조합한다. 순서는 다음과 같다.

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

- [ ] **8.5 런북 작성**

런북에 정확한 순서를 기록한다.

```bash
export ACCESS_TOKEN="$(python3 k6/mint-token.py 35 2)"
cp k6/fixtures/dev.example.json k6/fixtures/dev.json

scripts/perf/run-endpoint.sh app-version read 5 3m
scripts/perf/run-endpoint.sh home-auth read 20 3m
scripts/perf/run-endpoint.sh reviews-food-count read 10 3m
scripts/perf/run-endpoint.sh scan-v2-krw external 5 1
```

런북은 다음 PromQL을 포함한다.

```promql
histogram_quantile(0.95,
  sum by (le, uri, instance) (
    rate(http_server_requests_seconds_bucket{env="dev", application="kbap-api"}[2m])
  )
)

max by (instance) (tomcat_threads_busy_threads{env="dev", application="kbap-api"})

max by (instance) (hikaricp_connections_pending{env="dev", application="kbap-api"})
```

JFR 판정은 JMC의 Method Profiling, Threads, Socket I/O, File I/O, Lock Instances, Garbage Collections, Memory 탭 순서로 확인한다. DB 후보는 같은 시간 범위의 RDS SQL digest와 `EXPLAIN ANALYZE`를 함께 보도록 명시한다.

- [ ] **8.6 Green 확인**

```bash
bash -n scripts/perf/run-endpoint.sh scripts/perf/test/run-endpoint-test.sh
scripts/perf/test/run-endpoint-test.sh
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
git diff --check
```

- [ ] **8.7 커밋**

```bash
git add scripts/perf/run-endpoint.sh scripts/perf/test/run-endpoint-test.sh docs/performance k6/scan-burst.js
git commit -m "docs(load): dev 성능 캠페인 런북 추가"
```

### 작업 9: dev 배포·실사용 검증과 기준 결과

**파일:**

- 생성: `docs/performance/results/2026-08-31-baseline.md`

**인터페이스:**

- Consumes: profiling image ECR tag, Terraform dev plan, runner, 35번 token
- Produces: 대표 endpoint HTML·JSON·두 JFR과 저장소에 커밋 가능한 비민감 baseline 요약

- [ ] **9.1 전체 정적 검증**

```bash
./gradlew :api:test
docker build --target profile-runtime -t kbap-api-profile:local .
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
scripts/perf/test/terraform-profile-contract.sh
scripts/perf/test/jfr-scripts-test.sh
scripts/perf/test/run-endpoint-test.sh
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
terraform -chdir=iac/terraform fmt -recursive -check
terraform -chdir=iac/terraform validate
git diff --check
```

- [ ] **9.2 dev 인프라 적용 전 plan gate**

```bash
terraform -chdir=iac/terraform workspace select dev
terraform -chdir=iac/terraform plan -var-file=dev.tfvars -out=performance.tfplan
terraform -chdir=iac/terraform show -no-color performance.tfplan
```

허용 변경만 존재할 때 apply한다. 허용 범위는 artifact bucket 4종 설정, API task role policy, ECS Exec 활성화다.

- [ ] **9.3 profiling 이미지 배포와 steady state 확인**

profiling target을 ECR에 현재 40자 git SHA 기반 tag로 push하고 dev task definition의 image와 `SPRING_PROFILES_ACTIVE=dev,load`만 교체한다. CodeDeploy 배포가 완료될 때까지 기다린다.

```bash
aws ecs describe-services \
  --cluster kbap-dev-ecs-cluster \
  --services kbap-dev-ecs-api \
  --profile kbap-infra \
  --region ap-northeast-2 \
  --query 'services[0].[desiredCount,runningCount,enableExecuteCommand,taskDefinition]'
```

기대: desired 2, running 2, Exec true, 두 running task가 같은 profiling task definition을 사용.

- [ ] **9.4 대표 endpoint 수동 QA**

다음 target을 순서대로 실행한다.

```bash
scripts/perf/run-endpoint.sh app-version read 5 3m
scripts/perf/run-endpoint.sh home-auth read 20 3m
scripts/perf/run-endpoint.sh foods-search-all-ko-hit read 20 3m
scripts/perf/run-endpoint.sh reviews-food-count read 10 3m
```

각 report directory에서 다음을 확인한다.

- `report.html`, `summary.json`, `manifest.json` 크기 0 초과
- JFR 두 개 크기 0 초과, `summarize_jfr` 성공
- manifest task ID와 JFR 파일명 일치
- Prometheus endpoint bucket과 Tomcat busy metric 존재
- access token·secret 문자열 없음

- [ ] **9.5 스캔 side-load QA**

일반 API 10 RPS를 백그라운드에서 유지하고 스캔 5건을 실행한다. 스캔 단독 대비 일반 API p95, Tomcat busy, JFR SocketRead를 기록한다. 비용 상한은 정확히 5건이다.

- [ ] **9.6 baseline 문서 작성**

`2026-08-31-baseline.md`에 다음 값만 기록한다.

- git SHA, task definition, image tag
- endpoint별 RPS, p95, p99, 오류율, dropped iterations
- task별 CPU, Hikari pending, Tomcat busy 최대
- JFR 상위 CPU·SocketRead·allocation stack 이름과 비율
- RDS 상위 SQL digest와 `EXPLAIN ANALYZE` 요약
- 확인된 병목과 확인되지 않은 가설
- fixture 정리 결과와 일반 dev 이미지 복구 결과

JFR 원본, HTML 원본, token, bucket URL은 문서에 넣지 않는다.

- [ ] **9.7 dev 복구와 fixture 정리**

일반 dev 이미지를 다시 배포하고 `SPRING_PROFILES_ACTIVE=dev`로 복구한다. `cleanup-fixtures.sql`은 실제 run ID를 지정해 실행한다. API desired/running 2, health 2개, `/api/app-version` 200을 확인한다.

- [ ] **9.8 최종 커밋**

```bash
git add docs/performance/results/2026-08-31-baseline.md
git commit -m "docs(load): dev 성능 기준선 기록"
```

## 요구사항 추적

| 설계 요구사항 | 구현 작업 | 완료 증거 |
|---|---|---|
| prod와 동등한 SQL·HTTP·Tomcat 관측 | 작업 1 | profile 설정 테스트, Prometheus bucket·Tomcat metric |
| 운영 기본 이미지 불변과 동적 JFR | 작업 2 | JRE/JDK target smoke, 실제 JFR 파일 |
| dev 전용 Exec·비공개 7일 보관 | 작업 3 | Terraform contract·validate·dev plan |
| API 태스크 두 개 모두 JFR 수집 | 작업 4 | fake AWS test, task별 JFR 두 파일 |
| 의존성 없는 endpoint별 HTML·JSON | 작업 5 | mock server harness smoke |
| 모든 읽기 경로와 쿼리 변형 | 작업 6 | targets manifest 전체 inspect |
| 가역 쓰기·fixture 쓰기·외부 비용 상한 | 작업 7 | catalog test, seed·cleanup 계약 |
| warm-up 뒤 JFR·k6 결합과 실패 시 정리 | 작업 8 | fake runner 순서·trap test |
| 실제 dev 표면과 복구 확인 | 작업 9 | 대표 네 target·스캔 side-load·baseline 문서 |

## 최종 검증

모든 작업 커밋 후 다음을 한 번 실행한다.

```bash
./gradlew clean build
PROFILE_IMAGE=kbap-api-profile:local scripts/perf/profile-image-smoke.sh
scripts/perf/test/terraform-profile-contract.sh
scripts/perf/test/jfr-scripts-test.sh
scripts/perf/test/run-endpoint-test.sh
k6/tests/catalog-contract.sh
k6/tests/harness-smoke.sh
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
- 실제 dev 대표 네 target의 HTML·JSON·두 JFR 생성 성공
- 35번 회원과 dev 서비스 정상 복구
- JFR·token·secret·실제 fixture 파일이 Git status에 나타나지 않음
