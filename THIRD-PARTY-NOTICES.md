# Third-Party Notices — PrefabsUploader

O plugin PrefabsUploader é licenciado sob a GPL-3.0-only (ver `LICENSE`). Ele inclui, no fat JAR de
distribuição (shadow jar), código de terceiros sob as licenças abaixo — todas compatíveis com a GPLv3
como código incorporado. As respectivas licenças/atribuições são mantidas conforme exigido.

## Embaladas no JAR distribuído (redistribuídas)

| Componente | Versão | Licença |
|---|---|---|
| gRPC for Java (`io.grpc:grpc-protobuf`, `grpc-stub`) | 1.71.0 | Apache License 2.0 |
| gRPC Netty (shaded) (`io.grpc:grpc-netty-shaded`) — inclui uma cópia relocada do **Netty** | 1.71.0 | Apache License 2.0 (Netty: Apache-2.0, com NOTICE próprio + alguns arquivos MIT/BSD) |
| Protocol Buffers — Java (`com.google.protobuf:protobuf-java`) | 4.29.3 | BSD-3-Clause |
| Guava (`com.google.common`, transitiva via gRPC, relocada) | ~33.x | Apache License 2.0 |

> Os pacotes `com.google.protobuf` e `com.google.common` são **relocados** (shaded) para
> `dev.atlasia.prefabuploader.shaded.*` no JAR a fim de evitar conflito com as cópias que o
> HytaleServer já carrega. A relocação é uma modificação permitida; os avisos (NOTICE) das origens
> continuam aplicáveis e acompanham o binário.

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- BSD-3-Clause: https://opensource.org/license/bsd-3-clause
- Netty NOTICE: https://github.com/netty/netty/blob/4.1/NOTICE.txt

## Usadas em build/runtime, NÃO redistribuídas neste JAR

| Componente | Versão | Licença | Observação |
|---|---|---|---|
| SDK Hytale (`com.hypixel.hytale:*`) | release 0.5.2 | Proprietária (Hypixel/Hytale) | `compileOnly`; provido pelo servidor em runtime; excluído do shadow jar. O plugin GPL apenas **linka** contra esta API proprietária (system library). |
| ScaffoldIt (`dev.scaffoldit`) | 0.2.+ | Build-time | Plugin Gradle; não entra no JAR. |
| Lombok (`org.projectlombok:lombok`) | 1.18.46 | MIT | `compileOnly`/annotation processor; não entra no JAR. |
| `protoc` / `protoc-gen-grpc-java` | 4.29.3 / 1.71.0 | BSD-3-Clause / Apache-2.0 | Geradores de código (build-time). |
| `org.apache.tomcat:annotations-api` | 6.0.53 | Apache-2.0 | `compileOnly` (`javax.annotation.Generated`). |
| BSON (`org.bson`) | provido pelo servidor | Apache-2.0 | Importado em runtime; provido pelo HytaleServer. |
