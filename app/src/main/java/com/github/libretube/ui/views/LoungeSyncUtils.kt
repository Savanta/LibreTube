package com.github.libretube.ui.views

internal fun buildLoungeQueueSignature(
    videoId: String,
    currentIndex: Int,
    queueIds: List<String>,
    listId: String?,
    params: String?,
    playerParams: String?
): String {
    return listOf(
        videoId,
        currentIndex.toString(),
        queueIds.joinToString("|"),
        listId.orEmpty(),
        params.orEmpty(),
        playerParams.orEmpty()
    ).joinToString("#")
}
