/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

/**
 * XMP packets: the XML in which photos and videos keep ratings, edits, captions and, often, a copy of the location.
 *
 * Location properties are blanked with spaces of the same length: whitespace between XML attributes and elements
 * changes nothing, so the packet stays valid and nothing around it moves (docs/PLAN.md §6.4).
 */
internal object Xmp {

    /** Edits, relative to the packet's first byte, that blank every location property in [packet]. */
    fun locationEdits(packet: ByteArray): List<Edit> {
        // Byte offsets stay character offsets: XML syntax is ASCII, and other bytes are only ever replaced 1:1.
        val text = String(packet, Charsets.ISO_8859_1)
        if (packet.take(NARROW_CHECK).any { it == 0.toByte() }) malformed("XMP not in UTF-8")
        val namespaces = NAMESPACE.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        return Scanner(text) { name -> isLocation(name, namespaces) }.blankRanges()
            .map { (start, end) -> Edit.overwrite(start.toLong(), ByteArray(end - start) { SPACE }) }
    }

    /** A valid packet that says nothing, padded with spaces to exactly [length] bytes (all spaces if too short). */
    fun emptyPacket(length: Int): ByteArray {
        val head = EMPTY_HEAD.toByteArray(Charsets.UTF_8)
        val tail = EMPTY_TAIL.toByteArray(Charsets.UTF_8)
        if (length < head.size + tail.size) return ByteArray(length) { SPACE }
        return head + ByteArray(length - head.size - tail.size) { SPACE } + tail
    }

    private fun isLocation(qualifiedName: String, namespaces: Map<String, String>): Boolean {
        val local = qualifiedName.substringAfter(':')
        if (local.startsWith("GPS") || local.startsWith("Gps")) return true // Any namespace: cameras, drones.
        val namespace = namespaces[qualifiedName.substringBefore(':', "")] ?: return false
        return local in LOCATION_FIELDS[namespace].orEmpty()
    }

    private val NAMESPACE = Regex("""xmlns:([A-Za-z_][\w.\-]*)\s*=\s*["']([^"']*)["']""")

    private val LOCATION_FIELDS = mapOf(
        "http://ns.adobe.com/photoshop/1.0/" to setOf("City", "State", "Country"),
        "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/" to setOf("Location", "CountryCode"),
        "http://iptc.org/std/Iptc4xmpExt/2008-02-29/" to setOf("LocationCreated", "LocationShown"),
    )

    private const val SPACE = ' '.code.toByte()
    private const val NARROW_CHECK = 64
    private const val EMPTY_HEAD =
        "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
    private const val EMPTY_TAIL = "<?xpacket end=\"w\"?>"
}

/** Finds the character ranges of attributes and elements whose name [matches], in a forgiving single pass. */
private class Scanner(private val text: String, private val matches: (String) -> Boolean) {
    private val ranges = mutableListOf<Pair<Int, Int>>()

    fun blankRanges(): List<Pair<Int, Int>> {
        var i = 0
        while (true) {
            i = text.indexOf('<', i)
            if (i == -1) return ranges
            i = when {
                text.startsWith("<!--", i) -> after("-->", i)
                text.startsWith("<![CDATA[", i) -> after("]]>", i)
                text.startsWith("<?", i) || text.startsWith("<!", i) -> after(">", i)
                text.startsWith("</", i) -> after(">", i)
                else -> startTag(i)
            }
        }
    }

    /** Reads the start tag at [at]; returns where scanning continues. */
    private fun startTag(at: Int): Int {
        val tag = readTag(at)
        if (!matches(tag.name)) {
            tag.attributes.filter { (name, _) -> !name.startsWith("xmlns") && matches(name) }
                .forEach { (_, range) -> ranges += range }
            return tag.end
        }
        val end = if (tag.selfClosing) tag.end else closingEnd(tag.name, tag.end)
        ranges += at to end
        return end
    }

    private class Tag(
        val name: String,
        val attributes: List<Pair<String, Pair<Int, Int>>>,
        val selfClosing: Boolean,
        val end: Int,
    )

    private fun readTag(at: Int): Tag {
        var i = at + 1
        while (i < text.length && isNameChar(text[i])) i++
        val name = text.substring(at + 1, i)
        val attributes = mutableListOf<Pair<String, Pair<Int, Int>>>()
        while (true) {
            i = skipWhitespace(i)
            when {
                i >= text.length -> malformed("XMP ends in a tag")

                text[i] == '>' -> return Tag(name, attributes, selfClosing = false, end = i + 1)

                text.startsWith("/>", i) -> return Tag(name, attributes, selfClosing = true, end = i + 2)

                else -> readAttribute(i).let { (attribute, end) ->
                    attributes += attribute to (i to end)
                    i = end
                }
            }
        }
    }

    /** The attribute at [at]: its name and where it ends, after its quoted value. */
    private fun readAttribute(at: Int): Pair<String, Int> {
        var i = at
        while (i < text.length && text[i] != '=' && isNameChar(text[i])) i++
        val name = text.substring(at, i)
        i = skipWhitespace(i)
        if (text.getOrNull(i) != '=') malformed("XMP attribute without a value")
        i = skipWhitespace(i + 1)
        val quote = text.getOrNull(i)
        if (quote != '"' && quote != '\'') malformed("XMP attribute value not quoted")
        val close = text.indexOf(quote, i + 1).takeIf { it != -1 } ?: malformed("XMP attribute value not closed")
        return name to close + 1
    }

    private fun isNameChar(c: Char) = !c.isWhitespace() && c != '/' && c != '>'

    private fun skipWhitespace(from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** The end of the element named [name] whose content starts at [from], with nested ones of the same name. */
    private fun closingEnd(name: String, from: Int): Int {
        var depth = 1
        var i = from
        while (depth > 0) {
            i = text.indexOf('<', i).takeIf { it != -1 } ?: malformed("XMP element $name not closed")
            when {
                isTag("</$name", i) -> {
                    depth--
                    i = after(">", i)
                }

                text.startsWith("<!--", i) -> i = after("-->", i)

                text.startsWith("<![CDATA[", i) -> i = after("]]>", i)

                isTag("<$name", i) -> {
                    val nested = readTag(i)
                    if (!nested.selfClosing) depth++
                    i = nested.end
                }

                else -> i++
            }
        }
        return i
    }

    /** Whether [start], such as `<exif:GPSLatitude`, is at [at] as a whole name (not `<exif:GPSLatitudeRef`). */
    private fun isTag(start: String, at: Int): Boolean {
        if (!text.startsWith(start, at)) return false
        val next = text.getOrNull(at + start.length)
        return next == '>' || next == '/' || next?.isWhitespace() == true
    }

    private fun after(token: String, from: Int): Int =
        text.indexOf(token, from).takeIf { it != -1 }?.plus(token.length) ?: malformed("XMP not closed: $token")
}
