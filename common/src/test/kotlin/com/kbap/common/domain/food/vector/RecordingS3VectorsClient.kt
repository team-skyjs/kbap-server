package com.kbap.common.domain.food.vector

import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.DeleteVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.DeleteVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.GetOutputVector
import software.amazon.awssdk.services.s3vectors.model.GetVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.GetVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.PutVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.QueryOutputVector
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse

class RecordingS3VectorsClient : S3VectorsClient {
    val puts = mutableListOf<PutVectorsRequest>()
    val gets = mutableListOf<GetVectorsRequest>()
    val deletes = mutableListOf<DeleteVectorsRequest>()
    val queries = mutableListOf<QueryVectorsRequest>()

    var stored: List<GetOutputVector> = emptyList()
    var matches: List<QueryOutputVector> = emptyList()

    override fun serviceName(): String = "s3vectors"

    override fun close() = Unit

    override fun putVectors(request: PutVectorsRequest): PutVectorsResponse {
        puts += request
        return PutVectorsResponse.builder().build()
    }

    override fun getVectors(request: GetVectorsRequest): GetVectorsResponse {
        gets += request
        return GetVectorsResponse.builder().vectors(stored).build()
    }

    override fun deleteVectors(request: DeleteVectorsRequest): DeleteVectorsResponse {
        deletes += request
        return DeleteVectorsResponse.builder().build()
    }

    override fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse {
        queries += request
        return QueryVectorsResponse.builder().vectors(matches).build()
    }
}
