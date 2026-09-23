package com.shanshui.performance.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface CrashIngestApi {
    @Headers("Content-Type: application/json")
    @POST("ingest/v1/batches")
    suspend fun ingest(
        @Header("X-Schema-Version") schemaVersion: Int,
        @Body request: CrashBatchRequest,
    ): Response<CrashBatchResponse>
}
