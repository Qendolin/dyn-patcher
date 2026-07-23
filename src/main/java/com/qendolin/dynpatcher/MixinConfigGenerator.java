package com.qendolin.dynpatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates SpongePowered Mixin configuration JSON files for dynamically compiled classes.
 */
public class MixinConfigGenerator {

    private MixinConfigGenerator() {}

    public static Map<String, List<String>> groupByPackage(Collection<String> fqcns) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String fqcn : fqcns) {
            String normalized = fqcn.replace('/', '.');
            int lastDot = normalized.lastIndexOf('.');
            String pkg = lastDot == -1 ? "" : normalized.substring(0, lastDot);
            String simpleName = lastDot == -1 ? normalized : normalized.substring(lastDot + 1);
            result.computeIfAbsent(pkg, k -> new ArrayList<>()).add(simpleName);
        }
        return result;
    }

    public static String generate(Path cacheDir, String pkg, List<String> simpleNames) throws IOException {
        String configName = "dynpatcher." + pkg + ".json";
        Files.write(cacheDir.resolve(configName),
                buildJson(pkg, simpleNames).getBytes(StandardCharsets.UTF_8));
        return configName;
    }

    private static String buildJson(String pkg, List<String> simpleNames) {
        String classList = simpleNames.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(",\n    "));
        String compatLevel = JavaVersionUtil.getMixinCompatibilityLevel();
        return "{\n"
                + "  \"required\": true,\n"
                + "  \"package\": \"" + pkg + "\",\n"
                + "  \"compatibilityLevel\": \"" + compatLevel + "\",\n"
                + "  \"mixins\": [\n    " + classList + "\n  ],\n"
                + "  \"injectors\": { \"defaultRequire\": 1 }\n"
                + "}";
    }
}
