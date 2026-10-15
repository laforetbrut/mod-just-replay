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
