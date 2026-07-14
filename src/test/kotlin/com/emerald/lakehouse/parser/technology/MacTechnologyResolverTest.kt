package com.emerald.lakehouse.parser.technology

import kotlin.test.Test
import kotlin.test.assertEquals

class MacTechnologyResolverTest {

    @Test
    fun `matches known ImberaLink MAC prefixes regardless of reported technology`() {
        assertEquals("ImberaLink", MacTechnologyResolver.resolve("B4A2EB428B04", "Softel"))
        assertEquals("ImberaLink", MacTechnologyResolver.resolve("001BC5112233", null))
    }

    @Test
    fun `matches known ELTEC MAC prefixes even when reported technology is the lab placeholder`() {
        assertEquals("ELTEC", MacTechnologyResolver.resolve("3CA551AABBCC", "Softel"))
        assertEquals("ELTEC", MacTechnologyResolver.resolve("4827E2AABBCC", "Softel"))
    }

    @Test
    fun `is case-insensitive and tolerates separators in the MAC`() {
        assertEquals("ELTEC", MacTechnologyResolver.resolve("3c:a5:51:aa:bb:cc", "Softel"))
        assertEquals("ELTEC", MacTechnologyResolver.resolve("3c-a5-51-aa-bb-cc", "Softel"))
    }

    @Test
    fun `falls back to the reported technology when it is already a known value and the MAC is unmapped`() {
        assertEquals("ELTEC", MacTechnologyResolver.resolve("AABBCCDDEEFF", "ELTEC"))
        assertEquals("Villa", MacTechnologyResolver.resolve("AABBCCDDEEFF", "Villa"))
    }

    @Test
    fun `falls back to ImberaLink default for an unmapped MAC and an unrecognized reported value`() {
        assertEquals("ImberaLink", MacTechnologyResolver.resolve("AABBCCDDEEFF", "Softel"))
        assertEquals("ImberaLink", MacTechnologyResolver.resolve(null, "Softel"))
        assertEquals("ImberaLink", MacTechnologyResolver.resolve(null, null))
    }
}
