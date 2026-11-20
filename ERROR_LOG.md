# Error log

## [2026-10-11 14:20] — BOM written by PowerShell broke compilation
**Context:** editing a Java file with `Set-Content -Encoding utf8` in Windows PowerShell 5.1.
**Error:** `illegal character: '﻿'`.
**Root cause:** PowerShell 5.1 writes UTF-8 with a BOM.
**Fix:** stripped the BOM with sed.
**Prevention:** edit sources with the Edit/Write tools or sed, never PowerShell `Set-Content`.

## [2026-10-11 14:50] — Replay session dropped before playback started
**Context:** opening a replay from the title screen (self test step 4).
**Error:** playback never started; the login event saw `session=null`.
**Root cause:** loading the host world fires `ClientPlayerNetworkEvent.LoggingOut` with a null connection.
`isReplayConnection(null)` returned true because the session connection was still null, so the session closed.
**Fix:** `isReplayConnection` requires a non-null connection; only the replay connection ends a session.
**Prevention:** never compare a nullable connection with `==` without a null guard.

## [2026-10-15 13:11] — WaterFrames frame missing from a self test recording
**Context:** Arcadia self test, a WaterFrames frame placed with `/setblock` right before `Recorder.startManual()`.
**Error:** during playback WaterFrames logged `Received packet 'TimePacket' pointing to a invalid DisplayTile position`; no display was tracked.
**Root cause:** the block update was captured on the network thread before the recording was attached (so it only reached the buffer) but handled by the client after the snapshot, so neither the snapshot nor the stream of the new recording had the block.
**Fix:** the self test places modded content a few ticks after the recording starts. The race itself remains (see docs/COMPATIBILITY.md, known limits); a proper fix attaches outputs through a network thread barrier like checkpoints do.
**Prevention:** never change the world in the same tick a mid-session recording starts in automated tests.

## [2026-10-15 13:11] — Emote diagnostics failed on replayed emotes
**Context:** reading the name of the emote played by the recorder's actor in a replay.
**Error:** `InvocationTargetException` from `KeyframeAnimation.getName()`.
**Root cause:** not confirmed (no more game runs allowed); most likely emotes received from the network keep a name that is not a plain string, which `getName` casts.
**Fix:** names are read defensively, falling back to the emote UUID.
**Prevention:** treat every value read by reflection from another mod as possibly failing.

## [2026-10-15 18:00] — FFmpeg quit during a self-test export
**Context:** vanilla self-test run after the Drop the Beam work (no Drop the Beam jar loaded).
**Error:** `FFmpeg stopped accepting frames` / `java.io.IOException: Le canal de communication a été fermé`; FFmpeg's own log stops at frame 62 with no error line, the export is marked failed and the audio is not muxed.
**Root cause:** not found. The FFmpeg process ended on its own; the same test passed right before (with Drop the Beam) and right after, unchanged.
**Fix:** none needed for the run; the self-test was run again and passed.
**Prevention:** if it happens again, keep the FFmpeg log and check for an external kill or a crash of ffmpeg.exe in the Windows event log before changing the exporter.

## [2026-10-15 18:20] — Showcase recorded without Drop the Beam
**Context:** re-recording the Drop the Beam showcase world for the clip, right after a vanilla self-test.
**Error:** the replay opened with no Drop the Beam clock lines; the world had been opened and saved without the mod.
**Root cause:** the Drop the Beam jar had been moved out of `run/mods` for the vanilla self-test and was not put back. Opening the world without the mod drops its blocks and saves the loss.
**Fix:** restored the world from the Drop the Beam showcase build, put the jar back, recorded again.
**Prevention:** before any run on a modded world, list `run/mods` and check the "Drop the Beam detected" log line; keep a pristine copy of test worlds and copy from it for each run.

## [2026-10-27 06:45] — FFmpeg killed at the end of an export (root cause of the 2026-10-15 entry)
**Context:** vanilla self-test export of 60 ticks at 1080p 60 fps; second occurrence of the "FFmpeg stopped accepting frames" failure.
**Error:** `FFmpeg stopped accepting frames` / `Le canal de communication a été fermé`, FFmpeg's log stops mid-encode with no error, export marked failed, audio not muxed.
**Root cause:** `VideoExporter.finish()` used `queue.offer(END)` on the bounded frame queue and, when it was full, assumed the writer was stuck and called `process.destroyForcibly()`. A full queue is normal at the end of a short export: the game renders frames faster than FFmpeg encodes them.
**Fix:** the end marker is now queued by the background waiter thread, which waits for room and gives up only when FFmpeg is no longer alive.
**Prevention:** never treat a full producer/consumer queue as a dead consumer; check the consumer itself (process alive, failure flag) before killing anything.

## [2026-10-28 16:30] — Dev client crashed after a compile during the session
**Context:** the user was testing in the dev client (`runClient`) while a new button was being compiled with `./gradlew compileJava`.
**Error:** `NoClassDefFoundError: com/vyrriox/justreplay/record/ChunkCapture` on "Start recording", reported as `mouseClicked event handler`.
**Root cause:** the dev client loads the mod from `build/classes`; the compile rewrote that folder under the running game, so a class not yet loaded could no longer be found.
**Fix:** relaunch the client after the compile. No code change.
**Prevention:** never run a Gradle compile or build in the main checkout while a dev client is running; compile in a worktree or wait for the game to close.

