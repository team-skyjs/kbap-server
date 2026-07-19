# Feature Specification: 브랜치별 배포 자동화 — develop/staging 푸시 시 EC2 컨테이너 배포, main 병합 시 ECS 블루그린

**Feature Branch**: `kb-172-deploy-automation`

**Created**: 2026-07-20

**Status**: Draft

**Input**: User description: "kb-172 — [BE] 브랜치별 배포 자동화. dev·staging 공용 EC2 한 대에 docker 컨테이너 2개(api-dev :8080, api-staging :8081), 이미지는 ECR kbap-api 공유. 현재 수동 배포(로컬 빌드·push 후 EC2 접속 docker 명령)를 브랜치 푸시만으로 자동화한다. develop→dev, staging→staging, main→prod(기존 ECS 블루그린) 매핑."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - develop 푸시만으로 dev 환경 배포 (Priority: P1)

개발자가 PR 을 develop 에 병합(푸시)하면, 사람 손을 거치지 않고 최신 코드가 dev 환경(`api-dev` 컨테이너)에 배포되고 정상 기동까지 확인된다. 로컬 빌드·이미지 push·EC2 접속·docker 명령 실행이 전부 사라진다.

**Why this priority**: 가장 빈번한 배포 경로(일상 개발 사이클)이며, 현재 수동 절차의 비용·실수 위험이 가장 크게 누적되는 지점이다. 이 하나만 있어도 팀의 일일 배포가 자동화되는 MVP 다.

**Independent Test**: develop 브랜치에 커밋을 푸시하고, 별도 조작 없이 dev 환경이 해당 커밋 버전으로 교체되고 헬스체크가 통과하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** develop 브랜치에 새 커밋이 푸시됨, **When** 배포 파이프라인이 자동 실행됨, **Then** 해당 커밋으로 빌드된 이미지가 커밋 식별자(git sha) 태그로 저장소에 올라가고 `api-dev` 컨테이너가 그 이미지로 교체된다
2. **Given** `api-dev` 컨테이너 교체 완료, **When** 파이프라인이 헬스체크(`/actuator/health`)를 확인함, **Then** 정상 응답이면 배포 성공으로, 응답이 없거나 비정상이면 배포 실패로 표시된다
3. **Given** 빌드 또는 이미지 업로드가 실패함, **When** 파이프라인이 중단됨, **Then** 기존에 떠 있던 `api-dev` 컨테이너는 영향을 받지 않고 계속 서비스한다

---

### User Story 2 - staging 푸시만으로 staging 환경 배포 (Priority: P2)

개발자가 staging 브랜치에 푸시하면 동일한 흐름으로 `api-staging` 컨테이너(:8081)가 교체된다. dev 배포와 같은 EC2 한 대를 공유하지만, 서로의 배포가 간섭하지 않는다.

**Why this priority**: 릴리스 검증 경로로 dev 다음으로 자주 쓰이며, dev 파이프라인과 흐름이 동일해 P1 완성 후 낮은 비용으로 확장된다.

**Independent Test**: staging 브랜치에 커밋을 푸시하고 `api-staging` 만 해당 버전으로 교체되는지, 같은 EC2 의 `api-dev` 는 그대로인지 확인한다.

**Acceptance Scenarios**:

1. **Given** staging 브랜치에 새 커밋이 푸시됨, **When** 배포 파이프라인이 자동 실행됨, **Then** `api-staging` 컨테이너가 해당 커밋 이미지로 교체되고 헬스체크까지 확인된다
2. **Given** staging 배포가 진행 중임, **When** 같은 EC2 의 `api-dev` 를 조회함, **Then** dev 컨테이너는 중단·재시작 없이 기존 버전으로 계속 서비스한다

---

### User Story 3 - main 병합 시 prod 무중단 배포 (Priority: P3)

main 브랜치에 병합(푸시)되면 기존 ECS 블루그린 배포가 자동으로 실행되어 prod 가 무중단으로 교체된다.

**Why this priority**: 배포 빈도는 가장 낮지만 영향은 가장 크다. dev·staging 자동화로 파이프라인 신뢰가 쌓인 뒤 연결하는 것이 안전하다.

