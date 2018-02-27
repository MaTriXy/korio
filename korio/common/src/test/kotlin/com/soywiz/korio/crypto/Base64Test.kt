package com.soywiz.korio.crypto

import com.soywiz.korio.lang.Charsets
import com.soywiz.korio.lang.UTF8
import com.soywiz.korio.lang.toByteArray
import com.soywiz.korio.lang.toString
import kotlin.test.Test
import kotlin.test.assertEquals

class Base64Test {
	@Test
	fun name() {
		assertEquals("AQID", Base64.encode(byteArrayOf(1, 2, 3)))
		assertEquals("aGVsbG8=", Base64.encode("hello".toByteArray()))
		assertEquals("aGVsbG8=", Base64.encode("hello", UTF8))
		assertEquals(byteArrayOf(1, 2, 3).toList(), Base64.decode("AQID").toList())
		assertEquals("hello", Base64.decode("aGVsbG8=").toString(Charsets.UTF_8))
	}
}