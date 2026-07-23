package com.qendolin.dynpatcher;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link JavaFileManager} that captures compiled class bytecode in memory and
 * tracks which source files produced which compiled class names.
 *
 * <p>Source-to-class tracking is driven by the {@code sibling} argument supplied
 * by ECJ to {@link #getJavaFileForOutput}. When in-memory source objects are used
 * (see {@link EcjRuntimeCompiler}), their URI must point to the original on-disk file
 * so that the mapping keys are consistent with the source file paths.</p>
 */
public class MemoryJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    /** FQCN → compiled bytecode. */
    private final Map<String, byte[]> compiledClasses = new ConcurrentHashMap<>();

    /** Absolute source path string → list of FQCNs produced from that source. */
    private final Map<String, List<String>> sourceToClasses = new ConcurrentHashMap<>();

    public MemoryJavaFileManager(StandardJavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                JavaFileObject.Kind kind, FileObject sibling) {
        if (sibling != null) {
            try {
                String sourcePath = Paths.get(sibling.toUri()).toAbsolutePath().toString();
                sourceToClasses
                        .computeIfAbsent(sourcePath, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(className);
            } catch (Exception ignored) {
                // sibling URI may not represent a file path; source tracking is best-effort
            }
        }

        return new SimpleJavaFileObject(
                URI.create("memory:///" + className.replace('.', '/') + kind.extension), kind) {
            @Override
            public OutputStream openOutputStream() {
                return new ByteArrayOutputStream() {
                    @Override
                    public void close() throws java.io.IOException {
                        super.close();
                        compiledClasses.put(className, toByteArray());
                    }
                };
            }
        };
    }

    /** Returns an unmodifiable view of the compiled class bytecode map. */
    public Map<String, byte[]> getCompiledClasses() {
        return Collections.unmodifiableMap(compiledClasses);
    }

    /** Returns an unmodifiable view of the source-path → class-names mapping. */
    public Map<String, List<String>> getSourceToClasses() {
        return Collections.unmodifiableMap(sourceToClasses);
    }
}