**Independent Test**: main 에 병합 커밋을 푸시하고 블루그린 배포가 트리거되어 신규 버전으로 트래픽이 전환되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** main 브랜치에 병합 커밋이 푸시됨, **When** prod 배포 파이프라인이 자동 실행됨, **Then** 기존 블루그린 방식으로 신규 버전이 배포되고 트래픽이 전환된다
2. **Given** prod 배포 파이프라인이 실행됨, **When** dev·staging 용 자격 증명으로 prod 자원 접근을 시도함(또는 그 역), **Then** 권한 수준에서 거부된다

---

### Edge Cases

- 같은 브랜치에 연속 푸시가 겹치면? — 나중 푸시가 최종 상태가 되어야 하며, 동시 실행으로 컨테이너가 꼬이지 않아야 한다(환경별 배포는 직렬화).
- 헬스체크가 계속 실패하면? — 파이프라인은 무한 대기하지 않고 제한 시간 내 실패로 종료하며, 실패가 실행 이력에 명확히 남는다.
- 배포된 버전에 결함이 발견되면? — 이전 커밋 태그의 이미지를 재배포하는 것으로 롤백한다(이미지는 커밋 식별자 태그로 보존).
- EC2 인스턴스가 중지·통신 불가 상태면? — 원격 명령 전달 실패가 배포 실패로 표시된다(성공으로 오인되지 않는다).
- prod 워크플로 파일을 develop 브랜치에서 수정해 실행을 시도하면? — 환경별 자격 증명 분리로 dev 권한으로는 prod 자원에 접근할 수 없다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: develop 브랜치 푸시 시 자동으로 이미지를 빌드(linux/amd64)해 공유 이미지 저장소(ECR `kbap-api`)에 git sha 태그로 올리고, 공용 EC2 의 `api-dev` 컨테이너(:8080)를 그 이미지로 교체해야 한다.
- **FR-002**: staging 브랜치 푸시 시 동일한 흐름으로 `api-staging` 컨테이너(:8081)를 교체하되, 이미지 태그는 git sha 가 아닌 **명시적 릴리스 버전**(저장소의 버전 선언 단일 출처 기반, staging 식별 접미사 포함 — 예: `1.0-rc`)이어야 한다. `latest` 태그는 쓰지 않는다.
- **FR-003**: main 브랜치 푸시 시 ECS 네이티브 블루/그린 배포(CodeDeploy 미사용 — 서비스에 사전 구성된 블루/그린 전략을 태스크정의 교체로 트리거)를 실행하되, 이미지 태그는 **명시적 릴리스 버전 그대로**(예: `1.0`)여야 한다. `latest` 태그는 쓰지 않는다.
- **FR-004**: 환경별 워크플로 파일을 분리(`deploy-dev.yml`·`deploy-staging.yml`·`deploy-prod.yml`)해 실패 추적과 권한을 환경 단위로 격리해야 한다.
- **FR-005**: EC2 컨테이너 교체는 SSH 키 없이 원격 명령(SSM Run Command)으로 수행해야 한다 — 저장소나 CI 에 SSH 개인키를 두지 않는다.
- **FR-006**: 파이프라인 인증은 장기 액세스 키 없이 GitHub OIDC 로 환경별 IAM 역할을 assume 해야 하며, dev/staging 역할에는 이미지 push + 원격 명령 권한만, prod 역할에는 ECS 배포 권한(태스크정의 등록·서비스 갱신)만 부여해 교차 배포를 차단해야 한다. 차단은 **2겹**으로 구성한다 — IAM 신뢰 정책 `sub`(어느 environment 에서 실행됐나) + GitHub Environment 의 deployment branch policy(어느 브랜치가 그 environment 에 배포 가능한가, prod→main·staging→staging·dev→develop). OIDC `sub` 는 브랜치를 담지 않으므로 IAM 만으로는 브랜치를 격리하지 못한다.
- **FR-007**: secrets·환경값은 GitHub Environments(dev/staging/prod)로 분리해 관리해야 한다.
- **FR-008**: 각 배포는 컨테이너(또는 서비스) 교체 후 헬스체크(`/actuator/health`)를 확인하는 단계를 포함해야 하며, 제한 시간 내 정상 응답이 없으면 배포를 실패로 표시해야 한다.
- **FR-009**: 이미지는 환경별 태그 정책(dev=git sha, staging/prod=릴리스 버전)으로 보존되어, 이전 태그(sha 또는 이전 버전)를 재배포하는 것만으로 롤백할 수 있어야 한다 — 릴리스 버전은 배포마다 재사용하지 않고 증가시키는 것을 전제로 한다.
- **FR-010**: 배포 실패(빌드·업로드·원격 명령·헬스체크 어느 단계든)는 실행 이력에 실패로 명확히 남아야 하며, 실패한 배포가 성공으로 표시되어서는 안 된다.

