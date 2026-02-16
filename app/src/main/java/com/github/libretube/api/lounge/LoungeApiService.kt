package com.github.libretube.api.lounge

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Query

interface LoungeApiService {
    @FormUrlEncoded
    @POST("pairing/get_lounge_token_batch")
    suspend fun getLoungeTokenBatch(
        @Field("pairing_code") pairingCode: String,
        @Field("pairing_code_input") pairingCodeInput: String,
        @Field("access_type") accessType: String = "permanent",
        @Field("app") app: String = "com.google.android.youtube",
        @Field("service") service: String = "client_side",
        @Field("app_version") appVersion: String = "19.08.35",
        @Field("device") device: String = "REMOTE_CONTROL",
        @Field("device_id") deviceId: String,
        @Field("device_model") deviceModel: String,
        @Field("name") name: String,
        @Field("mdx-version") mdxVersion: Int = 3
    ): PairingResponse

    @FormUrlEncoded
    @POST("pairing/get_screen")
    suspend fun getScreen(
        @Field("pairing_code") pairingCode: String,
        @Field("access_type") accessType: String = "permanent"
    ): ScreenResponse

    @FormUrlEncoded
    @POST("bc/bind")
    suspend fun sendAction(
        @Query("device") device: String = "REMOTE_CONTROL",
        @Query("app") app: String = "android-remote",
        @Query("name") clientName: String,
        @Query("id") sessionId: String,
        @Query("gsessionid") gsessionId: String? = null,
        @Query("loungeIdToken") loungeIdToken: String,
        @Query("VER") protocolVersion: Int = 8,
        @Query("v") apiVersion: Int = 2,
        @Query("theme") theme: String = "cl",
        @Query("ui") ui: String = "1",
        @Query("capabilities") capabilities: String = "remote_queue",
        @Query("RID") requestId: String,
        @Query("conn") conn: String = "longpoll",
        @Query("prop") prop: String = "yls",
        @Query("ctype") ctype: String = "lb",
        @Query("t") t: Int = 1,
        @Query("cpn") cpn: String,
        @Query("TYPE") type: String = "xmlhttp",
        @Query("CI") ci: Int = 0,
        @Query("CVER") clientVersion: Int = 1,
        @Query("SID") sid: String? = null,
        @Query("AID") aid: Int? = null,
        @Field("count") count: Int = 0,
        @Field("ofs") ofs: Int = 0,
        @Field("actions") actions: String,
        @FieldMap(encoded = true) formFields: Map<String, String> = emptyMap()
    ): Response<ResponseBody>
}
