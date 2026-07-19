# Research: KB-172 브랜치별 배포 자동화

## R1. 워크플로 파일 구조 — 환경별 3개 독립 파일

- **Decision**: `.github/workflows/deploy-dev.yml`·`deploy-staging.yml`·`deploy-prod.yml` 3개 독립 파일. dev·staging 은 값(트리거 브랜치·environment 이름·컨테이너명·포트)만 다른 동일 구조.
- **Rationale**: Jira 가 "워크플로 파일은 환경별로 분리해 실패 추적과 권한을 격리한다"로 확정. GitHub Actions 실행 이력이 파일(워크플로) 단위로 묶여 환경별 실패 추적이 즉시 된다. 값 차이는 GitHub Environments variables 로 흡수해 파일 간 diff 를 최소화한다.
- **Alternatives considered**: reusable workflow(`workflow_call`) 1개 + 얇은 호출 파일 2개 — 소비자 2개에 간접화 한 겹이 추가되고 environment 전달·variables 스코프 규칙을 한 단계 더 알아야 한다. 소비자가 3~4개로 늘면 그때 승격(YAGNI).

## R2. 이미지 빌드 — 기존 멀티스테이지 Dockerfile 그대로 CI 에서 docker build

- **Decision**: 러너에서 `docker build --platform linux/amd64` 후 push. 빌드 로직은 기존 `Dockerfile`(Gradle bootJar 멀티스테이지)이 소유 — 워크플로는 build/push 만 한다. **이미지 태그는 전 환경 공통 `${{ github.sha }}`**(사용자 결정 2026-07-20 — 버전 개념 폐기, 단순화). `latest`·버전 태그 미사용.
- **커밋 sha 통일 근거**: 커밋마다 유일 태그라 환경 간 덮어쓰기·충돌이 원천적으로 없다(별도 `-rc` 접미사·버전 파일·태그 파싱 불필요). VERSION 파일·브랜치명 파싱·git tag 트리거는 전부 검토했으나(2026-07-20 논의), 버전 관리가 지금 요구사항이 아니라 기각 — 릴리스 버저닝이 필요해지면 후속으로 git 태그 도입(R8 롤백은 sha 로 이미 충족).
- **Rationale**: Dockerfile 이 이미 로컬 수동 배포에서 검증된 빌드 정의다. 로컬과 CI 가 같은 아티팩트 경로를 쓰므로 "prod 와 동일한 아티팩트" 성질이 유지된다. `ubuntu-latest` 러너가 amd64 라 `--platform` 은 명시적 안전핀.
- **Alternatives considered**: (1) 러너에서 gradle 빌드 후 jar 만 도커라이즈 — Dockerfile 과 빌드 정의가 이원화돼 드리프트. (2) buildx 레이어 캐시(gha cache) — Gradle 스테이지는 소스 복사 직후라 캐시 적중이 낮음. 빌드 시간이 문제 되면 후속.

## R3. EC2 컨테이너 교체 — SSM Run Command(AWS-RunShellScript) + 스크립트 내 헬스체크

- **Decision**: `aws ssm send-command`(document `AWS-RunShellScript`)로 EC2 에 셸 스크립트 전달: ECR 로그인(인스턴스 프로파일 권한) → `docker pull` → `docker stop/rm` → `docker run -d --restart unless-stopped --env-file /opt/kbap/<container>.env -p <port>:8080` → **헬스체크 루프**(`curl -sf localhost:<port>/actuator/health` 최대 30회×5초, 실패 시 `exit 1`). 워크플로는 `aws ssm wait command-executed` + `get-command-invocation` 으로 종료 코드·출력을 회수해 실패를 그대로 잡 실패로 만든다.
- **Rationale**: SSH 키 불필요(Jira 확정). 헬스체크를 SSM 스크립트 안에 두면 "헬스 실패 = 명령 실패 = 워크플로 실패"가 한 경로로 떨어져 FR-008·FR-010 을 추가 단계 없이 충족한다. 러너에서 EC2 로 직접 curl 하는 방식은 보안그룹 개방이 필요해 기각.
- **완료 대기는 `aws ssm wait` 대신 직접 폴링**(Codex 리뷰 2026-07-20 지적 반영): `aws ssm wait command-executed` 기본 상한은 20회×5초=100초인데 원격 헬스 루프는 30회×5초=150초라, 느린 JVM 기동 시 waiter 가 먼저 포기하고 `|| true` 가 타임아웃을 삼켜 즉시 status 확인이 `InProgress` 를 봐 **정상 배포를 실패로 오탐**한다. 러너에서 `get-command-invocation` 을 최대 60회×5초=300초 폴링해 종료 상태(Success/Failed/…)까지 기다린 뒤 판정한다 — 폴링 창(300초) > 원격 창(150초).
- **Alternatives considered**: (1) SSM 세션/포트포워딩으로 러너에서 헬스체크 — 플러그인 설치·세션 관리 복잡. (2) docker compose 파일을 EC2 에 두고 갱신 — 현재 운영이 단일 `docker run` 컨테이너 2개라 도입 이득 없음.
- **환경값**: 컨테이너 런타임 env 는 EC2 호스트의 `--env-file`(`/opt/kbap/api-dev.env`·`api-staging.env`)이 소유 — 기존 수동 배포의 env 구성을 그대로 승계하고, DB 비밀번호 등이 GitHub 로 이동하지 않는다.

