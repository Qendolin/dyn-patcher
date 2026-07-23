package com.qendolin.dynpatcher;

import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;

import java.lang.reflect.Field;

/**
 * Utility for determining target Java version based on the minimum of JVM runtime version
 * and maximum supported ECJ version.
 */
public class JavaVersionUtil {

    private JavaVersionUtil() {}

    /**
     * Returns the target Java version feature integer (e.g. 21, 25).
     */
    public static int getTargetJavaVersion() {
        int runtimeVersion = Runtime.version().feature();
        int ecjMaxVersion = getMaxEcjSupportedVersion();
        int target = Math.min(runtimeVersion, ecjMaxVersion);
        DynamicPatcher.LOGGER.debug(
                "[DynPatcher] Computed target Java version: {} (Runtime: {}, ECJ Max: {})",
                target, runtimeVersion, ecjMaxVersion);
        return target;
    }

    /**
     * Returns the ECJ command-line option string (e.g. "-21" or "-25").
     */
    public static String getEcjOption() {
        return "-" + getTargetJavaVersion();
    }

    /**
     * Returns the Mixin compatibility level string (e.g. "JAVA_21" or "JAVA_25").
     */
    public static String getMixinCompatibilityLevel() {
        return "JAVA_" + getTargetJavaVersion();
    }

    private static int getMaxEcjSupportedVersion() {
        int maxFound = 21; // Baseline fallback
        for (int v = 30; v >= 8; v--) {
            try {
                Field field = CompilerOptions.class.getField("VERSION_" + v);
                if (field != null) {
                    return v;
                }
            } catch (NoSuchFieldException ignored) {}
        }
        return maxFound;
    }
}
