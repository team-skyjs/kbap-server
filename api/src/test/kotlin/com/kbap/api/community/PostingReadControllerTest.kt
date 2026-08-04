package com.kbap.api.community

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class PostingReadControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val path = "/api/v1/community/posts"

        fun execute(sql: String, vararg params: Any?): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(sql).use { ps ->
                    params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                    ps.executeUpdate()
                }
            }

        fun clearPostings(): Unit = execute("DELETE FROM community_post")

        fun seedMember(memberId: Long, profileJson: String = """{"countryCode":"KR"}"""): Unit =
            execute(
                """
                INSERT INTO member (id, provider, provider_uid, nickname, profile, member_status,
                                    onboarding_completed, status, created_at, updated_at)
                VALUES (?, 'GOOGLE', ?, ?, ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
                memberId,
                "feed-test-$memberId",
                "피드테스터$memberId",
                profileJson,
            )

        fun withdrawMember(memberId: Long): Unit =
            execute("UPDATE member SET status = 'DELETED' WHERE id = ?", memberId)

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String, translationsJson: String = "{}"): Unit =
            execute(
                """
                INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                                  description_translations, avoidance_substances, content_status, status,
                                  created_at, updated_at)
                VALUES (?, ?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
                id,
                koreanName,
                translationsJson,
            )

        fun seedPosting(
            id: Long,
            memberId: Long,
            content: String = "글 $id",
            imageRefsJson: String? = null,
            foodIdsJson: String? = null,
        ): Unit =
            execute(
                """
                INSERT INTO community_post (id, member_id, content, image_refs, food_ids, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))
                """,
                id,
                memberId,
                content,
                imageRefsJson,
                foodIdsJson,
            )

        fun deletePosting(id: Long): Unit =
            execute("UPDATE community_post SET status = 'DELETED' WHERE id = ?", id)

        fun feed(token: String?, lang: String? = "en", cursor: String? = null): ResultActionsDsl =
            mockMvc.get(path) {
                lang?.let { param("lang", it) }
                cursor?.let { param("cursor", it) }
                token?.let { header("Authorization", "Bearer $it") }
            }

        fun detail(token: String?, postId: Long, lang: String? = "en"): ResultActionsDsl =
            mockMvc.get("$path/$postId") {
                lang?.let { param("lang", it) }
                token?.let { header("Authorization", "Bearer $it") }
            }

        given("게스트 GET 의 JWT 필터 통과") {
            `when`("게스트가 피드 목록을 조회하면") {
                then("401 로 차단되지 않는다") {
                    feed(token = null).andReturn().response.status shouldNotBe 401
                }
            }

            `when`("게스트가 글 상세를 조회하면") {
                then("401 로 차단되지 않는다") {
                    detail(token = null, postId = 999999L).andReturn().response.status shouldNotBe 401
                }
            }
        }

        given("빈 피드") {
            clearPostings()

            `when`("회원이 피드를 조회하면") {
                then("빈 목록과 다음 페이지 없음을 반환한다") {
                    feed(accessToken(9490L)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(0) }
                        jsonPath("$.payload.hasNext") { value(false) }
                        jsonPath("$.payload.nextCursor") { value(null) }
                    }
                }
            }
        }

        given("피드 목록 API — GET /api/v1/community/posts") {
            clearPostings()
            seedMember(9400L)
            seedFood(9401L, "피드김치찌개", """{"en":"Feed Kimchi Stew"}""")
            seedFood(9402L, "피드된장찌개")
            (1..24).forEach { seedPosting(940000L + it, memberId = 9400L) }
            seedPosting(
                940025L,
                memberId = 9400L,
                content = "커버와 태그가 있는 글",
                imageRefsJson = """["images/community/2026/08/9400_cover.jpg","images/community/2026/08/9400_second.jpg"]""",
                foodIdsJson = "[9401,9402]",
            )

            `when`("회원이 커서 없이 조회하면") {
                then("최신 20건과 다음 커서를 반환한다") {
                    feed(accessToken(9490L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.items[0].postId") { value(940025) }
                        jsonPath("$.payload.items[0].author.memberId") { value(9400) }
                        jsonPath("$.payload.items[0].author.nickname") { value("피드테스터9400") }
                        jsonPath("$.payload.items[0].content") { value("커버와 태그가 있는 글") }
                        jsonPath("$.payload.items[0].imageUrls.length()") { value(2) }
                        jsonPath("$.payload.items[0].imageUrls[0]") {
                            value("https://cdn.test/images/community/2026/08/9400_cover.jpg")
                        }
                        jsonPath("$.payload.items[0].foodTags.length()") { value(2) }
                        jsonPath("$.payload.items[0].foodTags[0].foodId") { value(9401) }
                        jsonPath("$.payload.items[0].foodTags[0].name") { value("Feed Kimchi Stew") }
                        jsonPath("$.payload.items[0].likeCount") { value(0) }
                        jsonPath("$.payload.items[0].dislikeCount") { value(0) }
                        jsonPath("$.payload.items[0].commentCount") { value(0) }
                        jsonPath("$.payload.items[1].postId") { value(940024) }
                        jsonPath("$.payload.items[1].imageUrls.length()") { value(0) }
                        jsonPath("$.payload.items[1].foodTags.length()") { value(0) }
                        jsonPath("$.payload.hasNext") { value(true) }
                        jsonPath("$.payload.nextCursor") { value(940006) }
                    }
                }
            }

            `when`("다음 커서로 이어 조회하면") {
                then("겹치지 않는 나머지를 반환하고 끝을 알린다") {
                    feed(accessToken(9490L), cursor = "940006").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(5) }
                        jsonPath("$.payload.items[0].postId") { value(940005) }
                        jsonPath("$.payload.items[4].postId") { value(940001) }
                        jsonPath("$.payload.hasNext") { value(false) }
                        jsonPath("$.payload.nextCursor") { value(null) }
                    }
                }
            }

            `when`("번역이 없는 언어로 조회하면") {
                then("음식 태그 이름이 한국어로 폴백된다") {
                    feed(accessToken(9490L), lang = "ja").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].foodTags[0].name") { value("피드김치찌개") }
                        jsonPath("$.payload.items[0].foodTags[1].name") { value("피드된장찌개") }
                    }
                }
            }

            `when`("lang 을 누락하면") {
                then("400 으로 거절한다") {
                    feed(accessToken(9490L), lang = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("커서 형식이 잘못되면") {
                then("400 FOOD-002 로 거절한다") {
                    feed(accessToken(9490L), cursor = "abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                    feed(accessToken(9490L), cursor = "-1").andExpect { status { isBadRequest() } }
                }
            }

            `when`("글이 삭제되면") {
                then("피드에서 사라진다") {
                    deletePosting(940024L)

                    feed(accessToken(9490L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.items[0].postId") { value(940025) }
                        jsonPath("$.payload.items[1].postId") { value(940023) }
                        jsonPath("$.payload.nextCursor") { value(940005) }
                    }
                }
            }
        }

        given("게스트 첫 페이지 게이트") {
            clearPostings()
            seedMember(9500L)
            (1..45).forEach { seedPosting(950000L + it, memberId = 9500L) }

            `when`("게스트가 1페이지를 조회하면") {
                then("회원과 동일한 형태로 반환한다") {
                    feed(token = null).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.items[0].postId") { value(950045) }
                        jsonPath("$.payload.items[0].author.memberId") { value(9500) }
                        jsonPath("$.payload.hasNext") { value(true) }
                        jsonPath("$.payload.nextCursor") { value(950026) }
                    }
                }
            }

            `when`("게스트가 다음 페이지 커서로 조회하면") {
                then("401 COMMUNITY-005 로 거절한다") {
                    feed(token = null, cursor = "950026").andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("COMMUNITY-005") }
                    }
                }
            }

            `when`("게스트가 임의의 깊은 커서를 넣으면") {
                then("401 COMMUNITY-005 로 거절한다") {
                    feed(token = null, cursor = "950001").andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.code") { value("COMMUNITY-005") }
                    }
                }
            }

            `when`("회원이 같은 커서로 조회하면") {
                then("제한 없이 반환한다") {
                    feed(accessToken(9590L), cursor = "950026").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.hasNext") { value(true) }
                    }
                }
            }

            `when`("위조 토큰으로 조회하면") {
                then("게스트로 강등하지 않고 토큰 오류로 거절한다") {
                    feed(token = "forged-token").andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.code") { value("AUTH-003") }
                    }
                }
            }

            `when`("Bearer 형식이 아닌 인증 헤더로 조회하면") {
                then("게스트로 취급한다") {
                    mockMvc.get(path) {
                        param("lang", "en")
                        header("Authorization", "Token abc")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(20) }
                    }
                }
            }
        }

        given("게스트 게이트 — 글이 한 페이지보다 적으면") {
            clearPostings()
            seedMember(9501L)
            (1..10).forEach { seedPosting(950100L + it, memberId = 9501L) }

            `when`("게스트가 첫 페이지를 조회하면") {
                then("게이트 없이 전부 보고 정상 종료한다") {
                    feed(token = null).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(10) }
                        jsonPath("$.payload.hasNext") { value(false) }
                        jsonPath("$.payload.nextCursor") { value(null) }
                    }
                }
            }
        }

        given("글 상세 API — GET /api/v1/community/posts/{postId}") {
            clearPostings()
            seedMember(9600L)
            seedFood(9601L, "상세김치찌개", """{"en":"Detail Kimchi Stew"}""")
            seedPosting(
                960001L,
                memberId = 9600L,
                content = "상세 본문",
                imageRefsJson = """["images/community/2026/08/9600_1.jpg","images/community/2026/08/9600_2.jpg","images/community/2026/08/9600_3.jpg","images/community/2026/08/9600_4.jpg"]""",
                foodIdsJson = "[9601]",
            )
            seedPosting(960002L, memberId = 9600L, content = "삭제될 글")

            `when`("회원이 상세를 조회하면") {
                then("본문·사진 전체·태그·카운트를 반환한다") {
                    detail(accessToken(9690L), 960001L).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.postId") { value(960001) }
                        jsonPath("$.payload.author.memberId") { value(9600) }
                        jsonPath("$.payload.content") { value("상세 본문") }
                        jsonPath("$.payload.imageUrls.length()") { value(4) }
                        jsonPath("$.payload.foodTags[0].name") { value("Detail Kimchi Stew") }
                        jsonPath("$.payload.likeCount") { value(0) }
                        jsonPath("$.payload.dislikeCount") { value(0) }
                        jsonPath("$.payload.commentCount") { value(0) }
                    }
                }
            }

            `when`("게스트가 상세를 조회하면") {
                then("회원과 동일하게 반환한다(게이트 없음)") {
                    detail(token = null, postId = 960001L).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.postId") { value(960001) }
                        jsonPath("$.payload.author.memberId") { value(9600) }
                        jsonPath("$.payload.imageUrls.length()") { value(4) }
                    }
                }
            }

            `when`("삭제된 글을 조회하면") {
                then("400 COMMUNITY-001 로 거절한다") {
                    deletePosting(960002L)

                    detail(token = null, postId = 960002L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("존재하지 않는 글을 조회하면") {
                then("400 COMMUNITY-001 로 거절한다") {
                    detail(token = null, postId = 99999999L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("lang 을 누락하면") {
                then("400 으로 거절한다") {
                    detail(token = null, postId = 960001L, lang = null).andExpect { status { isBadRequest() } }
                }
            }
        }

        given("탈퇴 작성자의 글 숨김") {
            clearPostings()
            seedMember(9700L)
            seedMember(9701L, profileJson = """{"countryCode":"KR","profileImageUrl":"images/profile/9701.jpg"}""")
            seedPosting(970001L, memberId = 9700L, content = "탈퇴 전에 쓴 글")
            seedPosting(970002L, memberId = 9701L, content = "활성 회원 글")
            withdrawMember(9700L)

            `when`("피드를 조회하면") {
                then("탈퇴 작성자의 글은 목록에서 제외된다") {
                    feed(accessToken(9790L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].postId") { value(970002) }
                        jsonPath("$.payload.items[0].author.memberId") { value(9701) }
                        jsonPath("$.payload.items[0].author.nickname") { value("피드테스터9701") }
                        jsonPath("$.payload.items[0].author.profileImageUrl") {
                            value("https://cdn.test/images/profile/9701.jpg")
                        }
                    }
                }
            }

            `when`("탈퇴 작성자의 글 상세를 조회하면") {
                then("400 COMMUNITY-001 로 거절한다") {
                    detail(token = null, postId = 970001L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("활성 회원의 글 상세를 조회하면") {
                then("정상 반환한다") {
                    detail(token = null, postId = 970002L).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.author.memberId") { value(9701) }
                    }
                }
            }
        }
    }
}
