# MCP server

Just Replay 1.2.0 (mod id `justreplay`), author / auteur : vyrriox.

- [English](#english)
- [Français](#français)

---

## English

Just Replay can run a small MCP server (Model Context Protocol) inside your game, so an AI assistant on your
computer can see what the mod is doing: the recording, the replay you watch or edit, your saved replays, the edit
of a replay (cameras, actors, actions, audio, effects, overlays) and its markers with their notes. It can also look
at the replay itself: any frame as a picture, every entity of any tick, the world and the blocks. For now it only
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
| `timeoutSeconds` | `10` | Longest wait for the game to answer one request (a world loading, for instance). A frame may take up to 60 seconds and a jump through the replay up to 30, whatever this says. |
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

### Seeing the replay

These tools look at the replay open in the game, frame by frame and entity by entity. Without `tick` they look at
the play head. Another tick is reached by jumping there and back within one game tick: you never see the jump, but
going back rebuilds the world from a checkpoint, so chunks may redraw for a moment. Nothing is changed or saved.

| Tool | Arguments | Answer |
|---|---|---|
| `render_frame` | `tick`, `camera` (`auto`, `project`, `current`, `free`) with `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, then `width`, `height`, `format` (`jpeg` or `png`), `quality` (1 to 100, 80), `ui` | The frame as a picture (MCP image content) and the camera it was drawn from. By default a clean video frame, drawn by the video export itself (effects, letterbox, overlays, no editor) from the project's camera path, or from the current view when the project has none. `ui=true` gives the window as you see it, editor and HUD included, from your current view unless a camera is asked for. 640 pixels wide by default, at most 1280 a side and 1280x720 in all: a bigger size is scaled down. |
| `list_entities` | `tick`, `types`, `source` (`recorded` or `actor`), `query`, a centre (`x`, `y`, `z`, or `marker`, or `near`) with `radius` (32), `limit` (1 to 200, 50), `offset`, `detail` (`summary` or `full`) | The entities of a tick: id, ref, UUID, type, name, recorded entity or editor actor, position, rotation, velocity in blocks per second, health, equipment, vehicle and passengers. Nearest first around a centre, by id otherwise; `nextOffset` gives the next page. `full` adds size, eyes, pose and item names, 50 entities a page at most. |
| `get_entity` | `id`, `tick` | One entity in full, with what it rides, what rides it and the markers pinned to it. |
| `get_entity_track` | `id`, `from`, `to`, `step` or `samples` (2 to 500, 100) | Where one entity is over time: rows `[tick, x, y, z, yaw, pitch]`, the distance travelled, its top speed and the box it moved in. At most 10 minutes (12000 ticks) and 500 samples a call. |
| `get_world` | `tick` | The replay's clock (length, play head, speed, paused) and the world: dimension, time of day shown and recorded, weather, difficulty, counts of entities, players and chunks, the project's time and weather settings, the camera of the window. |
| `get_block` | `x`, `y`, `z`, `tick` | The block there: id, state properties, fluid, block entity, biome and light, or that its chunk is not loaded. |
| `list_players` | `tick`, `limit` (1 to 200, 100) | Who recorded the replay, the players in the world with their position, and the tab list. |
| `project_to_screen` | `points` (each `[x, y, z]`), `markers` (ids, or `all`), `entities`, `tick`, the camera arguments, `width`, `height`, `ui` | Where each one falls on a frame drawn by `render_frame` with the same arguments: pixels from the left and from the top, depth in front of the camera, whether it is in front and on the picture. An entity gives its centre, feet and head. |

- An entity's `ref` is how markers and cameras name it: `recorder` for the player who recorded, `actor:<id>` for an
  actor of the editor, else its UUID. `id`, `ref` and UUID all work as `id`; prefer `ref` across ticks, an actor's
  network id changes when the editor rebuilds it.
- Positions are those the recording gives at the tick, in blocks. The game draws entities a little behind them, as it
  eases from one recorded position to the next, so a frame may show them up to a few tenths of a block away.
- A camera gives the position of its eye. Yaw 0 looks south (+Z), 90 west (-X), 180 north; a positive pitch looks
  down; `fov` is the vertical field of view in degrees. The camera keyframes of `get_project` store the position of
  the feet, 1.62 blocks lower.
- A frame takes about a second, longer while chunks load (60 seconds at most). A video frame plays the second of the
  replay before it, under the usual export veil, then everything is put back: play head, speed, pause, screen and
  camera. While a frame or an export is drawn, the tools only look at the play head.

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
notes. Il peut aussi regarder le replay lui-même : n'importe quelle frame en image, toutes les entités de n'importe
quel tick, le monde et les blocs. Pour l'instant il ne fait que lire : rien de ce que demande un client ne change tes
replays ni ton jeu.

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
| `timeoutSeconds` | `10` | Attente la plus longue d'une réponse du jeu à une requête (pendant le chargement d'un monde, par exemple). Une frame peut prendre jusqu'à 60 secondes et un saut dans le replay jusqu'à 30, quoi qu'il en dise. |
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

### Voir le replay

Ces outils regardent le replay ouvert dans le jeu, frame par frame et entité par entité. Sans `tick` ils regardent la
tête de lecture. Un autre tick est atteint par un saut aller-retour dans le même tick du jeu : tu ne vois jamais le
saut, mais revenir en arrière reconstruit le monde depuis un point de reprise, donc des chunks peuvent se redessiner
un instant. Rien n'est modifié ni sauvé.

| Outil | Arguments | Réponse |
|---|---|---|
| `render_frame` | `tick`, `camera` (`auto`, `project`, `current`, `free`) avec `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `fov`, puis `width`, `height`, `format` (`jpeg` ou `png`), `quality` (1 à 100, 80), `ui` | La frame en image (contenu image MCP) et la caméra qui l'a prise. Par défaut une frame vidéo propre, rendue par l'export vidéo lui-même (effets, bandes noires, overlays, sans l'éditeur) depuis la trajectoire de caméra du projet, ou depuis la vue actuelle si le projet n'en a pas. `ui=true` donne la fenêtre telle que tu la vois, éditeur et HUD compris, depuis ta vue actuelle sauf si une caméra est demandée. 640 pixels de large par défaut, au plus 1280 par côté et 1280x720 en tout : une taille plus grande est réduite. |
| `list_entities` | `tick`, `types`, `source` (`recorded` ou `actor`), `query`, un centre (`x`, `y`, `z`, ou `marker`, ou `near`) avec `radius` (32), `limit` (1 à 200, 50), `offset`, `detail` (`summary` ou `full`) | Les entités d'un tick : id, ref, UUID, type, nom, entité enregistrée ou acteur de l'éditeur, position, rotation, vitesse en blocs par seconde, vie, équipement, monture et passagers. Les plus proches d'abord autour d'un centre, par id sinon ; `nextOffset` donne la page suivante. `full` ajoute taille, yeux, posture et noms des objets, 50 entités par page au plus. |
| `get_entity` | `id`, `tick` | Une entité en détail, avec ce qu'elle monte, ce qui la monte et les repères épinglés sur elle. |
| `get_entity_track` | `id`, `from`, `to`, `step` ou `samples` (2 à 500, 100) | Où est une entité au fil du temps : lignes `[tick, x, y, z, yaw, pitch]`, distance parcourue, vitesse maximale et boîte où elle a bougé. Au plus 10 minutes (12000 ticks) et 500 échantillons par appel. |
| `get_world` | `tick` | L'horloge du replay (durée, tête de lecture, vitesse, pause) et le monde : dimension, heure affichée et enregistrée, météo, difficulté, nombre d'entités, de joueurs et de chunks, réglages d'heure et de météo du projet, caméra de la fenêtre. |
| `get_block` | `x`, `y`, `z`, `tick` | Le bloc à cet endroit : id, propriétés d'état, fluide, bloc-entité, biome et lumière, ou que son chunk n'est pas chargé. |
| `list_players` | `tick`, `limit` (1 à 200, 100) | Qui a enregistré le replay, les joueurs présents dans le monde avec leur position, et la liste des joueurs (tab). |
| `project_to_screen` | `points` (chacun `[x, y, z]`), `markers` (ids, ou `all`), `entities`, `tick`, les arguments de caméra, `width`, `height`, `ui` | Où chacun tombe sur une frame rendue par `render_frame` avec les mêmes arguments : pixels depuis la gauche et depuis le haut, profondeur devant la caméra, devant ou non, sur l'image ou non. Une entité donne son centre, ses pieds et sa tête. |

- Le `ref` d'une entité est le nom que lui donnent les repères et les caméras : `recorder` pour le joueur qui a
  enregistré, `actor:<id>` pour un acteur de l'éditeur, sinon son UUID. `id`, `ref` et UUID marchent tous comme `id` ;
  préfère `ref` d'un tick à l'autre, l'id réseau d'un acteur change quand l'éditeur le reconstruit.
- Les positions sont celles que l'enregistrement donne au tick, en blocs. Le jeu dessine les entités un peu en
  retard, en lissant d'une position enregistrée à la suivante : une frame peut les montrer à quelques dixièmes de bloc.
- Une caméra donne la position de son œil. Yaw 0 regarde au sud (+Z), 90 à l'ouest (-X), 180 au nord ; un pitch
  positif regarde vers le bas ; `fov` est le champ de vision vertical en degrés. Les keyframes de caméra de
  `get_project` gardent la position des pieds, 1,62 bloc plus bas.
- Une frame prend environ une seconde, plus pendant le chargement des chunks (60 secondes au plus). Une frame vidéo
  joue la seconde du replay qui la précède, sous le voile habituel de l'export, puis tout est remis : tête de
  lecture, vitesse, pause, écran et caméra. Pendant le rendu d'une frame ou d'un export, les outils ne regardent que
  la tête de lecture.

### Protocole

- Streamable HTTP sur un seul point d'accès, `/mcp` : un message JSON-RPC par POST, avec une réponse JSON (pas de
  flux d'événements : GET et DELETE reçoivent 405), sans session.
- Versions du protocole : `2026-07-28` (sans poignée de main : la version passe dans `_meta` et dans les en-têtes
  `MCP-Protocol-Version`, `Mcp-Method` et `Mcp-Name`, `server/discover` dit ce que le serveur prend en charge), et les
  versions à poignée de main `2025-11-25`, `2025-06-18` et `2025-03-26` (`initialize`, puis des requêtes simples).
- Erreurs : un outil ou une ressource inconnus donnent une erreur JSON-RPC ; un argument faux, un outil qui échoue
  ou un jeu qui ne répond pas à temps donnent un résultat d'outil avec `isError` et un message que l'assistant peut
  lire.
