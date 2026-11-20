# Compatibility target / Cible de compatibilité

## English

Just Replay 1.2.0 runs on nine targets: Minecraft 1.20.1, 1.21.1 and 26.1.2 on NeoForge, Forge and Fabric.

### Status per target

Words used here. **Run** means the self test exercised it and its checks passed. **Loaded** means the mod was in the
self test and gave no error, but its behaviour was not exercised. **Read** means the integration was only read
against the mod's build and is enabled in `CompatTargets.java`, nothing was run. "Production" means the real jar in
an offline client outside a development environment, with no launcher.

The self test of 1.1.2 passes with 0 failures on all nine targets, in a development client and with the production
jar. The self test of 1.2.0 passes with 0 failures on all nine targets in a development client
(2026-11-20), with Armourer's Workshop 3.4.0-beta.3 installed on NeoForge 1.21.1 and 26.1.2 and on Fabric 1.20.1,
1.21.1 and 26.1.2. More Player Models looks passed it on NeoForge 1.21.1 (unofficial port) and Forge 1.20.1;
the production jars have not run with it yet.

| Target | Mods in the self test | Enabled but only read |
|---|---|---|
| NeoForge 1.21.1 | Development run: GeckoLib, Mowzie's Mobs, Curios, Iron's Spellbooks, Iris, Sodium, Entity Culling, Exposure, Accessories, Jade, Immersive Paintings, Immersive Aircraft, Immersive Vehicles: run. More Player Models (unofficial port `kaan-mpm.jar` 1.21.1.20261109-cat-scale, on NeoForge 21.1.265): run. Create 6.0.10 with Create Aeronautics 1.3.2, Sable 2.0.6 and Create Propulsion: Simulated 1.1.5 (on NeoForge 21.1.265: Sable and Aeronautics need 21.1.228 or later): loaded. Accessories on mobs not exercised. | Older integrations keep the status in the table below. |
| Fabric 1.21.1 | Production: GeckoLib, Entity Culling, Iris, Sodium, Jade, Exposure, Accessories, Immersive Paintings: run. Immersive Aircraft: run. Development run: Armourer's Workshop 3.4.0-beta.3: loaded, its hidden armor checks run. | Better Combat, Bobby, Custom NPCs, Distant Horizons, Emotecraft, Pehkui, Player Animator, Sable, WaterFrames |
| Forge 1.21.1 | Production: GeckoLib, Entity Culling: run. | None |
| NeoForge 1.20.1 | No third-party mod. | Armourer's Workshop, Curios, Custom NPCs, GeckoLib, Jade, More Player Models |
| Forge 1.20.1 | Production: GeckoLib, Jade, Entity Culling: run. Curios: run. Development run: Immersive Vehicles: run. More Player Models 1.20.1.20240409: run. | Armourer's Workshop, Custom NPCs, Epic Fight |
| Fabric 1.20.1 | Development run: Armourer's Workshop 3.4.0-beta.3: loaded, its hidden armor checks run. | Custom NPCs, Entity Culling, GeckoLib, Jade |
| NeoForge 26.1.2 | Production: GeckoLib, Iris, Sodium, Jade, Entity Culling: run. Development run: Immersive Vehicles: run; Armourer's Workshop 3.4.0-beta.3: loaded, its hidden armor checks run. | None |
| Fabric 26.1.2 | Production: GeckoLib, Iris, Sodium, Jade, Entity Culling: run. Development run: Armourer's Workshop 3.4.0-beta.3: loaded, its hidden armor checks run. | None |
| Forge 26.1.2 | Production: GeckoLib: run. | None |

On 1.20.1 and 26.1.2 the other integrations are removed: they would need rewriting and could not be tested there.

### Epic Fight, Armourer's Workshop and Custom NPCs per target

**On** means the integration was read against that jar and is enabled in `CompatTargets.java`: every class, member
and mixin target it names was found in the jar, and a typed check of those names compiled against it with the game
and loader classes of the target. None of them was run in game yet, except Armourer's Workshop, loaded in the self
test where its development client takes it (status table above). **No build** means the mod publishes nothing that
loads on that target (CurseForge and Modrinth, November 2026), so the integration stays off. Custom NPCs is the
unofficial port here (CurseForge `customnpcs-unofficial`): the official Custom NPCs stops at 1.16.5.

| Target | Epic Fight | Armourer's Workshop | Custom NPCs |
|---|---|---|---|
| NeoForge 1.21.1 | On: 21.17.3.1 | On: 3.4.0-beta.3 | On: builds 20241226 and 20251230 |
| Fabric 1.21.1 | No build | On: 3.4.0-beta.3 | On: build 20260521 |
| Forge 1.21.1 | No build | No build (its 1.21.1 jar named "forge" is a NeoForge build) | No build |
| NeoForge 1.20.1 | No build: 20.x needs Forge 47.4 or later, NeoForge 1.20.1 is 47.1 | On: the Forge jar of 3.4.0-beta.3 | On: the Forge jars of builds 20240824 and 20260711 |
| Forge 1.20.1 | On: 20.14.17 (the 20.15.2 beta is the same for these names) | On: 3.4.0-beta.3 | On: builds 20240824 and 20260711 |
| Fabric 1.20.1 | No build | On: 3.4.0-beta.3 | On: build 20251214 |
| NeoForge 26.1.2 | No build | On: 3.4.0-beta.3 | No build |
| Fabric 26.1.2 | No build | On: 3.4.0-beta.3 | No build |
| Forge 26.1.2 | No build | No build (its 26.1.2 jar named "forge" is a NeoForge build) | No build |

Not checked on any target: the look of real shader packs, real multiplayer recording on the new targets, and a real
launcher (CurseForge, Modrinth App, Prism). Self tests ran on Windows (one machine and GPU) and on Linux (a client
with no screen and software rendering); macOS is untested. A screenshot was read for Fabric 1.21.1, Forge 1.21.1,
NeoForge 26.1.2 and Forge 1.20.1 and looked right.