## R4. prod — 태스크 정의 리비전 갱신 + ECS 네이티브 블루/그린(update-service)

- **Decision**(2026-07-20 사용자 확인 — CodeDeploy 미사용, ECS 네이티브 블루/그린 사용): `aws ecs describe-services` 로 현재 태스크정의 ARN 을 얻어 `describe-task-definition` → **이미지 태그(커밋 sha)만 교체**한 새 리비전 등록(`register-task-definition`) → `aws ecs update-service --task-definition <new-arn>` 로 블루/그린 트리거 → `aws ecs wait services-stable` 로 완료 대기. 블루/그린 전략(`deploymentConfiguration.strategy=BLUE_GREEN`)·타깃그룹·리스너·bake time 은 **ECS 서비스에 미리 구성**(인프라 소유 — CodeDeploy 의 배포그룹이 하던 역할을 서비스 설정이 대체). 헬스체크는 블루/그린 타깃그룹이 담당, wait 실패가 곧 워크플로 실패다.
- **Rationale**: CodeDeploy 를 쓰지 않으므로 `deploy create-deployment`·appspec·`AWS::ECS::Service` revision 이 전부 불필요해진다 — `update-service` 한 번이 블루/그린을 트리거한다(서비스가 BLUE_GREEN 전략으로 구성돼 있을 때). 태스크정의를 저장소에 두지 않고 현재 리비전에서 파생하는 원칙은 유지(인프라 드리프트 방지).
- **`--deployment-configuration` 미전달**: 매 배포에서 이 플래그를 넘기지 않는다 — AWS CLI 는 이 객체를 통째로 대체하므로 미지정 하위필드(bakeTime·서킷브레이커·min/maxPercent)가 기본값으로 덮어써진다. 전략·bake 는 서비스에 1회 구성하고 파이프라인은 태스크정의만 교체한다.
- **wait 시간 주의**: `aws ecs wait services-stable` 기본 상한은 10분(40회×15초) — bake time 이 길면 wait 가 타임아웃될 수 있다(배포 자체는 계속 진행). bake 를 wait 안에 맞추거나 필요 시 폴링 루프로 대체(현재는 표준 wait 사용).
- **Alternatives considered**: (1) CodeDeploy `create-deployment`+appspec — 사용자가 CodeDeploy 미사용이라 기각. (2) `taskdef.json` 저장소 반입 — 인프라 값 이중 관리라 기각. (3) `--force-new-deployment` — 서비스가 BLUE_GREEN 전략이면 task-def 교체만으로 블루/그린이 트리거되므로 불필요.

## R5. 인증 — GitHub OIDC + 환경별 IAM 역할(신뢰 정책으로 교차 차단)

