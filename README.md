# PrefabsUploader

**English** | [Português](README.pt-BR.md)

A Hytale plugin that lets players **upload their local prefabs to the server**, where they become
placeable *server prefabs* — with **staff review** before anything touches disk.

## How it works
1. The player exports a prefab from the Asset Editor (`.prefab.json`) and sends it through the server's **Discord bot**.
2. The hub keeps only a **pointer** to the attachment (the file stays hosted on Discord) and adds it to a **review queue**.
3. Staff open `/prefabs-uploader validate` in-game: list the pending items, **download on demand** and see a **3D preview** — all **in memory**.
4. The prefab is written to the world's server-prefab storage **only when staff approve it**. Rejecting writes nothing.

All security validation (size/count limits, entity rejection, the engine's native validation,
per-owner namespacing) runs **before** the write. See `docs/DESIGN.md`.

## Build
```bash
./gradlew compileJava   # check compilation
./gradlew jar           # build the plugin fat JAR (shadow)
```
The JAR lands in `build/libs/`. To test, copy it into the `mods/` folder of a Hytale server instance
and restart (Hytale has no hot-reload). On first run the plugin generates
`mods/ProjectAtlasia_PrefabsUploader/config.properties` — **point `hub.address` at your hub** there
(the default is `localhost:50051` for local testing).

**Requirements:** JDK **25+** (required by the ScaffoldIt toolchain). The **first** build needs network
access to resolve the `dev.scaffoldit` Gradle plugin and the **Hytale SDK** (`release 0.5.2`) from
HytaleMaven; later builds use the Gradle cache.

## Stack
- Hytale SDK `release 0.5.2` via ScaffoldIt (`dev.scaffoldit`).
- Java + gRPC (client). Gradle 9.2 (wrapper included).
- Manifest: `ProjectAtlasia:PrefabsUploader` → permission nodes `projectatlasia.prefabsuploader.command.*`.

## License
Copyright (C) 2026 **ProjectAtlasia** — author: astahjmo (Astaroth).

This plugin is free software licensed under the **GNU General Public License v3.0 (GPL-3.0-only)** —
see [`LICENSE`](LICENSE). You may use, study, modify and redistribute it (including commercially), as
long as you **keep the copyright notices** and any derivative work **stays under the GPLv3** (closed
forks are not allowed). The gRPC contract (`proto/prefabsuploader.proto`) ships with it under the same
license.

Attributions for the third-party libraries bundled in the JAR (gRPC, Netty, protobuf-java, Guava) are
in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). The plugin **links at runtime** against the
**Hytale SDK** (`com.hypixel.hytale:*`), which is proprietary and provided by the server — it is not
redistributed in this repository.

> The Discord **bot/hub** is a **private, proprietary** component (All Rights Reserved) in a separate
> repository — it is **not** covered by this license.