Reference pack for the NeoForge 1.21.1 target: **Arcadia V2** (444 mods). Just Replay must record, play back, edit and
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
| **ParCool** | A client sends its action changes (start, data, finish) to the server as `parcool:action.set` with its UUID; the server sends them to every player, batched in `parcool:action.compose`, and each client runs the action on the player with that UUID, which plays its animation. Other players' actions are recorded and replayed as they are. The recording player gets its own changes back flagged as sent by itself (ignored by ParCool): the recorder writes them for its actor's UUID instead, as received from the server (the packet the live client handles is untouched). Checkpoints and snapshots write the lasting actions (`ContinuableAction` in progress) of every player as a start with their synchronized values, read without touching ParCool's change flags. ParCool's serverbound packets are dropped during a replay. | Compiled against ParCool 4.0.1.6 (NeoForge 21.1.248 or newer), not yet tested in game. |
| **Better Combat** (player-animation-lib) | A client that attacks plays the animation and sends `bettercombat:attack_animation` (animation, hand, length, upswing, particles; `!STOP!` cancels) to the server, which sends it to the players tracking the attacker with its entity id; their clients play it on that player. Other players' attacks are recorded as they are. The server never sends an attack back to its attacker: the recorder copies the attack the client sends, with the local player's entity id (the actor keeps it), into the recording. Attacks already over at a seek target are dropped. Weapon poses follow the held item. The attack layer follows the replay clock through the player animation library mixins. Better Combat's serverbound packets are dropped during a replay. | Compiled against Better Combat 2.4.0 and player-animation-lib 2.0.4, not yet tested in game. |
| **Morph V2** (iChunUtil) | Morph sends a player's form (previous and next form, transition time) on `morph:morph_info` as `PacketMorphInfo`, keyed by the player's entity id, to every player tracking that player and to the player itself, on each morph and demorph and when a client first renders that player and asks for it. The client keeps a mob entity per morphed player, ticked from the player tick and drawn in place of the player. Form packets are recorded and replayed as they are; the actor uses the recording player's entity id, so the recording player's own forms land on it. A demorph turns into a "player form" naming the player by UUID (for its skin): in the recording player's forms that UUID is written as the actor's. Snapshots and checkpoints write the form each player has now (the client asked for it before the recording started). A form change handled during a seek starts its transition later by the skipped ticks; an acquisition animation long over at the target is dropped. The rest of the channel (morph list, biomass, the NBT editor screen) is the recording player's own session and is not replayed; Morph's serverbound packets are dropped during a replay. Optional mixins make the form follow the replay clock: it holds while paused and ticks once per recorded tick above x1. | Read against the sources and jar of Morph V2 0.1.4, compiled, not yet tested in game. |
| **Pehkui** | Pehkui sends an entity's scales on `pehkui:scale`, keyed by its entity id, to every player tracking it and to the player itself (`broadcastAndSend`), when tracking starts and on each change. These packets are recorded and replayed as they are; the actor uses the recording player's entity id, so the recording player's own scale lands on it. Snapshots and checkpoints write the non-default scales every entity has now (players and mobs), since the server sent them before the capture. A gradual resize (a scale tick delay) handled during a seek is moved forward by the skipped ticks. An optional mixin makes gradual resizes follow the replay clock: they hold while paused and tick once per recorded tick above x1. | Read against the jar of Pehkui 3.8.3 (1.21, NeoForge), compiled, not yet tested in game. |
| **Sable**, **Create Aeronautics**, **Simulated** | Sable sub-levels (airships and other moving block structures) are stored in plots of chunks about 20 million blocks away and drawn at their pose. Tracking start and stop, plot chunks, finalize, bounds, names and stop events travel through the connection (Veil payloads `sable:*` and normal chunk packets) and were already recorded. The poses (`sable:snapshot_packet`, `sable:snapshot_info_packet`) skip the connection: the singleplayer owner gets them by a direct call from the integrated server (`SableUDPServer.sendUDPPacketLocal`), a multiplayer client over Sable's UDP channel (`SableUDPChannelHandlerClient`). Two optional mixins catch both paths and record them as the same payloads Sable sends over TCP when UDP is off; the TCP fallback is recorded as it is. Playback hands them to Sable's client at their recorded tick and its snapshot interpolation smooths them; the send interval they carry is divided by the playback speed so the interpolation keeps pace away from x1. Snapshots and checkpoints write every sub-level the client knows (tracking start from its oldest buffered pose, its plot chunks with the plot light, finalize, the newer buffered poses, a stop for halted ones, then the interpolation clock), so a recording started mid-flight and seeks show the ships. Playback keeps plot chunks whatever their distance (they were refused as out of range before, which left ships empty) and never evicts them; a tracking start for a plot already in use is skipped. The replay world's own server cannot hand poses to the client, its TCP packets are blocked like any host packet. `sable:udp_activation`, `sable:freeze_player` and `sable:enter_gizmo_mode`, which act on the live player or connection, are dropped. Aeronautics and Simulated need nothing more: their blocks are Create block entities in the plot chunks, their packets (ropes, handles, contraption diagrams...) are Veil payloads on the connection, and their renderers read Create's animation clock. Simulated ropes are the exception: the server sends a rope (`simulated:rope_data`, then `simulated:rope_stopped` once it settles) only when the client starts tracking its owner block, and the client keeps it on that block entity, outside the chunk data, so snapshots and checkpoints rebuild those payloads from every rope the client knows (one per buffered point snapshot, then the stop), after the sub-levels so the owner blocks exist in the world or in a plot. Log lines at startup: `Sable detected: ...`, `Simulated detected: ...`. | Compiled against Sable 2.0.6 and Create Aeronautics bundled 1.3.2 (by reflection). Loaded in the self test with Create 6.0.10 and Create Propulsion: Simulated 1.1.5: both detection lines logged, no error. Ships and ropes are not part of the self test. |
| **Custom NPCs** (unofficial port) | An NPC's look (model or another entity's look, skin URL or texture, size, name, held items, animations) is NBT that Custom NPCs appends to the vanilla add entity packet; the client never asks for it. Later changes come in `PacketNpcUpdate`. The packet decoded on the client keeps those bytes but drops them when encoded again, so the recorder puts them back in the encoded bytes. Snapshots and checkpoints write each NPC's current look, read from the client entity, so NPCs present before the recording or changed since their spawn appear as they are, after seeks and in a later session too. Player skins set by Custom NPCs (sent once on join) are written into snapshots. Dialogs, GUIs, overlays, quest and achievement toasts of the recording player are not replayed, and Custom NPCs' serverbound packets are dropped during a replay. On 1.20.1 its packets are raw payloads: on Forge one channel with the message index first, on Fabric one channel per packet; the indexes are read from the running mod, since they change between builds. On Forge the look travels in Forge's own spawn message, or after a vanilla spawn when Custom NPCs' invisible NPCs are on (its default), so snapshots also write each NPC's current look as the update packet the server sends when an NPC changes. | Read against the jars of the builds listed in the table above, compiled, not yet tested in game. |
| **Armourer's Workshop** | Every Armourer's Workshop packet rides one custom payload channel, split in chunks. A wardrobe sync (`UpdateWardrobePacket`) names the entity by id and goes to the players tracking it and to the player itself; the actor uses the recording player's entity id, so the recording player's own wardrobe lands on it. Skin data is not in the wardrobe: wardrobes name skins by identifier (`db:...` once a skin is in a world's skin database). One skin loader (`SkinLoader`) serves the whole game, client and integrated server alike, and keeps loaded skins in a memory cache that is cleared when the client leaves a world. Where it reads a skin depends on how the world runs. On a dedicated server the client asks the server (`RequestSkinPacket`, answered by `ResponseSkinPacket`) or reads its own encrypted disk cache. In singleplayer (and on a LAN host) the integrated server prepares the loader for its own side, so a `db:` skin is read straight from that world's skin database and nothing crosses the connection. Just Replay therefore records skins at the loader, not on the network: every skin the loader finishes during a capture is written once as a compressed skin answer, whatever its source. Snapshots and checkpoints write every skin the loader holds, every skin a recorded entity's wardrobe or equipment references (read from the world's database in singleplayer if the loader does not hold it, asked of the server otherwise), and the wardrobe of every entity, players and actor included, because the server sends a wardrobe only on a change or when it starts tracking an entity. The log says how many skins each snapshot wrote, and warns about a worn skin the recording does not have. A replay runs in its own integrated world, whose skin database is empty, so the loader there would look every `db:` skin up in that empty database. During a replay an optional mixin on the loader's lookup serves every skin the replay played before any source is tried; a server skin the replay has not played yet waits for its recorded answer instead of failing. Played answers go into the loader directly (Armourer's Workshop hands an answer only to a pending request) and stay held for the whole replay. Skin requests to the replay host are dropped, or answered from the skins already played. A replay therefore needs no skin library, database, server or local cache, in the same session or after a restart. On 1.20.1, which has no typed payloads, the packets ride the channel the mod's dispatcher names. A hidden armor slot also hides the Armourer's Workshop skins that cover it (see "Hidden armor"). | Read against Armourer's Workshop 3.4.0-beta.3: sources and jar for NeoForge 1.21.1, jars for the other targets of the table above. Loaded in the self test (0 failures) on NeoForge 1.21.1 and 26.1.2 and Fabric 1.20.1, 1.21.1 and 26.1.2, where its hidden armor checks run; skins in replays not yet tested in game. |
| **Epic Fight** | Epic Fight draws battle mode players and patched mobs with its own animated models. Animations reach clients as `SPAnimatorControl` payloads keyed by entity id (players and mobs alike), living motions (idle, walk, run... per held weapon) as `SPChangeLivingMotion`, a player's mode (vanilla or battle) as `SetPlayerMode`; they are recorded and replayed as they are. What the recording player starts itself is played by its client and sent to the server, which forwards it to the players tracking it but not back to its sender: animation requests (`CPAnimatorControl`, with their animation variables), the mode key (`CPChangePlayerMode`), model yaw, movement input and animation position syncs. The recorder copies them as the payloads the trackers receive, with the local player's entity id (the actor keeps it); a request the server also answers to its sender is left to that answer (only its variables are copied). Animations the server plays on the player (hit reactions, server side skills, death) already come back to the client and are recorded as they are. Snapshots and checkpoints write, for every player (the actor included), its mode, and for every patched entity its living motions and the action animations running on its layers, as instant plays whose transition modifier carries the elapsed time in a marker (below -10000); playback turns the marker back into the animation's time once the packet is handled, so a recording started mid-fight shows the attack in progress. A seek drops action animations already over at the target (elapsed time from the skipped ticks and the animation's play speed) and plays the others at once, without their blend in, at the time they would have reached. Payloads that act on the receiving client's own player (`SPSkillFeedback`, `SPPlayUISound`, `SPAddLearnedSkill`, `SPRemoveSkillAndLearn`, `SPSetSkillContainerValue`, `SPInitSkills`) are not replayed, since they would land on the replay camera; Epic Fight's serverbound payloads are dropped during a replay. Two optional mixins make the client animator follow the replay clock: `ClientAnimator.tick` holds while paused and runs once per recorded tick above x1 (below x1 the client tick rate already slows down), and the pose blend (`AnimationPlayer.getCurrentPose`) holds on the current time while paused. Log line at the first use: `Epic Fight detected: ...`. On Forge 1.20.1 (Epic Fight 20.x) the messages are raw payloads on one Forge channel, `epicfight:network_manager`, read and written by Epic Fight's own code; the message indexes come from the running mod. Armor and held items on the models Epic Fight draws follow the hidden armor slots and arms, on both versions. | Read against Epic Fight 21.17.3.1 (NeoForge 1.21.1, jar and sources) and 20.14.17 (Forge 1.20.1, jar), compiled, not yet tested in game. |
| **Create** (Flywheel, Ponder) | Create's animation clock (`AnimationTickHolder`) and the level renderer clock read by Flywheel's shaders are set to the replay cursor: kinetic visuals stop with the replay, follow its speed and show the same angles after a seek and in exports. The train network packets (track graphs, edge groups, trains, roll calls), sent once per connection, are kept and written again in every snapshot and checkpoint. Contraptions keep their original spawn packets through the existing spawn cache; block entity data travels in chunk packets. | Clock tested in the Arcadia pack (the counter equals the cursor, stays still while paused). Trains and moving contraptions untested. |
| **Mekanism** | Machines are block entities synced by update packets and chunk data: no dedicated code. | Machine placed in the self test, no error in the log; visuals not inspected. |
| **Distant Horizons** | The distant terrain (LODs) of the recorded world shows in replays and exports. While recording, the DH level folder of each visited dimension (`IDhApiLevelWrapper.getDhSaveFolder()`, holding `DistantHorizons.sqlite`: `saves/<world>/data`, `saves/<world>/DIM-1/data`... in singleplayer, `Distant_Horizons_server_data/<server>/<dimension>` on a server) is written to `meta.json` (`distantHorizons`, relative to the game folder). When the replay opens, those databases are copied once to `justreplay/dh-session` (DH never opens the originals), and a save structure override (`IDhApiSaveStructure`, only for folders inside the replay world) points the replay world's levels at the copies. The DH world is set read only when its first level loads, before DH opens the database, so neither the void host chunks nor the replayed chunks change the LODs; distant generation is turned off with an API override. The copies are deleted once the replay world has closed. Without a copy (option `playback.distantHorizonsLods` off, recording made before this, missing database, database above `distantHorizonsMaxCopyMb`) LOD rendering is turned off for the replay as before. | Previous version (LODs off) tested with DH 3.3.3. LODs in replays: compiled against DH's public API names, not yet tested in game. |
| **GeckoLib** | GeckoLib's render clock and the tick its models read follow the replay: animated mobs, block entities, items, armor and textures hold while paused, follow the speed and advance by the exact frame time in an export. The clock never goes back after a seek, since a clock going back holds animations at 0. | Run by the self test on the targets whose row above lists it. Read on NeoForge and Fabric 1.20.1. On 26.1.2 it is GeckoLib 5. |
| **Curios** | A full sync is sent only when an entity starts being tracked, and later changes are slot deltas. Snapshots and checkpoints therefore write the worn items of every entity, so a recording started mid-session and seeks show them. | Run on NeoForge 1.21.1 and on Forge 1.20.1. Read on NeoForge 1.20.1. |
| **Iron's Spellbooks** | The recording player's cast packets are written for its actor, and snapshots and checkpoints carry cast state. Packets aimed at the receiving client's own player (mana, cooldowns, cast state, motion) are dropped, as are serverbound ones. | Run on NeoForge 1.21.1 only. |
| **Iris** | The frame time and frame counter seconds of shader packs are set from the replay clock: paused with the replay, at its speed, and the same at a given tick in every export. | Clock run by the self test. No human has looked at the result with real shader packs. |
| **Entity Culling** | The mod's global switch is saved, forced off during a replay (again every tick, so its key cannot bring it back) and restored at the end, since its culling thread uses wall clock time and would make exports differ. Replays with many entities can run slower. | Run on NeoForge, Fabric and Forge 1.21.1, Forge 1.20.1, NeoForge and Fabric 26.1.2. Read on Fabric 1.20.1. |
| **Exposure** | Photographs the client holds are written in snapshots and checkpoints. Recorded capture, export and shader packets, which would act on the viewer's game and disk, are dropped; requests to the replay host are blocked. | Run on NeoForge and Fabric 1.21.1. |
| **Accessories** | Slot definitions and worn accessories are written in snapshots and checkpoints, wrapped as the mod's own channel messages. Without the definitions, a fresh game would put every accessory in its invalid list. | Run on NeoForge and Fabric 1.21.1 on the player. Accessories on mobs not exercised. Beta mod. |
| **Jade** | With the clean HUD on, the tooltip is not computed during a replay and its requests never reach the replay host. A recorded packet that would rewrite the viewer's Jade settings is dropped. | Run on NeoForge and Fabric 1.21.1, Forge 1.20.1, NeoForge and Fabric 26.1.2. Read on NeoForge and Fabric 1.20.1. |
| **Immersive Paintings** | The painting list and the images in use are written in snapshots and checkpoints, as the payloads the server would send. Deletions are removed from recorded lists, so a replay never deletes the viewer's cache file. | Run on NeoForge and Fabric 1.21.1. |
| **Immersive Aircraft** | An aircraft asks the server for its equipment once. Snapshots and checkpoints write its slots, and its requests to the replay host are blocked. | Run on NeoForge and Fabric 1.21.1: an equipped biplane keeps its equipment at the start of a replay and after a backward seek. |
| **Immersive Vehicles** | A vehicle, a seat or an Immersive Vehicles block reaches the client as an empty shell that asks the server for its data once. Snapshots and checkpoints write that data as the server answers, and its requests to the replay host are blocked. The mod draws all its vehicles through one invisible entity that must follow the local player: a replay gets its own, that follows the camera, and recorded ones no longer draw. | Run on NeoForge 1.21.1 and Forge 1.20.1 with Immersive Vehicles 24.0.0 and its Official Content Pack, and on NeoForge 26.1.2 with Immersive Vehicles 24.0.0-26.1 and the R.A.G.E 3.0 beta 2 pack (the official pack has no 26.1 build): a car placed before the recording has its data at the start of a replay and after a backward seek. NeoForge 26.1.2 in a development client only. Forge 1.20.1 in a development client only, the mod remapped by `-PdevMods`. Driving and physics during a replay not checked by a human. |
| **More Player Models** (Noppes, Forge build for 1.20.1; unofficial NeoForge 1.21.1 port) | A player's look (body proportions, added parts, entity model, skin URL, name, animation) is NBT the server sends once, five seconds after a client starts tracking the player, then on each change; every message names its player by UUID (in the 1.21.1 port, one typed payload per message, rewritten through its own codec). They are recorded as they are, and the ones about the recording player (the server sends a player its own look) are written for its actor. Snapshots and checkpoints write the look and the back item of every player, the actor's at each spawn. On a server without the mod no packet carries a look (each client reads its own files and the mod's web service): during a capture the looks, and the recording player's animations, are written as they change. During a replay the mod's requests to the replay host are dropped, its editor stays closed, its web service is not asked, and a recorded look that names the viewer's own UUID is dropped, since the mod would save it as the viewer's own. The parts the mod adds and its back item follow hidden body parts; hidden armor works as on a vanilla player. | 1.20.1: read against the jar of build 1.20.1.20240409 (Forge, the same file NeoForge 47.1 loads). Every class, member and mixin target was checked against it with the classes of Forge and NeoForge 1.20.1. Run in a Forge 1.20.1 development client (2026-11-20). NeoForge 1.21.1: Noppes made no build, the integration follows the unofficial port `kaan-mpm.jar` (mod id `moreplayermodels`, version 1.21.1.20261109-cat-scale, nested in the CurseForge bundle kaanstyle 1.0.15, needs NeoForge 21.1.250 or later), checked against that jar and run in a development client (2026-11-20). No build for Fabric, Forge 1.21.1 or 26.1.2: off there. |
| **Bobby** | Bobby reports itself disabled while a replay runs (no cached chunks, nothing saved). | Unchanged. |
| **Drop the Beam** | Lights, beams, moving heads, haze and particles follow the level game time and need nothing. Everything that follows a song (music, light desk cues, video screens, sound reactive signals) follows the server clock as `ServerClock` estimates it. **Clock:** the recorded `clock_pong` packets plus a clock sample the recorder writes every 2 s (a pong carrying a marker, with the server instant the recording client estimated) map each recorded tick to the server instant; they are read ahead in the background when a replay opens and while playing. During a replay `ServerClock` answers with that mapping at the frame's replay time (cursor and partial tick): it stops with the replay, follows its speed and jumps with seeks. Recorded pongs never reach Drop the Beam; its pings and requests are not sent to the replay host; its "pause with the single-player game" logic is off during a replay. **Recording:** the latest music state, mix and layers per turntable, song offers and analyses, shows per desk, features and libraries are kept per connection and written into every snapshot and checkpoint, so a recording started mid-session plays the music already running. **Live audio:** fed silence while the replay is paused, away from x1 (songs are rendered at their natural pitch) or its game sounds are muted; its volume follows the project's game volume and ducking. **Export:** its audio thread renders nothing during an export; every voice is rendered offline for each frame with the clock pinned to that frame, attenuated (linear to its range, jukebox volume) and panned relative to the camera, then mixed as a game sound (game volume, mute, ducking). **Song starts:** `DropTheBeamCompat.songStartTicks()` gives the replay tick (with decimals) where each song is heard from its start, and each repeat of a song looping on its own; they are logged as `Drop the Beam: turntable ... starts "title" at replay tick ...`. | Compiled against Drop the Beam 0.1.0 sources, not yet tested in game. |

### Hidden armor (every target)

The editor's "Armor shown" switches hide the helmet, chestplate, leggings or boots of a recorded player (**Entities**
tab) or of an actor (actor screen), one by one; the `hidePlayerArmor` setting hides all four on every player, actors
included. A hidden helmet also takes a pumpkin or a mob head worn instead, a hidden chestplate the elytra. Only the
drawing changes, in the replay and in exported videos: the entity keeps its equipment, nothing changes outside a
running replay, the replay camera is never affected, and projects saved before this option keep their armor.

