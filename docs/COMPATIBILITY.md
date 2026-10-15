# Compatibility target / Cible de compatibilité

## English

Reference pack: **Arcadia V2** (444 mods, NeoForge 1.21.1). Just Replay must record, play back, edit and
export with every mod of this pack installed. Most mods work for free because recording captures every
clientbound packet, custom payloads included. The mods below need dedicated work, ordered by risk.

### Status of the dedicated integrations

Every integration is optional: it is reached by reflection or by optional mixins
(`justreplay.compat.mixins.json`, `require = 0`), and does nothing when its mod is absent. The code lives in
`com.vyrriox.justreplay.compat`.

| Mod | What Just Replay does | Status |
|---|---|---|
| **WaterFrames** (WaterMedia) | The play time of a display (`DisplayData.tick`) is rebuilt from the replay cursor: every time the stream loads display data (chunk, block entity update, WaterFrames control packet), the tick it carries is anchored to the recorded tick of that packet. The WaterMedia player then follows it: paused with the replay, played at the replay speed, moved after a seek or when it drifts by more than 1 s. During an export the video is held paused and moved to the exact time of every rendered frame, with its sound muted (the export sound track is built from game sounds only). | Code complete, partly tested: the Arcadia self test placed a frame, but it was placed in the same tick as the recording start and was missed by the recording (see "Known limits"); the self test now places it after the start. Video sync not yet observed in game. |
| **Emotecraft** (player-animation-lib) | Emotes of other players are recorded as packets. The local player's own emotes (never sent back by the server) are polled every tick and written as the packets another client receives, aimed at the recording player's actor. Snapshots and checkpoints carry the emote each player is playing. A seek starts skipped emotes late by the time skipped, or drops them when they are over. The animation stack follows the replay clock (holds while paused, runs once per recorded tick above x1). Editor actors get an `EMOTE` action (parameter: emote name or UUID). | Tested in the Arcadia pack: the local emote is recorded (`"waving"@20` at recording tick 60), the recorder's actor plays an emote after a seek into it and while paused (its tick was not printed: the diagnostic failed on network emote names, now fixed), no emote after a seek past its end, the `EMOTE` action plays on an actor (`"waving"@11` 11 ticks after the action). |
| **Create** (Flywheel, Ponder) | Create's animation clock (`AnimationTickHolder`) and the level renderer clock read by Flywheel's shaders are set to the replay cursor: kinetic visuals stop with the replay, follow its speed and show the same angles after a seek and in exports. The train network packets (track graphs, edge groups, trains, roll calls), sent once per connection, are kept and written again in every snapshot and checkpoint. Contraptions keep their original spawn packets through the existing spawn cache; block entity data travels in chunk packets. | Clock tested in the Arcadia pack (the counter equals the cursor, stays still while paused). Trains and moving contraptions untested. |
| **Mekanism** | Machines are block entities synced by update packets and chunk data: no dedicated code. | Machine placed in the self test, no error in the log; visuals not inspected. |
| **Distant Horizons** | While a replay runs, through DH's public API (`DhApi.Delayed`): LOD rendering and distant generation are turned off with API overrides, and the DH world is set read only so no LOD of the throw-away replay world is saved. The overrides are cleared when the replay ends. | Tested with DH 3.3.3 alone in a development client: overrides applied and restored on every replay, full self test passes. |
| **Bobby** | Bobby reports itself disabled while a replay runs (no cached chunks, nothing saved). | Unchanged. |
| **Drop the Beam** | Lights, beams, moving heads, haze and particles follow the level game time and need nothing. Everything that follows a song (music, light desk cues, video screens, sound reactive signals) follows the server clock as `ServerClock` estimates it. **Clock:** the recorded `clock_pong` packets plus a clock sample the recorder writes every 2 s (a pong carrying a marker, with the server instant the recording client estimated) map each recorded tick to the server instant; they are read ahead in the background when a replay opens and while playing. During a replay `ServerClock` answers with that mapping at the frame's replay time (cursor and partial tick): it stops with the replay, follows its speed and jumps with seeks. Recorded pongs never reach Drop the Beam; its pings and requests are not sent to the replay host; its "pause with the single-player game" logic is off during a replay. **Recording:** the latest music state, mix and layers per turntable, song offers and analyses, shows per desk, features and libraries are kept per connection and written into every snapshot and checkpoint, so a recording started mid-session plays the music already running. **Live audio:** fed silence while the replay is paused, away from x1 (songs are rendered at their natural pitch) or its game sounds are muted; its volume follows the project's game volume and ducking. **Export:** its audio thread renders nothing during an export; every voice is rendered offline for each frame with the clock pinned to that frame, attenuated (linear to its range, jukebox volume) and panned relative to the camera, then mixed as a game sound (game volume, mute, ducking). **Song starts:** `DropTheBeamCompat.songStartTicks()` gives the replay tick (with decimals) where each song is heard from its start, and each repeat of a song looping on its own; they are logged as `Drop the Beam: turntable ... starts "title" at replay tick ...`. | Compiled against Drop the Beam 0.1.0 sources, not yet tested in game. |