## [2026-11-01 17:10] — Replay closed at once in a large modpack (1.0.6)
**Context:** opening a singleplayer recording in a 440 mod pack (KubeJS, l2core, Puzzles Lib...).
**Error:** session server: `EncoderException: Failed to encode packet 'clientbound/minecraft:update_recipes'`, caused by `Can't find id for ShapelessRecipe$Serializer in map Registry[minecraft:recipe_serializer]`; the replay closed about 200 ms after it started.
**Root cause:** `RegistryIds.apply` hands the recorded ids to `RegistryManager.applySnapshot`, which clears every id of a registry and gives one back only to the keys the snapshot lists. A local entry absent from the recording kept no id. In singleplayer the replay world's integrated server shares these static registries, so it could no longer encode the recipes it sends at login.
**Fix:** `RegistryIds.complete` appends every local key the recording does not list after the highest recorded id before applying; the replay world's server no longer sends `update_recipes` during a session. Self-test step checks an id survives a recording that misses it.
**Prevention:** never apply a partial registry snapshot to the shared static registries; any code touching registry ids must keep every registered entry mapped.

## [2026-11-01 20:10] — Sable airships empty in replays
**Context:** first in-game test of the Sable support: an airship assembled before recording, replayed with a scene job.
**Error:** the replay showed no airship; Sable logged `Cannot add chunk at 0, 0 in nonexistent sub-level plot` at the start and after every seek.
**Root cause:** a replayed login packet keeps the existing camera player and adds it to the new level without updating the player's own level field. Sable's tracking packet reaches the level through the packet context player, so the sub-level was created in the level from before the seek, and its chunks then arrived in a level without it. Not a packet order problem (checked with debug logs before guessing further).
**Fix:** after a replayed login, the camera player is moved to the current level (`EntityInvoker.setLevel`).
**Prevention:** any mod handler that uses `context.player().level()` depends on this; when a mod's state vanishes in a replay, compare `mc.player.level()` with `mc.level` first.

## [2026-11-03 12:00] — Armourer's Workshop outfits missing from singleplayer replays (1.0.9)
**Context:** a user recorded in singleplayer with Armourer's Workshop 3.4.0-beta.3 after loading .armour files into the world's skin database, then replayed.
**Error:** replays showed no outfits; no error from Just Replay or Armourer's Workshop in the log.
**Root cause:** Armourer's Workshop has one SkinLoader for the whole JVM. The integrated server prepares it with its own sessions, so in singleplayer a db: skin is read straight from the world's skin database and no RequestSkinPacket or ResponseSkinPacket ever crosses the connection. 1.0.9 recorded skins only from those packets and from the loader cache at snapshot time. The replay world is another integrated world with an empty skin database, and its loader looks db: skins up there, never over the network, so the request mixin never fired.
**Fix:** record every skin at the loader (SkinLoader$Entry.accept mixin), add every worn skin at snapshots (loaded from the world database in singleplayer), and during a replay serve lookups from the recording at SkinLoader.resume before any source is tried. Snapshot skin count logged, missing worn skins warned.
**Prevention:** for any mod with a cache shared between client and integrated server, check how the client gets its data in singleplayer before assuming it comes over the network.

## [2026-11-04 14:00] — Armourer's Workshop outfits missing from replays (1.0.9 and 1.0.10)
**Context:** a user's singleplayer recordings with Armourer's Workshop showed no outfits; 1.0.10 logged `snapshot wrote 39 skins` yet nothing showed.
**Error:** no error anywhere; the recording file had no `armourers_workshop:*` payload at all.
**Root cause:** the compat encoded packets with Armourer's Workshop's `PacketSplitter.split`, which runs on its own thread pool and hands the parts back later. The collector was read right after the call, always empty, so nothing was written while the snapshot counted skins as written. Two releases shipped without an in-game test because no `.armour` file was available.
**Fix:** the packet id and data are encoded on the calling thread and cut with the same begin/body/end markers. Reproduced and verified with a user's `.armour` file and `/armourers setSkin @s head /<file>.armour` (the slot name is required).
**Prevention:** never trust a mod's helper to be synchronous; count what was actually written, not what was asked. Do not ship a compat claim without one end-to-end in-game check; ask for test assets first.

## [2026-11-05 14:40] — Replay closed right after opening, intermittently (decode error from the host)
**Context:** self test with GeckoLib, Curios, Iron's Spellbooks, Iris and others, opening the buffer clip.
**Error:** `Failed to decode packet 'clientbound/minecraft:update_advancements'`, caused by `IllegalArgumentException: No value with id 1568`, then `bundle_delimiter ... was larger than I expected`, then a disconnect. The next run of the same test passed.
**Root cause:** `ReplaySession.clientTick` applied the recorded registry ids on the render thread while the replay host's integrated server could still have packets in flight. `RegistryManager.applySnapshot` empties and refills each registry, so a packet decoded on the Netty thread in that window fails.
**Fix:** at STARTING the host is silenced, the server thread and both channels are drained without blocking the render thread, and only then are the ids applied (40 tick fallback with a warning).
**Prevention:** never change shared static registries while a connection is live; drain it first. A self test that passes once proves nothing about a race, so run it several times.

