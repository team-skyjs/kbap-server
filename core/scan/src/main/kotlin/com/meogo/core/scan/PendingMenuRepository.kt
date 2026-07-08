package com.meogo.core.scan

interface PendingMenuRepository {
    fun enqueue(name: String)
}
