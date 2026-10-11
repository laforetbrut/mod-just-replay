# Error log

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
