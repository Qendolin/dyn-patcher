package com.qendolin.dynpatcher;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Central Mixin plugin that discovers, compiles, and registers dynamic Mixin classes
 * from source files placed in {@code config/dynpatcher/patches/}.
 */
public class DynamicMixinPlugin implements IMixinConfigPlugin {

    private final List<String> pendingConfigs = new ArrayList<>();

    @Override
    public void onLoad(String mixinPackage) {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("dynpatcher");
            Path patchesDir = configDir.resolve("patches");
            Path cacheDir = configDir.resolve(".cache");
            Path configFile = configDir.resolve("config.json");

            Files.createDirectories(configDir);
            Files.createDirectories(patchesDir);
            Files.createDirectories(cacheDir);

            // ── 0. Load config.json ──────────────────────────────────────────
            PatcherConfig patcherConfig = PatcherConfig.loadOrCreate(configFile);

            // ── 1. Discover source files under patches/ ──────────────────────
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(patchesDir)) {
                javaFiles = walk
                        .filter(p -> p.toString().endsWith(".java"))
                        .collect(Collectors.toList());
            }

            if (javaFiles.isEmpty()) {
                DynamicPatcher.LOGGER.info("[DynPatcher] No source files found in '{}'.", patchesDir);
                return;
            }

            // ── 2. Determine Mod Version & Load Cache ────────────────────────
            String modVersion = FabricLoader.getInstance()
                    .getModContainer(DynamicPatcher.MOD_ID)
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");

            CompilationCache cache = new CompilationCache(cacheDir, patchesDir, modVersion);
            cache.load();

            // ── 3. Classify files & Check disabled status ───────────────────
            List<MixinSource> toCompile = new ArrayList<>();
            List<String> allMixinFqcns = new ArrayList<>();

            for (Path javaFile : javaFiles) {
                String relPath = patchesDir.relativize(javaFile.toAbsolutePath()).toString().replace('\\', '/');

                if (patcherConfig.isPatchDisabled(relPath)) {
                    DynamicPatcher.LOGGER.info("[DynPatcher] Patch '{}' is disabled in config.json; skipping.", relPath);
                    continue;
                }

                CompilationCache.ResolveResult result = cache.resolve(javaFile);
                if (result.isHit()) {
                    List<String> cachedClasses = result.classes();
                    List<String> mixins = collectMixinsFromCache(cachedClasses, cacheDir);
                    DynamicPatcher.LOGGER.info(
                            "[DynPatcher] Reusing cached class(es) {} for patch '{}' (cache hit: {}).",
                            cachedClasses, relPath, result.hitReason());
                    allMixinFqcns.addAll(mixins);
                } else {
                    DynamicPatcher.LOGGER.info(
                            "[DynPatcher] Queueing patch '{}' for compilation (cache miss: {}).",
                            relPath, result.missReason());
                    try {
                        toCompile.add(SourcePreprocessor.process(javaFile));
                    } catch (IOException e) {
                        DynamicPatcher.LOGGER.error(
                                "[DynPatcher] Could not read patch '{}'; skipping. Reason: {}",
                                relPath, e.getMessage());
                    }
                }
            }

            // ── 4. Compile queued sources ─────────────────────────────────────
            if (!toCompile.isEmpty()) {
                DynamicPatcher.LOGGER.info("[DynPatcher] Compiling {} source patch(es)...", toCompile.size());
                try {
                    EcjRuntimeCompiler.CompilationResult compilationResult = EcjRuntimeCompiler.compile(toCompile);
                    allMixinFqcns.addAll(processCompilationResult(compilationResult, toCompile, cache, patchesDir));
                } catch (Exception e) {
                    DynamicPatcher.LOGGER.error("[DynPatcher] Compilation failed unexpectedly.", e);
                }
            }

            if (allMixinFqcns.isEmpty()) {
                DynamicPatcher.LOGGER.info("[DynPatcher] No active @Mixin classes found.");
                cache.flush();
                return;
            }

            // ── 5. Add cache directory to classpath ───────────────────────────
            FabricLauncherBase.getLauncher().addToClassPath(cacheDir);

