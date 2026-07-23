package com.qendolin.dynpatcher;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, byte[]> compiledClasses = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sourceToClasses = new ConcurrentHashMap<>();

    public MemoryJavaFileManager(StandardJavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        if (sibling != null) {
            try {
                String sourcePath = Paths.get(sibling.toUri()).toAbsolutePath().toString();
                sourceToClasses.computeIfAbsent(sourcePath, k -> Collections.synchronizedList(new ArrayList<>())).add(className);
            } catch (Exception ignored) {}
        }

        return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + kind.extension), kind) {
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

    public Map<String, byte[]> getCompiledClasses() {
        return compiledClasses;
    }

    public Map<String, List<String>> getSourceToClasses() {
        return sourceToClasses;
    }
}