## [2026-11-05 14:50] — Self test launched with Iris but without Sodium
**Context:** building a mod folder for the compat self test from a dependency list.
**Error:** `ClassNotFoundException: net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer`, exit value 1.
**Root cause:** a `while read` loop skipped the last line of a list with no trailing newline.
**Fix:** copied the jar.
**Prevention:** check the number of copied files against the list.

## [2026-11-05 18:30] — fabric-1.20.1 does not compile against common/1.20.1
**Context:** first build of the Fabric 1.20.1 project (Loom 1.17.21, Fabric API 0.92.12+1.20.1) on the shared common code.
**Error:** `common/1.20.1/.../compat/CuriosCompat.java:12: error: package net.minecraftforge.common.util does not exist`, and the same for `net.minecraftforge.network.simple`, because `CuriosCompat` is written against Forge's `LazyOptional` and `SimpleChannel`.
**Root cause:** `CuriosCompat` sits in common, which must be loader-free. It compiles only where Forge is on the classpath. `ReplayCompat` and `SelfTest` in common call it, gated by `isLoaded("curios")`, which is always false on Fabric.
**Fix:** the Fabric project excludes that one file from the common source set with a source filter and adds a stand-in `compat/CuriosCompat` with the same two methods as no-ops. Curios has no Fabric build for 1.20.1, so the stand-in matches the behaviour. Common is unchanged; moving the class into the Forge glue is the proper fix and is still to do.
**Prevention:** a file in common that imports `net.minecraftforge` or `net.neoforged` is a bug. Run the Fabric compile on every common change, as rule R2 says.

## [2026-11-05 18:35] — Loom fails to set up Minecraft when a common srcDir is a FileTree
**Context:** excluding `CuriosCompat` from the common source set in `fabric-1.20.1/build.gradle`.
**Error:** `Failed to setup Minecraft, org.gradle.api.InvalidUserDataException: Source directory '...\common\1.20.1\src\main\java\...\Frames.java' is not a directory.`
**Root cause:** `sourceSets.main.java.srcDir(fileTree(dir) { exclude ... })` hands Loom the individual files of the tree, not the directory.
**Fix:** add the directory with `srcDir(new File(commonDir, 'java'))` and drop the one file with `sourceSets.main.java.filter.exclude { ... }`, matching the file by name and by its common path.
**Prevention:** for a Loom project, add a source root as a directory and filter it with the set's filter, never with a file tree.

## [2026-11-05 19:10] — Forge 1.20.1 jar built without its refmap after a build cache hit
**Context:** `gradlew clean build` of `forge-1.20.1` to prepare a production run.
**Error:** `justreplay.refmap.json` absent from the reobfuscated jar while the mixin configs still name it; the log shows `:compileJava FROM-CACHE`.
**Root cause:** MixinGradle writes the refmap outside the declared outputs of `compileJava`, so a cache hit restores the classes only. Dev runs do not read the jar and hide it.
**Fix:** `outputs.cacheIf { false }` on `compileJava` in `forge-1.20.1/build.gradle`.
**Prevention:** after a build of a mixin jar meant for release or a production run, read the refmap in the jar and check it holds SRG names.

## [2026-11-05 19:55] — Replays on 1.20.1 end after about 32 seconds
**Context:** first self test replays on the 1.20.1 targets.
**Error:** every replay closed about 32 seconds in, with a keep alive timeout on the replay host.
**Root cause:** the client ignores everything the replay host sends, keep alive packets included, and 1.20.1 also times out the singleplayer owner (later versions do not). The host kicked the camera player, which stopped the host and ended the replay.
**Fix:** new `HostKeepAliveMixin` in `common/1.20.1`: the keep alive challenge is never due on the replay host, as later versions do for the owner.
**Prevention:** on a backport, keep the self test replay running longer than every server side timeout, and read what the older version does for the singleplayer owner.

## [2026-11-05 19:56] — Mixin descriptors valid on one version fail only at class load
**Context:** porting the shared mixins to 1.20.1 and 26.1.2, where method signatures of the game and of other mods differ.
**Error:** the project compiled; the wrong target or descriptor only showed when the target class loaded, in a launched game.
**Root cause:** the compiler does not check `@Inject` targets written as strings, and `require = 0` mixins fail silently, so a descriptor copied from 1.21.1 stays valid Java.
**Fix:** a one-off local checker compared every mixin target with the real jar of each target, and the mixins were aligned (`515c186`, `40084e3`). The checker is not committed.
**Prevention:** verify every mixin target against the real jar of the target before launching. A compile says nothing about them, and a dev run does not exercise a refmap.