- **Decision**: 각 워크플로 잡에 `permissions: {id-token: write, contents: read}` + `aws-actions/configure-aws-credentials@v4`(`role-to-assume: ${{ vars.AWS_ROLE_ARN }}`). IAM 역할 3개(`gha-deploy-dev`/`-staging`/`-prod`)의 신뢰 정책 `sub` 조건을 `repo:<org>/<repo>:environment:<env>` 로 잠가, **해당 GitHub Environment 에서 실행된 잡만** 그 역할을 assume 할 수 있게 한다. 권한: dev/staging 역할 = ECR push + `ssm:SendCommand`(대상 인스턴스·문서 한정) + 결과 조회, prod 역할 = ECR push + `ecs:DescribeServices`·`DescribeTaskDefinition`·`RegisterTaskDefinition`·`UpdateService`(+`iam:PassRole` 태스크 역할 한정) — CodeDeploy 권한 없음.
- **Rationale**: 장기 액세스 키 0개. 단 **IAM `sub` 만으로는 브랜치를 격리하지 못한다** — environment 잡의 OIDC `sub` 는 `repo:…:environment:<env>` 로 브랜치를 담지 않으므로, 아무 브랜치의 워크플로가 `environment: prod` 를 선언하면 신뢰 정책이 통과한다. 따라서 브랜치 격리는 **GitHub Environment 의 deployment branch policy**(prod→main·staging→`staging-*`·dev→develop)가 담당하고, IAM `sub`(어느 환경) + branch policy(어느 브랜치) 2겹으로 FR-006 을 완성한다(quickstart §4). (Codex 리뷰 2026-07-20 지적 반영.)
- **Alternatives considered**: 액세스 키 secrets 저장 — 유출·로테이션 부담, Jira 가 OIDC 확정. 기각.

## R6. GitHub Environments — 값은 variables, secrets 불필요

- **Decision**: Environments `dev`/`staging`/`prod` 생성. 배포에 필요한 값은 전부 비밀 아님 → **environment variables** 로 등록: 공통 `AWS_REGION`·`AWS_ROLE_ARN`·`ECR_REPOSITORY`(=`kbap/api`), dev/staging `EC2_INSTANCE_ID`·`CONTAINER_NAME`(`api-dev`/`api-staging`)·`HOST_PORT`(`8080`/`8081`), prod `ECS_CLUSTER`·`ECS_SERVICE`(CODEDEPLOY_*·CONTAINER_* 불필요 — 네이티브 블루/그린은 컨테이너·포트·타깃그룹·전략을 서비스/태스크정의가 소유). prod 승인 게이트는 이번에 켜지 않되 environment 구조상 protection rule 설정만으로 추후 활성화 가능.
- **Rationale**: OIDC 라 자격 증명 secret 이 없고, 컨테이너 런타임 secret 은 EC2 env-file/ECS 태스크정의가 소유(R3·R4) — GitHub 에 비밀이 하나도 안 올라간다.

## R7. 동시 실행 — 환경별 직렬화, 마지막 푸시가 최종 상태

- **Decision**: 각 워크플로에 `concurrency: { group: deploy-<env>, cancel-in-progress: false }`.
- **Rationale**: 진행 중 배포는 완주시키고(중간 취소로 컨테이너 반쯤 교체 방지), GitHub 이 대기열을 최신 1건으로 압축하므로 연속 푸시의 최종 상태 = 마지막 푸시(엣지 케이스 요구 충족).

## R8. 롤백 — 같은 워크플로의 workflow_dispatch + image_tag 입력

- **Decision**: 세 워크플로 모두 `workflow_dispatch`(optional input `image_tag`)를 추가. 입력이 있으면 빌드·push 를 건너뛰고 해당 태그를 그대로 배포한다(없으면 현재 커밋 sha 빌드 — R2). 롤백 입력값은 전 환경 공통 **이전 커밋 sha**. 입력은 `env` 로 받아 ECR 태그 형식 검증 후 사용(셸 인젝션 차단 — Codex 리뷰 반영).
- **Rationale**: FR-009 롤백(이전 태그 재배포, 추가 빌드 불필요)이 별도 장치 없이 수동 실행 한 번으로 충족된다.
- **Alternatives considered**: 별도 rollback.yml — 배포 경로가 이원화돼 드리프트. 기각.

## R9. 검증 전략 — JVM 테스트 표면 없음, 정적 lint + 실배포 런북

- **Decision**: 애플리케이션 코드·설정 0줄이라 Kotest 테스트 표면이 없다(KB-169 선례 — 설정만 변경 시 리소스 가드 테스트는 정보량 낮아 기각). 검증은 (1) `actionlint` 로 워크플로 정적 검사(로컬, 설치돼 있을 때), (2) DoD 의 **환경별 실배포 1회**(quickstart 런북 §검증)로 한다.
- **Rationale**: 워크플로 yml 을 복창하는 테스트는 배포 실패를 예방하지 못한다. 실제 게이트는 헬스체크(R3·R4)가 배포 시마다 수행.