### Key Entities

- **배포 워크플로**: 브랜치(develop/staging/main) → 환경(dev/staging/prod) 매핑 하나당 파일 1개. 트리거 브랜치·대상 환경·자격 증명 범위를 소유한다.
- **컨테이너 이미지**: 배포 아티팩트. 태그 정책은 환경별 — dev 는 커밋 식별자(git sha), staging 은 릴리스 버전+식별 접미사(예: `1.0-rc`), prod 는 릴리스 버전(예: `1.0`). `latest` 미사용. dev·staging·prod 가 동일 저장소(ECR `kbap-api`)를 공유한다.
- **릴리스 버전**: 저장소 안의 단일 출처(버전 파일)로 선언되는 명시적 버전 문자열(예: `1.0`). 릴리스마다 커밋으로 증가시키며 staging·prod 이미지 태그의 근원이 된다.
- **배포 대상 환경**: dev(`api-dev` :8080)·staging(`api-staging` :8081)은 공용 EC2 의 컨테이너, prod 는 ECS 서비스(네이티브 블루/그린 — CodeDeploy 미사용). 환경별로 자격 증명·secrets 가 분리된다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: develop·staging·main 각 브랜치에 대해 푸시(병합) 이후 사람의 추가 조작 0회로 해당 환경 배포가 완료된다 — 각 1회 이상 실제 배포로 검증.
- **SC-002**: 배포 성공 판정은 항상 헬스체크 통과를 포함한다 — 헬스체크 미통과 배포가 성공으로 표시된 사례 0건.
- **SC-003**: dev/staging 자격 증명으로 prod 자원에, prod 자격 증명으로 dev/staging 자원에 접근할 수 없다(권한 거부 확인).
- **SC-004**: 결함 발견 시 이전 태그(dev=커밋 sha, staging/prod=이전 릴리스 버전) 재배포만으로 롤백이 가능하다(추가 빌드 불필요).
- **SC-005**: 로컬 빌드→push→EC2 접속→docker 명령으로 이어지던 수동 배포 절차가 dev·staging 에서 더 이상 필요하지 않다.

## Assumptions

- 공용 EC2, ECR `kbap-api` 저장소, prod ECS 서비스(네이티브 블루/그린 전략·타깃그룹·bake 사전 구성, CodeDeploy 미사용) 인프라는 이미 존재하며 이 작업은 GitHub 측 파이프라인과 그에 필요한 인증(OIDC·IAM 역할·Environments) 구성만 다룬다.
- `staging` 브랜치는 장기 브랜치로 운영된다(현재 없다면 develop 에서 분기해 생성).
- prod 승인 게이트는 이번 범위에서 강제하지 않는다 — GitHub Environments(prod) 구조상 추후 설정만으로 켤 수 있게 한다(Jira: "둘 수 있다").
- 롤백은 자동이 아닌 수동 재배포(이전 태그 지정 재실행)로 충분하다 — 자동 롤백은 범위 밖.
- 릴리스 버전은 저장소의 버전 파일 하나로 관리하고 릴리스마다 사람이 커밋으로 올린다(예: `1.0`→`1.1`) — 같은 버전을 올리지 않고 재배포하면 태그가 덮어써짐을 팀이 인지한다.
- 기존 PR 빌드 CI(`build.yml`)는 그대로 유지되며, 배포 워크플로는 별도 파일로 추가된다.
- 애플리케이션 코드·설정(yml) 변경은 없다 — 헬스체크 엔드포인트(`/actuator/health`)는 이미 노출되어 있다.
