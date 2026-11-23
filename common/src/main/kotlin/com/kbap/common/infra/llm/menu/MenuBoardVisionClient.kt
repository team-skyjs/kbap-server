package com.kbap.common.infra.llm.menu

import com.kbap.common.port.llm.MenuBoardLineExtractor
import com.kbap.common.port.llm.MenuBoardImageClient
import com.kbap.common.port.llm.ProcessedFoodPhotoReader

// 비전 빈 하나가 세 계약을 함께 노출한다 — 공급자 스위치(kbap.llm.vision.provider)가 고른 구현이 이 타입으로 등록된다.
interface MenuBoardVisionClient : MenuBoardImageClient, MenuBoardLineExtractor, ProcessedFoodPhotoReader
