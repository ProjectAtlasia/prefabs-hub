# Changelog

## [0.2.1](https://github.com/ProjectAtlasia/prefabs-hub/compare/v0.2.0...v0.2.1) (2026-06-05)


### Features

* discord upload pipeline, in-game status, permissions and security scanning ([eb41e45](https://github.com/ProjectAtlasia/prefabs-hub/commit/eb41e45313c5b8a4de8a1f1299e00f4f9dc3ef8a))
* initial open-source release under GPLv3 ([3ceb1ba](https://github.com/ProjectAtlasia/prefabs-hub/commit/3ceb1ba015ab0054ef1b4ed9d3ad3f649eb8f17b))


### Bug Fixes

* security, concurrency and review-UI hardening ([aece50d](https://github.com/ProjectAtlasia/prefabs-hub/commit/aece50d6a98487b7a466a1489b9d6e16776db38c))

## [0.2.0](https://github.com/ProjectAtlasia/prefabs-hub/compare/v0.1.0...v0.2.0) (2026-06-04)


### Features

* **client:** lazy windowed gRPC connection (idle channel + setup/link windows) ([46ea5bb](https://github.com/ProjectAtlasia/prefabs-hub/commit/46ea5bb87470c15672272d111c845f6eff254641))
* **client:** send x-plugin-version and x-server-name headers on every gRPC request ([f31abbf](https://github.com/ProjectAtlasia/prefabs-hub/commit/f31abbf06254ffa16407c7c3d2fd8acc09187be2))
* **config:** add persisted pair-message toggle ([2722305](https://github.com/ProjectAtlasia/prefabs-hub/commit/2722305c4e6e23752b2ab82ad39616f8792fbcd4))
* initial open-source release under GPLv3 ([3ceb1ba](https://github.com/ProjectAtlasia/prefabs-hub/commit/3ceb1ba015ab0054ef1b4ed9d3ad3f649eb8f17b))
* **plugin:** add pair-message command, /pu link, and in-game pairing/link confirmations ([9821d87](https://github.com/ProjectAtlasia/prefabs-hub/commit/9821d87cba474b3a060044ac5ebe538ee9f20cac))
* **plugin:** in-game status messages, guild invite with config fallback, and import/link permissions ([fc4b04c](https://github.com/ProjectAtlasia/prefabs-hub/commit/fc4b04ca761447f4ddf8d9f8ff54edfde72f0d70))
* **proto:** add dm-fallback statuses and guild invite fields ([59738a0](https://github.com/ProjectAtlasia/prefabs-hub/commit/59738a0a72d943ead9827d432ab13a9d419f490c))
* **proto:** add PlayerLinked hub-to-plugin signal for in-game link confirmation ([4548c7a](https://github.com/ProjectAtlasia/prefabs-hub/commit/4548c7a507152172fe49852a6e80c260d3e7802c))


### Bug Fixes

* read build defaults from a resource (IDE-friendly) instead of a generated class ([98005e2](https://github.com/ProjectAtlasia/prefabs-hub/commit/98005e22f93871f9fb9fcc8068a8ab220ea7ded1))
