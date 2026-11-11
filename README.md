# GameBox Vision Fix

LSPosed module for Xiaomi Security Center `com.miui.securitycenter`.

## Problem

On some cold starts, the Game Toolbox initializes the Joyose picture-enhancement service before
Joyose has populated its supported game list. The toolbox receives `isSupportGameEnhancePkg = 0`,
releases the service, and later hides or disables the super-resolution and frame-insertion controls.
Moving the game to the background and reopening it creates a new session and usually repairs the UI.

## Repair

The module does not force support for arbitrary games and does not replace the original decision.
It watches the normal failure path:

1. initialization completes;
2. the toolbox immediately invokes its service-cleanup method.

Only that path starts a bounded recovery window. The module rebinds the Joyose service and re-runs
the original initialization up to three times. A successful initialization never enters recovery.

## DexKit matching

No obfuscated class, method, or field name is present in the module. DexKit resolves the three methods
at runtime by exact unique log strings:

- `Initialization completed, curPkg = `
- `release service...`
- `startService fail. service not null, pkg = `

Signatures are additionally constrained to the same declaring class, return type, and parameter count.
If a future ROM changes these markers or more than one method matches, the module fails open and logs
the reason without modifying behavior.

## Build

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

The signed APK is written to `outputs/gamebox-vision-fix-v1.0.1.apk`.

## Verification

The build checks:

- APK v2/v3 signatures;
- exact LSPosed scope `com.miui.securitycenter`;
- DexKit, Kotlin, FlatBuffers and `libdexkit.so` are packaged;
- the target descriptor is absent from the generated DEX;
- all three DexKit log markers are present.

Runtime validation on the tablet still requires explicit authorization to install, enable and test
the LSPosed module.
