package com.meogo.domain.scan

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

class BoundingBoxTest : StringSpec({

    "유효한 정규화 좌표는 생성된다" {
        shouldNotThrowAny {
            BoundingBox(x = 0.12, y = 0.34, width = 0.5, height = 0.08)
            BoundingBox(x = 0.0, y = 0.0, width = 1.0, height = 1.0)
        }
    }

    "x 가 음수면 예외" {
        shouldThrow<IllegalArgumentException> { BoundingBox(x = -0.1, y = 0.0, width = 0.5, height = 0.5) }
    }

    "y 가 음수면 예외" {
        shouldThrow<IllegalArgumentException> { BoundingBox(x = 0.0, y = -0.1, width = 0.5, height = 0.5) }
    }

    "width 가 0 이면 예외" {
        shouldThrow<IllegalArgumentException> { BoundingBox(x = 0.0, y = 0.0, width = 0.0, height = 0.5) }
    }

    "height 가 0 이면 예외" {
        shouldThrow<IllegalArgumentException> { BoundingBox(x = 0.0, y = 0.0, width = 0.5, height = 0.0) }
    }

    "x + width 가 1 을 초과하면 예외" {
        shouldThrow<IllegalArgumentException> { BoundingBox(x = 0.8, y = 0.0, width = 0.5, height = 0.5) }
    }

    "y + height 가 1 을 초과하면 예외" {
        shouldThrow<IllegalArgumentException> { BoundingBox(x = 0.0, y = 0.8, width = 0.5, height = 0.5) }
    }
})
