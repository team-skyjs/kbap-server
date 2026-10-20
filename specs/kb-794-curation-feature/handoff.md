# 큐레이션 FE/admin 연결 계약 — 구현 후보

아직 dev 배포 계약이 아니다. 현재 기능 브랜치의 DTO/로컬 검증을 인계하며 dev Swagger URL은 배포 후 실제 조회로 확인해야 한다. API host와 X-API-Version은 기존 환경에 고정한다. 새 경로는1.0+, 새 API 릴리스 번호 없음. 실제 콘텐츠·사진·외부 링크는 별도 승인 입력이다.

## 공개 조회

GET `/api/curations/seoul?lang=en`, GET `/api/curations/seoul/{sights|stations|busan}?lang=en`(KB-825 — 세 번째 모음 부산 명소, 구 restaurants 404). Authorization 불필요. lang/버전헤더 누락과 blank400, 미지원lang→en. 모든 응답 no-store. 기존 /api/home 계약 무변.

index payload: state(PUBLISHED/UNPUBLISHED), sectionVersion, requestedLang(원 요청), resolvedLang, fallbackUsed, collections. 정상게시3카드/정상미게시0카드이며 null/2카드 성공은 없다. 카드 title/description/coverImageUrl/coverImageAttribution(nullable, KB-823)/itemCount10/publishedRevision. 상세는 collectionKey/title/description/coverImageUrl/coverImageAttribution(nullable, KB-823)/itemCount/publishedRevision/publishedAt/lang정보/items를 제공한다. 항목entryId/position1부터/name/area/introduction/recommendationReason/imageUrl/imageAttribution(nullable, KB-823)/externalUrl/nearby 문자열배열. 내부증빙·foodId·위험도·credit 없음.

최초3모음 전 상세404. 최초활성 후 하나내림은 홈숨김+그상세404, 나머지상세200. index미게시 때 홈캐시만 제거하고 detail404일 때 해당상세만 제거한다. 네트워크/5xx는 마지막성공값과 오류를 유지한다. 예외적 번역결손은 전체모음 또는 카드3개가 en으로 fallback; en결손503. 사진CDN 실패가 텍스트/외부CTA를 지우는 이유는 아니다.

## 관리자

prefix `/api/admin/curations/seoul`. 활성 ADMIN 계정과 JWT 재사용, 새역할없음. GET 개요/`/{key}` 상세, PUT `/{key}/draft`, POST `/{key}/validate|publish|unpublish`, POST `/{key}/assets/upload-url`, POST `/{key}/assets/{assetId}/complete`.

mutation 모두 `X-Target-Environment: dev|prod` 필수. 서버프로필 local/dev/staging은dev, prod는prod로 명시 매핑한다. 헤더는 라우팅/권한 선택이 아니다. 누락/형식오류400, 서버환경불일치409. GET에도 environment 제공. 요청 당시 env/host/token/key/version/document를 함께 고정한다.

상세: draftDocument, publishedDocument, version, publishedSourceVersion, publishedRevision, isPublished, 각 published/updated/unpublished 감사 시각·관리자, hasUnpublishedChanges, validationErrors, requiredContentHash, sectionState/sectionVersion, 참조 자산 assets[{assetId,state,previewUrl}]. assets는 초안과 보존 게시본에 있는 ID의 합집합이며 새로고침 후에도 사진을 비교할 수 있다. staging key는 포함하지 않는다. expectedVersion은 음수/누락400, stale409 payload.currentVersion/collectionKey. 응답유실 후 GET 비교하며 무조건 덮어쓰기 금지.

초안문서 title/description locale map, cover, items, editorialReview. 항목 name/area/introduction/recommendationReason locale map, entryId UUID, image, externalUrl, nearby[{nearbyId,label}], evidence. evidence에는 placeIdentity{canonicalName,locationDiscriminator}/researchSources/checkedOn/externalLinkReview{url,finalUrl,checkedOn}/editorialNotes. missing값은 불완전초안으로 저장 가능하며 무효형식·unsupportedlocale·중복entryID·자산소유 위반은 저장400. 본문1MiB/50항목, 개별길이는 승인원문 그대로.

