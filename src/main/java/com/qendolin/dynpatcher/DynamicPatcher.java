package com.qendolin.dynpatcher;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicPatcher implements ModInitializer {

    public static final String MOD_ID = "dynpatcher";

    /** Shared logger. Referenced by all DynPatcher classes to avoid {@code System.out/err}. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[DynPatcher] Mod initialized.");
    }
}
