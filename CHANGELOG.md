# Changelog

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
