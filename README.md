# DynPatcher

Dynamically compile and load Mixin patches from source at runtime.

## User Guide

### Overview
DynPatcher allows you to add or modify Fabric Mixins without recompiling the mod JAR. Source `.java` files placed in the patches directory are compiled at startup using an embedded Java compiler and registered with SpongePowered Mixin.

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

### Caching
Compiled bytecode is cached in `config/dynpatcher/.cache/`. A source file is recompiled only if its content or timestamp changes, or if the installed mod version changes.

## Developer Documentation

### Architecture
DynPatcher executes during early startup as an `IMixinConfigPlugin`:

1. Configuration & Discovery: Reads `config.json` and scans `config/dynpatcher/patches/` for `.java` files, filtering out disabled patches.
2. Cache Resolution: Validates cached `.class` files against `index.json` using timestamp and SHA-256 content hashes.
3. Preprocessing: Reads stale or uncached sources, applying syntax adjustments (default package injection and `public` modifier stripping) in memory.
4. Runtime Compilation: Compiles source objects in memory using the Eclipse Compiler for Java (ECJ).
5. Bytecode Injection: Uses ASM to append `remap = false` to Mixin annotations on compiled classes that do not specify a remapping preference.
6. Mixin Registration: Dynamically generates per-package Mixin JSON configurations and registers them via `Mixins.addConfiguration`.

### Class Summary
* `DynamicPatcher`: Mod entry point and shared SLF4J logger instance.
* `DynamicMixinPlugin`: Mixin config plugin entry point orchestrating discovery, compilation, and registration.
* `SourcePreprocessor`: Applies in-memory source transformations prior to compilation.
* `EcjRuntimeCompiler`: Interfaces with ECJ to compile in-memory Java file objects against the Fabric runtime classpath.
* `CompilationCache`: Manages disk caching, hash validation, and invalidation upon mod updates.
* `MixinConfigGenerator`: Creates valid Mixin configuration JSON strings per package.
* `AsmRemapInjector`: ASM class visitor that injects `remap = false` into Mixin annotations.
* `JavaVersionUtil`: Resolves the target Java version based on the runtime JVM feature version and maximum ECJ version supported.
* `PatcherConfig`: Data structure for `config.json`.