## [2026-11-05 19:57] — getLoadingModList is an instance method on FML 11
**Context:** `EarlyMods` in `common/26.1.2`, which reads the mod list while mixins are selected, carried from 1.21.1.
**Error:** the reflective `FMLLoader.getLoadingModList` call with a null target fails on the 26.1.2 NeoForge loader, because it is no longer static. Exact message not kept.
**Root cause:** FML 11 turned `getLoadingModList` into an instance method. Reflection hides that from the compiler.
**Fix:** read the list through `LoadingModList.get()` on NeoForge, and `LoadingModList.getModFileById` on Forge (`40084e3`).
**Prevention:** reflection on a loader API is re-read against the loader jar on every target, as for mixins; it never fails at compile time.

## [2026-11-05 19:58] — Mixed sound silent on Fabric, unseen by any self test
**Context:** reading the audio of the Fabric 1.21.1 and 1.20.1 production jars.
**Error:** no error and no failed check: `MixSoundInstance` and `ScaledSoundInstance` simply made no sound on Fabric.
**Root cause:** Fabric API's sound API calls `getAudioStream` on a sound instance, and these classes only overrode the vanilla stream method. The self test checks states and clocks, not what comes out of the speakers.
**Fix:** both classes declare `getAudioStream`, with the wrapped instance's own method reached by reflection where it exists (`c9b335f`, `bf5e032`).
**Prevention:** a custom `SoundInstance` must be read against Fabric API's sound API on every Fabric target. A passing self test proves nothing about audible output: listen, or add a check on the stream actually requested.

## [2026-11-05 20:40] — NeoForge 1.20.1 compiled locally but not on CI after the 1.1.0 tag
**Context:** the build workflow started by the v1.1.0 tag; the eight other targets passed, and the released jar was built and tested locally.
**Error:** `ActorPlacement.java:81: error: variable o is already defined`, on an anonymous subclass of `ClipContext`.
**Root cause:** javac builds a synthetic constructor for an anonymous class and names its parameters from the superclass; here those names clashed with the ones in the patched Minecraft classes on the CI machine. The exact difference with the local machine was not established.
**Fix:** a named `NoEntityClip` class with an explicit constructor. The tag was not moved, and the behaviour is the same.
**Prevention:** run the CI matrix on the release commit before tagging, through a pull request or a manual workflow run.

## [2026-11-05 21:30] — Curios worn items dropped in a replay when a datapack supplies the slots (1.20.1)
**Context:** production self test on Forge 1.20.1 with Curios 5.14.1 and the test ring datapack.
**Error:** `SELFTEST curios at start: worn stacks on the actor=-1 -> FAIL`, with no warning logged.
**Root cause:** `CurioInventoryCapability$Provider.getCapability` returns empty while the client's `CuriosEntityManager.CLIENT` lists no slot for the entity type. That map comes from the data of the server played on; the replay host lacks a world or server datapack, and the recording did not carry the definitions, so every Curios sync message was dropped.
**Fix:** snapshots and checkpoints write an `SPacketSyncData` built from the client's definitions, before the entities.
**Prevention:** a mod's per-entity sync can depend on definitions from server data. Test an integration with data only the recorded world has, not only with data shipped in a mod jar.
**Follow-up:** the next run gave `worn stacks on the actor=0 -> FAIL`. This one was the test: the recording held no live Curios message besides the definitions, checkpoint 60 held no worn stack and the saved player had an empty `Curios` list, so the ring was never worn. The test player joins before its datapack exists, and `CurioInventoryWrapper.readTag` returns on an empty list, so Curios' reload never gives handlers to a player who had none, while `curios add` and `curios replace` still report success. The test now rebuilds the handlers on the server and reloads again, waits for the slot on the client, and logs the worn count during the recording. A player joining with the datapack present is not affected, and the definitions fix stays needed.
**1.21.1:** Curios 9.5.1 has the same gate (`Curios.registerCaps` returns no capability while `CuriosApi.getEntitySlots` is empty, and `CuriosClientPackets` drops `SPacketSyncCurios` without one), so the definitions and the test datapack were ported there. Until then the 1.21.1 run only passed with a ring slot from Iron's Spellbooks, which the replay host also loads.

## [2026-11-06 12:17] — CurseForge upload rejects an empty relations list with HTTP 400 errorCode 1002
**Context:** Sending the 1.1.0 jars to CurseForge through its upload API; jars without any mod dependency sent relations.projects as an empty list
**Error:** HTTP 400 errorCode 1002: Error in field metadata: Array item count 0 is less than minimum count of 1. Path relations.projects
**Root cause:** The CurseForge upload API requires at least one item in relations.projects when the relations field is present, so an empty list is refused while a missing field is accepted
**Fix:** Left the relations field out of the metadata for jars with no dependency; the failed file had not been created, so the upload resumed without a duplicate
**Prevention:** Send relations to CurseForge only when it holds a project, and make upload scripts skip what is already sent so a rerun resumes safely