### Known limits

- A block changed in the very tick a recording starts in the middle of a session can be missed: the packet is
  captured before the start but handled by the client after the snapshot.
- WaterFrames in exports: WaterMedia seeks are asynchronous, so a video can lag a few frames behind the exported
  time on a fast export.
- Create trains in a recording started mid-session depend on the kept train network packets; this path is untested.
- Drop the Beam: song, video and image files come from the local caches (`dropthebeam/song-cache`, `video-cache`,
  `image-cache`); a replay watched on another computer that lacks them stays silent or blank, since there is no
  server to send them. A replay recorded before this integration has only the real pongs as clock (one every 2 s,
  late by half a round trip). Music is muted at speeds other than x1.

### Tier 1: rendering pipeline (blocking for playback and export)

| Mod | Risk | Plan |
|---|---|---|
| Sodium 0.8 (NeoForge), Sodium Extras, Reese's Sodium Options | Chunk rebuild on time jumps, frame capture | Force full chunk rebuild after a seek, read the framebuffer after Sodium's pass |
| Iris 1.8 | Shader pipeline during export, depth for depth of field | Export through Iris' final pass, test with 2 shader packs |
| Create 6 + Flywheel (bundled) | Contraptions and instanced visuals tick on client time | Drive Flywheel's partial ticks from replay time, test trains and contraptions while seeking |
| ImmediatelyFast, EntityCulling, ModernFix, FerriteCore | Batching and culling may hide entities in free camera | Disable entity culling while a replay is open, check batching with frame capture |
| Ixeris | Moves window and event handling off the render thread | Check input in the editor (ImGui) and frame pacing during export |
| Bobby (reneoforged) | Serves cached chunks, would mix stale terrain into replays | Disable Bobby while a replay world is loaded |
| Distant Horizons | Not in this pack | LOD rendering, generation and saving turned off during a replay (see above) |

### Tier 2: animations driven by client time (must follow replay time, pause and speed)

| Mod | Plan |
|---|---|
| GeckoLib (Mowzie's Mobs, Iron's Spellbooks, many creatures) | Feed GeckoLib's animation clock from the replay clock |
| Player Animation Lib, Emotecraft, Better Combat, Not Enough Animations, ParCool | Record their payloads, replay them on the replay clock, also on actors |
| Pehkui | Scale data is synced by packets, verify actors can be scaled |
| Fancy Entity Renderer, Cosmetic Armours, Curios, Accessories, Simple Hats, Elytra Slot | Must render on recorded players and on editor actors |
| Particle Core, Particular, Ars Nouveau and Iron's Spellbooks particles | Client side particles, seed randomness from the replay tick for reproducible exports |

### Tier 3: audio and media

