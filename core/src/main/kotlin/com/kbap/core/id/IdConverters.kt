package com.kbap.core.id

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

abstract class IdConverter<T>(
    private val wrap: (Long) -> T,
    private val unwrap: (T) -> Long,
) : AttributeConverter<T, Long> {
    override fun convertToDatabaseColumn(attribute: T?): Long? = attribute?.let(unwrap)

    override fun convertToEntityAttribute(dbData: Long?): T? = dbData?.let(wrap)
}

@Converter(autoApply = true)
class FoodIdConverter : IdConverter<FoodId>(::FoodId, FoodId::value)

@Converter(autoApply = true)
class MemberIdConverter : IdConverter<MemberId>(::MemberId, MemberId::value)
