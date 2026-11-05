# Error log

## [2026-11-04 14:00] — Armourer's Workshop outfits missing from replays (1.0.9 and 1.0.10)
**Context:** a user's singleplayer recordings with Armourer's Workshop showed no outfits; 1.0.10 logged `snapshot wrote 39 skins` yet nothing showed.
**Error:** no error anywhere; the recording file had no `armourers_workshop:*` payload at all.
**Root cause:** the compat encoded packets with Armourer's Workshop's `PacketSplitter.split`, which runs on its own thread pool and hands the parts back later. The collector was read right after the call, always empty, so nothing was written while the snapshot counted skins as written. Two releases shipped without an in-game test because no `.armour` file was available.
**Fix:** the packet id and data are encoded on the calling thread and cut with the same begin/body/end markers. Reproduced and verified with a user's `.armour` file and `/armourers setSkin @s head /<file>.armour` (the slot name is required).
**Prevention:** never trust a mod's helper to be synchronous; count what was actually written, not what was asked. Do not ship a compat claim without one end-to-end in-game check; ask for test assets first.

## [2026-11-01 20:10] — Sable airships empty in replays
**Context:** first in-game test of the Sable support: an airship assembled before recording, replayed with a scene job.
**Error:** the replay showed no airship; Sable logged `Cannot add chunk at 0, 0 in nonexistent sub-level plot` at the start and after every seek.
**Root cause:** a replayed login packet keeps the existing camera player and adds it to the new level without updating the player's own level field. Sable's tracking packet reaches the level through the packet context player, so the sub-level was created in the level from before the seek, and its chunks then arrived in a level without it. Not a packet order problem (checked with debug logs before guessing further).
**Fix:** after a replayed login, the camera player is moved to the current level (`EntityInvoker.setLevel`).
**Prevention:** any mod handler that uses `context.player().level()` depends on this; when a mod's state vanishes in a replay, compare `mc.player.level()` with `mc.level` first.

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

## [2026-10-11 14:50] — Replay session dropped before playback started
**Context:** opening a replay from the title screen (self test step 4).
**Error:** playback never started; the login event saw `session=null`.
**Root cause:** loading the host world fires `ClientPlayerNetworkEvent.LoggingOut` with a null connection.
`isReplayConnection(null)` returned true because the session connection was still null, so the session closed.
**Fix:** `isReplayConnection` requires a non-null connection; only the replay connection ends a session.
**Prevention:** never compare a nullable connection with `==` without a null guard.

## [2026-10-11 14:20] — BOM written by PowerShell broke compilation
**Context:** editing a Java file with `Set-Content -Encoding utf8` in Windows PowerShell 5.1.
**Error:** `illegal character: '﻿'`.
**Root cause:** PowerShell 5.1 writes UTF-8 with a BOM.
**Fix:** stripped the BOM with sed.
**Prevention:** edit sources with the Edit/Write tools or sed, never PowerShell `Set-Content`.

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

## [2026-11-03 12:00] — Armourer's Workshop outfits missing from singleplayer replays (1.0.9)
**Context:** a user recorded in singleplayer with Armourer's Workshop 3.4.0-beta.3 after loading .armour files into the world's skin database, then replayed.
**Error:** replays showed no outfits; no error from Just Replay or Armourer's Workshop in the log.
**Root cause:** Armourer's Workshop has one SkinLoader for the whole JVM. The integrated server prepares it with its own sessions, so in singleplayer a db: skin is read straight from the world's skin database and no RequestSkinPacket or ResponseSkinPacket ever crosses the connection. 1.0.9 recorded skins only from those packets and from the loader cache at snapshot time. The replay world is another integrated world with an empty skin database, and its loader looks db: skins up there, never over the network, so the request mixin never fired.
**Fix:** record every skin at the loader (SkinLoader$Entry.accept mixin), add every worn skin at snapshots (loaded from the world database in singleplayer), and during a replay serve lookups from the recording at SkinLoader.resume before any source is tried. Snapshot skin count logged, missing worn skins warned.
**Prevention:** for any mod with a cache shared between client and integrated server, check how the client gets its data in singleplayer before assuming it comes over the network.

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

## [2026-11-05 21:30] - Curios worn items dropped in a replay when a datapack supplies the slots (1.20.1)
**Context:** production self test on Forge 1.20.1 with Curios 5.14.1 and the test ring datapack.
**Error:** `SELFTEST curios at start: worn stacks on the actor=-1 -> FAIL`, with no warning logged.
**Root cause:** `CurioInventoryCapability$Provider.getCapability` returns empty while the client's `CuriosEntityManager.CLIENT` lists no slot for the entity type. That map comes from the data of the server played on; the replay host lacks a world or server datapack, and the recording did not carry the definitions, so every Curios sync message was dropped.
**Fix:** snapshots and checkpoints write an `SPacketSyncData` built from the client's definitions, before the entities.
**Prevention:** a mod's per-entity sync can depend on definitions from server data. Test an integration with data only the recorded world has, not only with data shipped in a mod jar.
