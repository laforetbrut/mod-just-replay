# Error log

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
