# Changelog

## [0.16.0](https://github.com/danyk20/mediasync/compare/v0.15.0...v0.16.0) (2026-11-23)


### Features

* the iPhone's link carries whole syncs with timeouts and backpressure ([#200](https://github.com/danyk20/mediasync/issues/200)) ([321a16f](https://github.com/danyk20/mediasync/commit/321a16fbde710362f9c3261f40c1ebd1529bddd5))

## [0.15.0](https://github.com/danyk20/mediasync/compare/v0.14.0...v0.15.0) (2026-11-23)


### Features

* an iPhone can open a mutual-TLS link to a receiver ([#197](https://github.com/danyk20/mediasync/issues/197)) ([a9d3513](https://github.com/danyk20/mediasync/commit/a9d3513902e1e2465b3084608f9d9f977065695a))
* the iPhone keeps its identity across launches ([#199](https://github.com/danyk20/mediasync/issues/199)) ([1cfc723](https://github.com/danyk20/mediasync/commit/1cfc7230702b79ca49483dd894f52154156c4d0e))

## [0.14.0](https://github.com/danyk20/mediasync/compare/v0.13.0...v0.14.0) (2026-11-23)


### Features

* most of the sync engine builds for iPhone ([#189](https://github.com/danyk20/mediasync/issues/189)) ([461d372](https://github.com/danyk20/mediasync/commit/461d3723b7090afb38c4f3ddd1f79fbaec4a1a17))
* the diagnostic log runs on iPhone too ([#187](https://github.com/danyk20/mediasync/issues/187)) ([1aabd3b](https://github.com/danyk20/mediasync/commit/1aabd3bbb0f47130306ffe7a05a5bdb08d19df16))
* the file sender runs on coroutines and builds for iPhone ([#190](https://github.com/danyk20/mediasync/issues/190)) ([1cf1182](https://github.com/danyk20/mediasync/commit/1cf1182672c04240cd77ff672687fe5f2e6b26aa))
* the photo metadata code runs on iPhone too ([#184](https://github.com/danyk20/mediasync/issues/184)) ([8716b13](https://github.com/danyk20/mediasync/commit/8716b13d6cc0b202632ea0aa7f5566be349d73b5))
* the sync runtime builds for iPhone ([#196](https://github.com/danyk20/mediasync/issues/196)) ([f8ef0f1](https://github.com/danyk20/mediasync/commit/f8ef0f1c7507e0a83768714f76141e95e2a84d0f))


### Bug Fixes

* a new photo goes to the new backup folder after a folder change ([#191](https://github.com/danyk20/mediasync/issues/191)) ([3b80dcb](https://github.com/danyk20/mediasync/commit/3b80dcb656031d5a65894c37a5b7497164dfa48d))

## [0.13.0](https://github.com/danyk20/mediasync/compare/v0.12.0...v0.13.0) (2026-11-22)


### Features

* a receiving computer says computer ([#178](https://github.com/danyk20/mediasync/issues/178)) ([1550a25](https://github.com/danyk20/mediasync/commit/1550a25734ae36fac82102bc17b55cb111557eee))
* a sending computer says computer and pairs by pasting a link ([#179](https://github.com/danyk20/mediasync/issues/179)) ([f3967f3](https://github.com/danyk20/mediasync/commit/f3967f34e1a801e1b754d77089aaa0afe3d2a7be))
* a sending computer syncs on the household's schedule ([#176](https://github.com/danyk20/mediasync/issues/176)) ([2735e93](https://github.com/danyk20/mediasync/commit/2735e93ff7086cc57232b5904c8c3542688ca664))

## [0.12.0](https://github.com/danyk20/mediasync/compare/v0.11.0...v0.12.0) (2026-11-22)


### Features

* the desktop chooses to send or receive ([#173](https://github.com/danyk20/mediasync/issues/173)) ([5426fee](https://github.com/danyk20/mediasync/commit/5426fee9931473eba35fb8f6a5792524f4517906))
* the desktop reads photos and videos from folders to send ([#171](https://github.com/danyk20/mediasync/issues/171)) ([5c39296](https://github.com/danyk20/mediasync/commit/5c39296ed4e69cbd218997361973d29be9cbf946))


### Bug Fixes

* the macOS disk image opens again ([#174](https://github.com/danyk20/mediasync/issues/174)) ([af1befe](https://github.com/danyk20/mediasync/commit/af1befe9ccf1a34ec0ca504fa3b8405aa01eb48d))

## [0.11.0](https://github.com/danyk20/mediasync/compare/v0.10.2...v0.11.0) (2026-11-22)


### Features

* a backup connector that copies received photos into a folder ([#161](https://github.com/danyk20/mediasync/issues/161)) ([ef5cf9c](https://github.com/danyk20/mediasync/commit/ef5cf9c0d1e60078e1c97b7b9d85dd0ef08af120))
* desktop installers on GitHub Releases ([#170](https://github.com/danyk20/mediasync/issues/170)) ([148a2f1](https://github.com/danyk20/mediasync/commit/148a2f1e9b3f06bdc2b1d7c33dcc27ef48e7729c))
* the desktop app lives in the tray ([#168](https://github.com/danyk20/mediasync/issues/168)) ([18cd743](https://github.com/danyk20/mediasync/commit/18cd7436bad03ba4b793dc5f53a1f8d762a3d2d6))
* the desktop app starts and receives ([#166](https://github.com/danyk20/mediasync/issues/166)) ([1d866bb](https://github.com/danyk20/mediasync/commit/1d866bbeba99a69f23bf610327ca0106c22567b3))
* the desktop app starts when you log in ([#169](https://github.com/danyk20/mediasync/issues/169)) ([c799a3b](https://github.com/danyk20/mediasync/commit/c799a3b6c806abf04fd6b1cae78dac83370d78e0))
* the desktop copies received photos into a backup folder ([#167](https://github.com/danyk20/mediasync/issues/167)) ([9f56895](https://github.com/danyk20/mediasync/commit/9f568950860c1a1c2d19064534afbd0e01732bc9))
* the desktop finds phones and is found over mDNS ([#165](https://github.com/danyk20/mediasync/issues/165)) ([356ae02](https://github.com/danyk20/mediasync/commit/356ae028566f3de5921780b4b37ffcad6769bf5a))
* the desktop's device identity ([#163](https://github.com/danyk20/mediasync/issues/163)) ([92a6b92](https://github.com/danyk20/mediasync/commit/92a6b926fd1c862661b364a022575c3dd62a49c3))
* the desktop's network, conditions and inbox ([#164](https://github.com/danyk20/mediasync/issues/164)) ([39c022a](https://github.com/danyk20/mediasync/commit/39c022ad28eafafc927ffb1b5e611666d04125db))

## [0.10.2](https://github.com/danyk20/mediasync/compare/v0.10.1...v0.10.2) (2026-11-20)


### Bug Fixes

* WebRTC device tests connect without the emulator's Wi-Fi, and Dependabot's PRs merge once green ([#138](https://github.com/danyk20/mediasync/issues/138)) ([162ecbe](https://github.com/danyk20/mediasync/commit/162ecbe61e651082c9cedc9d0c11dcb3652556af))

## [0.10.1](https://github.com/danyk20/mediasync/compare/v0.10.0...v0.10.1) (2026-11-12)


### Bug Fixes

* offer Turbo when only a test file is waiting ([#134](https://github.com/danyk20/mediasync/issues/134)) ([8162f6c](https://github.com/danyk20/mediasync/commit/8162f6caaf4ef9dcbe365338dff17f026e0afef2))

## [0.10.0](https://github.com/danyk20/mediasync/compare/v0.9.7...v0.10.0) (2026-11-12)


### Features

* a paper-airplane animation while sending, with a switch in Settings ([#131](https://github.com/danyk20/mediasync/issues/131)) ([2f9f759](https://github.com/danyk20/mediasync/commit/2f9f7593d54a9baf828a283bc0f87b2d13e873fd))


### Bug Fixes

* time-outs on the monotonic clock, and a hung unit test that fails fast and names itself ([#133](https://github.com/danyk20/mediasync/issues/133)) ([894ea51](https://github.com/danyk20/mediasync/commit/894ea519cec5b09c1d7191aeb9684063053c9dd3))

## [0.9.7](https://github.com/danyk20/mediasync/compare/v0.9.6...v0.9.7) (2026-11-11)


### Features

* a Settings page, and a diagnostics file size the user chooses ([#128](https://github.com/danyk20/mediasync/issues/128)) ([6bda266](https://github.com/danyk20/mediasync/commit/6bda2669c1a3829eac7f1c292f4f6dd9a32d7070))
* a test file in Diagnostics, to test the speed and connection to the receiving phone ([#130](https://github.com/danyk20/mediasync/issues/130)) ([c71504d](https://github.com/danyk20/mediasync/commit/c71504d07912e1e2d925b2135c539151b506821a))


### Bug Fixes

* Turbo goes over Wi-Fi Direct first, starts sooner, and the receiving screen goes when the sync ends ([#127](https://github.com/danyk20/mediasync/issues/127)) ([617e3a1](https://github.com/danyk20/mediasync/commit/617e3a176bc313d2a495b653d5555e55d43a033d))

## [0.9.6](https://github.com/danyk20/mediasync/compare/v0.9.5...v0.9.6) (2026-11-11)


### Features

* before Android 13, the receiving phone shows itself so Turbo starts with its screen off ([#124](https://github.com/danyk20/mediasync/issues/124)) ([efa0c55](https://github.com/danyk20/mediasync/commit/efa0c556136d0d8b5d5c7f764eb7a9bed82cf5bb))


### Miscellaneous Chores

* release 0.9.6 ([#126](https://github.com/danyk20/mediasync/issues/126)) ([8f60740](https://github.com/danyk20/mediasync/commit/8f60740c9145270c821dd120e2424e5b1f59242c))

## [0.9.5](https://github.com/danyk20/mediasync/compare/v0.9.4...v0.9.5) (2026-11-11)


### Bug Fixes

* the receiving phone no longer crashes when Android refuses a Turbo link ([#122](https://github.com/danyk20/mediasync/issues/122)) ([e7211a8](https://github.com/danyk20/mediasync/commit/e7211a8dabf0764c108733fde252c13f32657395))

## [0.9.4](https://github.com/danyk20/mediasync/compare/v0.9.3...v0.9.4) (2026-11-11)


### Features

* away from the Wi-Fi, Turbo asks the receiving phone over the internet ([#120](https://github.com/danyk20/mediasync/issues/120)) ([af7f3eb](https://github.com/danyk20/mediasync/commit/af7f3ebf3b7da368082b9c173b2b18fd05858b6c))


### Bug Fixes

* a stopped receiving phone turns away a connection Linux still accepts ([#118](https://github.com/danyk20/mediasync/issues/118)) ([825dce8](https://github.com/danyk20/mediasync/commit/825dce83548e3b00af57baecf20454a7ba047319))


### Miscellaneous Chores

* release 0.9.4 ([#121](https://github.com/danyk20/mediasync/issues/121)) ([8384ec3](https://github.com/danyk20/mediasync/commit/8384ec35b68e24b0df39f14bb406059f57e72ae1))

## [0.9.3](https://github.com/danyk20/mediasync/compare/v0.9.2...v0.9.3) (2026-11-06)


### Bug Fixes

* a sync waiting to try again keeps its wait when Android restarts the app ([#93](https://github.com/danyk20/mediasync/issues/93)) ([ab98048](https://github.com/danyk20/mediasync/commit/ab98048760d0cdfe062235cb1bebdbb72ed321db))

## [0.9.2](https://github.com/danyk20/mediasync/compare/v0.9.1...v0.9.2) (2026-11-05)


### Bug Fixes

* **deps:** raise Guava in the build tools to 33.7.2 ([#84](https://github.com/danyk20/mediasync/issues/84)) ([ce65bee](https://github.com/danyk20/mediasync/commit/ce65bee289c45384b8bd96506309e20aff09a48c))
* find the receiving phone on Wi-Fi where mDNS doesn't get through, and let Turbo wait for Turbo receive ([#91](https://github.com/danyk20/mediasync/issues/91)) ([07db3da](https://github.com/danyk20/mediasync/commit/07db3da37fb55eed2fee5e5873dd30e895a52842))

## [0.9.1](https://github.com/danyk20/mediasync/compare/v0.9.0...v0.9.1) (2026-10-30)


### Bug Fixes

* wake-ups reach a receiving phone whose Worker shares the wake relay's account ([#81](https://github.com/danyk20/mediasync/issues/81)) ([c2397c1](https://github.com/danyk20/mediasync/commit/c2397c14833c8352fef3515867cabbad7b7643b6))

## [0.9.0](https://github.com/danyk20/mediasync/compare/v0.8.5...v0.9.0) (2026-10-30)


### Features

* see the new photos and videos before they sync, and leave some out ([#79](https://github.com/danyk20/mediasync/issues/79)) ([34f4967](https://github.com/danyk20/mediasync/commit/34f4967c08fe51d18831a5b89510b442f588e02d))

## [0.8.5](https://github.com/danyk20/mediasync/compare/v0.8.4...v0.8.5) (2026-10-30)


### Bug Fixes

* the time left counts down steadily instead of jumping with the speed ([#77](https://github.com/danyk20/mediasync/issues/77)) ([ba27284](https://github.com/danyk20/mediasync/commit/ba27284de5d013f565c1f3f6f55f755636f402e2))

## [0.8.4](https://github.com/danyk20/mediasync/compare/v0.8.3...v0.8.4) (2026-10-19)


### Bug Fixes

* internet sync no longer drops files that take over a minute, and videos wait off the relay as set ([#75](https://github.com/danyk20/mediasync/issues/75)) ([d569590](https://github.com/danyk20/mediasync/commit/d56959019b2b86b2688fed92316cab6a76dbbd54))

## [0.8.3](https://github.com/danyk20/mediasync/compare/v0.8.2...v0.8.3) (2026-10-19)


### Bug Fixes

* internet sync no longer crashes release builds when it starts a WebRTC connection ([#70](https://github.com/danyk20/mediasync/issues/70)) ([bbf4968](https://github.com/danyk20/mediasync/commit/bbf4968811099c5e6a1cf669f8e07b5e42c54385))

## [0.8.2](https://github.com/danyk20/mediasync/compare/v0.8.1...v0.8.2) (2026-10-19)


### Bug Fixes

* internet sync sets up Cloudflare's relay again (the TURN key's secret is now called "secret") ([#68](https://github.com/danyk20/mediasync/issues/68)) ([da1faf8](https://github.com/danyk20/mediasync/commit/da1faf8cb06efe0351b7348d6e4aa808ce80d5d6))

## [0.8.1](https://github.com/danyk20/mediasync/compare/v0.8.0...v0.8.1) (2026-10-19)


### Bug Fixes

* a TURN key answer without its key no longer stops internet sync setup ([#67](https://github.com/danyk20/mediasync/issues/67)) ([011fca0](https://github.com/danyk20/mediasync/commit/011fca0da21d44c301be871b47a831f9e407c788))
* pasting a Cloudflare token with a line break no longer crashes the app ([#65](https://github.com/danyk20/mediasync/issues/65)) ([d4535af](https://github.com/danyk20/mediasync/commit/d4535afe46208e3009ea80a1ff016b0be291f81b))

## [0.8.0](https://github.com/danyk20/mediasync/compare/v0.7.0...v0.8.0) (2026-10-19)


### Features

* sign in with Cloudflare to set up internet sync ([#63](https://github.com/danyk20/mediasync/issues/63)) ([b11d23c](https://github.com/danyk20/mediasync/commit/b11d23c1709dfbf345917c50db2fe3ef8e94ac93))

## [0.7.0](https://github.com/danyk20/mediasync/compare/v0.6.1...v0.7.0) (2026-10-19)


### Features

* received files get simple names, so Google Photos shows them ([#60](https://github.com/danyk20/mediasync/issues/60)) ([7a2dbb8](https://github.com/danyk20/mediasync/commit/7a2dbb81996b8d72a4fdee87d5793e3fcc425790))


### Bug Fixes

* on the charger with the battery held below full, background syncs run instead of looping ([#62](https://github.com/danyk20/mediasync/issues/62)) ([ce377e4](https://github.com/danyk20/mediasync/commit/ce377e418c7c6d2a4cbd9d48c34f161ac70aa164))

## [0.6.1](https://github.com/danyk20/mediasync/compare/v0.6.0...v0.6.1) (2026-10-19)


### Bug Fixes

* the speed shows steadily, also over Turbo and on the receiving phone ([#58](https://github.com/danyk20/mediasync/issues/58)) ([b8f3ba5](https://github.com/danyk20/mediasync/commit/b8f3ba5b4ed55d471cc55b2911a57d4954921e12))

## [0.6.0](https://github.com/danyk20/mediasync/compare/v0.5.0...v0.6.0) (2026-10-18)


### Features

* an info icon opens a chart of the transfer speed over time ([#56](https://github.com/danyk20/mediasync/issues/56)) ([40dbda4](https://github.com/danyk20/mediasync/commit/40dbda4e477a1c2474adfe4ebfca3939758ca8f4))

## [0.5.0](https://github.com/danyk20/mediasync/compare/v0.4.0...v0.5.0) (2026-10-18)


### Features

* progress shows the transfer speed and the time left ([#54](https://github.com/danyk20/mediasync/issues/54)) ([a7b36d6](https://github.com/danyk20/mediasync/commit/a7b36d605d8654745e66e0e0a5458bc7921acbdf))

## [0.4.0](https://github.com/danyk20/mediasync/compare/v0.3.0...v0.4.0) (2026-10-16)


### Features

* accessibility pass: labels, touch targets, headings, large text ([#40](https://github.com/danyk20/mediasync/issues/40)) ([aa048b2](https://github.com/danyk20/mediasync/commit/aa048b2acb9d182faccf3412574118f9e99ec659))
* check GitHub for updates on copies installed from GitHub ([#43](https://github.com/danyk20/mediasync/issues/43)) ([3be2a08](https://github.com/danyk20/mediasync/commit/3be2a089df1169863d927dad2bfdc35b09980d29))
* choose the order a sending phone sends in ([#48](https://github.com/danyk20/mediasync/issues/48)) ([37ddb50](https://github.com/danyk20/mediasync/commit/37ddb503a46aa4b7c11773c07e6995a18a2594c0))
* MediaSync in nine more languages ([#42](https://github.com/danyk20/mediasync/issues/42)) ([a746e20](https://github.com/danyk20/mediasync/commit/a746e209e029632777500004eac5c9f9c42a2472))
* remove a sending phone, unpair a receiving phone, reset MediaSync ([#39](https://github.com/danyk20/mediasync/issues/39)) ([1c1410c](https://github.com/danyk20/mediasync/commit/1c1410c6f95c0a7ff7b52b564df8dfbd1bcf0def))
* send only photos and videos taken within chosen days ([#49](https://github.com/danyk20/mediasync/issues/49)) ([cfab587](https://github.com/danyk20/mediasync/commit/cfab5872ac264f58a84fd2d0cbbc11bad91ebff1))
* share photos and videos to MediaSync to send them now ([#37](https://github.com/danyk20/mediasync/issues/37)) ([3dc01e7](https://github.com/danyk20/mediasync/commit/3dc01e7adb2e89fdf4cfb8dc2108cae7c75d7caf))
* Sync now from the Quick Settings tile and a home-screen widget ([#35](https://github.com/danyk20/mediasync/issues/35)) ([1e3eecb](https://github.com/danyk20/mediasync/commit/1e3eecbb675c498c7f8112e7802eaadbe949b49f))
* the welcome screen says how MediaSync works ([#41](https://github.com/danyk20/mediasync/issues/41)) ([d4e71db](https://github.com/danyk20/mediasync/commit/d4e71dbdedfad42168d8e80c6cb51d8d34f819d2))
* Turbo goes over Wi-Fi Aware where both phones have it ([#47](https://github.com/danyk20/mediasync/issues/47)) ([c1b2d08](https://github.com/danyk20/mediasync/commit/c1b2d08aee15d30a3f5a6798aa3a0f4f6538421e))


### Bug Fixes

* a removed sending phone can't find Turbo receive any more ([#45](https://github.com/danyk20/mediasync/issues/45)) ([f4abc8e](https://github.com/danyk20/mediasync/commit/f4abc8e01c17e81fec4f48c0dd0b992f5a62e357))

## [0.3.0](https://github.com/danyk20/mediasync/compare/v0.2.0...v0.3.0) (2026-10-15)


### Features

* back up through the Google Photos app on the receiving phone ([#31](https://github.com/danyk20/mediasync/issues/31)) ([ae531d9](https://github.com/danyk20/mediasync/commit/ae531d9ac3b95207e04a5a62066ca0d413db9258))
* received photos and videos without a date get the best one there is ([#29](https://github.com/danyk20/mediasync/issues/29)) ([1f324d0](https://github.com/danyk20/mediasync/commit/1f324d0ee94c72975786eb892a8773accc88d51c))
* set up cloud backup with the Google Photos app from the receiving phone ([#32](https://github.com/danyk20/mediasync/issues/32)) ([eb8aa4d](https://github.com/danyk20/mediasync/commit/eb8aa4d58290368855f3d4e3514a4e25c3241484))

## [0.2.0](https://github.com/danyk20/mediasync/compare/v0.1.0...v0.2.0) (2026-10-15)


### Features

* households' Workers wake phones through the wake relay ([#24](https://github.com/danyk20/mediasync/issues/24)) ([86bddcd](https://github.com/danyk20/mediasync/commit/86bddcd2e9aeee37c9425fb68a5f06d933f3be5e))
* internet sync on Android, through the household's own Cloudflare Worker ([#21](https://github.com/danyk20/mediasync/issues/21)) ([7084230](https://github.com/danyk20/mediasync/commit/70842309035e64f2a7878f7778f35f7a487d4a6f))
* wake the receiving phone over the internet ([#23](https://github.com/danyk20/mediasync/issues/23)) ([980861f](https://github.com/danyk20/mediasync/commit/980861fbfda8a57aec771f981d8908385eb8d818))

## 0.1.0 (2026-10-15)


### Features

* background syncs that keep going and say when they didn't ([#7](https://github.com/danyk20/mediasync/issues/7)) ([c01c1c5](https://github.com/danyk20/mediasync/commit/c01c1c53c30bea8a40be1c1138327d2c4c96bc9c))
* connector framework for backing up received photos ([#18](https://github.com/danyk20/mediasync/issues/18)) ([9409e19](https://github.com/danyk20/mediasync/commit/9409e195132b088a3eae5c7c0a7a789541eec8cc))
* diagnostic log and export, to explain what a sync did ([#10](https://github.com/danyk20/mediasync/issues/10)) ([14580bf](https://github.com/danyk20/mediasync/commit/14580bf7298bb956a28968dae2e84900feee59fd))
* link test in Diagnostics measures the raw speed to the receiving phone ([#12](https://github.com/danyk20/mediasync/issues/12)) ([19ca377](https://github.com/danyk20/mediasync/commit/19ca377bcaf1dcfec477d6d7b66396bc58f94648))
* M0 foundations: project skeleton, CI/CD and test setup ([#1](https://github.com/danyk20/mediasync/issues/1)) ([fe774dd](https://github.com/danyk20/mediasync/commit/fe774dd7ddd22ac2490ad08960d53e5d03280ff8))
* M1 Wi-Fi sync on Android: pairing, transfer and folder selection ([#4](https://github.com/danyk20/mediasync/issues/4)) ([7d99702](https://github.com/danyk20/mediasync/commit/7d997029e1454135ce628669a7579ccd969b3023))
* parallel streams that grow while each one adds speed ([#13](https://github.com/danyk20/mediasync/issues/13)) ([8aa4db1](https://github.com/danyk20/mediasync/commit/8aa4db1991610159ee1b9613783f7f5aef57f590))
* Photo details setting removes location or all details before sending ([#9](https://github.com/danyk20/mediasync/issues/9)) ([91f7846](https://github.com/danyk20/mediasync/commit/91f78467ea518c67533af7ff03c8c70628041afd))
* pipelined transfers, Wi-Fi locks and a throughput benchmark ([#11](https://github.com/danyk20/mediasync/issues/11)) ([07da33f](https://github.com/danyk20/mediasync/commit/07da33ffca0b3dd29411980318290b80f8dc5133))
* receiving carries on with the screen off ([#16](https://github.com/danyk20/mediasync/issues/16)) ([555e4e5](https://github.com/danyk20/mediasync/commit/555e4e536a692f82b68e9f50a27a070f821eb1f6))
* rendezvous Worker for internet sync ([#17](https://github.com/danyk20/mediasync/issues/17)) ([d76b9d3](https://github.com/danyk20/mediasync/commit/d76b9d385f26ab3397f1e1aef65d531df42400cc))
* sync schedule with charging-only background sync ([#6](https://github.com/danyk20/mediasync/issues/6)) ([1dc1ae4](https://github.com/danyk20/mediasync/commit/1dc1ae4ddf1015a983c180db0726768833e11d42))
* Turbo receive links phones that share no Wi-Fi ([#15](https://github.com/danyk20/mediasync/issues/15)) ([6032e44](https://github.com/danyk20/mediasync/commit/6032e44508f891f2d2af003eef46b7b9bda436fc))
* Turbo transfer sends over a direct Wi-Fi link between the phones ([#14](https://github.com/danyk20/mediasync/issues/14)) ([b7f7a41](https://github.com/danyk20/mediasync/commit/b7f7a41677182d89801730523de9e3c13dfd06de))


### Bug Fixes

* **deps:** raise build-tool libraries to patched versions ([#8](https://github.com/danyk20/mediasync/issues/8)) ([43237f9](https://github.com/danyk20/mediasync/commit/43237f9baa1b8dc6dd2d0b11002f1e004f87ef8a))
