# DynPatcher

Dynamically compile and load Mixin patches from source at runtime.

## User Guide

### Overview
DynPatcher allows you to add or modify Fabric Mixins without recompiling the mod JAR. Source `.java` files placed in the patches directory are compiled at startup using an embedded Java compiler and registered with Mixin.

### File Structure
When the game launches with DynPatcher installed, the following directory structure is created in your `.minecraft/config/dynpatcher/` folder:

* `patches/`: Place your Mixin `.java` source files here. Subdirectories can be used to organize files.
* `config.json`: Mod configuration file.
* `.cache/`: Internal directory containing compiled bytecode and the cache index. Do not modify manually.

### Disabling Patches
To disable specific patches without removing the source files, list their relative paths under `disabledPatches` in `config.json`:

```json
{
  "disabledPatches": [
    "example/MyMixin.java"
  ]
}
```

Paths are relative to the `config/dynpatcher/patches/` directory.

### Source File Handling
* Renamed files: If a `.java` file is renamed such that its filename no longer matches its `public class` declaration, DynPatcher removes the `public` modifier in memory during compilation to avoid compiler errors.
* Default package: If a `.java` file lacks a `package` declaration, DynPatcher automatically remaps it to `dynpatcher.generated` in memory so Mixin can load it.
* Environment filtering: Mixin classes can be annotated with `net.fabricmc.api.Environment` (`@Environment(EnvType.CLIENT)` or `@Environment(EnvType.SERVER)`). Unannotated Mixins are loaded in all environments, while annotated Mixins are loaded only in their corresponding target environment (`CLIENT` or `SERVER`).

### MixinSquared Support
DynPatcher includes [MixinSquared](https://github.com/Bawnorton/MixinSquared) out of the box. This allows dynamic patches to target and modify methods injected by other Mixins.

When writing a MixinSquared patch to target another Mixin:
* Point `@Mixin(...)` at the **target class being transformed** (e.g. `SkyRenderer.class` or `Object.class`), **not** the target Mixin class itself.
* Use `@TargetHandler` to specify the target Mixin class name and method.
* Specify `method = "@MixinSquared:Handler"` in your injector.

For further details, refer to the [MixinSquared Wiki](https://github.com/Bawnorton/MixinSquared/wiki).

### Caching
Compiled bytecode is cached in `config/dynpatcher/.cache/`. A source file is recompiled only if its content or timestamp changes, or if the installed mod version changes.

## Developer Documentation

### Architecture
DynPatcher executes during early startup as an `IMixinConfigPlugin`:

1. Configuration & Discovery: Initializes MixinSquared bootstrap, reads `config.json`, and scans `config/dynpatcher/patches/` for `.java` files, filtering out disabled patches.
2. Cache Resolution: Validates cached `.class` files against `index.json` using timestamp and SHA-256 content hashes.
3. Preprocessing: Reads stale or uncached sources, applying syntax adjustments (default package injection and `public` modifier stripping) in memory.
4. Runtime Compilation: Compiles source objects in memory using the Eclipse Compiler for Java (ECJ).
5. Bytecode Inspection & Patching: Uses ASM to inspect compiled classes for `net.fabricmc.api.Environment` annotations (filtering out Mixins incompatible with the current environment) and appends `remap = false` to Mixin annotations that do not specify a remapping preference.
6. Mixin Registration: Dynamically generates per-package Mixin JSON configurations, re-orders MixinSquared extensions, and registers configurations via `Mixins.addConfiguration`.
