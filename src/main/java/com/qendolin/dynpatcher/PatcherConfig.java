package com.qendolin.dynpatcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages DynPatcher configuration loaded from {@code config/dynpatcher/config.json}.
 */
public class PatcherConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public List<String> disabledPatches = new ArrayList<>();

    public boolean isPatchDisabled(String relativePath) {
        if (disabledPatches == null) return false;
        String normalized = relativePath.replace('\\', '/');
        for (String disabled : disabledPatches) {
            if (disabled.replace('\\', '/').equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static PatcherConfig loadOrCreate(Path configFile) {
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                PatcherConfig config = GSON.fromJson(reader, PatcherConfig.class);
                if (config != null) {
                    if (config.disabledPatches == null) config.disabledPatches = new ArrayList<>();
                    return config;
                }
            } catch (Exception e) {
                DynamicPatcher.LOGGER.warn("[DynPatcher] Failed to read config.json; using defaults. Reason: {}", e.getMessage());
            }
        }

        PatcherConfig defaultConfig = new PatcherConfig();
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(defaultConfig, writer);
            }
        } catch (IOException e) {
            DynamicPatcher.LOGGER.warn("[DynPatcher] Could not create default config.json: {}", e.getMessage());
        }
        return defaultConfig;
    }
}
