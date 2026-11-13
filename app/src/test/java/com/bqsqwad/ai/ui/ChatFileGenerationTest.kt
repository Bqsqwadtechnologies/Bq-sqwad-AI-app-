package com.bqsqwad.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFileGenerationTest {
    @Test
    fun recognizesExplicitFileCreationRequests() {
        assertTrue(isChatFileGenerationRequest("Create a JSON file containing three sample users"))
        assertTrue(isChatFileGenerationRequest("Write a Python script that sorts a CSV"))
        assertFalse(isChatFileGenerationRequest("How do I create a JSON file?"))
        assertFalse(isChatFileGenerationRequest("Explain what a Python script does"))
    }

    @Test
    fun infersCommonFileFormatsAndNames() {
        val json = chatGeneratedFileSpec("Create a JSON file named \"users\" with three records")
        assertEquals("json", json.extension)
        assertEquals("application/json", json.mimeType)
        assertEquals("users", json.title)

        val python = chatGeneratedFileSpec("Create app.py, a Python script that prints hello")
        assertEquals("py", python.extension)
        assertEquals("text/x-python", python.mimeType)

        val csv = chatGeneratedFileSpec("Generate a CSV file of sample products")
        assertEquals("csv", csv.extension)
        assertEquals("text/csv", csv.mimeType)
    }

    @Test
    fun stripsOneOuterMarkdownCodeFenceForSavedFile() {
        assertEquals("print('hello')\n", normalizeGeneratedFileContent("```python\nprint('hello')\n```"))
        assertEquals("{\"ok\":true}\n", normalizeGeneratedFileContent("{\"ok\":true}"))
    }
}
