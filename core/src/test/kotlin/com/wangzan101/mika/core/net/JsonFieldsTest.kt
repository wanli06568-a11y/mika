package com.wangzan101.mika.core.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonFieldsTest {
    private val o = Json.parseToJsonElement("""{"s":"a","n":3,"b":true,"z":null,"o":{},"a":[]}""") as kotlinx.serialization.json.JsonObject

    @Test fun strOnlyTakesStrings() {
        assertEquals("a", o.str("s"))
        listOf("n", "b", "z", "o", "a", "missing").forEach { assertNull(it, o.str(it)) }
    }

    @Test fun scalarTakesAnyPrimitiveButNull() {
        assertEquals("a", o.scalar("s"))
        assertEquals("3", o.scalar("n"))
        assertEquals("true", o.scalar("b"))
        listOf("z", "o", "a", "missing").forEach { assertNull(it, o.scalar(it)) }
    }

    @Test fun jsonObjectOrNullRejectsBadJsonAndNonObjects() {
        assertEquals("a", Json.jsonObjectOrNull("""{"s":"a"}""")?.str("s"))
        assertNull(Json.jsonObjectOrNull("not json"))
        assertNull(Json.jsonObjectOrNull("[1]"))
        assertNull(Json.jsonObjectOrNull("\"x\""))
    }
}
