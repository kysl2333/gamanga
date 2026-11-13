package app.gamanga.desktop.source

import kotlin.test.*
import kotlinx.serialization.json.*

class BangumiClientTest {
    @Test fun searchParsesEnvelopeAndRejectsNovelsAndAnimation() {
        val page = BangumiClient.parsePage("""{"total":4,"data":[
            {"id":1,"type":1,"name":"原名","name_cn":"漫画","platform":"漫画","images":{"large":"https://example.test/cover.jpg"},"rating":{"score":8.5,"rank":9,"count":{"10":12}}},
            {"id":2,"type":1,"name":"小说","platform":"小说","tags":[{"name":"漫画"}]},
            {"id":3,"type":2,"name":"动画","platform":"TV"},
            {"id":4,"type":1,"name":"漫画2","book_category":1001}
        ]}""")
        assertEquals(4, page.total)
        assertEquals(listOf(1, 4), page.items.map { it.id })
        assertEquals(9, page.items.first().rank)
        assertEquals(12, page.items.first().votes[10])
    }
    @Test fun parsesInfoboxVariantsAndCollections() {
        val item = BangumiClient.parseItem(Json.parseToJsonElement("""{"id":1,"name":"原名","infobox":[{"key":"作者","value":"某作者"},{"key":"别名","value":[{"v":"名字A"},{"v":"名字B"}]}],"collection":{"wish":12,"doing":34}}""").jsonObject)!!
        assertEquals("原名", item.title)
        assertEquals("名字A、名字B", item.info[1].second)
        assertEquals(34, item.collections["doing"])
    }
    @Test fun absentPlatformIsNotAssumedComic() {
        assertFalse(BangumiClient.isComic(Json.parseToJsonElement("""{"id":1,"type":1,"tags":[{"name":"漫画"}]}""").jsonObject))
    }
    @Test fun searchTitleRemovesVolumeSuffix() {
        assertEquals("作品", BangumiClient.searchTitle("作品 第12卷"))
        assertEquals("作品", BangumiClient.searchTitle("作品 (3)"))
    }
    @Test fun legacyArrayAndPagedRankingBothParse() {
        assertEquals(1, BangumiClient.parseRanking("""[{"id":1,"name":"书"}]""").size)
        assertEquals(1, BangumiClient.parseRanking("""{"data":[{"id":1,"name":"书"}]}""").size)
    }
}