사진 `{assetId,rights}`. rights는 basis/sourceOrEvidenceRef/rightsHolder/permittedUse/checkedOn/checkedAssetId/attributionRequirement/attributionText. 확인된 assetId를 checkedAssetId에 함께 남긴다. NONE·TEXT만게시(KB-823): TEXT는 attributionText가 비공백·80자이하·Unicode 범주 Cc·Cf·Zl·Zp 문자(개행·U+2028·U+2029·양방향 재정렬·폭 0 포함) 없음이어야 하고 위반은 ATTRIBUTION_TEXT_INVALID, LINK/UNKNOWN/null/누락은 ATTRIBUTION_UNSUPPORTED. TEXT 문구만 공개 coverImageAttribution·imageAttribution 으로 내려가고 NONE이면 null. NONE·TEXT도 필수증빙이 없으면 차단. 문구 규칙(80자·문자 범주)은 게시 시점(validate·publish)에만 검사하고 공개 조회는 재검사하지 않는다 — 규칙을 강화하면 기존 게시본은 그대로 노출되므로 해당 모음을 재검수·재게시해야 한다. 서버검증은 법적권리 인증이 아니다.

검수 흐름: 콘텐츠편집→PUT초안→응답requiredContentHash로 현재내용검토→editorialReview `{contentHash:requiredContentHash,checkedOn:"YYYY-MM-DD"}` 추가한 PUT→validate→publish. hash는 editorialReview 자신을 제외한 저장문서의 canonical SHA256이다. 별도검수권한은 없다. JSON검수 필드 외 내용·URL·사진·증빙 변경 시 hash가 달라져 기존확인이 무효다. 링크 review.url은 실제externalUrl과 동일해야 한다. 날짜는 ISO날짜이고 미래값거절. 클라이언트가 서버직렬화 hash를 자체 재현할 필요 없이 상세/validate 응답값을 쓴다.

validate는 `{expectedVersion}`으로 저장본만 검사하고 invalid도200(valid=false/errors/version/requiredContentHash). publish는 같은 요청형식으로 다시검사하며 실패400(CURATION-002, errors/version), 기존게시본보존. errors는 path/reason/entryId?/locale?; 문자열message대신 reason으로 자체문구매핑. 다른enum입력은역직렬화400.

## 사진 업로드

예약 `{expectedVersion,contentType,contentLength}` → assetId/uploadUrl/method/requiredHeaders/contentLength/expiresAt/completeDeadline. JPEG/PNG/WebP 최대10MiB. presign5분, 발급만료+1일 고정완료기한, 시각은 offset을 가진Instant. collection내용version증가없음. requiredHeaders에브라우저금지Content-Length없음. S3 PUT에ADMIN토큰/X-API-Version/X-Target-Environment를전달하지않는다.

완료 `{contentType,size}` → assetId/stateREADY/previewUrl. 발급관리자만완료가능, 이후다른활성관리자도같은모음READY자산편집가능. 완료가초안에자동첨부되지않는다. staging은 `curation/{dev|prod}/staging/UUID`, 최종은 `curation/{dev|prod}/final/UUID`이며 final에presign을발급하지않는다. HEAD형식/크기/ETag와 CopySourceIfMatch를검사하고 외부복사후DB잠금으로채택한다. 재시도는같은final, 패자복사본은채택되지않고기존final을삭제하지않는다.

원본없음/소유·형식·기한위반400(CURATION-005), storage의존실패503(CURATION-006). 단 운영(dev·prod)에서는 업로드 없이 complete 하면 503(CURATION-006) — 조건부 ListBucket 이 HEAD 에 적용되지 않아 404 대신 403(#422 결정 A, 승인 계약 오류표 아래 메모). 실제새prefix IAM/CORS/CDN/S3정책은로컬fake/SDK요청검증으로검증되지않는다. 운영연동은별도실측필요. 자동cleanup추가없음; 현재별도prefix를기존업로드정리대상에넣지않는다. 미참조복사본·staging정리는7일유예/모든draft와보존published참조보호/dry-run기술검증후별도작업이다.
