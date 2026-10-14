#!/usr/bin/env python3
# Copyright 2026 The MediaSync Authors
# SPDX-License-Identifier: Apache-2.0
"""
Creates the golden media set for the Photo details tests (docs/PLAN.md §6.5) in
src/test/resources/golden. Everything is generated here (images, videos, the colour profile), so the set is
free of third-party content. Needs ImageMagick (magick), exiftool, ffmpeg (libx264, libx265) and macOS sips.

    python3 core/metadata/golden/make_golden.py

Each file carries details of every kind the setting removes: GPS in EXIF, location in XMP and IPTC, video
location, camera details, captions, a thumbnail. The tests check what's left with an independent reader.
"""
import os
import struct
import subprocess
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "testFixtures", "resources", "golden")

LAT, LON, ALT = "47.3769", "8.5417", "408"
TAKEN = "2026:10:14 09:30:00"


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def exiftool(path, *tags):
    run("exiftool", "-q", "-overwrite_original", *tags, path)


# A small but valid sRGB ICC profile (v2), written from scratch.
def srgb_icc():
    def s15(v):
        return struct.pack(">i", round(v * 65536))

    def xyz(x, y, z):
        return b"XYZ " + b"\0" * 4 + s15(x) + s15(y) + s15(z)

    def desc(text):
        ascii_ = text.encode() + b"\0"
        return (b"desc" + b"\0" * 4 + struct.pack(">I", len(ascii_)) + ascii_ + b"\0" * 4 + b"\0" * 4
                + b"\0" * 2 + b"\0" + b"\0" * 67)

    curve = b"curv" + b"\0" * 4 + struct.pack(">I", 1) + struct.pack(">H", int(2.2 * 256)) + b"\0\0"
    tags = [
        (b"desc", desc("MediaSync test sRGB")),
        (b"cprt", b"text" + b"\0" * 4 + b"No copyright, generated for tests\0"),
        (b"wtpt", xyz(0.9642, 1.0, 0.8249)),
        (b"rXYZ", xyz(0.4361, 0.2225, 0.0139)),
        (b"gXYZ", xyz(0.3851, 0.7169, 0.0971)),
        (b"bXYZ", xyz(0.1431, 0.0606, 0.7141)),
        (b"rTRC", curve),
        (b"gTRC", curve),
        (b"bTRC", curve),
    ]
    table_size = 4 + 12 * len(tags)
    offset = 128 + table_size
    table, data = struct.pack(">I", len(tags)), b""
    for signature, body in tags:
        while (offset + len(data)) % 4:
            data += b"\0"
        table += signature + struct.pack(">II", offset + len(data), len(body))
        data += body
    size = 128 + table_size + len(data)
    header = (struct.pack(">I", size) + b"none" + bytes([2, 0x10, 0, 0]) + b"mntr" + b"RGB " + b"XYZ "
              + b"\0" * 12 + b"acsp" + b"APPL" + b"\0" * 4 + b"none" + b"none" + b"\0" * 8 + b"\0" * 4
              + s15(0.9642) + s15(1.0) + s15(0.8249) + b"none" + b"\0" * 44)
    return header + table + data


PHOTO_TAGS = [
    "-EXIF:DateTimeOriginal=" + TAKEN, "-EXIF:OffsetTimeOriginal=+02:00", "-EXIF:SubSecTimeOriginal=123",
    "-EXIF:CreateDate=" + TAKEN, "-EXIF:ModifyDate=" + TAKEN, "-EXIF:Orientation#=6", "-EXIF:ColorSpace#=1",
    "-EXIF:ImageDescription=Lake at dawn", "-EXIF:Artist=Anna", "-EXIF:SerialNumber=A1B2C3",
    "-EXIF:LensModel=Main camera", "-EXIF:FNumber=1.7", "-EXIF:ExposureTime=1/120", "-EXIF:ISO=100",
    "-GPSLatitude=" + LAT, "-GPSLatitudeRef=N", "-GPSLongitude=" + LON, "-GPSLongitudeRef=E",
    "-GPSAltitude=" + ALT, "-GPSAltitudeRef#=0", "-GPSDateStamp=2026:10:14", "-GPSTimeStamp=07:30:00",
    "-GPSImgDirection=123", "-GPSProcessingMethod=fused",
    "-XMP-photoshop:City=Zurich", "-XMP-photoshop:State=ZH", "-XMP-photoshop:Country=Switzerland",
    "-XMP-iptcCore:Location=Seefeld", "-XMP-iptcCore:CountryCode=CH",
    "-XMP-iptcExt:LocationCreatedCity=Zurich", "-XMP-iptcExt:LocationShownSublocation=Bellevue",
    "-XMP-exif:GPSLatitude=" + LAT + " N", "-XMP-exif:GPSLongitude=" + LON + " E", "-XMP-exif:GPSAltitude=" + ALT,
    "-XMP-dc:Description=Lake at dawn", "-XMP-dc:Subject=holiday", "-XMP-xmp:Rating=4",
    "-XMP-xmp:CreatorTool=MediaSync tests",
]
IPTC_TAGS = [
    "-IPTC:City=Zurich", "-IPTC:Sub-location=Seefeld", "-IPTC:Province-State=ZH",
    "-IPTC:Country-PrimaryLocationCode=CHE", "-IPTC:Country-PrimaryLocationName=Switzerland",
    "-IPTC:Caption-Abstract=Lake at dawn", "-IPTC:Keywords=holiday", "-IPTC:By-line=Anna",
]


