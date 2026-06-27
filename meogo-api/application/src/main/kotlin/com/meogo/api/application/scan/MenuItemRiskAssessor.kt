package com.meogo.api.application.scan

import com.meogo.api.scan.MenuItemAssessment

interface MenuItemRiskAssessor {
    fun assess(index: Int, rawMenuName: String): MenuItemAssessment
}