## [2026-11-06 12:17] — Upload sheet older than the reissued jars, wrong hashes and changelog entries missing
**Context:** Preparing the 1.1.0 upload to the mod sites from build/mod-release/1.1.0 after the jars and notes had been replaced on the GitHub release by later fixes
**Error:** 6 of 9 jars in build/mod-release/1.1.0 differed from jars/1.1.0 and the GitHub release, and changelog.md lacked the Curios slot definitions and Building from source entries in both languages
**Root cause:** The fixes went out under 1.1.0 through tools/collect_jars.sh and a manual asset replacement, which do not refresh the sheet that mod-release wrote at the first publication
**Fix:** Uploaded jars/1.1.0 after proving each jar identical to the GitHub release, with the changelog text read from the current CHANGELOG.md section, and checked Modrinth by sha512 afterwards
**Prevention:** Before any upload, compare each jar with the GitHub release and every entry of the current CHANGELOG section with the text to send; never trust a sheet older than the last commit

## [2026-11-06 12:17] — Upload sheet tags a client-only mod Client and Server on Forge and NeoForge
**Context:** Reading the mod-release upload sheet for the 1.1.0 Forge and NeoForge jars before sending them to CurseForge
**Error:** Sheet environment Client, Server while README, Fabric metadata, the Modrinth project and every earlier CurseForge file say client only
**Root cause:** The sheet derives the environment from the side key of the dependency blocks in mods.toml, which says where a dependency applies and not where the mod runs
**Fix:** Tagged the nine CurseForge files with Client only, as on every earlier file of the project
**Prevention:** Check the environment tag against the previous file on the site and the README; never take it from a mods.toml dependency side

