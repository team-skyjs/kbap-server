package com.kbap.api.scanbatch

class ScanBatchSlotsBusyException : RuntimeException("스캔 묶음 전역 동시 호출 슬롯이 모두 사용 중")
