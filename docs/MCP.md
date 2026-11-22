# MCP server

Just Replay 1.2.0 (mod id `justreplay`), author / auteur : vyrriox.

- [English](#english)
- [Français](#français)

---

## English

Just Replay can run a small MCP server (Model Context Protocol) inside your game, so an AI assistant on your
computer can see what the mod is doing: the recording, the replay you watch or edit, your saved replays, the edit
of a replay (cameras, actors, actions, audio, effects, overlays) and its markers with their notes. For now it only
reads: nothing a client asks changes your replays or your game.

### Switching it on

The server is off by default. Open the Just Replay settings (the gear of the editor, or the Config button of the
mod in the Mods list on NeoForge and Forge), section **MCP server (AI assistants)**, switch on
**Start the MCP server** and press **Done**: it starts at once and a toast gives its address,
`http://127.0.0.1:24464/mcp`. Switching it off stops it at once. The same options are in the `mcp` section of
`.minecraft/config/justreplay-client.json`; a change made by hand there applies at the next start.

| Option | Default | What it does |
|---|---|---|
| `enabled` | `false` | Starts the server. |
| `port` | `24464` | Port on 127.0.0.1. The address is `http://127.0.0.1:<port>/mcp`. |
| `token` | empty | Access token, written here the first time the server starts. Empty it to get a new one. |
| `timeoutSeconds` | `10` | Longest wait for the game to answer one request (a world loading, for instance). |
| `allowedOrigins` | empty | Web pages allowed to call the server from a browser, such as `http://localhost:6274` (the MCP Inspector). Desktop and command line clients need nothing here. |

While the server listens, an **MCP** badge sits in the top left corner, in the world and on the title screen.
Its dot turns green when a client asks something. It is never in your videos. When the port is taken by another
program, a toast says so and the server waits until you pick another port.

### Security

- It listens on 127.0.0.1 only: other computers cannot reach it, not even on your network.
- Every request must carry the token, as `Authorization: Bearer <token>`. Without it the answer is 401.
- Web pages are turned away: a request whose `Origin` is not allowed, or whose `Host` is not 127.0.0.1 or
  localhost, gets 403. A website cannot reach the server through your browser, DNS rebinding included.
- Every tool reads the game on the game thread, through a queue with a time limit: a client can neither freeze the
  game nor touch it from another thread. Answers name replays by their id (folder name), never by a path of your
  computer.
- The token is in your settings file: keep it to yourself, and leave it out when you share that file.

### Connecting a client

Copy the token from the settings (or from `justreplay-client.json`), then add the server to your client. With
Claude Code:

```bash
claude mcp add --transport http just-replay http://127.0.0.1:24464/mcp --header "Authorization: Bearer <token>"
```

Clients that read an `mcpServers` file, such as the `.mcp.json` of a Claude Code project, take this form
(`${JUST_REPLAY_MCP_TOKEN}` is read from the environment):

```json
{
  "mcpServers": {
    "just-replay": {
      "type": "http",
      "url": "http://127.0.0.1:24464/mcp",
      "headers": { "Authorization": "Bearer ${JUST_REPLAY_MCP_TOKEN}" }
    }
  }
}
```

A client that only starts local programs (Claude Desktop, for one) can reach it through the `mcp-remote` bridge
(Node.js), in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "just-replay": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:24464/mcp", "--header", "Authorization:${JUST_REPLAY_AUTH_HEADER}"],
      "env": { "JUST_REPLAY_AUTH_HEADER": "Bearer <token>" }
    }
  }
}
```

A quick check from a terminal, with the game running:

```bash
curl -s http://127.0.0.1:24464/mcp -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_state"}}'
```

### What it offers

Tools, all read only. Times are in ticks (20 per second) and also as text such as `1:02.5`.

| Tool | Arguments | Answer |
|---|---|---|
| `get_state` | none | What the mod is doing: recording, instant replay buffer, the open replay (play head, length, speed, camera path, spectated entity), the editor, the export in progress, the screen and the versions. |
| `list_replays` | `query`, `limit` (1 to 500, 50), `offset` | Saved replays, newest first: id, name, server or singleplayer, date, length, versions, size, whether it has a project, which one is open. |
| `get_project` | `replay`, `sections`, `detail` (`summary` or `full`) | The edit of a replay, with the field names of `project.json`: `overview`, `cameras`, `cuts`, `actors`, `actions`, `audio`, `effects`, `overlays`, `world`, `speed`, `entities`. A summary shortens keyframe lists to their count and first and last tick. |
| `list_markers` | `replay`, `type`, `query` | The markers in time order with their notes (see [MARKERS.md](MARKERS.md)). |
| `get_marker` | `id`, `replay` | One marker: full note, times, colour, spot or entity, the markers before and after it. |

Without `replay`, a tool reads the replay open in the game, edits not saved yet included; with the id of a saved
replay it reads its files, which stay as they are.

Resources: `justreplay://state`, `justreplay://replays`, `justreplay://project` (summary of the open project),
`justreplay://markers` and the template `justreplay://markers/{id}`, all JSON.