## [2026-11-17 07:55] — Release build stopped: gradlew not executable
**Context:** mod-release dry run of 1.1.1 on the Debian box, tools/collect_jars.sh --build
**Error:** tools/collect_jars.sh: ./gradlew: Permission denied, every target FAIL
**Root cause:** the gradlew files of the targets are committed with mode 100644 and the script ran ./gradlew
**Fix:** collect_jars.sh runs sh ./gradlew (PR #5)
**Prevention:** scripts launch the wrapper with sh, never rely on the exec bit of files that came from Windows

## [2026-11-17 07:55] — Immersive Vehicles invisible in replays
**Context:** player report: vehicles of Immersive Vehicles never showed in replays (NeoForge 1.21.1)
**Error:** replay plays, vehicles absent; the self test shows 1 of 3 builder entities with data and no draw entity
**Root cause:** IV draws every vehicle through BuilderEntityRenderForwarder, which renders only when it follows Minecraft.player; in a replay it follows the recording player or nobody. Vehicles already loaded before the recording also never get their handshake answer
**Fix:** ImmersiveVehiclesCompat: own client-side forwarder that follows the camera player, handshake answers written in snapshots and checkpoints, serverbound mts:main blocked
**Prevention:** for a mod with its own render or sync entity, check who it follows and what it asks the server once, and cover it in the self test

## [2026-11-17 17:24] — Immersive Vehicles packs and released jars in a Forge 1.20.1 dev run
**Context:** self test of the Immersive Vehicles support on forge-1.20.1, the mod and its Official Content Pack loaded as remapped dev dependencies (-PdevMods)
**Error:** SELFTEST vehicles: no car item of the official content pack, then vehicles=0 -> FAIL and JsonSyntaxException: Unknown item 'mts:mtsofficialpack.screws'
**Root cause:** Immersive Vehicles reads its content packs only from the jars of the game's mods folder; a pack on the run classpath loads its assets but registers no item. A released 1.20.1 jar (SRG names) cannot go in mods/ of a dev run either
**Fix:** the mod goes through -PdevMods=run-iv/devmods (fg.deobf), the content pack (no game class) in run-iv/mods
**Prevention:** for a mod with content packs, put the packs where the mod scans for them and check the pack items exist before the test scene

## [2026-11-18 07:46] — Page badges redrawn in pixel style, push to the images repository refused
**Context:** Redrawing badge-minecraft.png and badge-loaders.png of the CurseForge page with images/tools/titles.py on the Debian box, then publishing them to Team-Arcadia/images
**Error:** titles.py wrote 33 px pixel badges (the published ones are 68 px vector badges); git push to Team-Arcadia/images: could not read Username (no credentials for that repository in a mod-just-replay task)
**Root cause:** VectorCraft and the Inter font are not installed here, so vector.available() is false and the pixel fallback is used; the task runner only hands out GitHub credentials for the repository of the task
**Fix:** Rendered the SVG of vector.badge() with headless Chromium and the static Inter 4.1 TTF files from a temp folder (same widths as the published badges within 1 px); the push was left to a task on the images repository
**Prevention:** Never publish badges or titles drawn by titles.py on Linux without comparing them to the published ones; run the images push as a task of the images repository
**Recurred:** 2026-11-18 13:30, Tasks 440 and 453 were both started as mod-just-replay tasks, so the images clone stayed read-only and nothing was pushed. vigie proposer always files the follow-up on the current project (Vigie issue 462): create the badges task from the dashboard on team-arcadia/other/images, together with mod-just-replay for the page.

## [2026-11-18 09:42] — Page badge widths of unpublished pictures
**Context:** Task 422 redrew the Minecraft and loader badges but could not push them to Team-Arcadia/images; the page rebuilt with the new local badges was committed anyway.
**Error:** curseforge_page.md gave badge-minecraft.png width 250 and badge-loaders.png width 222 while images_base still pins 7d35eb95556b, whose badges are 312 and 227 px wide: pasted as is, both badges show up 1.6x and 2x too big. check_page.py --online said nothing to report.
**Root cause:** build_page.py reads the widths from the local images/ copies, which held the unpublished badges; check_page.py --online only checks that the pictures load, not their size.
**Fix:** fetch_published.py . --force brought back the published badges, build_page.py wrote widths 156 and 114 again; a width check against the online pictures (half the natural width) found 0 mismatches.
**Prevention:** Build and commit the page only from the pictures images_base serves: move images_base and rebuild in the same commit once the new pictures are pushed, never before.

## [2026-11-18 09:51] — Report: Create refuses to register its blocks with Just Replay (not reproduced)
**Context:** A test on another machine summed up as: its usual mods plus Just Replay do not load, Create refuses to register its blocks; without Create, Create Aeronautics and Create Propulsion the game loads. Only a screenshot of that summary was given, no log and no mod list.
**Error:** Summary only: Create refuses to register its blocks at startup when Just Replay is installed with Create, Create Aeronautics and Create Propulsion (NeoForge 1.21.1 dev client).
**Root cause:** Not in Just Replay: with Create 6.0.10, Create Aeronautics 1.3.2, Sable 2.0.6 and Create Propulsion: Simulated 1.1.5 on NeoForge 21.1.265 the dev client reaches the title screen (alone and with 16 common mods) and the full self test passes (155 lines, 0 failure). The owner ruled the report a mistake of the tester.
**Fix:** No code change. The combination is now listed as loaded in docs/COMPATIBILITY.md.
**Prevention:** Before hunting a load failure from someone else's summary, get the failing latest.log and its mods folder; first replay the same mods in a separate game folder (neoforge-1.21.1/run-<name>/mods, ignored by git) with -P neo_version=21.1.265 (Sable and Aeronautics need 21.1.228 or later).

## [2026-11-18 13:50] — Page images refused by jsDelivr
**Context:** Checking curseforge_page.md online before moving the badge pin of images_base (jsDelivr, commit 7d35eb95556b of Team-Arcadia/images)
**Error:** check_page.py --online: 6 images HTTP 403 'Package size exceeded' (icons/play.png, title-films-fr.gif...), the 33 others served only from the jsDelivr cache
**Root cause:** Team-Arcadia/images grew past 50 MB (156 MB), so jsDelivr refuses every file of the repository it has not cached, any commit pin included
**Fix:** images_base moved to GitHub Pages (https://team-arcadia.github.io/images/mods/justreplay/curseforge/, on, branch main); page rebuilt, only the host changed; check_page.py --online nothing to report, widths 0 mismatches on 39 images
**Prevention:** Never pin page images on jsDelivr again: Pages serves main, so rebuild the page (widths) in the same task as any push of new pictures

## [2026-11-18 19:35] — Session killed by memory while two agents decompiled mod jars
**Context:** Two background agents ran Vineflower on Epic Fight and Custom NPCs jars while the main session ran a javap loop over six Armourer's Workshop jars.
**Error:** The main shell command ended with exit code 137 and the whole session stopped; both agents were stopped with no report.
**Root cause:** Each Vineflower JVM takes a quarter of the 8 GB by default; two of them plus javap and the Claude processes ran the machine out of memory.
**Fix:** Resumed one agent at a time with java -Xmx1500m for Vineflower, nothing else heavy alongside.
**Prevention:** One JVM-heavy job at a time on this machine (decompiler, Gradle, game); give Vineflower -Xmx1500m in every agent brief.

## [2026-11-19 00:40] — javac cannot find Entity in the NeoForge 26.1.2 patched game jar
**Context:** Compiling a typed check of Armourer's Workshop names outside Gradle against the minecraft-patched merged jar of neoforge-26.1.2/build/moddev/artifacts.
**Error:** error: cannot find symbol, symbol: class Entity, location: package net.minecraft.world.entity (javac -verbose shows Entity.class loaded from that jar).
**Root cause:** The ModDevGradle patched jar holds Minecraft only; Entity extends NeoForge's AttachmentHolder, which is in the NeoForge universal jar, and javac reports the incomplete class as a missing symbol.
**Fix:** Added the NeoForge universal jar of the same version (net.neoforged/neoforge in the Gradle modules cache) to the classpath.
**Prevention:** Outside Gradle, compile against NeoForge 26.1.2 with both the minecraft-patched jar and the neoforge universal jar (the 1.21.1 neoforge merged jar already holds both).

## [2026-11-19 10:08] — ModDevGradle unit tests: no Gson on the test classpath, then 'Mod development has not been enabled yet'
**Context:** Adding JUnit 5 tests of common/<mc>/src/test to the NeoForge targets (ModDevGradle 2.0, legacyforge for NeoForge 1.20.1), task 470
**Error:** compileTestJava: package com.google.gson does not exist; after adding neoForge.addModdingDependenciesTo(sourceSets.test) above the neoForge block: A problem occurred evaluating root project 'justreplay'. > Mod development has not been enabled yet
**Root cause:** ModDevGradle puts the game and its libraries on the main source set only; addModdingDependenciesTo needs the neoForge { version = ... } block (which enables mod development) to have run first
**Fix:** Call neoForge.addModdingDependenciesTo(sourceSets.test) (legacyForge.addModdingDependenciesTo on NeoForge 1.20.1) after the neoForge/legacyForge block, at the end of build.gradle. Loom and ForgeGradle 6/7 need nothing
**Prevention:** On ModDevGradle targets, add test dependencies on the game with addModdingDependenciesTo placed after the neoForge or legacyForge block

## [2026-11-19 10:08] — Unit tests leave a logs folder in the loader project
**Context:** First runs of the shared unit tests (./gradlew test) on the 1.21.1 targets, task 470
**Error:** Untracked neoforge-1.21.1/logs/, forge-1.21.1/logs/, fabric-1.21.1/logs/ (empty latest.log and debug.log) after the test task
**Root cause:** The tested classes log through SLF4J; the game's Log4j configuration on the test classpath writes logs/ in the working folder, which is the project folder by default
**Fix:** In each build.gradle, the test task runs in build/test-run: workingDir = layout.buildDirectory.dir('test-run').get().asFile, doFirst { testRun.mkdirs() }
**Prevention:** Give every JUnit test task of a mod project a working folder under build/ so the game's logging never writes into the project

## [2026-11-19 15:13] — List.of contains(null) crashed the project repair
**Context:** Unit test of WorldSettings.normalize with a hand edited project (fog, particles or weather set to null)
**Error:** java.lang.NullPointerException in WorldSettings.normalize: WEATHERS.contains(weather) with weather == null
**Root cause:** Lists made by List.of throw on contains(null); the repair code of the World tab settings checked membership before checking for null, so a project with "weather": null could not be loaded and the replay did not open
**Fix:** Check null first in WorldSettings.normalize (weather, fog, particles) and in particleHidden, in the three common folders; WorldVisualsTest covers it
**Prevention:** Never call contains on a List.of or Set.of with a value read from JSON without a null check; add a null case to the unit test of every normalize method

## [2026-11-19 17:00] — Merged branches gave two self test steps the same numbers
**Context:** Merging the local branches of tasks 471 (replay naming window) and 474 (Flashback gaps) into one branch, task 475
**Error:** case 70, 71 and 72 twice in the switch (step) of SelfTest.java in the three common folders (javac: duplicate case label); git merged both without a conflict
**Root cause:** Both branches started from develop and took the first free numbers after 62 for their new steps; the hunks did not overlap, so git saw no conflict
**Fix:** The naming window steps moved to 80, 81 and 82 in the three versions; the case labels and the go() transitions were listed to check that no step is duplicated or unreachable
**Prevention:** After merging branches that add self test steps, list the case labels of switch (step) in each SelfTest.java and follow go()/next() from step 0 before building; give each new feature its own block of ten step numbers

## [2026-11-19 17:10] — Temp folder full after copying the loader folders to /tmp
**Context:** Comparing changed files with develop during task 475: extracted common/ from develop and copied the nine loader folders next to it in /tmp
**Error:** No space left on device: the /tmp volume had 0 MB free and the command output was lost
**Root cause:** /tmp is a 3.9 GB tmpfs held in memory on this machine, and the loader folders carry their build and run folders, several GB together
**Fix:** Removed the copy (2.2 GB free again) and compared single files with git show develop:<path>
**Prevention:** Never copy loader folders (build/, run*/) to /tmp; compare with git show <ref>:<path>, or git archive only the paths needed

## [2026-11-20 00:32] — Hidden armor did nothing on NeoForge 1.21.1: a bare mixin method name hooks one overload
**Context:** Task 467: in-game self test of hidden armor on NeoForge 1.21.1 with the armor layer counted on its own
**Error:** SELFTEST hidden armor: pieces skipped 26 of which by the armor layer 0 -> FAIL (helmet still drawn)
**Root cause:** Mixin 0.8 narrows @Redirect(method = "renderArmorPiece") to the first method of that name (TargetSelectors, SELECT_MEMBER turns the default quantifier into SINGLE). NeoForge declares the vanilla renderArmorPiece before its 12 argument overload, and render() only calls the overload.
**Fix:** method = "renderArmorPiece*" takes every overload (HiddenArmorMixin, commit e9f1734); the Fabric refmap maps it to the single vanilla method.
**Prevention:** Never rely on a bare method name to cover overloads: use name* or full descriptors, and run livrables/verif-mixins/scan_overloads.py of task 467 on each common tree after adding a name-only selector.

## [2026-11-20 00:32] — Self test passed hidden armor while the armor layer hid nothing
**Context:** Task 467: the hidden armor self test step counted skipped pieces to prove the armor hooks work
**Error:** pieces skipped 19 -> OK on NeoForge 1.21.1 although the vanilla helmet was still drawn
**Root cause:** The head item layer hook calls ArmorSlots.countSkipped() on every frame of a hidden head by itself, so the total was above zero without the armor layer skipping anything.
**Fix:** The armor layer hooks count apart (ArmorSlots.countArmorLayerSkipped); the check requires that counter above zero (commit 93fbfd1). Proved red before the mixin fix, green after.
**Prevention:** A self test counter must only be fed by the hook it proves; prove each new check red once on a known bad case.

## [2026-11-20 00:32] — Slow motion export self test: file not found although the export succeeded
**Context:** Task 467: first in-game run of self test steps 72 to 74 (speed track export, task 474)
**Error:** slow motion export: 241 frames ... file false -> FAIL
**Root cause:** The export named the file with its range (rangeInFileName, task 472: selftest_slow_00m01s-00m03s.mp4) while the step looked for selftest_slow.mp4, and it looked as soon as capture ended, before the finisher thread muxed the file.
**Fix:** The step asks for the exact name like SceneJob (start with rangeInName false) and waits for VideoExporter.completion() before reading output() (commit 44bc397).
**Prevention:** Check an export through VideoExporter.output() once completion() is done, never by a guessed name right after isActive() turns false.

## [2026-11-20 00:33] — Game froze on Fabric when leaving a replay (IntegratedServer.halt waits for a dead thread)
**Context:** Task 467: Fabric 26.1.2 self test, leaving the replay after the slow motion export (a server resource pack was active)
**Error:** Self test TIMEOUT; jstack: Render thread parked in CompletableFuture.join <- BlockableEventLoop.executeBlocking <- IntegratedServer.halt <- Minecraft.disconnect, no Server thread left
**Root cause:** The integrated server stops by itself when its player logs out. Leaving reloaded the resources (server pack dropped) before Minecraft.disconnect reached IntegratedServer.halt, which hands one more task to the server thread with executeBlocking, unguarded in vanilla; the thread was gone. NeoForge and Forge 47 guard it with isRunning().
**Fix:** IntegratedServerHaltMixin redirects that executeBlocking behind server.isRunning() in every version; self test step 74 now leaves once the host thread has ended, which froze for sure without the guard (commit 63f52ae, Fabric 26.1.2 PASS).
**Prevention:** Before leaving a world from code, assume the host may already be stopped; test leave paths on Fabric too, NeoForge hides this deadlock.

## [2026-11-20 00:33] — Forge 1.21.1 and 26.1.2 dev clients cannot start in the Vigie sandbox (~/.minecraft/assets)
**Context:** Task 467: test matrix runClient of forge-1.21.1 and forge-26.1.2 (ForgeGradle 7, Slime Launcher 0.2.3)
**Error:** java.lang.IllegalArgumentException: Failed to create index directory: ~/.minecraft/assets/indexes (self test INCOMPLETE after 30 s)
**Root cause:** Slime Launcher keeps the game assets in <user.home>/.minecraft/assets and checks or downloads them before launch; the task sandbox cannot write the home folder. No launcher option is reachable from the FG7 run config.
**Fix:** -PmcHome=<dir> sets user.home of the dev game (forge build.gradle, commit 2c3eabb); <dir>/.minecraft/assets links to ~/.gradle/caches/neoformruntime/assets (indexes 17 and 30 complete, nothing downloaded).
**Prevention:** On this machine run Forge 1.21.1 and 26.1.2 dev clients with -P mcHome=<task folder>/mchome and that link.

## [2026-11-20 00:33] — Armourer's Workshop 3.4 stops the Forge 1.20.1 dev client at launch
**Context:** Task 467: test matrix forge-1.20.1 with the Armourer's Workshop Forge 1.20.1 jar in devmods (-PdevMods)
**Error:** Critical injection failure: @Inject annotation on aw2$renderGuiItemPre could not find any targets matching 'm_280405_(...)' (armourersworkshop-compat-mixins.json GuiGraphicsMixin)
**Root cause:** Armourer's Workshop names its mixin targets in SRG itself through its config plugin (moe.plushie.armourers_workshop.api.annotation.Resolver), without a refmap, so fg.deobf and mixin.env.remapRefMap cannot translate them to the Parchment names of the dev game.
**Fix:** None on our side: the hidden armor hooks were checked on Forge 1.20.1 by name against the real jar (verif-compat of task 467) and in game on Fabric 1.20.1, whose code is the same.
**Prevention:** Test Armourer's Workshop 1.20.1 in game on Fabric 1.20.1 or with an installed Forge game, not in the Forge 1.20.1 dev client.

## [2026-11-20 01:19] — Replay crashed with ConcurrentModificationException in ActorManager.pumpLookups
**Context:** Task 467: Fabric 1.21.1 self test with player actors while the profile service answered an HTML error page
**Error:** java.util.ConcurrentModificationException at ActorManager.pumpLookups(ActorManager.java:462) <- ActorManager.tick <- ReplaySession.clientTick (client crash)
**Root cause:** pumpLookups started each due lookup inside its iterator loop. A lookup whose future was already complete (failed at once) ran its whenComplete callback on the render thread, and Minecraft.execute ran complete() right away, which queued the retry into the list being walked.
**Fix:** pumpLookups collects the due lookups, ends the walk, then starts them (commit after 'fix: a failed actor skin lookup could crash the replay'), in the three versions.
**Prevention:** Never start work that may complete synchronously while iterating the queue it can modify; collect first, act after the loop.
