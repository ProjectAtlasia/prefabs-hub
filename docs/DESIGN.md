# PrefabsUploader — Design

## Objetivo
Permitir que um jogador **envie prefabs locais do PC dele** (`.prefab.json`, criados no Asset
Editor do Hytale) **para o servidor**, virando *server prefabs* que podem ser colocados no mundo.

## Por que não é trivial
O Hytale **não tem** transferência de arquivo client→server nativa (o launcher não sincroniza
prefabs locais pro servidor). Então o "upload" precisa de um dos caminhos abaixo.

## A cadeia de API do engine (já existe — ver HYTALE-PREFAB-API.md)
```
fonte de blocos  →  BlockSelection  →  PrefabStore.saveServerPrefab(nome, sel)  →  .prefab.json no servidor
                                                                                   ↓
                                   PrefabStore.getServerPrefab(nome) → PrefabUtil.paste(...) → mundo
```
Storage + load + paste já estão prontos. O que falta é a etapa **obter os blocos do prefab local do
player**.

## Caminhos viáveis

### Caminho 1 — Upload HTTP (o "enviar arquivo" literal) ⭐ recomendado se players NÃO têm editor
1. `/prefab upload` → o plugin gera um **token de uso único** (curto, por player) e manda um link no
   chat: `http://<host>:<porta>/up?t=<token>`.
2. O player abre no navegador, escolhe o `.prefab.json` local e envia (form multipart).
3. O plugin **valida e sanitiza** (ver Segurança) e chama `saveServerPrefab(nome_namespaced, sel)`.
4. `/prefab place <nome>` cola no mundo.
- Servidor HTTP: `com.sun.net.httpserver.HttpServer` (JDK, sempre disponível) ou Netty HTTP (no
  classpath do server). Um único endpoint, bind numa porta dedicada.
- **Precisa abrir uma porta TCP** no host de produção (o server já expõe UDP 5520).

### Caminho 2 — Bridge pelo editor in-game (sem HTTP) — recomendado se players TÊM editor/build
1. O player **carrega o prefab local dele no editor** e coloca/seleciona a região no mundo.
2. `/prefab save <nome>` → `SelectionManager.getSelectionProvider().computeSelectionCopy(ref, player,
   sel -> saveServerPrefab(nome, sel), accessor)`.
3. `/prefab place <nome>` cola.
- Sem porta nova, sem servidor HTTP. Depende de o player ter acesso à ferramenta de seleção/build.

## Segurança (não-negociável)
Prefab pode carregar **componentes de entidade** (`PrefabCopyableComponent`) — não é só bloco.
Mitigações obrigatórias em qualquer caminho:
- **Tamanho/contagem**: limite de bytes do arquivo e de blocos da seleção (ex: ≤ 64×64×64 = 262k blocos, ≤ ~2MB).
- **Whitelist** de block ids e de componentes; **rejeitar** entidades/spawners/itens perigosos.
- **Auth** (Caminho 1): token de uso único, expira em ~2min, 1 upload por token.
- **Rate-limit** por player + cota de prefabs por player.
- **Namespacing** por dono: salvar como `player_<uuid8>_<nome>` pra não colidir/sobrescrever os do servidor.
- Validar parse via `SelectionPrefabSerializer.deserialize` **antes** de persistir; rejeitar BSON malformado.

## Decisões em aberto (perguntar ao usuário)
1. Players têm **acesso ao editor/build** no servidor? (sim → Caminho 2; não → Caminho 1)
2. Pode **abrir porta TCP** no host de prod? (necessário pro Caminho 1)
3. **Quem** pode upar — todos, ou um grupo (VIP/trusted)? → `requirePermission(...)`.
4. **Onde** pode colar — só claim próprio / livre / com custo?
5. **Comando vs UI** — `/prefab save|place|list|delete|upload` e/ou uma página browser de prefabs.

## MVP recomendado
Caminho 1 (HTTP) **ou** Caminho 2 (editor) conforme a resposta #1/#2, com:
- Comandos `/prefab upload|list|place <nome>|delete <nome>` (gate de permissão).
- Sanitização forte + namespacing por dono + limites.
- Colocar só em **claim próprio** no começo (anti-griefing) — mas claims vivem no plugin Atlasia;
  decidir se há integração ou regra própria.

## Milestones sugeridos
1. **M1 — esqueleto + comando**: `/prefab` com subcomandos stub + `list` lendo `getServerPrefabsPath()`.
2. **M2 — place**: `/prefab place <nome>` via `getServerPrefab` + `PrefabUtil.paste` na mira do player.
3. **M3 — capturar/salvar**: implementar Caminho 1 (HTTP+token) ou Caminho 2 (computeSelectionCopy).
4. **M4 — segurança**: validação/sanitização/limites/rate-limit/namespacing.
5. **M5 — polish**: UI browser, rotação na hora de colar, undo, cota por player.

## Notas de integração
- Este plugin é **separado** do Atlasia (outra instância). Compartilham só o SDK e a convenção de
  permission nodes. Se precisar de claims, ou se integra com o Atlasia (API/eventos) ou define regra própria.