| Mod | Plan |
|---|---|
| Simple Voice Chat, Simple Voice Broadcast | Record voice streams per player into the replay as an optional audio track |
| Sound Physics Remastered, AmbientSounds, Sound Culling, Extreme Sound Muffler | Keep them active in playback, route the final mix into the export audio track |
| WaterFrames / WaterMedia | Sync in-world video playback with the replay clock |
| Exposure | Camera items are packets, check that photo frames render in replay |

### Tier 4: UI and screen mods (editor must not conflict)

FancyMenu, Drippy Loading Screen, Spiffy HUD, BetterF3, JEI, Jade, Legendary Tooltips, Controlling, Better
Third Person, Just Zoom, Mouse Tweaks: hide or bypass their overlays inside the editor, keep them optional
in the exported HUD.

### Tier 5: networking and server side mods

- **Arcadia mods** (arcadia-lib, arcadiaguard, admin panel, AH, pets, prestige, spawn, lootbox,
  arcadiatweaks...): custom payloads, recorded as is. Check that no mod rejects a replay connection.
- **NoChatReports**: chat signing differs, record chat as plain system messages.
- **SkinRestorer**: skins come from packets, record the resolved skin texture with the player.
- **FTB suite, KubeJS, Forgified Fabric API, owo-lib, Kotlin for Forge**: registry and config sync during
  the configuration phase, replayed by the fake server.
- **Easy NPC**: NPCs are regular entities, also usable as editor actors.
- **Immersive Aircraft, Man of Many Planes, Create Jetpack, Create Hypertube, Railways**: fast vehicles,
  check camera follow mode and interpolation.

### Test protocol

1. Dev run with a subset (Sodium, Iris, Create, GeckoLib mods, Voice Chat, Emotecraft) in `localRuntime`.
2. Full pack run: copy the built jar into the Arcadia V2 instance, record 10 minutes on a server, then play,
   seek, edit and export.
3. Any crash or visual difference is logged in `ERROR_LOG.md` and gets a compatibility module.

## Français

Pack de référence : **Arcadia V2** (444 mods, NeoForge 1.21.1). Just Replay doit enregistrer, relire,
éditer et exporter avec tous les mods de ce pack installés. La plupart des mods fonctionnent sans travail
spécifique, car l'enregistrement capture tous les paquets reçus par le client, y compris les paquets
personnalisés. Les mods ci-dessus demandent un traitement dédié, classés par niveau de risque :

- **Niveau 1, rendu** : Sodium, Iris, Create et Flywheel, les mods d'optimisation, Ixeris, Bobby.
- **Niveau 2, animations** : GeckoLib, les mods d'animation du joueur, Pehkui, les cosmétiques, les
  particules. Tout doit suivre l'horloge du replay (pause, vitesse, saut dans le temps).
- **Niveau 3, audio et médias** : Simple Voice Chat (piste audio des voix), les mods de son, WaterFrames,
  Exposure.
- **Niveau 4, interface** : FancyMenu, Spiffy HUD, JEI, Jade et les autres ne doivent pas gêner l'éditeur.
- **Niveau 5, réseau** : les mods Arcadia, NoChatReports, SkinRestorer, la suite FTB, KubeJS, Easy NPC et
  les véhicules rapides.

Protocole de test : un environnement de développement avec les mods principaux, puis le pack complet sur un
serveur (10 minutes enregistrées, relues, éditées et exportées). Chaque problème est consigné dans
`ERROR_LOG.md`.

### État des intégrations dédiées

Chaque intégration est optionnelle (réflexion ou mixins optionnels de `justreplay.compat.mixins.json`) et ne
fait rien quand le mod est absent. Le code se trouve dans `com.vyrriox.justreplay.compat`.

