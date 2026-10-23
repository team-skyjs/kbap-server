# 큐레이션 게시·사진 확정 흐름

이 문서는 기능 브랜치의 구현을 설명한다. 실제 dev/prod 기능 배포나 사진 권리 승인을 뜻하지 않는다.

## UC-1. 저장된 초안 게시

- **트리거:** 활성 관리자가 현재 version의 초안을 검수하고 publish를 요청한다.
- **사용 컨텍스트:** admin, curation. 권한·환경·내용 검증에 실패하면 기존 게시본을 유지한다.

```mermaid
sequenceDiagram
    actor Admin
    participant Client
    participant API as api.admin
    participant Curation as api.curation
    participant DB as MySQL
    Admin->>Client: 현재 초안 검수
    Client->>API: publish expectedVersion / target environment
    API->>Curation: 관리자 주체와 요청 전달
    Note over Curation,DB: 하나의 트랜잭션, 기본 REPEATABLE READ
    Curation->>DB: 활성 ADMIN 확인
    Curation->>DB: root 잠금
    Curation->>DB: 고정 3모음을 ID순 현재 잠금 조회
    Curation->>DB: 대상 asset을 ID순 잠금
    Curation->>Curation: expectedVersion, 10항목, 10언어, 권리, URL, 검수 hash
    alt 검증 또는 버전 실패
        Curation-->>API: 400 errors 또는 409 currentVersion
        Note over Curation,DB: 게시 스냅샷 교체 없음
    else 유효한 초안
        Curation->>DB: 깊은 게시 스냅샷과 감사 정보 저장
        opt 최초 세 모음이 모두 현재 게시 중이며 유효
            Curation->>DB: firstActivatedAt 설정
        end
        Curation->>DB: section version 증가 / commit
        Curation-->>Client: 관리 상세와 전체 공개 상태
    end
```

관리자 확인이 이전 읽기 스냅샷을 만들 수 있으므로 다른 모음의 상태는 잠금 조회로 읽는다. 공개 GET은 별도 readOnly 트랜잭션에서 일관된 스냅샷을 조회한다. 최초 활성화 후 하나를 내리면 홈은 숨지만 나머지 게시 상세는 유지한다.

## UC-2. 임시 사진을 변경 불가능한 최종 사진으로 확정

- **트리거:** 발급 관리자가 staging PUT 뒤 complete를 요청한다.
- **사용 컨텍스트:** admin, curation, storage. final 경로에는 presigned PUT을 발급하지 않는다.

```mermaid
sequenceDiagram
    actor Admin
    participant API as api.curation
    participant DB as MySQL
    participant Storage as api.infra.storage
    participant S3
    Admin->>API: complete assetId / MIME / size
    API->>DB: 짧은 읽기 TX로 소유권과 고정 기한 확인
    alt 이미 READY
        API-->>Admin: 기존 final previewUrl
    else ISSUED
        Note over API,S3: 외부 호출 동안 DB 트랜잭션 없음
        API->>Storage: inspect staging
        Storage->>S3: HEAD
        S3-->>API: MIME / size / ETag
        API->>Storage: 새 final UUID로 조건부 복사
        Storage->>S3: CopyObject + CopySourceIfMatch
        alt 원본 변경 또는 스토리지 장애
            API-->>Admin: 실패, READY 채택 없음
        else 복사 성공
            API->>Storage: final HEAD로 형식과 크기 확인
            API->>DB: 짧은 TX에서 asset 잠금 및 권한·기한 재확인
            alt 다른 요청이 먼저 확정
                DB-->>API: 먼저 채택한 final
            else 아직 ISSUED이며 유효
                API->>DB: final / fingerprint / READY 저장
            end
            API-->>Admin: 채택된 final previewUrl
        end
    end
```

패자의 복사본이나 DB 채택 실패 후 객체는 미참조로 남으며 승자의 final을 삭제하지 않는다. 자동 정리 실행은 추가하지 않았다. 후속 정리는 정확한 전용 prefix, 고정 기한과 유예 기간, 초안 및 내린 게시 스냅샷의 참조 보호를 검증한 뒤 진행해야 한다.
