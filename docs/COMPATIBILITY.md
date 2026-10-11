# Compatibility target / Cible de compatibilité

## English

Reference pack: **Arcadia V2** (444 mods, NeoForge 1.21.1). Just Replay must record, play back, edit and
export with every mod of this pack installed. Most mods work for free because recording captures every
clientbound packet, custom payloads included. The mods below need dedicated work, ordered by risk.

### Tier 1: rendering pipeline (blocking for playback and export)

| Mod | Risk | Plan |
|---|---|---|
| Sodium 0.8 (NeoForge), Sodium Extras, Reese's Sodium Options | Chunk rebuild on time jumps, frame capture | Force full chunk rebuild after a seek, read the framebuffer after Sodium's pass |
| Iris 1.8 | Shader pipeline during export, depth for depth of field | Export through Iris' final pass, test with 2 shader packs |
| Create 6 + Flywheel (bundled) | Contraptions and instanced visuals tick on client time | Drive Flywheel's partial ticks from replay time, test trains and contraptions while seeking |
| ImmediatelyFast, EntityCulling, ModernFix, FerriteCore | Batching and culling may hide entities in free camera | Disable entity culling while a replay is open, check batching with frame capture |
| Ixeris | Moves window and event handling off the render thread | Check input in the editor (ImGui) and frame pacing during export |
| Bobby (reneoforged) | Serves cached chunks, would mix stale terrain into replays | Disable Bobby while a replay world is loaded |
| Distant Horizons | Not in this pack | Planned later |

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