- **WaterFrames** : le temps de lecture d'un écran est recalculé à partir du curseur du replay, ancré sur le
  tick enregistré de chaque paquet qui charge ses données. Le lecteur WaterMedia suit : pause avec le replay,
  vitesse du replay, repositionné après un saut dans le temps ou s'il dérive de plus d'une seconde. En export,
  la vidéo reste en pause et est placée au temps exact de chaque image, son coupé. Code terminé, testé en
  partie : la vidéo synchronisée n'a pas encore été observée en jeu (le cadre du test automatique était posé
  trop tôt ; c'est corrigé dans le test).
- **Emotecraft** : les emotes des autres joueurs sont enregistrées telles quelles ; celles du joueur local
  (jamais renvoyées par le serveur) sont relevées à chaque tick et écrites comme les paquets reçus par un autre
  client, sur l'acteur du joueur enregistré. Les instantanés et points de reprise contiennent les emotes en
  cours, un saut dans le temps les démarre au bon moment, la pile d'animation suit l'horloge du replay. Les
  acteurs de l'éditeur ont une action `EMOTE` (paramètre : nom ou UUID de l'emote). Testé dans le pack
  Arcadia.
- **Create** : l'horloge d'animation de Create et celle du rendu du niveau (lue par les shaders de Flywheel)
  valent le curseur du replay : les éléments cinétiques s'arrêtent en pause, suivent la vitesse et retrouvent
  le même angle après un saut et en export. Les paquets du réseau ferroviaire, envoyés une seule fois par
  connexion, sont conservés et réécrits dans chaque instantané et point de reprise. Horloge testée dans le pack
  Arcadia ; trains et contraptions en mouvement non testés.
- **Mekanism** : aucune intégration nécessaire (données de blocs synchronisées par paquets). Machine posée dans
  le test, aucune erreur ; rendu non vérifié.
- **Distant Horizons** : pendant un replay, via l'API publique de DH, le rendu et la génération des LOD sont
  coupés et le monde DH passe en lecture seule, donc rien n'est sauvegardé pour le monde jetable du replay.
  Les réglages reviennent à la fin du replay. Testé avec DH 3.3.3 seul dans un client de développement : le test complet passe.
- **Bobby** : désactivé pendant un replay (inchangé).
- **Drop the Beam** : les lumières, faisceaux, lyres, brouillard et particules suivent le temps de jeu du niveau
  et n'ont besoin de rien. Tout ce qui suit un morceau (musique, cues de la console lumière, écrans vidéo, signaux
  réactifs au son) suit l'horloge du serveur estimée par `ServerClock`. Les paquets `clock_pong` enregistrés et un
  échantillon d'horloge écrit toutes les 2 s par l'enregistreur donnent l'instant serveur de chaque tick ; pendant
  un replay, `ServerClock` répond avec cet instant au temps du replay : pause, vitesse et sauts dans le temps
  suivis. L'état des platines, mixages, couches, morceaux proposés, shows et bibliothèques est conservé et réécrit
  dans chaque instantané et point de reprise. En lecture, la musique se tait en pause, hors x1 ou quand les sons du
  jeu sont coupés, et suit le volume des sons du jeu et l'atténuation du projet. En export, chaque voix est rendue
  hors ligne pour chaque image, placée par rapport à la caméra et mixée comme un son du jeu.
  `DropTheBeamCompat.songStartTicks()` donne le tick du replay où chaque morceau commence (et chaque répétition
  d'un morceau en boucle), aussi écrit dans le journal (`Drop the Beam: turntable ...`). Compilé avec les sources
  de Drop the Beam 0.1.0, pas encore testé en jeu.

Limites connues : un bloc modifié dans le tick exact où un enregistrement démarre en cours de partie peut
manquer ; en export, une vidéo WaterFrames peut avoir quelques images de retard ; les trains de Create dans un
enregistrement démarré en cours de partie ne sont pas testés ; Drop the Beam lit morceaux, vidéos et images
dans ses caches locaux, un replay regardé sur un autre ordinateur qui ne les a pas reste muet ou vide, et sa
musique est coupée hors x1.
