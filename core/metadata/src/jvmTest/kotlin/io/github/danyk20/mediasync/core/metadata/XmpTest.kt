/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class XmpTest {

    private fun blank(xmp: String): String {
        val bytes = xmp.toByteArray(Charsets.UTF_8)
        val out = apply(bytes, Xmp.locationEdits(bytes))
        out.size shouldBe bytes.size
        return String(out, Charsets.UTF_8)
    }

    private fun description(namespaces: String, body: String, attributes: String = "") =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" $namespaces $attributes>$body</rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test
    fun attributesAndElementsAreBlankedWherever() {
        val xmp = description(
            """xmlns:ps="http://ns.adobe.com/photoshop/1.0/" xmlns:exif="http://ns.adobe.com/exif/1.0/"""",
            "<exif:GPSLatitude>47,22.6N</exif:GPSLatitude><ps:Credit>Anna</ps:Credit>",
            """ps:City='Zürich' exif:GPSAltitude="408/1" ps:Headline="Lake"""",
        )

        val out = blank(xmp)

        listOf("Zürich", "City", "GPS", "47,22").forEach { out shouldNotContain it }
        out shouldContain """ps:Headline="Lake""""
        out shouldContain "<ps:Credit>Anna</ps:Credit>"
        out shouldContain """xmlns:ps="http://ns.adobe.com/photoshop/1.0/""""
    }

    @Test
    fun structuresAreBlankedWholeEvenWhenNested() {
        val xmp = description(
            """xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"""",
            """<Iptc4xmpExt:LocationShown><rdf:Bag><rdf:li rdf:parseType="Resource">""" +
                """<Iptc4xmpExt:City>Zurich</Iptc4xmpExt:City>""" +
                """</rdf:li><rdf:li><Iptc4xmpExt:LocationShown/></rdf:li></rdf:Bag></Iptc4xmpExt:LocationShown>""" +
                """<!-- <Iptc4xmpExt:LocationShown> in a comment --><Iptc4xmpExt:Event>Dawn</Iptc4xmpExt:Event>""",
        )

        val out = blank(xmp)

        out shouldNotContain "Zurich"
        out shouldNotContain "rdf:Bag"
        out shouldContain "<Iptc4xmpExt:Event>Dawn</Iptc4xmpExt:Event>"
    }

    @Test
    fun similarNamesInOtherNamespacesStay() {
        val xmp = description(
            """xmlns:my="http://example.com/my/1.0/"""",
            "<my:City>Not a location field</my:City><my:Country>Neither</my:Country>",
        )

        blank(xmp) shouldBe xmp
    }

    @Test
    fun anythingNamedGpsGoesWhateverItsNamespace() {
        val xmp = description(
            namespaces = """xmlns:drone-dji="http://www.dji.com/drone-dji/1.0/"""",
            body = "",
            attributes = """drone-dji:GpsLatitude="47.37"""",
        )

        blank(xmp) shouldNotContain "47.37"
    }

    @Test
    fun brokenPacketsAreNotGuessedAt() {
        shouldThrow<MalformedMediaException> { Xmp.locationEdits("<a b=c>".toByteArray()) }
        shouldThrow<MalformedMediaException> { Xmp.locationEdits("<exif:GPSLatitude>1".toByteArray()) }
        val utf16 = "<\u0000x\u0000m\u0000p".toByteArray(Charsets.ISO_8859_1)
        shouldThrow<MalformedMediaException> { Xmp.locationEdits(utf16) }
    }

    @Test
    fun anEmptyPacketFillsExactlyItsSpace() {
        Xmp.emptyPacket(200).size shouldBe 200
        String(Xmp.emptyPacket(200), Charsets.UTF_8) shouldContain "<x:xmpmeta"
        Xmp.emptyPacket(10).toList() shouldBe List(10) { ' '.code.toByte() }
    }
}
