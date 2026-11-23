/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

/**
 * What the golden media set comes out as on the JVM before the iPhone port, one line per outcome (the format is the
 * one `GoldenDigestTest` writes). Every platform must reproduce these lines exactly: the redacted bytes by their
 * SHA-256, the capture dates and the names' dates as read by the same parsers. Generated once from the JVM and kept
 * as test data; regenerate only when an outcome is meant to change.
 */
internal object GoldenDigests {
    val REDACTION = """
redaction|pixel.jpg|REMOVE_LOCATION|EDITED:6663:0f237f05543e65085689ad5227fa2f599f70f354910d311f5477bcdbfdf5fef2
redaction|pixel.jpg|REMOVE_ALL|EDITED:1146:d003497592abac25d9c1ab6a62dbe6921dbc09cb21f7a51e0aae6ac762664302
redaction|samsung.jpg|REMOVE_LOCATION|EDITED:4244:d064a57cce96d210331d5c1540a203d8fd180a37cb804723595f0fd676a0f4ab
redaction|samsung.jpg|REMOVE_ALL|EDITED:620:e02aae35c2ab9d49238902cd5d4943a5e2467b9b73c62010cebd546519ec6344
redaction|motion.jpg|REMOVE_LOCATION|EDITED:5122:b64544b2ff0bf0fa4ad8287f4da1f974453249c119b66f673a3303249c3c3450
redaction|motion.jpg|REMOVE_ALL|EDITED:604:5c26be2b3153ee2e147737f80b0589c57bc674f6d7b5faebec360db0009f3455
redaction|ultrahdr.jpg|REMOVE_LOCATION|EDITED:8940:d3aaee37bf59e9da1b3e12069856e227d367398c58a26c8d80c25fdc27c3a0d8
redaction|ultrahdr.jpg|REMOVE_ALL|EDITED:604:5c26be2b3153ee2e147737f80b0589c57bc674f6d7b5faebec360db0009f3455
redaction|whatsapp.jpg|REMOVE_LOCATION|NONE
redaction|whatsapp.jpg|REMOVE_ALL|NONE
redaction|screenshot.png|REMOVE_LOCATION|EDITED:3195:fa0355c511a7d1f8dea17302ac334de60cd704622f08c795835107803333ceb6
redaction|screenshot.png|REMOVE_ALL|EDITED:643:4dea02cdcedd10d6a3ffc51783df134c9466d254c8690bf603c08b2025d2921d
redaction|photo.webp|REMOVE_LOCATION|EDITED:5772:1ee86b4ecb195e04a0c1d513650f1b36d05530438184463ff8c6c0ceb17ad15f
redaction|photo.webp|REMOVE_ALL|EDITED:872:dbda83f7c6f6a32d397235a3febd7344a32af60bf2b242e9e49cd99215a74d3a
redaction|photo.heic|REMOVE_LOCATION|EDITED:5722:eaf0fc4237833bd61c116f76cab3e1207310a8022a8aa331d69795042a12b8cb
redaction|photo.heic|REMOVE_ALL|EDITED:5722:f3c64a9d14e3a5637e08dbfef2c909a7288aae4a6606198843f4c7e68d92c7d9
redaction|raw.dng|REMOVE_LOCATION|EDITED:11432:d57ae01542df39e26d411ba4d8d1e8f0b16337a78b7eb6fafba933728d3fe7f9
redaction|raw.dng|REMOVE_ALL|WITHHELD:RAW
redaction|video.mp4|REMOVE_LOCATION|EDITED:3036:fdd9a97ac2513613105dd4f7b3574271e98f30ff8b7a54eee15c8e1275ae2f6c
redaction|video.mp4|REMOVE_ALL|EDITED:3036:e146d5c1f7e3e7aef302d7716e204354d7d6562c9cbc1ff48c785288f938769d
redaction|video.mov|REMOVE_LOCATION|EDITED:3277:ad4a4e3add11f37504f01ecb19809a04fa6467ec7d117428a37c3a8745daecd8
redaction|video.mov|REMOVE_ALL|EDITED:3277:811f9899ab7c158531e3c15131dfe93371803a3f5290279b9e6a594d38dda63c
redaction|hdr.mp4|REMOVE_LOCATION|EDITED:4515:976e9602d1ddc954466ccef51249ed3e8db44026591de55a24944edf6f867e6b
redaction|hdr.mp4|REMOVE_ALL|EDITED:4515:f8a2d7d64796931a892bdb5645f7b3573db429e30778e73c48ca423fbe511c39
    """.trimIndent()