The render hooks are optional mixins (`require = 0`) on the vanilla armor, elytra and head item layers, written for
the signatures of each version: the vanilla ones on 1.20.1 and 1.21.1 (plus NeoForge's own overload on NeoForge
1.21.1), and the new render state layers on 26.1.2. The self test checks the mask, the pieces the armor layer itself
skipped, the camera player and the global setting: it passes on the nine targets in a development client, and a
capture of NeoForge 1.21.1 shows the actor's helmet gone and its chestplate kept. Nobody has played with it in a
real game yet. A hook that does not apply fails silently: the armor then simply stays visible.

Limits:

- Mobs, Custom NPCs NPCs and other non-player entities are not covered: the switches only show for players, and the
  setting only targets players and actors.
- Armourer's Workshop draws its skins (wardrobe and skinned armor) itself, from the items it collects for each
  entity once a tick, and reloads them when those items change. Optional mixins on its two collectors (equipment
  and wardrobe, in `EntitySlotsHandler`) leave the hidden slots out, so a hidden slot hides the skin of the piece
  worn there and the wardrobe skins of that slot. Wings go with the chestplate like an elytra; an outfit, drawn as
  one piece over the whole body, goes as soon as one of the four slots is hidden. Weapons, tools, shields and
  backpacks are not armor and stay. Only players tick in a paused replay, so the skins of any other entity are
  collected again as soon as its hidden slots change. Names checked against Armourer's Workshop 3.4.0-beta.3 on
  its seven targets. The self test checks it with that version installed (NeoForge 1.21.1 and 26.1.2, Fabric
  1.20.1, 1.21.1 and 26.1.2): the hidden helmet leaves the armor Armourer's Workshop collected while the
  chestplate stays, the global setting takes the chestplate away and back without the actor ticking, and wardrobe
  skins of each kind follow their slots. Its Forge 1.20.1 build cannot start in a development client and NeoForge
  1.20.1 loads no released mod there: on those two only the names were checked. No real skin was looked at yet.
- Epic Fight models (battle mode players): armor and held items follow the hidden slots and arms, on NeoForge 1.21.1
  and Forge 1.20.1.
- More Player Models (1.20.1 and the NeoForge 1.21.1 port): hidden armor works as on a vanilla player, except on a
  player drawn as another entity
  (an entity model), which keeps its armor.
- Armor a mod draws with its own layer instead of the vanilla armor layer is not covered.

### When an integration fails

Big modpacks mix versions nobody tested together, so every integration has a safety net:

- An integration runs only when its mod is installed, it is supported on the running loader (`CompatTargets`) and
  the config leaves it on. Its mixins apply under the same conditions (`CompatMixinPlugin`).
