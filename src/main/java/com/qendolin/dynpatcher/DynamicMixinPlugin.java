package com.qendolin.dynpatcher;


import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class DynamicMixinPlugin implements IMixinConfigPlugin {
    private final List<String> dynamicallyCompiledMixins = new ArrayList<>();

    @Override
    public void onLoad(String mixinPackage) {
        try {
            // 1. Locate the source files (e.g., config/dynamic_mixins/)
            Path sourceDir = FabricLoader.getInstance().getConfigDir().resolve("dynamic_mixins");
            if (!Files.exists(sourceDir)) {
                Files.createDirectories(sourceDir);
                // In a real mod, you might extract an example .java file here
                return;
            }

            // 2. Read all .java files
            List<Path> sourceFiles;
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                sourceFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            }

            if (sourceFiles.isEmpty()) return;

            System.out.println("[DynamicMixins] Found " + sourceFiles.size() + " runtime mixin sources. Compiling...");

            // 3. Compile the Java files using ECJ
            Map<String, byte[]> compiledClasses = EcjRuntimeCompiler.compile(sourceFiles);

            // 4. Create a hidden NIO temporary directory to act as our virtual classpath
            Path dynamicRoot = Files.createTempDirectory("dynamic_mixins_out_");

            // 5. Write the compiled byte[] arrays to this directory
            for (Map.Entry<String, byte[]> entry : compiledClasses.entrySet()) {
                // 1. ECJ sometimes returns keys with slashes. Normalize to standard Java dots.
                String fqcn = entry.getKey().replace('/', '.');

                // 2. Write the file to the virtual classpath folder
                Path classPath = dynamicRoot.resolve(fqcn.replace('.', '/') + ".class");
                Files.createDirectories(classPath.getParent());
                Files.write(classPath, entry.getValue());

                // 3. Format the name for the Mixin Engine
                // Mixin requires the class name to be RELATIVE to the package defined in your mixins.json!
                if (fqcn.startsWith(mixinPackage + ".")) {
                    String relativeName = fqcn.substring(mixinPackage.length() + 1);
                    dynamicallyCompiledMixins.add(relativeName);
                    System.out.println("[DynamicMixins] Staged and registered Mixin: " + relativeName);
                } else {
                    System.err.println("[DynamicMixins] WARNING: " + fqcn + " does not belong to the package '" + mixinPackage + "' defined in your mixins.json! It will be skipped.");
                }
            }

            // 6. THE MAGIC: Tell Fabric to append this folder to the runtime Classpath.
            // Knot will natively treat this folder as a jar/resource root!
            FabricLauncherBase.getLauncher().addToClassPath(dynamicRoot);

        } catch (Exception e) {
            throw new RuntimeException("[DynamicMixins] Critical failure during runtime mixin compilation", e);
        }
    }

    @Override
    public List<String> getMixins() {
        // Tell the Mixin engine to load these classes.
        // Because they are on the Knot classpath now, Mixin will natively find them!
        return dynamicallyCompiledMixins.isEmpty() ? null : dynamicallyCompiledMixins;
    }

    // Required IMixinConfigPlugin boilerplates
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