def make_image(path, size="64x48", colors="#d04020-#2040d0"):
    run("magick", "-size", size, "gradient:" + colors, "-quality", "92", path)


def mp4_with_location(path, rotate=None):
    args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
    if rotate is not None:
        args += ["-display_rotation", str(rotate)]
    args += ["-f", "lavfi", "-i", "testsrc=size=64x48:rate=10", "-t", "1", "-c:v", "libx264", "-pix_fmt",
             "yuv420p", "-metadata", "location=+47.3769+008.5417/", "-metadata",
             "creation_time=2026-10-14T07:30:00Z", path]
    run(*args)
    # Android phones write ©xyz, ffmpeg writes loci: carry both.
    exiftool(path, "-UserData:GPSCoordinates=" + LAT + ", " + LON + ", " + ALT, "-UserData:Make=Google",
             "-UserData:Model=Pixel 8")


def jpeg_segments(data):
    """(marker, start, end) of each segment before SOS, and the position of SOS."""
    assert data[:2] == b"\xff\xd8"
    i, segments = 2, []
    while True:
        marker = data[i + 1]
        if marker == 0xDA:
            return segments, i
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        segments.append((marker, i, i + 2 + length))
        i += 2 + length


def mpf_segment(primary_size, gain_map_offset, gain_map_size):
    """An APP2 MPF segment (big-endian) listing a primary image and a gain map."""
    entries = 3
    ifd = struct.pack(">H", entries)
    mp_entry_offset = 8 + 2 + entries * 12 + 4
    ifd += struct.pack(">HHI", 0xB000, 7, 4) + b"0100"
    ifd += struct.pack(">HHII", 0xB001, 4, 1, 2)
    ifd += struct.pack(">HHII", 0xB002, 7, 32, mp_entry_offset)
    ifd += struct.pack(">I", 0)
    mp_entries = struct.pack(">IIIHH", 0x030000, primary_size, 0, 0, 0)
    mp_entries += struct.pack(">IIIHH", 0, gain_map_size, gain_map_offset, 0, 0)
    tiff = b"MM\x00\x2a" + struct.pack(">I", 8) + ifd + mp_entries
    payload = b"MPF\0" + tiff
    return b"\xff\xe2" + struct.pack(">H", len(payload) + 2) + payload


def ultra_hdr(path, work):
    primary, gain_map = os.path.join(work, "primary.jpg"), os.path.join(work, "gain.jpg")
    make_image(primary)
    exiftool(primary, *PHOTO_TAGS, "-XMP-hdrgm:Version=1.0")
    run("magick", "-size", "32x24", "gradient:#ffffff-#000000", "-colorspace", "Gray", "-quality", "90", gain_map)
    exiftool(gain_map, "-XMP-hdrgm:Version=1.0", "-XMP-hdrgm:GainMapMax=2.0")
    primary_bytes, gain_bytes = open(primary, "rb").read(), open(gain_map, "rb").read()
    segments, _ = jpeg_segments(primary_bytes)
    insert_at = segments[-1][2]
    placeholder = mpf_segment(0, 0, 0)
    primary_size = len(primary_bytes) + len(placeholder)
    mpf_tiff_start = insert_at + 4 + 4
    mpf = mpf_segment(primary_size, primary_size - mpf_tiff_start, len(gain_bytes))
    with open(path, "wb") as f:
        f.write(primary_bytes[:insert_at] + mpf + primary_bytes[insert_at:] + gain_bytes)


MOTION_XMP = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
 xmlns:Container="http://ns.google.com/photos/1.0/container/"
 xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
 xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
 GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="0"
 photoshop:City="Zurich">
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"
 Item:Padding="0"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto"
 Item:Length="%d" Item:Padding="0"/></rdf:li>
</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""


def motion_photo(path, work):
    video = os.path.join(work, "motion.mp4")
    mp4_with_location(video)
    video_bytes = open(video, "rb").read()
    xmp = os.path.join(work, "motion.xmp")
    open(xmp, "w").write(MOTION_XMP % len(video_bytes))
    make_image(path)
    exiftool(path, "-xmp<=" + xmp)
    exiftool(path, *[t for t in PHOTO_TAGS if not t.startswith("-XMP")])
    with open(path, "ab") as f:
        f.write(video_bytes)


