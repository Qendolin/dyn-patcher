package com.qendolin.dynpatcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DynamicMixinPlugin implements IMixinConfigPlugin {

    private final List<String> configNamesToLoad = new ArrayList<>();

    public static class CacheEntry {
        public long lastModified;
        public List<String> classes;

        public CacheEntry(long lastModified, List<String> classes) {
            this.lastModified = lastModified;
            this.classes = classes;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve("dynpatcher");
            Path cacheDir = configDir.resolve(".cache");
            Files.createDirectories(configDir);
            Files.createDirectories(cacheDir);

            // 1. Load the Cache Map JSON
            Path cacheMapFile = cacheDir.resolve("cache_map.json");
            Map<String, CacheEntry> cacheMap = new HashMap<>();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            if (Files.exists(cacheMapFile)) {
                try (Reader reader = Files.newBufferedReader(cacheMapFile)) {
                    Type type = new TypeToken<Map<String, CacheEntry>>(){}.getType();
                    Map<String, CacheEntry> loaded = gson.fromJson(reader, type);
                    if (loaded != null) cacheMap = loaded;
                } catch (Exception ignored) {}
            }

            // 2. Discover Java files
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(configDir)) {
                javaFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            }

            if (javaFiles.isEmpty()) return;

            List<Path> toCompile = new ArrayList<>();
            Map<String, List<String>> packageToMixins = new HashMap<>();

            // 3. Cache Validation
            for (Path javaFile : javaFiles) {
                String javaPathStr = javaFile.toAbsolutePath().toString();
                long currentModified = Files.getLastModifiedTime(javaFile).toMillis();
                CacheEntry entry = cacheMap.get(javaPathStr);

                boolean validCache = false;
                if (entry != null && entry.lastModified >= currentModified) {
                    validCache = true;
                    // Verify all compiled class files actually exist
                    for (String clazz : entry.classes) {
                        Path classFile = cacheDir.resolve(clazz.replace('.', '/') + ".class");
                        if (!Files.exists(classFile)) {
                            validCache = false;
                            break;
                        }
                    }
                }

                if (validCache) {
                    // Cache Hit: Directly parse the cached .class files to check if they are Mixins
                    for (String clazz : entry.classes) {
                        Path classFile = cacheDir.resolve(clazz.replace('.', '/') + ".class");
                        byte[] bytes = Files.readAllBytes(classFile);
                        if (AsmRemapInjector.isMixinClass(bytes)) {
                            registerClassInPackage(clazz, packageToMixins);
                        }
                    }
                } else {
                    // Cache Miss: Schedule compilation
                    toCompile.add(javaFile);
                }
            }

            // 4. Compilation of Out-Of-Date Files
            if (!toCompile.isEmpty()) {
                System.out.println("[DynPatcher] Compiling " + toCompile.size() + " out-of-date mixin sources...");

                MemoryJavaFileManager memoryManager = null;
                Map<String, byte[]> compiledClasses = Collections.emptyMap();
                try {
                    // Ensure the compiler receives our Custom Memory Manager
                    compiledClasses = EcjRuntimeCompiler.compile(toCompile);
                } catch (Exception e) {
                    System.err.println("[DynPatcher] ECJ failed to run compiled classes. Skipping compilation.");
                    e.printStackTrace();
                }

                if (!compiledClasses.isEmpty()) {
                    // We must find our memory manager's source map
                    // Since compile() returns a flat map, we query our compiler class for sourceToClasses
                    Map<String, List<String>> sourceToClasses = EcjRuntimeCompiler.getLastSourceToClasses();

                    for (Map.Entry<String, List<String>> srcEntry : sourceToClasses.entrySet()) {
                        String sourcePath = srcEntry.getKey();
                        List<String> generatedClassNames = srcEntry.getValue();
                        List<String> validCacheClasses = new ArrayList<>();

                        for (String fqcn : generatedClassNames) {
                            byte[] classBytes = compiledClasses.get(fqcn);
                            if (classBytes == null) continue;

                            // Auto-inject remap = false
                            classBytes = AsmRemapInjector.injectRemapFalse(classBytes);

                            // Write to Cache
                            Path classFile = cacheDir.resolve(fqcn.replace('.', '/') + ".class");
                            Files.createDirectories(classFile.getParent());
                            Files.write(classFile, classBytes);

                            // Keep track of this class for mapping
                            validCacheClasses.add(fqcn);

                            // Check if this compiled class is actually a Mixin (skipping inner classes)
                            if (!fqcn.contains("$") && AsmRemapInjector.isMixinClass(classBytes)) {
                                registerClassInPackage(fqcn, packageToMixins);
                            }
                        }

                        // Update the Cache map for this specific Java File
                        Path javaFile = Paths.get(sourcePath);
                        long modifiedTime = Files.getLastModifiedTime(javaFile).toMillis();
                        cacheMap.put(sourcePath, new CacheEntry(modifiedTime, validCacheClasses));
                    }

                    // Save cache mapping back to disk
                    try (Writer writer = Files.newBufferedWriter(cacheMapFile)) {
                        gson.toJson(cacheMap, writer);
                    }
                }
            }

            // 5. Register the Cache Directory to classpath
            FabricLauncherBase.getLauncher().addToClassPath(cacheDir);

            // 6. Generate dynamic mixin configs for arbitrary packages
            for (Map.Entry<String, List<String>> entry : packageToMixins.entrySet()) {
                String pkg = entry.getKey();
                List<String> classes = entry.getValue();

                String configName = "dynpatcher." + (pkg.isEmpty() ? "default" : pkg) + ".json";
                Path configFile = cacheDir.resolve(configName);

                String json = generateMixinJson(pkg, classes);
                Files.write(configFile, json.getBytes());

                configNamesToLoad.add(configName);
                System.out.println("[DynPatcher] Registered Mixin Config: " + configName + " (" + classes.size() + " classes)");
            }

        } catch (Exception e) {
            System.err.println("[DynPatcher] Critical initialization failure!");
            e.printStackTrace();
        }
    }

    private void registerClassInPackage(String rawFqcn, Map<String, List<String>> packageToMixins) {
        String fqcn = rawFqcn.replace('/', '.');
        int lastDot = fqcn.lastIndexOf('.');
        String pkg = lastDot == -1 ? "" : fqcn.substring(0, lastDot);
        String className = lastDot == -1 ? fqcn : fqcn.substring(lastDot + 1);
        packageToMixins.computeIfAbsent(pkg, k -> new ArrayList<>()).add(className);
    }

    private String generateMixinJson(String pkg, List<String> classes) {
        String classList = classes.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(",\n    "));
        return "{\n" +
               "  \"required\": true,\n" +
               "  \"package\": \"" + pkg + "\",\n" +
               "  \"compatibilityLevel\": \"JAVA_21\",\n" +
               "  \"mixins\": [\n    " + classList + "\n  ],\n" +
               "  \"injectors\": { \"defaultRequire\": 1 }\n" +
               "}";
    }
    @Override
    public void acceptTargets(Set<String> my, Set<String> other) {
        for (String config : configNamesToLoad) {
            Mixins.addConfiguration(config);
        }
        configNamesToLoad.clear();
    }


    @Override public List<String> getMixins() { return null; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String t, String m) { return true; }
    @Override public void preApply(String t, ClassNode cn, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode cn, String m, IMixinInfo i) {}
}