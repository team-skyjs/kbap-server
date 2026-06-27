package com.meogo.application.scan

import com.meogo.domain.scan.MenuItemAssessment

interface MenuItemRiskAssessor {
    fun assess(index: Int, rawMenuName: String): MenuItemAssessment
}