- Every call into another mod goes through the guard of its integration (`Integration`). The first error (a method
  or class that changed, `NoSuchMethodError`, `NoClassDefFoundError`, a crash in the other mod's code) turns that
  integration off until the next start, with one warning in the log naming it and the hook that failed. What it
  changed in its mod (Entity Culling's switch, the Distant Horizons folder override) is still put back when the
  replay ends.
- To turn an integration off by hand: `compat.<mod id>` set to `false` in `config/justreplay-client.json`, or its
  switch in the settings screen (shown for the installed mods). It applies at the next start, mixins included.
- A packet that cannot be encoded, read back or played (a mod payload, most of the time) is skipped and counted: the
  recording or the replay goes on. The first of each type logs a warning, and a `Recording: N packets skipped (...)`
  or `Replay: N packets skipped (...)` line sums them up at the end.
- The mixins tolerate a method another mod rewrote (`defaultRequire` 0): that hook is lost, the game starts.
- At startup Just Replay logs one line with its version, the loader, Minecraft and the state of each integration
  found, for example `Just Replay 1.2.0 on neoforge 21.1.221, Minecraft 1.21.1. Integrations on: Curios, Jade; off in
  the config: GeckoLib; installed but not supported on this loader: none; not installed: 26.` Read it first in a
  crash log.
- The self test checks this net in game on every target. It turns one integration off at startup
  (`-PselftestFailCompat`, Simple Voice Chat by default, counted as installed when it is not), fires its hooks
  300 times during the replay, and writes an unreadable packet (`packet #16383`) into its recording. Expected: one
  warning, `failed: Simple Voice Chat (in self test forced failure)` in the startup line,
  `packet #16383 could not be read` in the `Replay: N packets skipped (...)` line, and the replay plays on.
  Run on the nine targets on 2026-11-20: as expected everywhere (one warning per game, the packet counted 6 or 7
  times in the main replay because of its seeks, once in the buffer clip).

### Known limits

- A block changed in the very tick a recording starts in the middle of a session can be missed: the packet is
  captured before the start but handled by the client after the snapshot.
- WaterFrames in exports: WaterMedia seeks are asynchronous, so a video can lag a few frames behind the exported
  time on a fast export.
- Create trains in a recording started mid-session depend on the kept train network packets; this path is untested.
- Sable: in singleplayer the first pose of a sub-level can be recorded a tick before its tracking start (the pose
  is taken on the server thread, the tracking start on the connection); Sable then logs one "non-existent
  sub-level" error for it. Aeronautics propellers turn in their block entity tick, which runs once per client
  tick: above x1 they spin slower than recorded. Sable adds a byte to some entity movement packets, so a recording
  made with Sable needs Sable to play back, and the other way round.
- ParCool: the recording player's actions come from the server's echo, so a recording on a server without
  ParCool has none (ParCool needs it on both sides anyway). Short actions (vault, roll, dodge) cut by a seek are
  not resumed; only lasting actions are written in checkpoints. ParCool animates players with its own renderer,
  not the player animation library: its animations may keep moving while the replay is paused (unverified).
- Better Combat: an attack is replayed from its start when a seek lands in it (the attack animation has no start
  tick to resume from). Recordings made before this version have no attacks for the recording player.
- Epic Fight: action animations written by a snapshot or moved by a seek start without their blend in. Skills
  the recording player executed that only change client state (no animation sent) are not replayed, and its
  actor has no skill book. Animation sound and particle events fire from the replayed animations on the client,
  as for any tracked player; whether this doubles the recording player's own swing sounds (also recorded as
  local sounds) is unverified. Weapon and mob data come from the replay world's own datapacks, which must match
  the recorded ones. Recordings made before this version have no animations for the recording player. On Forge
  1.20.1 an animation is an int id on the wire, given by Epic Fight's registration order and datapacks: a recording
  replays right only with the same Epic Fight version, addons and datapacks. There the recording player turning its
  model yaw lock off is not copied for its actor (Epic Fight's own handler of that message falls into a mode change
  with no mode), so the actor keeps its last model yaw until the next one.
- Hidden armor: see "Hidden armor (every target)" above.
- Morph: the form of a player is known only once its client asked for it (first time it renders that player), so
  a player never seen by the recording client before the recording started replays in its form only from the
  server's answer on. The morph selector and biomass HUD of the recording player are not replayed.
- Custom NPCs: a recording made before this version has no NPC looks (the add entity packets were recorded without them). Skins given as a URL are downloaded again at replay time, so an offline client or a dead link shows the default skin. Chat bubbles and NPC chat lines are replayed as recorded.
- More Player Models: no build exists for Fabric, Forge 1.21.1 or 26.1.2, so nothing is enabled there. On NeoForge 1.21.1 the integration follows the unofficial port `kaan-mpm.jar` only. Its kaanstyle bundle (stylem) requires seven other mods of its author that it does not include; the nested `kaan-mpm.jar` loads on its own. Another port or a new build is checked by name the first time it is used: when a class or member differs, the integration turns itself off with a warning. A player drawn as another entity (an entity model) ignores hidden body parts and hidden armor. A part that spans both arms or both legs goes only when both are hidden. Chat bubbles and eye blinks follow real time, not the replay clock. A skin given as a URL is downloaded again at replay time. Your own character in someone else's recording shows the look you have now. Until a player's recorded look plays (up to five seconds after it appears, as on a live server), the mod shows the look it last saw of that player. A recording made before this version has no looks in its snapshots: they show when their packets play, and a seek loses them.
- Armourer's Workshop: each snapshot and checkpoint carries every skin the client holds or a recorded entity wears,
  which makes recordings larger when many skins are loaded. On a server, a worn skin the client never loaded is
  asked for at the snapshot and recorded when its answer arrives. Singleplayer recordings made with 1.0.9 hold only
  the skins the client had loaded when they started; recordings made before 1.0.9 have no skins written at their start.
- A recording made with a mod that is missing at replay time plays without that mod's packets (logged once per
  payload as `Skipping recorded ... packets`).
- Drop the Beam: song, video and image files come from the local caches (`dropthebeam/song-cache`, `video-cache`,
  `image-cache`); a replay watched on another computer that lacks them stays silent or blank, since there is no
  server to send them. A replay recorded before this integration has only the real pongs as clock (one every 2 s,
  late by half a round trip). Music is muted at speeds other than x1.
- Distant Horizons: the LODs shown are the ones on disk when the replay opens, not the ones of the recorded
  moment (terrain changed since then shows changed far away). Recordings made before this version, or on another
  computer, have no LOD database to read and play without LODs. A dimension the replay world does not have
  (a mod dimension missing from the installed mods) shows no LODs. Opening a replay copies each recorded
  dimension's database once (time and disk writes grow with its size; `distantHorizonsMaxCopyMb` caps it). The
  instant replay buffer notes only the dimension the player is in when the clip is saved. LODs load from the copy
  in the background: the first seconds of an export may show them popping in (raise `export.warmupFrames`).

### Known limits of the targets

- All Fabric: the clean HUD cannot hide other mods' HUD callbacks on 1.20.1 and 1.21.1. No key modifiers. No settings
  button without Mod Menu.
- Forge 1.21.1 and 26.1.2: the text shadow toggle has no effect, since Forge's text field has no such option.
- 1.20.1: pause and slow motion are rebuilt without a vanilla tick rate manager (they passed the self test). Ogg audio
  files over 2 channels are refused. The editor timeline has no horizontal wheel scroll. Screens show dirt instead of
  the panorama.
- 26.1.2: the section buffer guard is removed, the spawn chunk radius rule is dropped, and GeckoLib spawn animations
  likely do not play in replays.
- A recording opens only on the Minecraft version and the loader it was made on.
- GeckoLib: animations triggered before a recording start or a seek target are not restored, and looping idle phases
  can differ after a backward seek.
- Iris and Entity Culling: exports with shader packs still need a look by a person. Entity Culling off can slow down
  replays with many entities.

### Tier 1: rendering pipeline (blocking for playback and export)

| Mod | Risk | Plan |
|---|---|---|
| Sodium 0.8 (NeoForge), Sodium Extras, Reese's Sodium Options | Chunk rebuild on time jumps, frame capture | Force full chunk rebuild after a seek, read the framebuffer after Sodium's pass |
| Iris 1.8 | Shader pipeline during export, depth for depth of field | Export through Iris' final pass, test with 2 shader packs |
| Create 6 + Flywheel (bundled) | Contraptions and instanced visuals tick on client time | Drive Flywheel's partial ticks from replay time, test trains and contraptions while seeking |
| ImmediatelyFast, EntityCulling, ModernFix, FerriteCore | Batching and culling may hide entities in free camera | Disable entity culling while a replay is open, check batching with frame capture |
| Ixeris | Moves window and event handling off the render thread | Check input in the editor (ImGui) and frame pacing during export |
| Bobby (reneoforged) | Serves cached chunks, would mix stale terrain into replays | Disable Bobby while a replay world is loaded |
| Distant Horizons | Not in this pack | Recorded LODs read from a temporary copy, generation and saving off during a replay (see above) |

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

1. Dev run with a subset (Sodium, Iris, Create, GeckoLib mods, Voice Chat, Emotecraft) in `localRuntime`, or the
   whole `mods` folder of a pack with `tools/test_matrix.py --mods` ([TEST_MATRIX.md](TEST_MATRIX.md)).
2. Full pack run: copy the built jar into the Arcadia V2 instance, record 10 minutes on a server, then play,
   seek, edit and export.
3. Any crash or visual difference is logged in `ERROR_LOG.md` and gets a compatibility module.

## Français

Just Replay 1.2.0 tourne sur neuf cibles : Minecraft 1.20.1, 1.21.1 et 26.1.2 sur NeoForge, Forge et Fabric.

### État par cible

Vocabulaire. **Testé** veut dire que le test automatique l'a exécuté et que ses vérifications passent. **Chargé** veut
dire que le mod était dans le test sans erreur, mais que son comportement n'a pas été exercé. **Lu** veut dire que
l'intégration a seulement été lue d'après le build du mod et qu'elle est active dans `CompatTargets.java` : rien n'a
été exécuté. « Production » désigne le vrai jar dans un client hors ligne, hors environnement de développement et sans
launcher.

Le test automatique de la 1.1.2 passe avec 0 échec sur les neuf cibles, en client de développement et avec le jar de
production. Celui de la 1.2.0 passe avec 0 échec sur les neuf cibles en client de développement
(20/11/2026), avec Armourer's Workshop 3.4.0-beta.3 installé sur NeoForge 1.21.1 et 26.1.2 et sur Fabric 1.20.1,
1.21.1 et 26.1.2. Les apparences de More Player Models y passent sur NeoForge 1.21.1 (portage non officiel) et
Forge 1.20.1 ; les jars de production n'ont pas encore tourné avec.

| Cible | Mods dans le test automatique | Actives mais seulement lues |
|---|---|---|
| NeoForge 1.21.1 | Développement : GeckoLib, Mowzie's Mobs, Curios, Iron's Spellbooks, Iris, Sodium, Entity Culling, Exposure, Accessories, Jade, Immersive Paintings, Immersive Aircraft, Immersive Vehicles : testés. More Player Models (portage non officiel `kaan-mpm.jar` 1.21.1.20261109-cat-scale, sur NeoForge 21.1.265) : testé. Create 6.0.10 avec Create Aeronautics 1.3.2, Sable 2.0.6 et Create Propulsion: Simulated 1.1.5 (sur NeoForge 21.1.265 : Sable et Aeronautics demandent 21.1.228 ou plus) : chargés. Accessories sur les mobs non exercé. | Les anciennes intégrations gardent l'état écrit plus bas. |
| Fabric 1.21.1 | Production : GeckoLib, Entity Culling, Iris, Sodium, Jade, Exposure, Accessories, Immersive Paintings : testés. Immersive Aircraft : testé. Développement : Armourer's Workshop 3.4.0-beta.3 : chargé, ses vérifications d'armure masquée tournent. | Better Combat, Bobby, Custom NPCs, Distant Horizons, Emotecraft, Pehkui, Player Animator, Sable, WaterFrames |
| Forge 1.21.1 | Production : GeckoLib, Entity Culling : testés. | Aucune |
| NeoForge 1.20.1 | Aucun mod tiers. | Armourer's Workshop, Curios, Custom NPCs, GeckoLib, Jade, More Player Models |
| Forge 1.20.1 | Production : GeckoLib, Jade, Entity Culling : testés. Curios : testé. Développement : Immersive Vehicles : testé. More Player Models 1.20.1.20240409 : testé. | Armourer's Workshop, Custom NPCs, Epic Fight |
| Fabric 1.20.1 | Développement : Armourer's Workshop 3.4.0-beta.3 : chargé, ses vérifications d'armure masquée tournent. | Custom NPCs, Entity Culling, GeckoLib, Jade |
| NeoForge 26.1.2 | Production : GeckoLib, Iris, Sodium, Jade, Entity Culling : testés. Développement : Immersive Vehicles : testé ; Armourer's Workshop 3.4.0-beta.3 : chargé, ses vérifications d'armure masquée tournent. | Aucune |
| Fabric 26.1.2 | Production : GeckoLib, Iris, Sodium, Jade, Entity Culling : testés. Développement : Armourer's Workshop 3.4.0-beta.3 : chargé, ses vérifications d'armure masquée tournent. | Aucune |
| Forge 26.1.2 | Production : GeckoLib : testé. | Aucune |

