package com.github.libretube.api.lounge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairingResponse(
    val screens: List<LoungeScreen> = emptyList()
)

@Serializable
data class ScreenResponse(
    val screen: LoungeScreen? = null
)

@Serializable
data class LoungeScreen(
    @SerialName("screenId") val screenId: String = "",
    @SerialName("loungeToken") val loungeToken: String = "",
    @SerialName("name") val name: String? = null
)
