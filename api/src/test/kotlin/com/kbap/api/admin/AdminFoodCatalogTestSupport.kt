package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

abstract class AdminFoodCatalogTestSupport : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    protected lateinit var tokenIssuer: TokenIssuer

    @Autowired
    protected lateinit var dataSource: DataSource

    @Autowired
    protected lateinit var adminFoodService: AdminFoodService

    @Autowired
    protected lateinit var foodContentOutboxJpaRepository: FoodContentOutboxJpaRepository

    @Autowired
    protected lateinit var foodVectorOutboxJpaRepository: FoodVectorOutboxJpaRepository

    protected val path = "/api/admin/foods"

    protected val spaOrigin = "https://kbap-admin.pages.dev"

    protected val mapper = jacksonObjectMapper()

    protected fun clearFoods() = TestTables.clearAll(dataSource)

    protected fun saveFood(koreanName: String, contentStatus: FoodContentStatus = FoodContentStatus.READY): Food =
        foodJpaRepository.save(
            Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = contentStatus),
        )

    protected fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

    protected fun adminAuth(request: MockHttpServletRequestDsl) {
        request.header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}")
    }

    protected fun getList(query: String = "", token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
        mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

    protected fun preflight(target: String, origin: String): ResultActionsDsl =
        mockMvc.options(target) {
            header("Origin", origin)
            header("Access-Control-Request-Method", "GET")
            header("Access-Control-Request-Headers", "authorization,x-api-version")
        }

    protected fun getDetail(id: Long): ResultActionsDsl = mockMvc.get("$path/$id") { adminAuth(this) }

    protected fun putUpdate(id: Long, body: Map<String, Any?>): ResultActionsDsl =
        mockMvc.put("$path/$id") {
            adminAuth(this)
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }

    protected fun updateBody(koreanName: String, version: Long = 0): Map<String, Any?> = mapOf(
        "koreanName" to koreanName,
        "description" to "설명",
        "spiciness" to 1,
        "contentStatus" to "READY",
        "version" to version,
    )

    protected fun recollectOne(id: Long): ResultActionsDsl = mockMvc.post("$path/$id/recollect") { adminAuth(this) }

    protected fun recollectBulk(query: String = ""): ResultActionsDsl =
        mockMvc.post("$path/recollect$query") { adminAuth(this) }

    protected fun deleteFood(id: Long): ResultActionsDsl = mockMvc.delete("$path/$id") { adminAuth(this) }

    protected fun getDeletedList(query: String = ""): ResultActionsDsl =
        mockMvc.get("$path/deleted$query") { adminAuth(this) }

    protected fun getDeletedDetail(id: Long): ResultActionsDsl = mockMvc.get("$path/deleted/$id") { adminAuth(this) }

    protected fun postRestore(id: Long): ResultActionsDsl = mockMvc.post("$path/$id/restore") { adminAuth(this) }

    protected fun hasPendingOutbox(foodId: Long, operation: FoodVectorOutboxOperation): Boolean =
        foodVectorOutboxJpaRepository.existsByFoodIdAndOperationAndOutboxStatus(
            foodId,
            operation,
            FoodVectorOutboxStatus.PENDING,
        )

    protected fun hasPendingUpsertOutbox(foodId: Long): Boolean =
        hasPendingOutbox(foodId, FoodVectorOutboxOperation.UPSERT)

    init {
        beforeContainer { clearFoods() }
        afterSpec { clearFoods() }
    }
}
