package com.videograbber.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkResolverTest {

    @Test
    fun extractsKwaiFromArabicPromoText() {
        assertEquals(
            "https://k.kwai.com/p/eC7BAtZ5",
            LinkResolver.clean("أظن أن هذا الفيديو يعجبك!\nhttps://k.kwai.com/p/eC7BAtZ5"),
        )
    }

    @Test
    fun prefersVideoOverStoreLink() {
        assertEquals(
            "https://k.kwai.com/p/ArnMS5C4",
            LinkResolver.clean(
                "Install https://play.google.com/store/apps/details?id=com.kwai.video " +
                    "then watch https://k.kwai.com/p/ArnMS5C4",
            ),
        )
    }

    @Test
    fun supportsLikeeShortLinks() {
        assertEquals(
            "https://likee.video/v/k6QcOp",
            LinkResolver.clean("Watch now: https://likee.video/v/k6QcOp،"),
        )
    }

    @Test
    fun selectsVideoWhenArabicShareTextContainsSeveralLinks() {
        assertEquals(
            "https://k.kwai.com/p/eYhACn6G",
            LinkResolver.clean(
                "من يرغب في الرقص؟ 🤣\n" +
                    "https://play.google.com/store/apps/details?id=com.kwai.video\n" +
                    "https://k.kwai.com/p/eYhACn6G\n" +
                    "https://www.kwai.com/",
            ),
        )
    }

    @Test
    fun removesInvisibleBidiControls() {
        assertEquals(
            "https://vt.tiktok.com/ZSabc/",
            LinkResolver.clean("\u202Bhttps://vt.tiktok.com/ZSabc/\u202C"),
        )
    }

    @Test
    fun keepsImportantQueryParameters() {
        assertEquals(
            "https://youtube.com/watch?v=abc&list=xyz",
            LinkResolver.clean(
                "https://youtube.com/watch?v=abc&list=xyz&utm_source=share",
            ),
        )
    }
}