En 1.20.1 et 26.1.2, les autres intégrations sont retirées : il faudrait les réécrire et elles n'étaient pas testables.

### Epic Fight, Armourer's Workshop et Custom NPCs par cible

**Actif** : l'intégration a été lue sur ce jar et activée dans `CompatTargets.java` : chaque classe, membre et cible de
mixin qu'elle nomme a été trouvé dans le jar, et un contrôle typé de ces noms a compilé contre lui avec les classes du
jeu et du chargeur de la cible. Aucune n'a encore tourné en jeu, sauf Armourer's Workshop, chargé dans le test
automatique là où son client de développement le prend (tableau d'état plus haut). **Pas de build** : le mod ne publie rien qui se
charge sur cette cible (CurseForge et Modrinth, novembre 2026), l'intégration reste éteinte. Custom NPCs désigne ici
le portage non officiel (CurseForge `customnpcs-unofficial`) : le Custom NPCs officiel s'arrête à la 1.16.5.

| Cible | Epic Fight | Armourer's Workshop | Custom NPCs |
|---|---|---|---|
| NeoForge 1.21.1 | Actif : 21.17.3.1 | Actif : 3.4.0-beta.3 | Actif : builds 20241226 et 20251230 |
| Fabric 1.21.1 | Pas de build | Actif : 3.4.0-beta.3 | Actif : build 20260521 |
| Forge 1.21.1 | Pas de build | Pas de build (son jar 1.21.1 nommé « forge » est un build NeoForge) | Pas de build |
| NeoForge 1.20.1 | Pas de build : la 20.x demande Forge 47.4 ou plus, NeoForge 1.20.1 est en 47.1 | Actif : le jar Forge de la 3.4.0-beta.3 | Actif : les jars Forge des builds 20240824 et 20260711 |
| Forge 1.20.1 | Actif : 20.14.17 (la bêta 20.15.2 est identique pour ces noms) | Actif : 3.4.0-beta.3 | Actif : builds 20240824 et 20260711 |
| Fabric 1.20.1 | Pas de build | Actif : 3.4.0-beta.3 | Actif : build 20251214 |
| NeoForge 26.1.2 | Pas de build | Actif : 3.4.0-beta.3 | Pas de build |
| Fabric 26.1.2 | Pas de build | Actif : 3.4.0-beta.3 | Pas de build |
| Forge 26.1.2 | Pas de build | Pas de build (son jar 26.1.2 nommé « forge » est un build NeoForge) | Pas de build |

Vérifié sur aucune cible : le rendu de vrais shader packs, un vrai enregistrement multijoueur sur les nouvelles cibles,
un vrai launcher (CurseForge, Modrinth App, Prism). Les tests automatiques ont tourné sous Windows (une seule machine
et une seule carte graphique) et sous Linux (client sans écran, rendu logiciel) ; macOS n'est pas testé. Une capture a
été lue pour Fabric 1.21.1, Forge 1.21.1, NeoForge 26.1.2 et Forge 1.20.1 et semblait correcte.

Pack de référence pour la cible NeoForge 1.21.1 : **Arcadia V2** (444 mods). Just Replay doit enregistrer, relire,
éditer et exporter avec tous les mods de ce pack installés. La plupart des mods fonctionnent sans travail
spécifique, car l'enregistrement capture tous les paquets reçus par le client, y compris les paquets
personnalisés. Les mods ci-dessous demandent un traitement dédié, classés par niveau de risque :

- **Niveau 1, rendu** : Sodium, Iris, Create et Flywheel, les mods d'optimisation, Ixeris, Bobby.
- **Niveau 2, animations** : GeckoLib, les mods d'animation du joueur, Pehkui, les cosmétiques, les
  particules. Tout doit suivre l'horloge du replay (pause, vitesse, saut dans le temps).
- **Niveau 3, audio et médias** : Simple Voice Chat (piste audio des voix), les mods de son, WaterFrames,
  Exposure.
- **Niveau 4, interface** : FancyMenu, Spiffy HUD, JEI, Jade et les autres ne doivent pas gêner l'éditeur.
- **Niveau 5, réseau** : les mods Arcadia, NoChatReports, SkinRestorer, la suite FTB, KubeJS, Easy NPC et
  les véhicules rapides.

Protocole de test : un environnement de développement avec les mods principaux (ou tout le dossier `mods` d'un
pack avec `tools/test_matrix.py --mods`, voir [TEST_MATRIX.md](TEST_MATRIX.md)), puis le pack complet sur un
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
- **Distant Horizons** : le terrain lointain (LOD) du monde enregistré s'affiche dans les replays et les
  exports. Pendant l'enregistrement, le dossier DH de chaque dimension visitée (celui qui contient
  `DistantHorizons.sqlite` : `saves/<monde>/data`... en solo, `Distant_Horizons_server_data/<serveur>/<dimension>`
  sur un serveur) est noté dans `meta.json` (`distantHorizons`, relatif au dossier du jeu). À l'ouverture du
  replay, ces bases sont copiées une fois dans `justreplay/dh-session` (DH n'ouvre jamais les originaux) et une
  surcharge de structure de sauvegarde (`IDhApiSaveStructure`, seulement pour les dossiers du monde du replay)
  dirige les niveaux du replay vers les copies. Le monde DH passe en lecture seule dès le chargement de son
  premier niveau, avant l'ouverture de la base ; la génération lointaine est coupée. Les copies sont supprimées
  une fois le monde du replay fermé. Sans copie (option `playback.distantHorizonsLods` coupée, enregistrement
  plus ancien, base absente ou plus grosse que `distantHorizonsMaxCopyMb`), le rendu des LOD est coupé comme
  avant. Compilé, pas encore testé en jeu.
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
- **ParCool** : un client envoie ses changements d'action au serveur (`parcool:action.set`, avec son UUID), le
  serveur les renvoie à tous les joueurs (`parcool:action.compose`) et chaque client joue l'action, donc son
  animation, sur le joueur de cet UUID. Les actions des autres joueurs sont enregistrées telles quelles. Le joueur
  enregistré reçoit aussi les siennes, marquées comme envoyées par lui (ignorées par ParCool) : l'enregistreur les
  écrit pour l'UUID de son acteur, comme reçues du serveur. Les points de reprise et instantanés contiennent les
  actions durables en cours de chaque joueur, avec leurs valeurs synchronisées. Les paquets de ParCool vers le
  serveur sont bloqués pendant un replay. Compilé avec ParCool 4.0.1.6 (NeoForge 21.1.248 ou plus récent), pas
  encore testé en jeu.
- **Better Combat** : un client qui attaque joue l'animation et envoie `bettercombat:attack_animation` au
  serveur, qui la renvoie aux joueurs proches avec l'id d'entité de l'attaquant. Les attaques des autres joueurs
  sont enregistrées telles quelles ; celle du joueur local (jamais renvoyée) est copiée depuis le paquet envoyé,
  avec son id d'entité, que l'acteur conserve. Une attaque déjà finie à la cible d'un saut dans le temps est
  ignorée ; les poses d'arme suivent l'objet tenu. Les paquets de Better Combat vers le serveur sont bloqués
  pendant un replay. Compilé avec Better Combat 2.4.0 et player-animation-lib 2.0.4, pas encore testé en jeu.
- **Morph V2** : Morph envoie la forme d'un joueur (forme précédente et suivante, durée de la transformation) sur
  `morph:morph_info` avec l'id d'entité du joueur, aux joueurs qui le voient et au joueur lui-même, à chaque
  transformation et retour à la forme humaine, et quand un client la demande la première fois qu'il affiche ce
  joueur. Le client garde une entité de mob par joueur transformé, mise à jour au tick du joueur et dessinée à sa
  place. Ces paquets sont enregistrés tels quels ; l'acteur reprend l'id d'entité du joueur enregistré, ses
  propres formes arrivent donc sur lui. La forme humaine nomme le joueur par son UUID (pour son skin) : dans les
  formes du joueur enregistré, c'est l'UUID de l'acteur qui est écrit. Les instantanés et points de reprise
  contiennent la forme actuelle de chaque joueur. Un changement de forme lu pendant un saut dans le temps reprend
  sa transformation plus loin ; une animation d'acquisition déjà finie est ignorée. Le reste du canal (liste des
  formes, biomasse, écran d'édition NBT) appartient à la session du joueur enregistré et n'est pas rejoué ; les
  paquets de Morph vers le serveur sont bloqués pendant un replay. Des mixins optionnels font suivre l'horloge du
  replay à la forme : figée en pause, un tick par tick enregistré au-delà de x1. Lu dans les sources et le jar de
  Morph V2 0.1.4, compilé, pas encore testé en jeu.
- **Pehkui** : Pehkui envoie les échelles d'une entité sur `pehkui:scale` avec son id d'entité, aux joueurs qui
  la voient et au joueur lui-même, au début du suivi et à chaque changement. Ces paquets sont enregistrés tels
  quels ; l'acteur reprend l'id d'entité du joueur enregistré, sa propre taille arrive donc sur lui. Les
  instantanés et points de reprise contiennent les échelles actuelles de chaque entité (joueurs et mobs). Un
  changement progressif (délai de tick) lu pendant un saut dans le temps reprend plus loin. Un mixin optionnel
  fait suivre l'horloge du replay aux changements progressifs : figés en pause, un tick par tick enregistré
  au-delà de x1. Lu dans le jar de Pehkui 3.8.3 (1.21, NeoForge), compilé, pas encore testé en jeu.
- **Custom NPCs** (portage non officiel) : l'apparence d'un PNJ (modèle ou apparence d'une autre entité, skin par URL ou
  texture, taille, nom, objets tenus, animations) est un NBT que Custom NPCs ajoute au paquet vanilla d'apparition d'entité ; le client ne
  la demande jamais. Les changements suivants arrivent par `PacketNpcUpdate`. Le paquet décodé côté client garde ces octets mais les perd
  quand il est réencodé : l'enregistreur les remet dans les octets enregistrés. Les instantanés et points de reprise écrivent l'apparence
  actuelle de chaque PNJ, lue sur l'entité du client : les PNJ présents avant l'enregistrement ou modifiés depuis leur apparition
  s'affichent tels quels, après un saut dans le temps et dans une session ultérieure. Les skins de joueurs définis par Custom NPCs
  (envoyés une fois à la connexion) sont écrits dans les instantanés. Dialogues, interfaces, overlays, notifications de quête et de
  succès du joueur enregistré ne sont pas rejoués, et les paquets de Custom NPCs vers le serveur sont bloqués pendant un replay. En
  1.20.1 ses paquets sont des charges brutes : sur Forge un seul canal avec l'index du message en tête, sur Fabric un canal par paquet ;
  les index sont lus sur le mod en marche, car ils changent d'un build à l'autre. Sur Forge l'apparence voyage dans le message
  d'apparition de Forge, ou après une apparition vanilla quand les PNJ invisibles de Custom NPCs sont actifs (son réglage par défaut) :
  les instantanés écrivent donc aussi l'apparence actuelle de chaque PNJ, sous la forme du paquet de mise à jour que le serveur envoie
  quand un PNJ change. Lu dans les jars des builds du tableau plus haut, compilé, pas encore testé en jeu.
- **Armourer's Workshop** : tous les paquets d'Armourer's Workshop passent par un seul canal, découpés en morceaux.
  La garde-robe d'une entité (`UpdateWardrobePacket`) la désigne par son identifiant d'entité ; l'acteur reprend celui
  du joueur enregistré, sa propre garde-robe arrive donc sur l'acteur. Les données des skins ne sont pas dans la
  garde-robe, qui les désigne par identifiant (`db:...` une fois le skin dans la base de skins d'un monde). Un seul
  chargeur de skins (`SkinLoader`) sert tout le jeu, client et serveur intégré compris, et garde les skins chargés
  dans un cache mémoire vidé quand le client quitte un monde. Sur un serveur dédié, le client demande chaque skin au
  serveur (`RequestSkinPacket`, réponse `ResponseSkinPacket`) ou le lit dans son cache disque chiffré. En solo (et
  sur un hôte LAN), le serveur intégré prépare le chargeur pour son propre côté : un skin `db:` est lu directement
  dans la base de skins du monde et rien ne passe par la connexion. Just Replay enregistre donc les skins au niveau
  du chargeur et non du réseau : chaque skin que le chargeur termine pendant une capture est écrit une fois comme
  réponse compressée, quelle que soit sa source. Les instantanés et points de reprise écrivent chaque skin que le
  chargeur détient, chaque skin référencé par la garde-robe ou l'équipement d'une entité enregistrée (lu dans la
  base du monde en solo s'il n'est pas chargé, demandé au serveur sinon) et la garde-robe de chaque entité, joueurs
  et acteur compris. Le journal indique combien de skins chaque instantané a écrits et avertit pour un skin porté
  que l'enregistrement n'a pas. Un replay tourne dans son propre monde intégré, dont la base de skins est vide.
  Pendant un replay, un mixin optionnel sur la recherche du chargeur sert chaque skin déjà joué avant toute source ;
  un skin serveur pas encore joué attend sa réponse enregistrée au lieu d'échouer. Les réponses jouées vont
  directement dans le chargeur (le mod ne donne une réponse qu'à une demande en attente) et restent gardées jusqu'à
  la fin du replay ; les demandes envoyées au serveur du replay sont bloquées ou servies depuis les skins déjà joués.
  Un replay n'a besoin ni de la bibliothèque, ni de la base, ni du serveur, ni du cache local, dans la même session
  comme après un redémarrage. En 1.20.1, sans charges typées, les paquets passent par le canal que nomme le
  répartiteur du mod. Un emplacement d'armure masqué cache aussi les skins d'Armourer's Workshop qui le
  couvrent (voir « Armure masquée »). Lu sur Armourer's Workshop 3.4.0-beta.3 : sources et jar pour NeoForge 1.21.1, jars pour les
  autres cibles du tableau plus haut. Chargé dans le test automatique (0 échec) sur NeoForge 1.21.1 et 26.1.2 et
  Fabric 1.20.1, 1.21.1 et 26.1.2, où ses vérifications d'armure masquée tournent ; les skins dans les replays ne
  sont pas encore testés en jeu.
- **Epic Fight** : Epic Fight dessine les joueurs en mode combat et les mobs qu'il prend en charge avec ses
  propres modèles animés. Les animations arrivent en `SPAnimatorControl` avec l'id d'entité (joueurs comme mobs),
  les mouvements de base (repos, marche, course... selon l'arme) en `SPChangeLivingMotion`, le mode d'un joueur
  (vanilla ou combat) en `SetPlayerMode` ; ils sont enregistrés tels quels. Ce que le joueur enregistré lance
  lui-même est joué par son client puis envoyé au serveur, qui le renvoie aux joueurs proches mais pas à
  l'expéditeur : demandes d'animation (avec leurs variables), touche de mode, orientation du modèle, entrées de
  déplacement. L'enregistreur les copie tels que les reçoivent les autres, avec l'id d'entité du joueur local,
  que l'acteur conserve ; une demande à laquelle le serveur répond aussi à l'expéditeur est laissée à cette
  réponse. Les instantanés et points de reprise écrivent le mode de chaque joueur (acteur compris), les
  mouvements de base et les animations d'action en cours de chaque entité, avec leur temps écoulé : un
  enregistrement démarré en plein combat montre l'attaque en cours. Un saut dans le temps ignore les animations
  déjà finies à la cible et joue les autres directement au temps atteint, sans fondu d'entrée. Les paquets qui
  visent le joueur du client qui les reçoit (livre de compétences, retour de compétence, sons d'interface) ne
  sont pas rejoués ; les paquets d'Epic Fight vers le serveur sont bloqués pendant un replay. Deux mixins
  optionnels font suivre l'horloge du replay à l'animateur : figé en pause (pose comprise), un tick par tick
  enregistré au-delà de x1. Sur Forge 1.20.1 (Epic Fight 20.x), les messages sont des charges brutes sur un seul
  canal Forge, `epicfight:network_manager`, lues et écrites par le code d'Epic Fight lui-même ; les index des messages
  viennent du mod en marche. L'armure et les objets tenus des modèles dessinés par Epic Fight suivent les pièces et
  les bras masqués, dans les deux versions. Lu dans le jar et les sources d'Epic Fight 21.17.3.1 (NeoForge 1.21.1) et
  dans le jar de la 20.14.17 (Forge 1.20.1), compilé, pas encore testé en jeu.
- **Sable**, **Create Aeronautics**, **Simulated** : les sous-niveaux de Sable (dirigeables et autres structures
  mobiles) sont rangés dans des parcelles de chunks à environ 20 millions de blocs et dessinés à leur pose. Début
  et fin de suivi, chunks des parcelles, bornes et noms passent par la connexion et étaient déjà enregistrés. Les
  poses, elles, ne passent pas par la connexion : appel direct du serveur intégré en solo, canal UDP de Sable en
  multijoueur. Deux mixins optionnels captent ces deux chemins et les enregistrent comme les paquets que Sable
  envoie en TCP quand l'UDP est coupé ; la lecture les rend au client de Sable à leur tick, son interpolation les
  lisse, et leur intervalle d'envoi suit la vitesse de lecture. Les instantanés et points de reprise écrivent
  chaque sous-niveau connu (début de suivi, chunks, poses récentes, horloge d'interpolation) : un enregistrement
  démarré en plein vol et les sauts dans le temps montrent les dirigeables. Pendant la lecture, les chunks des
  parcelles sont gardés quelle que soit leur distance (ils étaient refusés comme trop lointains, d'où des
  dirigeables vides), le serveur du monde de replay ne peut pas envoyer de poses, et les paquets réservés au
  joueur en direct (activation UDP, gel du joueur, mode gizmo) sont ignorés. Aeronautics et Simulated n'ont besoin
  de rien de plus : blocs de Create dans les chunks, paquets Veil sur la connexion, rendu sur l'horloge de Create.
  Sauf les cordes de Simulated : le serveur envoie une corde (`simulated:rope_data`, puis `simulated:rope_stopped`
  quand elle se pose) seulement quand le client commence à suivre son bloc porteur, et le client la garde sur ce
  bloc, hors des données de chunk ; instantanés et points de reprise reconstruisent donc ces paquets pour chaque
  corde connue du client, après les sous-niveaux pour que les blocs porteurs existent.
  Lignes du journal au démarrage : `Sable detected: ...`, `Simulated detected: ...`. Compilé (par réflexion) pour Sable 2.0.6 et Create
  Aeronautics 1.3.2. Chargés dans le test automatique avec Create 6.0.10 et Create Propulsion: Simulated 1.1.5 :
  les deux lignes de détection apparaissent, sans erreur. Dirigeables et cordes ne font pas partie du test automatique.

- **GeckoLib** : l'horloge de rendu de GeckoLib et le tick lu par ses modèles suivent le replay : mobs, blocs, objets, armures et textures animés se figent en pause, suivent la vitesse et avancent du temps exact de l'image en export. Testé par le test automatique sur les cibles dont la ligne du tableau le cite ; seulement lu sur NeoForge et Fabric 1.20.1. En 26.1.2 c'est GeckoLib 5.
- **Curios** : les instantanés et points de reprise écrivent les objets portés de chaque entité. Testé sur NeoForge 1.21.1 et sur Forge 1.20.1 ; seulement lu sur NeoForge 1.20.1.
- **Iron's Spellbooks** : les paquets de sorts du joueur enregistré sont écrits pour son acteur, et les instantanés et points de reprise gardent l'état d'incantation. Les paquets visant le joueur du client qui les reçoit (mana, recharges, poussées) et ceux vers le serveur sont bloqués. Testé sur NeoForge 1.21.1 seulement.
- **Iris** : le temps des shader packs vient de l'horloge du replay, figé en pause, à sa vitesse et identique à un tick donné dans chaque export. Horloge testée ; personne n'a regardé le résultat avec de vrais shader packs.
- **Entity Culling** : l'interrupteur global du mod est sauvé, forcé à l'arrêt pendant un replay (à chaque tick, sa touche ne peut donc pas le remettre) et rétabli à la fin. Un replay avec beaucoup d'entités peut être plus lent. Testé sur NeoForge, Fabric et Forge 1.21.1, Forge 1.20.1, NeoForge et Fabric 26.1.2 ; seulement lu sur Fabric 1.20.1.
- **Exposure** : les photos que le client détient sont écrites dans les instantanés et points de reprise. Les paquets enregistrés de capture, d'export et de shader sont ignorés, les demandes au serveur du replay bloquées. Testé sur NeoForge et Fabric 1.21.1.
- **Accessories** : les définitions d'emplacements et les accessoires portés sont écrits dans les instantanés et points de reprise. Testé sur NeoForge et Fabric 1.21.1 sur le joueur ; accessoires sur les mobs non exercés. Mod en bêta.
- **Jade** : avec le HUD propre, l'infobulle n'est pas calculée pendant un replay et ses demandes n'atteignent pas le serveur du replay. Un paquet enregistré qui réécrirait les réglages Jade est ignoré. Testé sur NeoForge et Fabric 1.21.1, Forge 1.20.1, NeoForge et Fabric 26.1.2 ; seulement lu sur NeoForge et Fabric 1.20.1.
- **Immersive Paintings** : la liste des tableaux et les images utilisées sont écrites dans les instantanés et points de reprise ; les suppressions sont retirées des listes enregistrées, un replay n'efface donc jamais le cache de celui qui regarde. Testé sur NeoForge et Fabric 1.21.1.
- **Immersive Aircraft** : les instantanés et points de reprise écrivent l'équipement d'un appareil, et ses demandes au serveur du replay sont bloquées. Testé sur NeoForge et Fabric 1.21.1 : un biplan équipé garde son équipement au début d'un replay et après un retour en arrière.
- **Immersive Vehicles** : un véhicule, un siège ou un bloc d'Immersive Vehicles arrive chez le client comme une coquille vide qui demande une fois ses données au serveur. Les instantanés et points de reprise écrivent ces données comme le serveur y répond, et ses demandes au serveur du replay sont bloquées. Le mod dessine tous ses véhicules par une entité invisible qui doit suivre le joueur local : un replay a la sienne, qui suit la caméra, et celles enregistrées ne dessinent plus. Testé sur NeoForge 1.21.1 et Forge 1.20.1 avec Immersive Vehicles 24.0.0 et son Official Content Pack, et sur NeoForge 26.1.2 avec Immersive Vehicles 24.0.0-26.1 et le pack R.A.G.E 3.0 bêta 2 (l'Official Content Pack n'existe pas en 26.1) : une voiture posée avant l'enregistrement a ses données au début d'un replay et après un retour en arrière. NeoForge 26.1.2 en client de développement seulement. Forge 1.20.1 en client de développement seulement, le mod remappé par `-PdevMods`. Conduite et physique pendant un replay non vérifiées par un humain.
- **More Player Models** (Noppes, build Forge pour 1.20.1 ; portage NeoForge 1.21.1 non officiel) : l'apparence d'un joueur (proportions, parties ajoutées, modèle d'entité, skin par URL, nom, animation) est un NBT que le serveur envoie une fois, cinq secondes après le début du suivi, puis à chaque changement ; chaque message nomme son joueur par UUID (dans le portage 1.21.1, un payload typé par message, réécrit par son propre codec). Ils sont enregistrés tels quels, et ceux du joueur enregistré (le serveur renvoie à un joueur sa propre apparence) sont écrits pour son acteur. Les instantanés et points de reprise écrivent l'apparence et l'objet dans le dos de chaque joueur, celle de l'acteur à chaque apparition. Sur un serveur sans le mod, aucun paquet ne porte d'apparence (chaque client lit ses fichiers et le service web du mod) : pendant une capture, les apparences et les animations du joueur enregistré sont écrites quand elles changent. Pendant un replay, les demandes du mod au serveur du replay sont bloquées, son éditeur reste fermé, son service web n'est pas interrogé, et une apparence enregistrée qui nomme l'UUID de celui qui regarde est ignorée, car le mod l'enregistrerait comme la sienne. Les parties ajoutées par le mod et l'objet dans le dos suivent les parties du corps masquées ; l'armure masquée marche comme sur un joueur vanilla. 1.20.1 : lu dans le jar du build 1.20.1.20240409 (Forge, le même fichier que charge NeoForge 47.1) ; chaque classe, membre et cible de mixin a été contrôlé contre ce jar avec les classes de Forge et de NeoForge 1.20.1. Testé en client de développement Forge 1.20.1 (20/11/2026). NeoForge 1.21.1 : Noppes n'a fait aucun build, l'intégration suit le portage non officiel `kaan-mpm.jar` (mod id `moreplayermodels`, version 1.21.1.20261109-cat-scale, imbriqué dans le bundle CurseForge kaanstyle 1.0.15, demande NeoForge 21.1.250 ou plus), contrôlée contre ce jar et testée en client de développement (20/11/2026). Aucun build pour Fabric, Forge 1.21.1 ni 26.1.2 : désactivé là-bas.

### Armure masquée (toutes les cibles)

Les interrupteurs « Armor shown » de l'éditeur cachent le casque, le plastron, les jambières ou les bottes d'un joueur
enregistré (onglet **Entités**) ou d'un acteur (écran de l'acteur), pièce par pièce ; le réglage `hidePlayerArmor`
cache les quatre sur tous les joueurs, acteurs compris. Un casque masqué emporte aussi une citrouille ou une tête de
mob portée à sa place, un plastron masqué les élytres. Seul le dessin change, dans le replay et dans les vidéos
exportées : l'entité garde son équipement, rien ne change hors d'un replay en cours, la caméra du replay n'est jamais
touchée, et les projets sauvés avant cette option gardent leur armure.

Les points d'accroche du rendu sont des mixins optionnels (`require = 0`) sur les couches vanilla d'armure, d'élytres
et d'objet porté sur la tête, écrits pour les signatures de chaque version : celles de vanilla en 1.20.1 et 1.21.1
(plus la surcharge propre à NeoForge sur NeoForge 1.21.1), et les nouvelles couches à état de rendu en 26.1.2. Toutes
les cibles compilent ; le test automatique vérifie le masque, les pièces sautées par la couche d'armure elle-même,
le joueur caméra et le réglage global : il passe sur les neuf cibles en client de développement, et une capture de
NeoForge 1.21.1 montre le casque de l'acteur parti et son plastron gardé. Personne n'y a encore joué dans un vrai
jeu. Un point d'accroche qui ne s'applique
pas échoue sans bruit : l'armure reste alors simplement visible.

Limites :

- Les mobs, les PNJ de Custom NPCs et les autres entités qui ne sont pas des joueurs ne sont pas couverts : les
  interrupteurs n'apparaissent que pour les joueurs, et le réglage ne vise que les joueurs et les acteurs.
- Armourer's Workshop dessine lui-même ses skins (garde-robe et armures à skin), d'après les objets qu'il collecte
  pour chaque entité une fois par tick, et les recharge quand ces objets changent. Des mixins optionnels sur ses deux
  collecteurs (équipement et garde-robe, dans `EntitySlotsHandler`) en retirent les emplacements masqués : un
  emplacement masqué cache le skin de la pièce qui y est portée et les skins de garde-robe de cet emplacement. Les
  ailes partent avec le plastron comme des élytres ; une tenue (outfit), dessinée d'un seul tenant sur tout le
  corps, part dès qu'un des quatre emplacements est masqué. Les armes, outils, boucliers et sacs ne sont pas de
  l'armure et restent. Seuls les joueurs tickent dans un replay en pause : les skins de toute autre entité sont
  recollectés dès que ses emplacements masqués changent. Noms vérifiés sur Armourer's Workshop 3.4.0-beta.3 pour
  ses sept cibles. Le test automatique le vérifie avec cette version installée (NeoForge 1.21.1 et 26.1.2, Fabric
  1.20.1, 1.21.1 et 26.1.2) : le casque masqué quitte l'armure qu'Armourer's Workshop a collectée et le plastron
  reste, le réglage global retire puis rend le plastron sans que l'acteur ne tick, et les skins de garde-robe de
  chaque sorte suivent leurs emplacements. Sa version Forge 1.20.1 ne démarre pas en client de développement et
  NeoForge 1.20.1 n'y charge aucun mod publié : sur ces deux cibles, seuls les noms ont été vérifiés. Aucun vrai
  skin n'a encore été regardé.
- Modèles d'Epic Fight (joueurs en mode combat) : l'armure et les objets tenus suivent les emplacements et les bras
  masqués, sur NeoForge 1.21.1 et Forge 1.20.1.
- More Player Models (1.20.1 et le portage NeoForge 1.21.1) : l'armure masquée marche comme sur un joueur vanilla,
  sauf sur un joueur dessiné comme
  une autre entité (un modèle d'entité), qui garde son armure.
- Une armure qu'un mod dessine avec sa propre couche au lieu de la couche d'armure vanilla n'est pas couverte.

### Quand une intégration échoue

Les gros modpacks mélangent des versions que personne n'a testées ensemble, alors chaque intégration a un filet :

- Une intégration ne tourne que si son mod est installé, qu'elle est prise en charge sur le loader lancé
  (`CompatTargets`) et que la config la laisse active. Ses mixins s'appliquent aux mêmes conditions
  (`CompatMixinPlugin`).
- Chaque appel vers un autre mod passe par la garde de son intégration (`Integration`). La première erreur (méthode
  ou classe changée, `NoSuchMethodError`, `NoClassDefFoundError`, plantage dans le code de l'autre mod) coupe cette
  intégration jusqu'au prochain démarrage, avec un seul avertissement dans le log qui la nomme avec le point
  d'accroche en cause. Ce qu'elle avait changé dans son mod (l'interrupteur d'Entity Culling, la redirection de
  dossier de Distant Horizons) est quand même remis à la fin du replay.
- Pour couper une intégration à la main : `compat.<id du mod>` à `false` dans `config/justreplay-client.json`, ou son
  interrupteur dans l'écran des réglages (affiché pour les mods installés). Effet au prochain démarrage, mixins
  compris.
- Un paquet impossible à encoder, relire ou rejouer (un paquet de mod, le plus souvent) est ignoré et compté :
  l'enregistrement ou le replay continue. Le premier de chaque type écrit un avertissement, et une ligne
  `Recording: N packets skipped (...)` ou `Replay: N packets skipped (...)` en fait le compte à la fin.
- Les mixins tolèrent une méthode réécrite par un autre mod (`defaultRequire` 0) : ce point d'accroche est perdu, le
  jeu démarre.
- Au démarrage, Just Replay écrit une ligne avec sa version, le loader, Minecraft et l'état de chaque intégration
  trouvée, par exemple `Just Replay 1.2.0 on neoforge 21.1.221, Minecraft 1.21.1. Integrations on: Curios, Jade; off
  in the config: GeckoLib; installed but not supported on this loader: none; not installed: 26.` C'est la première
  ligne à lire dans un log de crash.
- Le test automatique vérifie ce filet en jeu sur toutes les cibles. Il coupe une intégration au démarrage
  (`-PselftestFailCompat`, Simple Voice Chat par défaut, compté comme installé s'il ne l'est pas), déclenche ses
  points d'accroche 300 fois pendant le replay, et écrit un paquet illisible (`packet #16383`) dans son
  enregistrement. Attendu : un seul avertissement, `failed: Simple Voice Chat (in self test forced failure)` dans la
  ligne de démarrage, `packet #16383 could not be read` dans la ligne `Replay: N packets skipped (...)`, et le replay
  continue. Passé sur les neuf cibles le 2026-11-20 : conforme partout (un avertissement par jeu, le paquet compté 6
  ou 7 fois dans le replay principal à cause de ses sauts, une fois dans le clip du tampon).

### Limites connues

- Un bloc modifié dans le tick exact où un enregistrement démarre en cours de partie peut manquer : le paquet est
  capté avant le début mais traité par le client après l'instantané.
- WaterFrames en export : les sauts de WaterMedia sont asynchrones, une vidéo peut donc avoir quelques images de
  retard sur le temps exporté lors d'un export rapide.
- Les trains de Create dans un enregistrement démarré en cours de partie dépendent des paquets du réseau ferroviaire
  conservés ; ce chemin n'est pas testé.
- Sable : en solo, la première pose d'un sous-niveau peut être enregistrée un tick avant son début de suivi (la pose
  est prise sur le fil du serveur, le début de suivi sur la connexion) ; Sable écrit alors une erreur « non-existent
  sub-level » dans le journal. Les hélices d'Aeronautics tournent au tick de leur entité de bloc, une fois par tick
  client : au-delà de x1, elles tournent moins vite qu'à l'enregistrement. Sable ajoute un octet à certains paquets
  de mouvement d'entité : un enregistrement fait avec Sable demande Sable pour être relu, et inversement.
- ParCool : les actions du joueur enregistré viennent de l'écho du serveur, un enregistrement sur un serveur sans
  ParCool n'en a donc aucune (ParCool doit de toute façon être des deux côtés). Les actions courtes (saut d'obstacle,
  roulade, esquive) coupées par un saut dans le temps ne reprennent pas ; seules les actions durables sont écrites
  dans les points de reprise. ParCool anime les joueurs avec son propre rendu, pas avec la bibliothèque d'animation
  du joueur : ses animations peuvent continuer pendant la pause du replay (non vérifié).
- Better Combat : une attaque dans laquelle tombe un saut dans le temps repart de son début (l'animation d'attaque
  n'a pas de tick de départ d'où reprendre). Les enregistrements faits avant cette version n'ont pas les attaques du
  joueur enregistré.
- Epic Fight : les animations d'action écrites par un instantané ou déplacées par un saut dans le temps démarrent sans
  fondu d'entrée. Les compétences du joueur enregistré qui ne changent que l'état du client (aucune animation
  envoyée) ne sont pas rejouées, et son acteur n'a pas de livre de compétences. Les sons et particules des
  animations partent des animations rejouées côté client, comme pour tout joueur suivi ; un doublement des sons
  d'attaque du joueur enregistré (aussi enregistrés comme sons locaux) n'est pas exclu (non vérifié). Les données
  d'armes et de mobs viennent des datapacks du monde du replay, qui doivent être ceux de l'enregistrement. Les
  enregistrements faits avant cette version n'ont pas les animations du joueur enregistré. Sur Forge 1.20.1, une
  animation est un numéro sur le réseau, donné par l'ordre d'enregistrement d'Epic Fight et les datapacks : un
  enregistrement ne se rejoue bien qu'avec la même version d'Epic Fight, les mêmes addons et les mêmes datapacks.
  Là, quand le joueur enregistré coupe le verrouillage de l'orientation du modèle, ce n'est pas copié pour son acteur
  (le gestionnaire d'Epic Fight pour ce message tombe dans un changement de mode sans mode) : l'acteur garde sa
  dernière orientation jusqu'à la suivante.
- Armure masquée : voir « Armure masquée (toutes les cibles) » plus haut.
- Morph : la forme d'un joueur n'est connue qu'une fois que son client l'a demandée (la première fois qu'il affiche
  ce joueur) : un joueur jamais vu par le client enregistré avant le début de l'enregistrement n'est rejoué dans sa
  forme qu'à partir de la réponse du serveur. Le sélecteur de formes et le HUD de biomasse du joueur enregistré ne
  sont pas rejoués.
- Custom NPCs : un enregistrement fait avant cette version n'a pas l'apparence des PNJ (les paquets d'apparition ont
  été enregistrés sans elle). Les skins donnés par URL sont retéléchargés à la lecture : un client hors ligne ou un
  lien mort montre le skin par défaut. Les bulles et lignes de discussion des PNJ sont rejouées telles
  qu'enregistrées.
- More Player Models : aucun build n'existe pour Fabric, Forge 1.21.1 ni 26.1.2, rien n'y est donc activé. Sur
  NeoForge 1.21.1, l'intégration suit seulement le portage non officiel `kaan-mpm.jar`. Son bundle kaanstyle (stylem)
  exige sept autres mods de son auteur qu'il n'inclut pas ; le `kaan-mpm.jar` imbriqué se charge seul. Un autre
  portage ou un nouveau build est contrôlé par nom à sa première utilisation : si une classe ou un membre diffère,
  l'intégration se coupe avec un avertissement. Un joueur dessiné comme une autre entité
  (un modèle d'entité) ignore les parties du corps et l'armure masquées. Une partie qui couvre les deux bras ou les
  deux jambes ne part que si les deux sont masqués. Les bulles de discussion et les clignements d'yeux suivent le
  temps réel, pas l'horloge du replay. Un skin donné par URL est retéléchargé à la lecture. Votre propre personnage
  dans l'enregistrement d'un autre montre votre apparence actuelle. Tant que l'apparence enregistrée d'un joueur n'est
  pas jouée (jusqu'à cinq secondes après son arrivée, comme sur un serveur en direct), le mod montre la dernière qu'il
  lui a vue. Un enregistrement fait avant cette version n'a pas d'apparences dans ses instantanés : elles s'affichent
  quand leurs paquets sont joués, et un saut dans le temps les perd.
- Armourer's Workshop : chaque instantané et point de reprise contient tous les skins que le client détient ou qu'une
  entité enregistrée porte, ce qui grossit les enregistrements quand beaucoup de skins sont chargés. Sur un serveur,
  un skin porté que le client n'a jamais chargé est demandé à l'instantané et enregistré à l'arrivée de la réponse.
  Les enregistrements solo faits avec la 1.0.9 ne contiennent que les skins chargés par le client à leur début ; ceux
  faits avant la 1.0.9 n'ont aucun skin écrit à leur début.
- Un enregistrement fait avec un mod absent à la lecture se lit sans les paquets de ce mod (une ligne
  `Skipping recorded ... packets` par type dans le journal).
- Drop the Beam : les morceaux, vidéos et images viennent des caches locaux (`dropthebeam/song-cache`, `video-cache`,
  `image-cache`) ; un replay regardé sur un autre ordinateur qui ne les a pas reste muet ou vide, faute de serveur
  pour les envoyer. Un replay enregistré avant cette intégration n'a que les vrais pongs comme horloge (un toutes les
  2 s, en retard d'un demi aller-retour). La musique est coupée aux vitesses autres que x1.
- Distant Horizons : les LOD affichés sont ceux présents sur le disque à l'ouverture du replay, pas ceux du moment
  enregistré (un terrain changé depuis apparaît changé au loin). Les enregistrements faits avant cette version, ou
  sur un autre ordinateur, n'ont pas de base de LOD à lire et se lisent sans LOD. Une dimension que le monde du replay
  n'a pas (dimension d'un mod absent) n'a pas de LOD. Ouvrir un replay copie une fois la base de chaque dimension
  enregistrée (le temps et les écritures disque grandissent avec sa taille ; `distantHorizonsMaxCopyMb` la plafonne).
  Le tampon de replay instantané ne note que la dimension où se trouve le joueur quand le clip est sauvé. Les LOD se
  chargent depuis la copie en arrière-plan : les premières secondes d'un export peuvent les montrer apparaître
  (augmenter `export.warmupFrames`).

### Limites connues des cibles

- Tout Fabric : le HUD propre ne peut pas masquer les HUD des autres mods en 1.20.1 et 1.21.1. Pas de modificateurs
  de touches. Pas de bouton de réglages sans Mod Menu.
- Forge 1.21.1 et 26.1.2 : l'option d'ombre du texte n'a pas d'effet, le champ de texte de Forge n'ayant pas cette
  option.
- 1.20.1 : la pause et le ralenti sont reconstruits sans gestionnaire de tick rate vanilla (ils passent le test
  automatique). Les fichiers audio Ogg de plus de 2 canaux sont refusés. La timeline de l'éditeur n'a pas de
  défilement horizontal à la molette. Les écrans montrent de la terre au lieu du panorama.
- 26.1.2 : la garde du tampon de sections est retirée, la règle du rayon de chunks d'apparition est abandonnée, et
  les animations d'apparition de GeckoLib ne se jouent probablement pas dans les replays.
- Un enregistrement ne s'ouvre que sur la version de Minecraft et le loader où il a été fait.
- GeckoLib : les animations déclenchées avant le début d'un enregistrement ou la cible d'un saut ne sont pas
  restaurées, et la phase des boucles au repos peut différer après un saut en arrière.
- Iris et Entity Culling : les exports avec shader packs demandent encore l'œil d'une personne. Entity Culling coupé
  peut ralentir les replays avec beaucoup d'entités.