    val DATES = """
dates|pixel.jpg|UTC|1791963000000,120,true
dates|pixel.jpg|Asia/Tokyo|1791963000000,120,true
dates|pixel.jpg|America/New_York|1791963000000,120,true
video|pixel.jpg|null
dates|samsung.jpg|UTC|1791963000000,120,true
dates|samsung.jpg|Asia/Tokyo|1791963000000,120,true
dates|samsung.jpg|America/New_York|1791963000000,120,true
video|samsung.jpg|null
dates|motion.jpg|UTC|1791963000000,120,true
dates|motion.jpg|Asia/Tokyo|1791963000000,120,true
dates|motion.jpg|America/New_York|1791963000000,120,true
video|motion.jpg|null
dates|ultrahdr.jpg|UTC|1791963000000,120,true
dates|ultrahdr.jpg|Asia/Tokyo|1791963000000,120,true
dates|ultrahdr.jpg|America/New_York|1791963000000,120,true
video|ultrahdr.jpg|null
dates|whatsapp.jpg|UTC|null
dates|whatsapp.jpg|Asia/Tokyo|null
dates|whatsapp.jpg|America/New_York|null
video|whatsapp.jpg|null
dates|screenshot.png|UTC|null
dates|screenshot.png|Asia/Tokyo|null
dates|screenshot.png|America/New_York|null
video|screenshot.png|null
dates|photo.webp|UTC|null
dates|photo.webp|Asia/Tokyo|null
dates|photo.webp|America/New_York|null
video|photo.webp|null
dates|photo.heic|UTC|1791963000000,120,true
dates|photo.heic|Asia/Tokyo|1791963000000,120,true
dates|photo.heic|America/New_York|1791963000000,120,true
video|photo.heic|null
dates|raw.dng|UTC|1791963000000,120,true
dates|raw.dng|Asia/Tokyo|1791963000000,120,true
dates|raw.dng|America/New_York|1791963000000,120,true
video|raw.dng|null
dates|video.mp4|UTC|1791963000000,0,false
dates|video.mp4|Asia/Tokyo|1791963000000,540,false
dates|video.mp4|America/New_York|1791963000000,-240,false
video|video.mp4|1791963000000
dates|video.mov|UTC|1791963000000,0,false
dates|video.mov|Asia/Tokyo|1791963000000,540,false
dates|video.mov|America/New_York|1791963000000,-240,false
video|video.mov|1791963000000
dates|hdr.mp4|UTC|1791963000000,0,false
dates|hdr.mp4|Asia/Tokyo|1791963000000,540,false
dates|hdr.mp4|America/New_York|1791963000000,-240,false
video|hdr.mp4|1791963000000
    """.trimIndent()

    val GUESSES = """
guess|PXL_20250512_103045123.jpg|1747045845123
guess|IMG_20250512_103045.jpg|1747038645000
guess|20250512_103045.jpg|1747038645000
guess|Screenshot_20250512-103045.png|1747038645000
guess|Screenshot_2025-05-12-10-30-45-120_com.android.chrome.jpg|1747038645120
guess|IMG-20250512-WA0003.jpg|1747044000000
guess|photo.jpg|null
guess|IMG_20250330_023000.jpg|1743298200000
guess|IMG_20251026_023000.jpg|1761438600000
guess|IMG_20251340_101010.jpg|null
    """.trimIndent()
}
