package com.github.libretube.ui.views

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for lounge sync helpers via reflection to avoid coupling to the view.
 */
class LoungeSyncGuardsTest {

    private fun buildSignature(
        videoId: String,
        index: Int,
        queue: List<String>,
        listId: String?,
        params: String?,
        playerParams: String?
    ): String = buildLoungeQueueSignature(videoId, index, queue, listId, params, playerParams)

    @Test
    fun signatureChangesWithQueueOrder() {
        val base = buildSignature("vid", 0, listOf("a", "b", "c"), null, null, null)
        val reordered = buildSignature("vid", 0, listOf("b", "a", "c"), null, null, null)
        assertNotEquals("Queue order must affect signature", base, reordered)
    }

    @Test
    fun signatureChangesWithIndexAndListId() {
        val s1 = buildSignature("vid", 0, listOf("vid"), null, null, null)
        val s2 = buildSignature("vid", 1, listOf("vid"), null, null, null)
        val s3 = buildSignature("vid", 0, listOf("vid"), "list", "p", "pp")
        assertNotEquals("Index change must affect signature", s1, s2)
        assertNotEquals("Playlist params must affect signature", s1, s3)
        // identical inputs -> identical signature
        val s4 = buildSignature("vid", 0, listOf("vid"), "list", "p", "pp")
        assertEquals(s3, s4)
    }
}
