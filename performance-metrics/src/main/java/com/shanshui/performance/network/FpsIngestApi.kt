package com.shanshui.performance.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

/** FPS 指标批量接收 API。 */
internal interface FpsIngestApi {
    /** 将 frame_scene_summary v2 批次提交到服务端。 */
    @Headers("Content-Type: application/json")
    @POST("ingest/v1/batches")
    suspend fun ingest(
        @Header("X-Schema-Version") schemaVersion: Int,
        @Body request: FpsBatchRequest,
    ): Response<FpsBatchResponse>
}
