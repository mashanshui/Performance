package com.example.nativelib.network

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

internal interface JankArtifactIngestApi {
    @POST("ingest/v1/stack-artifacts:parse")
    suspend fun ingest(@Body artifact: RequestBody): Response<JankArtifactUploadResponse>
}