### Protocol

- Streamable HTTP on one endpoint, `/mcp`: one JSON-RPC message per POST, answered with JSON (no event stream: GET
  and DELETE get 405), no session.
- Protocol versions: `2026-07-28` (no handshake: the version goes in `_meta` and in the `MCP-Protocol-Version`,
  `Mcp-Method` and `Mcp-Name` headers, `server/discover` tells what the server supports), and the handshake versions
  `2025-11-25`, `2025-06-18` and `2025-03-26` (`initialize`, then plain requests).
- Errors: an unknown tool or resource is a JSON-RPC error; a wrong argument, a tool that fails or a game that does
  not answer in time gives a tool result with `isError` and a message the assistant can read.

---

## Français

Just Replay peut faire tourner un petit serveur MCP (Model Context Protocol) dans ton jeu, pour qu'un assistant IA
de ton ordinateur voie ce que fait le mod : l'enregistrement, le replay que tu regardes ou montes, tes replays
sauvés, le montage d'un replay (caméras, acteurs, actions, audio, effets, overlays) et ses repères avec leurs
notes. Pour l'instant il ne fait que lire : rien de ce que demande un client ne change tes replays ni ton jeu.

### L'activer

Le serveur est coupé par défaut. Ouvre les réglages de Just Replay (l'engrenage de l'éditeur, ou le bouton Config
du mod dans la liste des mods sur NeoForge et Forge), section **MCP server (AI assistants)**, active
**Start the MCP server** puis **Done** : il démarre aussitôt et un toast donne son adresse,
`http://127.0.0.1:24464/mcp`. Le couper l'arrête aussitôt. Les mêmes options sont dans la section `mcp` de
`.minecraft/config/justreplay-client.json` ; une modification à la main s'applique au démarrage suivant.

| Option | Défaut | Rôle |
|---|---|---|
| `enabled` | `false` | Démarre le serveur. |
| `port` | `24464` | Port sur 127.0.0.1. L'adresse est `http://127.0.0.1:<port>/mcp`. |
| `token` | vide | Jeton d'accès, écrit ici au premier démarrage du serveur. Vide-le pour en obtenir un nouveau. |
| `timeoutSeconds` | `10` | Attente la plus longue d'une réponse du jeu à une requête (pendant le chargement d'un monde, par exemple). |
| `allowedOrigins` | vide | Pages web autorisées à appeler le serveur depuis un navigateur, comme `http://localhost:6274` (le MCP Inspector). Les clients de bureau et en ligne de commande n'ont besoin de rien ici. |

Tant que le serveur écoute, un badge **MCP** s'affiche en haut à gauche, dans le monde et sur l'écran titre. Son
point passe au vert quand un client demande quelque chose. Il n'est jamais dans tes vidéos. Si le port est pris
par un autre programme, un toast le dit et le serveur attend que tu choisisses un autre port.

### Sécurité

- Il n'écoute que sur 127.0.0.1 : les autres ordinateurs ne peuvent pas l'atteindre, même sur ton réseau.
- Chaque requête doit porter le jeton, sous la forme `Authorization: Bearer <jeton>`. Sans lui, la réponse est 401.
- Les pages web sont refusées : une requête dont l'`Origin` n'est pas autorisée, ou dont le `Host` n'est ni
  127.0.0.1 ni localhost, reçoit 403. Un site ne peut pas atteindre le serveur à travers ton navigateur, DNS
  rebinding compris.
- Chaque outil lit le jeu sur le thread du jeu, par une file avec un délai maximal : un client ne peut ni figer le
  jeu ni y toucher depuis un autre thread. Les réponses désignent les replays par leur id (nom du dossier), jamais
  par un chemin de ton ordinateur.
