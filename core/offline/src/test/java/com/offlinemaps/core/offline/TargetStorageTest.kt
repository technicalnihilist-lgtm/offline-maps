package com.offlinemaps.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The folder check that tells a removed card apart from a dropped connection (OPTIMIZATION.md I-06). */
class TargetStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a writable folder is usable`() {
        val dir = temp.newFolder("packages")
        assertTrue(TargetStorage.isUsable(dir))
        assertNull(TargetStorage.problem(dir))
    }

    @Test
    fun `a folder that does not exist is not usable`() {
        val dir = temp.newFolder("packages").also { it.delete() }
        assertFalse(TargetStorage.isUsable(dir))
        assertEquals(TargetStorage.UNAVAILABLE_SENTENCE, TargetStorage.problem(dir))
    }

    @Test
    fun `a regular file where the folder should be is not usable`() {
        val file = temp.newFile("packages")
        assertFalse("a file cannot be downloaded into", TargetStorage.isUsable(file))
        assertEquals(TargetStorage.UNAVAILABLE_SENTENCE, TargetStorage.problem(file))
    }

    @Test
    fun `the sentence names the storage and what to do about it`() {
        val sentence = TargetStorage.UNAVAILABLE_SENTENCE
        assertTrue(sentence.contains("storage"))
        assertTrue(sentence.contains("try again"))
        // It must not be mistaken for the network message it replaces.
        assertFalse(sentence.contains("Network", ignoreCase = true))
    }
}
