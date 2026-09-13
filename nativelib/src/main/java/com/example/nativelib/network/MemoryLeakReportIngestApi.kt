package com.example.nativelib.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/** 内存泄漏报告 multipart 接收 API。 */
internal interface MemoryLeakReportIngestApi {
    @Multipart
    @POST("ingest/v1/memory-reports")
    suspend fun ingest(
        @Part metadata: MultipartBody.Part,
        @Part report: MultipartBody.Part,
    ): Response<MemoryLeakReportUploadResponse>
}
