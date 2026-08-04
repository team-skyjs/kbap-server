package com.kbap.common.domain.community

import com.kbap.common.domain.community.model.Posting
import org.springframework.data.jpa.repository.JpaRepository

interface PostingJpaRepository : JpaRepository<Posting, Long>
