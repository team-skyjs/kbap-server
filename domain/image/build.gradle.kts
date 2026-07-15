// image 도메인 컨텍스트(업로드 이미지 완료 검증·소유 기록, KB-138). 리프 — 도메인 간 의존 없음.
// 공통 설정(core 의존 + 영속)은 컨벤션 플러그인에서 온다. 스토리지 접근은 :core 의 StorageObjectStore seam.
plugins {
    id("kbap.domain-conventions")
}
