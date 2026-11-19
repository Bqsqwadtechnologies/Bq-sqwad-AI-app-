package com.bqsqwad.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageGenerationTest {
    @Test
    fun recognizesExplicitImageCreationRequests() {
        assertTrue(isImageGenerationRequest("generate image for me"))
        assertTrue(isImageGenerationRequest("generate an image of a sunset over the ocean"))
        assertTrue(isImageGenerationRequest("create a picture of a futuristic city"))
        assertTrue(isImageGenerationRequest("draw a cute cat with wings"))
        assertTrue(isImageGenerationRequest("draw me a cute cat"))
        assertTrue(isImageGenerationRequest("sketch a portrait of a warrior"))
        assertTrue(isImageGenerationRequest("make an illustration of a robot"))
        assertTrue(isImageGenerationRequest("design a logo for a coffee shop"))
        assertTrue(isImageGenerationRequest("render a 3d graphic of a sports car"))
        assertTrue(isImageGenerationRequest("produce an artwork of mountains"))
    }

    @Test
    fun rejectsImageAnalysisAndNonImageRequests() {
        assertFalse(isImageGenerationRequest("describe this picture"))
        assertFalse(isImageGenerationRequest("analyze the photo attached"))
        assertFalse(isImageGenerationRequest("what is an image?"))
        assertFalse(isImageGenerationRequest("tell me a story about an image"))
        assertFalse(isImageGenerationRequest("write a poem about love"))
        assertFalse(isImageGenerationRequest("how do I generate an image in photoshop?"))
    }
}