def samsung(path, work):
    make_image(path, colors="#20a040-#a02040")
    exiftool(path, *[t for t in PHOTO_TAGS if not t.startswith("-XMP")], "-EXIF:Make=samsung",
             "-EXIF:Model=SM-S911B")
    video = os.path.join(work, "samsung.mp4")
    mp4_with_location(video)
    name = b"MotionPhoto_Data"
    block = b"\x00\x00\x30\x0a" + struct.pack("<I", len(name)) + name + open(video, "rb").read()
    directory = b"SEFH" + struct.pack("<II", 107, 1) + struct.pack("<HHII", 0, 0x0A30, len(block), len(block))
    trailer = block + directory + struct.pack("<I", len(directory)) + b"SEFT"
    with open(path, "ab") as f:
        f.write(trailer)


def main():
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        icc = os.path.join(work, "srgb.icc")
        open(icc, "wb").write(srgb_icc())
        thumb = os.path.join(work, "thumb.jpg")
        run("magick", "-size", "16x12", "gradient:#d04020-#2040d0", thumb)

        pixel = os.path.join(OUT, "pixel.jpg")
        make_image(pixel)
        exiftool(pixel, "-ICC_Profile<=" + icc, *PHOTO_TAGS, *IPTC_TAGS, "-EXIF:Make=Google",
                 "-EXIF:Model=Pixel 8", "-ThumbnailImage<=" + thumb)

        samsung(os.path.join(OUT, "samsung.jpg"), work)
        motion_photo(os.path.join(OUT, "motion.jpg"), work)
        ultra_hdr(os.path.join(OUT, "ultrahdr.jpg"), work)

        whatsapp = os.path.join(OUT, "whatsapp.jpg")
        make_image(whatsapp, colors="#808020-#208080")
        run("magick", whatsapp, "-strip", whatsapp)

        png = os.path.join(OUT, "screenshot.png")
        run("magick", "-size", "64x48", "gradient:#40c0c0-#c04040", "-strip", png)
        exiftool(png, "-ICC_Profile<=" + icc, *PHOTO_TAGS, "-PNG:Software=Screenshots", "-PNG:Comment=Shared")

        webp = os.path.join(OUT, "photo.webp")
        run("magick", "-size", "64x48", "gradient:#c0c040-#4040c0", "-quality", "90", webp)
        exiftool(webp, "-ICC_Profile<=" + icc, *PHOTO_TAGS)

        heic_source = os.path.join(work, "heic.jpg")
        make_image(heic_source, colors="#c08040-#4080c0")
        heic = os.path.join(OUT, "photo.heic")
        run("sips", "-s", "format", "heic", heic_source, "--out", heic)
        exiftool(heic, *PHOTO_TAGS, "-EXIF:Make=Google", "-EXIF:Model=Pixel 8")

        tiff = os.path.join(work, "raw.tif")
        run("magick", "-size", "64x48", "gradient:#606060-#a0a0a0", "-compress", "none", tiff)
        dng = os.path.join(OUT, "raw.dng")
        os.replace(tiff, dng)
        exiftool(dng, "-IFD0:DNGVersion=1.4.0.0", "-IFD0:Make=Google", "-IFD0:Model=Pixel 8", *PHOTO_TAGS)

        mp4_with_location(os.path.join(OUT, "video.mp4"), rotate=90)

        mov = os.path.join(OUT, "video.mov")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "testsrc=size=64x48:rate=10", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags",
            "use_metadata_tags", "-metadata", "com.apple.quicktime.location.ISO6709=+47.3769+008.5417+408.000/",
            "-metadata", "com.apple.quicktime.make=Apple", "-metadata", "com.apple.quicktime.model=iPhone 15",
            "-metadata", "com.apple.quicktime.creationdate=2026-10-14T09:30:00+0200", "-metadata",
            "creation_time=2026-10-14T07:30:00Z", mov)

        hdr = os.path.join(OUT, "hdr.mp4")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "testsrc=size=64x48:rate=10", "-t", "1", "-c:v", "libx265", "-pix_fmt", "yuv420p10le", "-x265-params",
            "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc", "-color_primaries",
            "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-tag:v", "hvc1", "-metadata",
            "location=+47.3769+008.5417/", "-metadata", "creation_time=2026-10-14T07:30:00Z", hdr)
        exiftool(hdr, "-UserData:GPSCoordinates=" + LAT + ", " + LON + ", " + ALT)

    for name in sorted(os.listdir(OUT)):
        print("%-16s %7d bytes" % (name, os.path.getsize(os.path.join(OUT, name))))


if __name__ == "__main__":
    main()
