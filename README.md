# PrefabsUploader

Plugin Hytale que permite jogadores **enviarem seus prefabs locais para o servidor**, onde viram
*server prefabs* colocáveis no mundo — com **revisão da staff** antes de qualquer coisa tocar o disco.

## Como funciona
1. O jogador exporta um prefab no Asset Editor (`.prefab.json`) e envia pelo **bot do Discord** do servidor.
2. O hub guarda só um **ponteiro** pro anexo (o arquivo continua hospedado no Discord) e o coloca numa **fila de revisão**.
3. A staff abre `/prefabs-uploader validate` in-game: lista os pendentes, **baixa sob demanda** e vê o **preview 3D** — tudo **em memória**.
4. Só quando a staff **aprova** o prefab é gravado no storage de server prefabs do mundo. Rejeitar não grava nada.

Toda a validação de segurança (limite de tamanho/contagem, rejeição de entidades, validação nativa do
engine, namespacing por dono) roda **antes** da gravação. Ver `docs/DESIGN.md`.

## Build
```bash
./gradlew compileJava   # checa compilação
./gradlew jar           # gera o fat JAR do plugin (shadow)
```
O JAR sai em `build/libs/`. Pra testar, copie pra pasta `mods/` de uma instância de Hytale server e
reinicie (Hytale não tem hot-reload). Na primeira execução o plugin gera
`mods/ProjectAtlasia_PrefabsUploader/config.properties` — **aponte `hub.address` pro seu hub** ali
(o default é `localhost:50051` pra teste local).

**Pré-requisitos:** JDK **25+** (exigência do toolchain do ScaffoldIt). A **primeira** build precisa de
rede pra resolver o plugin Gradle `dev.scaffoldit` e o **SDK Hytale** (`release 0.5.2`) na HytaleMaven;
builds seguintes usam o cache do Gradle.

## Stack
- SDK Hytale `release 0.5.2` via ScaffoldIt (`dev.scaffoldit`).
- Java + gRPC (cliente). Gradle 9.2 (wrapper incluso).
- Manifest: `ProjectAtlasia:PrefabsUploader` → permission nodes `projectatlasia.prefabsuploader.command.*`.

## Licença
Copyright (C) 2026 **ProjectAtlasia** — autor: astahjmo (Astaroth).

Este plugin é software livre licenciado sob a **GNU General Public License v3.0 (GPL-3.0-only)** — ver
[`LICENSE`](LICENSE). Você pode usar, estudar, modificar e redistribuir (inclusive comercialmente),
desde que **mantenha os avisos de copyright** e qualquer trabalho derivado **continue sob a GPLv3**
(forks fechados não são permitidos). O contrato gRPC (`proto/prefabsuploader.proto`) é distribuído
junto, sob a mesma licença.

Atribuições das bibliotecas de terceiros embaladas no JAR (gRPC, Netty, protobuf-java, Guava) estão em
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). O plugin **linka em runtime** contra o **SDK Hytale**
(`com.hypixel.hytale:*`), proprietário e provido pelo servidor — não é redistribuído neste repositório.

> O **bot/hub** do Discord é um componente **privado e proprietário** (All Rights Reserved), em
> repositório separado — **não** faz parte desta licença.
