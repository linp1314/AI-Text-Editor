package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {

    private fun multiStatus(vararg responses: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
        ${responses.joinToString("\n")}
        </d:multistatus>""".trimIndent()

    private fun fileResponse(href: String, size: Long, modified: String = "Wed, 12 Nov 2026 10:10:10 GMT"): String =
        """<d:response>
            <d:href>$href</d:href>
            <d:propstat><d:prop>
                <d:getcontentlength>$size</d:getcontentlength>
                <d:getlastmodified>$modified</d:getlastmodified>
                <d:resourcetype/>
            </d:prop></d:propstat>
        </d:response>"""

    private fun dirResponse(href: String): String =
        """<d:response>
            <d:href>$href</d:href>
            <d:propstat><d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop></d:propstat>
        </d:response>"""

    @Test
    fun `parses files and skips the collection itself`() {
        val xml = multiStatus(
            dirResponse("/dav/AiTextEditor/"),
            fileResponse("/dav/AiTextEditor/aieditor-20261112-101010.json", 1024),
            fileResponse("/dav/AiTextEditor/aieditor-20261111-090000.json", 2048),
        )
        val files = WebDavClient.parseMultiStatus(xml)

        assertEquals(2, files.size)
        assertEquals("aieditor-20261112-101010.json", files[0].name)
        assertEquals(1024L, files[0].size)
        assertEquals("Wed, 12 Nov 2026 10:10:10 GMT", files[0].modified)
        assertEquals("/dav/AiTextEditor/aieditor-20261112-101010.json", files[0].href)
    }

    @Test
    fun `decodes percent-encoded names but keeps plus signs`() {
        val xml = multiStatus(
            fileResponse("/dav/%E6%B5%8B%E8%AF%95+aieditor.json", 10),
        )
        val files = WebDavClient.parseMultiStatus(xml)

        assertEquals(1, files.size)
        assertEquals("测试+aieditor.json", files[0].name)
    }

    @Test
    fun `handles namespace prefix and absolute href`() {
        val xml = """<?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>https://dav.example.com/dav/AiTextEditor/aieditor-1.json</D:href>
                <D:propstat><D:prop>
                  <D:getcontentlength>64</D:getcontentlength>
                  <D:resourcetype></D:resourcetype>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>"""
        val files = WebDavClient.parseMultiStatus(xml)

        assertEquals(1, files.size)
        assertEquals("aieditor-1.json", files[0].name)
        assertTrue(files[0].href.startsWith("https://"))
    }

    @Test
    fun `empty body yields no files`() {
        assertTrue(WebDavClient.parseMultiStatus("").isEmpty())
    }

    @Test
    fun `decodeHref leaves plain strings untouched`() {
        assertEquals("/dav/a.json", WebDavClient.decodeHref("/dav/a.json"))
        assertFalse(WebDavClient.decodeHref("/dav/a%20b.json").contains("%20"))
    }
}
