package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 스캔 사진", description = "관리자 전용 — 모든 회원의 스캔 사진 피드")
@SecurityRequirement(name = "bearerAuth")
interface AdminScanPhotoApi {
    @Operation(
        summary = "전체 스캔 사진 피드",
        description = """
            모든 회원의 스캔 사진을 업로드 기록(uploaded_image, 스캔 용도·ACTIVE) 기준 최신 업로드순(업로드 id 내림차순) 24장씩 커서로 조회한다(KB-888).
            - 메뉴판 스캔 사진과 가공식품 스캔 사진(앞면·성분표, KB-964)을 함께 준다 — scanType 으로 구분하고 type 으로 거른다. 가공식품 사진은 kind=SCAN_ONLY·sourceId=null 이고, 이름 스캔 확정으로 연결된 사진만 processedFoodId·processedFoodName 이 있다(연결 기록은 KB-964 배포 이후 확정분부터). 가공식품 사진은 참조가 없으면 고아 정리로 지워져 오래된 것은 안 보일 수 있다.
            - 업로드 한 장당 1건이다. kind 는 참조 관계로 정한다: 주문 사진이면 ORDER(sourceId = 주문 id), 아니고 묶음 스캔 사진이면 BATCH(sourceId = 묶음 id), 둘 다 아니면 SCAN_ONLY(sourceId null — 스캔만 하고 카드는 저장하지 않음).
            - 총 건수는 주지 않는다 — hasNext 와 nextCursor 로 다음 페이지를 읽는다. 잘못된 cursor 는 400(FOOD-002).
            - 탈퇴·정지 회원 사진도 포함한다(memberStatus). 분석용 회원 제외는 excludeMemberIds 로 호출자가 정한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008).
        """,
    )
    fun getScanPhotoFeed(
        @Parameter(description = "true 면 봇 계정 사진만, 없거나 false 면 봇 제외") isBot: Boolean,
        @Parameter(description = "이 회원 사진만") memberId: Long?,
        @Parameter(description = "이 회원들의 사진은 뺀다 — 쉼표 구분 회원 id 목록", example = "10,12,18") excludeMemberIds: List<Long>?,
        @Parameter(description = "이전 응답의 nextCursor") cursor: String?,
        @Parameter(description = "ALL(기본)·MENU·PACKAGED — 그 외 값은 400") type: ScanPhotoTypeFilter,
    ): ResponseEntity<BaseResponse<AdminScanPhotoFeed>>

    @Operation(
        summary = "스캔 사진 한 장의 스캔 결과 기록",
        description = """
            업로드 기록 id(피드 항목의 uploadedImageId)로 그 사진의 스캔 결과 기록(scan_result_log, KB-983)을 최신순 최대 50건 준다.
            - 한 사진이 여러 번 스캔될 수 있다 — 예: 사진 스캔(PFOOD_VISION)이 메뉴판으로 판별해 앱이 같은 사진으로 메뉴판 스캔(MENU_V2)을 이어 부르면 2건이다. 피드의 scanOutcome·uiKind 는 이 중 최신 1건이다.
            - outcome: SUCCESS(결과를 보여 줌)·EMPTY(결과 0건 — 메뉴 없음·후보 없음)·RETAKE(다시 찍기 — SCAN-003·PFOOD-006·사진 스캔 OTHER)·ERROR(그 밖의 실패, errorCode 에 코드).
            - uiKind 는 응답으로 유도한 화면 종류다. 메뉴판: 결과 1건↑ MENU_LIST, 0건 EMPTY. 이름 스캔·사진 스캔 앞면: 후보 있으면 PFOOD_CANDIDATES, 없으면 PFOOD_NO_CANDIDATE. 성분표: LABEL_RESULT. 사진 스캔 MENU: NOT_PACKAGED(앱이 메뉴판 스캔으로 넘김).
            - responseSummary 의 메뉴판 항목 중 foodId 가 null 인 메뉴가 DB 에 없는 메뉴다. items 는 OCR 좌표와 맞은 결과(v1), photoOnly 는 사진에서만 나온 결과(v2 는 전부 photoOnly)다.
            - 기록은 KB-983 배포 이후 스캔부터 남는다. 기록이 없거나 없는 id 면 payload 는 null 이다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008).
        """,
    )
    fun getScanResults(
        @Parameter(description = "업로드 기록 id — 피드 항목의 uploadedImageId") uploadedImageId: Long,
    ): ResponseEntity<BaseResponse<AdminScanResults?>>
}