- Le jeton est dans ton fichier de réglages : garde-le pour toi, et retire-le quand tu partages ce fichier.

### Brancher un client

Copie le jeton depuis les réglages (ou depuis `justreplay-client.json`), puis ajoute le serveur à ton client.
Avec Claude Code :

```bash
claude mcp add --transport http just-replay http://127.0.0.1:24464/mcp --header "Authorization: Bearer <jeton>"
```

Les clients qui lisent un fichier `mcpServers`, comme le `.mcp.json` d'un projet Claude Code, prennent cette forme
(`${JUST_REPLAY_MCP_TOKEN}` est lu dans l'environnement) :

```json
{
  "mcpServers": {
    "just-replay": {
      "type": "http",
      "url": "http://127.0.0.1:24464/mcp",
      "headers": { "Authorization": "Bearer ${JUST_REPLAY_MCP_TOKEN}" }
    }
  }
}
```

Un client qui ne lance que des programmes locaux (Claude Desktop, par exemple) passe par le pont `mcp-remote`
(Node.js), dans `claude_desktop_config.json` :

```json
{
  "mcpServers": {
    "just-replay": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:24464/mcp", "--header", "Authorization:${JUST_REPLAY_AUTH_HEADER}"],
      "env": { "JUST_REPLAY_AUTH_HEADER": "Bearer <jeton>" }
    }
  }
}
```

Un essai rapide depuis un terminal, jeu lancé :

```bash
curl -s http://127.0.0.1:24464/mcp -H "Authorization: Bearer <jeton>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_state"}}'
```

### Ce qu'il propose

Des outils, tous en lecture seule. Les temps sont en ticks (20 par seconde) et aussi en texte comme `1:02.5`.

| Outil | Arguments | Réponse |
|---|---|---|
| `get_state` | aucun | Ce que fait le mod : enregistrement, tampon de replay instantané, replay ouvert (tête de lecture, durée, vitesse, trajectoire de caméra, entité suivie), éditeur, export en cours, écran affiché et versions. |
| `list_replays` | `query`, `limit` (1 à 500, 50), `offset` | Les replays sauvés, du plus récent au plus ancien : id, nom, serveur ou solo, date, durée, versions, taille, présence d'un projet, celui qui est ouvert. |
| `get_project` | `replay`, `sections`, `detail` (`summary` ou `full`) | Le montage d'un replay, avec les noms de champs de `project.json` : `overview`, `cameras`, `cuts`, `actors`, `actions`, `audio`, `effects`, `overlays`, `world`, `speed`, `entities`. Un résumé réduit les listes de keyframes à leur nombre et à leurs premier et dernier ticks. |
| `list_markers` | `replay`, `type`, `query` | Les repères dans l'ordre du temps avec leurs notes (voir [MARKERS.md](MARKERS.md)). |
| `get_marker` | `id`, `replay` | Un repère : note complète, temps, couleur, point ou entité, repères d'avant et d'après. |

Sans `replay`, un outil lit le replay ouvert dans le jeu, modifications pas encore sauvées comprises ; avec l'id
d'un replay sauvé il lit ses fichiers, qui restent tels quels.

Ressources : `justreplay://state`, `justreplay://replays`, `justreplay://project` (résumé du projet ouvert),
`justreplay://markers` et le modèle `justreplay://markers/{id}`, toutes en JSON.

### Protocole

- Streamable HTTP sur un seul point d'accès, `/mcp` : un message JSON-RPC par POST, avec une réponse JSON (pas de
  flux d'événements : GET et DELETE reçoivent 405), sans session.
- Versions du protocole : `2026-07-28` (sans poignée de main : la version passe dans `_meta` et dans les en-têtes
  `MCP-Protocol-Version`, `Mcp-Method` et `Mcp-Name`, `server/discover` dit ce que le serveur prend en charge), et les
  versions à poignée de main `2025-11-25`, `2025-06-18` et `2025-03-26` (`initialize`, puis des requêtes simples).
- Erreurs : un outil ou une ressource inconnus donnent une erreur JSON-RPC ; un argument faux, un outil qui échoue
  ou un jeu qui ne répond pas à temps donnent un résultat d'outil avec `isError` et un message que l'assistant peut
  lire.
