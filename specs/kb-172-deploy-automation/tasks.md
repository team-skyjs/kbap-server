# Tasks: 브랜치별 배포 자동화 — develop/staging 푸시 시 EC2 컨테이너 배포, main 병합 시 ECS 블루그린

**Input**: Design documents from `/specs/kb-172-deploy-automation/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R9), quickstart.md(§1~8 런북)

**Tests**: 헌법 원칙 I 게이트는 plan.md 에서 정당화 통과 — 프로덕션 코드·설정 0줄이라 JVM 테스트 표면이 없다(KB-169 선례). 검증은 각 스토리의 **actionlint 정적 검사 + 실배포 1회**(quickstart §6, DoD)로 대체하며, 실배포 검증 태스크가 각 스토리의 수용 테스트다.

**Organization**: 스토리별 독립 완결 — US1(dev)만으로 MVP, US2(staging)·US3(prod)는 각각 독립 검증 가능.

**주의**: AWS 콘솔/CLI 태스크(OIDC·IAM·EC2·Environments)는 저장소 밖 작업 — quickstart 런북 §번호를 따라 수행하고 완료 체크만 남긴다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일/대상, 미완 태스크 의존 없음)
- **[Story]**: US1(dev)·US2(staging)·US3(prod)

---

## Phase 1: Setup

**Purpose**: 저장소 쪽 공통 전제

- [X] T001 (폐기) 버전 개념 폐기 — 전 환경 커밋 sha 태그(사용자 결정 07-20). VERSION 파일 없음, Setup 저장소 작업 없음.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 전제하는 AWS 공통 구성 — quickstart 런북 수행

**⚠️ CRITICAL**: US1~US3 실배포 검증 전 완료 필수

- [ ] T002 GitHub OIDC provider 존재 확인/생성 — quickstart §1 (`aws iam create-open-id-connect-provider`)
- [ ] T003 [P] 공용 EC2 사전 조건 확인 — quickstart §3: SSM Agent + `AmazonSSMManagedInstanceCore`, 인스턴스 프로파일 ECR pull 권한, env-file 2개(`/opt/kbap/api-dev.env`·`api-staging.env`, `SPRING_PROFILES_ACTIVE` 포함), 포트 8080/8081

**Checkpoint**: OIDC·EC2 준비 완료 — 스토리별 IAM/Environment/워크플로 작업 시작 가능

---

## Phase 3: User Story 1 - develop 푸시만으로 dev 환경 배포 (Priority: P1) 🎯 MVP

**Goal**: develop 푸시 → 이미지 빌드·ECR push(git sha 태그) → SSM 으로 `api-dev`(:8080) 교체 → 헬스체크까지 무인 완료

**Independent Test**: develop 에 커밋 푸시 → 사람 조작 0회로 `api-dev` 가 해당 sha 이미지로 교체되고 `/actuator/health` UP (quickstart §6.1)

### Implementation for User Story 1

- [ ] T004 [P] [US1] IAM 역할 `gha-deploy-dev` 생성 — quickstart §2: 신뢰 정책 `sub`=`repo:team-skyjs/kbap-server:environment:dev`, 권한 = ECR push(`kbap-api` 한정) + `ssm:SendCommand`(대상 인스턴스·`AWS-RunShellScript` 한정) + 조회
- [ ] T005 [P] [US1] GitHub Environment `dev` 생성 + variables 등록 + **deployment branch policy `develop`** — quickstart §4: `AWS_REGION`·`AWS_ROLE_ARN`·`ECR_REPOSITORY=kbap-api`·`EC2_INSTANCE_ID`·`CONTAINER_NAME=api-dev`·`HOST_PORT=8080` (secret 0개). branch policy 로 develop 만 dev 배포 가능하게 잠금(브랜치 격리 실집행 — FR-006)
- [X] T006 [P] [US1] `.github/workflows/deploy-dev.yml` 작성 — plan "워크플로 공통 골격": `on: push(develop)` + `workflow_dispatch(image_tag)`(R8), `concurrency: deploy-dev, cancel-in-progress: false`(R7), `permissions: id-token: write`(R5), `environment: dev`, steps = checkout → configure-aws-credentials(OIDC, `vars.AWS_ROLE_ARN`) → ecr-login → [image_tag 미지정 시] `docker build --platform linux/amd64` + push 태그 `${{ github.sha }}`(R2) → `aws ssm send-command`(pull/stop/rm/run `--env-file /opt/kbap/api-dev.env -p 8080:8080` + 헬스체크 루프 `curl -sf localhost:8080/actuator/health` 30회×5초, 실패 `exit 1`)(R3) → `ssm wait command-executed` + `get-command-invocation`(실패 전파, FR-010)
- [X] T007 [US1] `actionlint` 로 `.github/workflows/deploy-dev.yml` 정적 검증 (R9)
- [ ] T008 [US1] 실배포 검증 — develop 푸시 → Actions `deploy-dev` 성공, EC2 `docker ps` 태그=푸시 sha, health UP, `api-staging` 비간섭 (quickstart §6.1, SC-001)

**Checkpoint**: dev 배포 자동화 완결 — 수동 배포 절차 소멸(SC-005)

---

## Phase 4: User Story 2 - staging 푸시만으로 staging 환경 배포 (Priority: P2)

**Goal**: staging 푸시 → 커밋 sha 이미지 → SSM 으로 `api-staging`(:8081) 교체, `api-dev` 비간섭

**Independent Test**: staging 에 커밋 푸시 → `api-staging` 만 해당 sha 이미지로 교체, 같은 EC2 의 `api-dev` STATUS 유지 (quickstart §6.2)

### Implementation for User Story 2

- [ ] T009 [P] [US2] 장기 브랜치 `staging` 생성 — quickstart §5: `git push origin develop:staging`
- [ ] T010 [P] [US2] IAM 역할 `gha-deploy-staging` 생성 — quickstart §2 (dev 와 동일 구조, `sub`=`environment:staging`)
- [ ] T011 [P] [US2] GitHub Environment `staging` 생성 + variables 등록 + **deployment branch policy `staging`** — quickstart §4: `CONTAINER_NAME=api-staging`·`HOST_PORT=8081`, 나머지 dev 와 동일 항목. branch policy 로 staging 브랜치만 배포 가능하게 잠금(FR-006)
- [X] T012 [P] [US2] `.github/workflows/deploy-staging.yml` 작성 — deploy-dev.yml 과 동일 구조(R1, 태그도 `github.sha` 동일), 차이: 트리거 `staging`, `concurrency: deploy-staging`, `environment: staging`, env-file `/opt/kbap/api-staging.env`, 포트 `8081:8080`, 헬스체크 `localhost:8081`
- [X] T013 [US2] `actionlint` 로 `.github/workflows/deploy-staging.yml` 정적 검증 (R9)
- [ ] T014 [US2] 실배포 검증 — staging 푸시 → `api-staging` 태그=푸시 sha, health UP(:8081), `api-dev` 재시작 없음 (quickstart §6.2, FR-002)

**Checkpoint**: dev·staging 이 같은 EC2 에서 독립 배포됨

---

## Phase 5: User Story 3 - main 병합 시 prod 무중단 배포 (Priority: P3)

**Goal**: main 병합 → 커밋 sha 이미지 → 태스크정의 리비전 갱신 → `update-service` 로 ECS 네이티브 블루/그린 트리거·`wait services-stable`

**Independent Test**: main 병합 → 새 태스크정의 이미지 태그=커밋 sha, 블루/그린 트래픽 전환(새 배포 PRIMARY), 서비스 헬스 UP (quickstart §6.3)

### Implementation for User Story 3

- [ ] T015 [P] [US3] IAM 역할 `gha-deploy-prod` 생성 — quickstart §2: `sub`=`environment:prod`, 권한 = ECR push + `ecs:DescribeServices`·`DescribeTaskDefinition`·`RegisterTaskDefinition`·`UpdateService` + `iam:PassRole`(태스크 역할 한정). CodeDeploy 권한 없음(네이티브 블루/그린)
- [ ] T016 [P] [US3] GitHub Environment `prod` 생성 + variables 등록 + **deployment branch policy `main`** — quickstart §4: `ECS_CLUSTER`·`ECS_SERVICE` + 공통 3종(`AWS_REGION`·`AWS_ROLE_ARN`·`ECR_REPOSITORY`). CODEDEPLOY_*·CONTAINER_* 불필요(서비스/태스크정의가 소유). branch policy 로 main 만 prod 배포 가능하게 잠금(FR-006 브랜치 격리 실집행 — IAM sub 은 브랜치를 못 담음). 승인 게이트 미설정 — 추후 protection rule 만. **전제**: ECS 서비스에 `deploymentConfiguration.strategy=BLUE_GREEN`·타깃그룹·bake 사전 구성
- [X] T017 [P] [US3] `.github/workflows/deploy-prod.yml` 작성 — 골격 공통(트리거 `main`·`concurrency: deploy-prod`·OIDC·ECR push 태그 **`github.sha`**), 배포 단계는 R4: `describe-services` → `describe-task-definition` → 이미지만 교체한 새 리비전 등록 → `aws ecs update-service --task-definition <new-arn>`(블루/그린 트리거) → `aws ecs wait services-stable`(실패 전파). `--deployment-configuration` 미전달(bake·서킷브레이커 덮어쓰기 방지)
- [X] T018 [US3] `actionlint` 로 `.github/workflows/deploy-prod.yml` 정적 검증 (R9)
- [ ] T019 [US3] 실배포 검증 — main 병합 → 태스크정의 태그=커밋 sha, 블루그린 전환, 헬스 UP (quickstart §6.3, FR-003)

**Checkpoint**: 3개 브랜치→환경 매핑 전부 자동화 — DoD 배포 검증 3종 완료

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T020 실패 전파 확인 — 존재하지 않는 `image_tag` 로 dev `workflow_dispatch` 실행 → 워크플로 실패 표시 + 기존 컨테이너 생존 확인 (quickstart §6.4, FR-010·US1-AC3)
- [ ] T021 [P] 롤백 리허설(권장) — dev 에서 직전 sha 로 `workflow_dispatch` 재배포 성공 확인 (quickstart §7, SC-004)
- [ ] T022 [P] 교차 권한 차단 확인 — dev 역할 자격으로 prod 자원(예: `ecs:UpdateService`) 호출 시 AccessDenied 확인 (SC-003, 스팟 체크)
- [X] T023 PR #71 제목·본문 갱신 — `docs(spec)` → `feat(ci): 브랜치별 배포 자동화` + 변경/검증 결과 반영, Ready for review 전환은 사용자 승인 후

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1(T001)**: 폐기(버전 개념 없음 — 저장소 Setup 작업 없음)
- **Phase 2(T002~T003)**: 즉시 가능 — 각 스토리의 **실배포 검증**(T008·T014)을 블록(워크플로 파일 작성 자체는 비블록)
- **US1(T004~T008)** → **US2(T009~T014)** → **US3(T015~T019)**: 우선순위 순 권장. 파일·역할·환경이 전부 분리라 기술적으론 병렬 가능하나, 검증 순서는 P1→P2→P3(신뢰 축적 후 prod 연결 — spec)
- **Polish(T020~T023)**: 해당 스토리 검증 완료 후

### Within Each User Story

- IAM 역할·Environment·워크플로 작성([P] 3건)은 상호 독립 — 동시 진행 가능
- actionlint → 실배포 검증 순서 고정(정적 통과 후 푸시)
- 실배포 검증은 Phase 2 + 스토리 내 전 태스크 완료 후

### Parallel Example: User Story 1

```text
동시 진행: T004(IAM 콘솔) + T005(GitHub 설정) + T006(deploy-dev.yml 작성)
이후 직렬: T007(actionlint) → T008(develop 푸시 검증)
```

---

## Implementation Strategy

### MVP First (US1 = dev 자동화)

1. T001~T003(Setup·Foundational) → T004~T008(US1) → **검증 후 정지 가능** — 일일 배포 자동화라는 MVP 가치가 이 시점에 성립
2. US2 추가 → staging 독립 검증
3. US3 추가 → prod 블루그린 연결(가장 마지막 — 영향 최대, 신뢰 축적 후)
4. Polish 로 실패 전파·롤백·권한 차단 스팟 체크

### 커밋 단위

- T001+T006(+T007) = dev 파이프라인 커밋, T009~T013 = staging 커밋, T017~T018 = prod 커밋 — 워크플로 파일별 논리 커밋, AWS 콘솔 작업은 커밋 없음(quickstart 가 기록)