            // ── 6. Generate per-package Mixin configs ─────────────────────────
            Map<String, List<String>> byPackage = MixinConfigGenerator.groupByPackage(allMixinFqcns);
            for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
                String pkg = entry.getKey();
                List<String> simpleNames = entry.getValue();

                if (pkg.isEmpty()) {
                    DynamicPatcher.LOGGER.error(
                            "[DynPatcher] Skipping {} Mixin class(es) with no package — this is a bug.",
                            simpleNames.size());
                    continue;
                }

                try {
                    String configName = MixinConfigGenerator.generate(cacheDir, pkg, simpleNames);
                    pendingConfigs.add(configName);
                    DynamicPatcher.LOGGER.info(
                            "[DynPatcher] Registered Mixin config '{}' ({} class(es)).",
                            configName, simpleNames.size());
                } catch (IOException e) {
                    DynamicPatcher.LOGGER.error(
                            "[DynPatcher] Could not write Mixin config for package '{}'.", pkg, e);
                }
            }

            // ── 7. Save cache index ───────────────────────────────────────────
            try {
                cache.flush();
            } catch (IOException e) {
                DynamicPatcher.LOGGER.warn("[DynPatcher] Failed to save cache index.", e);
            }

        } catch (Exception e) {
            DynamicPatcher.LOGGER.error("[DynPatcher] Critical initialization failure.", e);
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        for (String config : pendingConfigs) {
            Mixins.addConfiguration(config);
        }
        pendingConfigs.clear();
    }

    private List<String> collectMixinsFromCache(List<String> fqcns, Path cacheDir) {
        List<String> mixins = new ArrayList<>();
        for (String fqcn : fqcns) {
            if (fqcn.contains("$")) continue;
            Path classFile = cacheDir.resolve(fqcn.replace('.', '/') + ".class");
            try {
                if (AsmRemapInjector.isMixinClass(Files.readAllBytes(classFile))) {
                    mixins.add(fqcn);
                }
            } catch (IOException e) {
                DynamicPatcher.LOGGER.warn(
                        "[DynPatcher] Could not read cached class '{}': {}", fqcn, e.getMessage());
            }
        }
        return mixins;
    }

    private List<String> processCompilationResult(
            EcjRuntimeCompiler.CompilationResult result,
            List<MixinSource> sources,
            CompilationCache cache,
            Path patchesDir) {

        List<String> mixinFqcns = new ArrayList<>();

        for (MixinSource source : sources) {
            String absPath = source.path().toAbsolutePath().toString();
            String relPath = patchesDir.relativize(source.path().toAbsolutePath()).toString().replace('\\', '/');
            List<String> classNames = result.sourceToClasses().get(absPath);

            if (classNames == null || classNames.isEmpty()) {
                DynamicPatcher.LOGGER.warn(
                        "[DynPatcher] No compiled classes produced for patch '{}'. Check compiler output.",
                        relPath);
                continue;
            }

            Map<String, byte[]> processedBytes = new HashMap<>();
            List<String> validFqcns = new ArrayList<>();

            for (String fqcn : classNames) {
                byte[] bytes = result.compiledClasses().get(fqcn);
                if (bytes == null) continue;

                bytes = AsmRemapInjector.injectRemapFalse(bytes);
                processedBytes.put(fqcn, bytes);
                validFqcns.add(fqcn);

                if (!fqcn.contains("$") && AsmRemapInjector.isMixinClass(bytes)) {
                    mixinFqcns.add(fqcn);
                }
            }

            try {
                cache.store(source.path(), validFqcns, processedBytes);
                DynamicPatcher.LOGGER.info(
                        "[DynPatcher] Successfully compiled and cached patch '{}' -> classes: {}",
                        relPath, validFqcns);
            } catch (IOException e) {
                DynamicPatcher.LOGGER.error(
                        "[DynPatcher] Failed to cache compiled classes for patch '{}'.", relPath, e);
            }
        }

        return mixinFqcns;
    }

    @Override public List<String> getMixins() { return null; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void preApply(String t, ClassNode cn, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode cn, String m, IMixinInfo i) {}
}