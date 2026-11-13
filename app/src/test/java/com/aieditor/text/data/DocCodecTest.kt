package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocCodecTest {

    private val sample = listOf(
        Doc(
            id = 11L,
            title = "钉钉直连.yaml",
            subtitle = "/storage/emulated/0/Download/钉钉直连.yaml",
            uri = "content://com.android.providers.downloads.documents/document/1",
            text = "name: 钉钉\nport: 8080\n",
        ),
        Doc(id = 12L, title = "未命名 2", subtitle = "未命名文档", text = "第二篇的正文"),
    )

    @Test
    fun `round trip keeps every field`() {
        val restored = DocCodec.decode(DocCodec.encode(sample))

        assertEquals(2, restored.size)
        assertEquals("钉钉直连.yaml", restored[0].title)
        assertEquals("/storage/emulated/0/Download/钉钉直连.yaml", restored[0].subtitle)
        assertEquals(sample[0].uri, restored[0].uri)
        assertEquals("name: 钉钉\nport: 8080\n", restored[0].text)
        assertEquals(12L, restored[1].id)
        assertNull(restored[1].uri)
    }

    @Test
    fun `empty list survives round trip`() {
        assertTrue(DocCodec.decode(DocCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `broken json decodes to empty list instead of throwing`() {
        assertTrue(DocCodec.decode("不是 json").isEmpty())
        assertTrue(DocCodec.decode("").isEmpty())
        assertTrue(DocCodec.decode("""{"docs":"坏格式"}""").isEmpty())
    }

    @Test
    fun `duplicate ids are reassigned so rows stay distinct`() {
        val raw = """
            {"schema":1,"docs":[
              {"id":5,"title":"A","text":"一"},
              {"id":5,"title":"B","text":"二"}
            ]}
        """.trimIndent()

        val restored = DocCodec.decode(raw)

        assertEquals(2, restored.size)
        assertNotEquals(restored[0].id, restored[1].id)
    }

    @Test
    fun `reassign gives restored documents new unique ids`() {
        val restored = DocCodec.reassign(sample)

        assertEquals(2, restored.size)
        assertEquals(sample.map { it.text }, restored.map { it.text })
        assertTrue(restored.none { it.id == 11L || it.id == 12L })
        assertEquals(restored.size, restored.map { it.id }.toSet().size)
    }
}
