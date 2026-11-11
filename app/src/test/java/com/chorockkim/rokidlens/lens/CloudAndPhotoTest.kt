package com.chorockkim.rokidlens.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudAndPhotoTest {
    @Test
    fun serverAnswerBecomesItems() {
        val items = CloudTranslator.parse(
            """{"model":"gemini-2.5-flash-lite","paragraphs":[
                {"source":"味噌ラーメン 950円","translated":"된장 라멘 950엔","from":"JA"},
                {"source":"안내","translated":"Information","from":"ko"},
                {"source":"x","translated":"","from":"en"}]}"""
        )!!
        assertEquals(2, items.size)
        assertEquals(LensPipeline.Item("味噌ラーメン 950円", "된장 라멘 950엔", "ja", "ko"), items[0])
        // Korean text was translated into English.
        assertEquals("en", items[1].to)
    }

    @Test
    fun malformedAnswersFallBackToOffline() {
        assertNull(CloudTranslator.parse("not json"))
        assertNull(CloudTranslator.parse("""{"detail":"too many photos"}"""))
        assertEquals(emptyList<LensPipeline.Item>(), CloudTranslator.parse("""{"paragraphs":[]}"""))
    }

    @Test
    fun photosAreDecodedSmall() {
        // MAX_SIDE 2048: halve only while the result stays at least that big.
        assertEquals(1, Photo.sampleSize(1920, 1080))
        assertEquals(1, Photo.sampleSize(4000, 3000))
        assertEquals(2, Photo.sampleSize(8000, 6000))
        assertEquals(4, Photo.sampleSize(16000, 12000))
    }
}
