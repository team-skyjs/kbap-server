package com.meogo.domain.scan

/**
 * scan 컨텍스트의 DomainRepository(공개 인터페이스). 구현은 infrastructure 어댑터에 은닉한다(헌법 IV).
 */
interface MenuScanRepository {
    fun save(menuScan: MenuScan): MenuScan

    /** 재열람 API 는 없지만 저장 검증·후속 소유권 조회용. */
    fun findById(scanId: Long): MenuScan?
}
