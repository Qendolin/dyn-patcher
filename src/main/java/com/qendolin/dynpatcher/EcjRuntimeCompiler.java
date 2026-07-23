package com.qendolin.dynpatcher;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import javax.tools.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class EcjRuntimeCompiler {

    public static Map<String, byte[]> compile(List<Path> javaFiles) {
        try {
            // 1. Find the jar file that contains the ECJ compiler
            java.net.URL ecjUrl = EclipseCompiler.class.getProtectionDomain().getCodeSource().getLocation();

            // 2. Create an isolated ClassLoader.
            // We set the parent to the JVM Platform Classloader to completely bypass Knot and Mixin!
            try (java.net.URLClassLoader isolatedLoader = new java.net.URLClassLoader(
                new java.net.URL[]{ecjUrl},
                ClassLoader.getPlatformClassLoader())) {

                // 3. Load the compiler completely isolated from Fabric
                Class<?> compilerClass = isolatedLoader.loadClass("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler");
                JavaCompiler compiler = (JavaCompiler) compilerClass.getDeclaredConstructor().newInstance();

                // Setup Memory Manager
                StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(null, null, null);
                MemoryJavaFileManager memoryFileManager = new MemoryJavaFileManager(standardFileManager);

                // 1. Add a Diagnostic Collector to capture compiler errors/warnings
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

                List<File> files = javaFiles.stream().map(Path::toFile).collect(Collectors.toList());
                Iterable<? extends JavaFileObject> compilationUnits = standardFileManager.getJavaFileObjectsFromFiles(files);

                // 2. Add "-proc:none" to disable the Mixin Annotation Processor
                List<String> options = Arrays.asList(
                    "-21",
                    "-classpath", buildFabricClasspath()
                );

                // 3. Pass the diagnostics collector here
                JavaCompiler.CompilationTask task = compiler.getTask(null, memoryFileManager, diagnostics, options, null, compilationUnits);

                boolean success = task.call();

                // 4. Print any compiler errors to the console so we can debug syntax issues
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    System.err.println("[ECJ Diagnostic] " + diagnostic.getKind() + ": " + diagnostic.getMessage(null));
                    if (diagnostic.getSource() != null) {
                        System.err.println(" -> Line " + diagnostic.getLineNumber() + " in " + diagnostic.getSource().getName());
                    }
                }

                if (!success) {
                    throw new RuntimeException("ECJ Compilation failed! Check the console for syntax errors.");
                }

                return memoryFileManager.getCompiledClasses();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to isolate ECJ compiler", e);
        }
    }

    /**
     * Extracts all loaded mods and game jars from Fabric Loader to feed into ECJ.
     */
    private static String buildFabricClasspath() {
        Set<String> paths = new HashSet<>();

        // 1. Add current JVM classpath (Fabric Loader itself, standard libraries)
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));

        // 2. Add all physical mods and the Minecraft Game Jar loaded by Fabric
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path path : mod.getRootPaths()) {
                try {
                    URI uri = path.toUri();
                    // Fabric handles nested jars using the ZipFileSystem (jar://)
                    // We must peel back the URI to get the underlying physical .jar file for ECJ
                    if ("jar".equals(uri.getScheme())) {
                        String spec = uri.getRawSchemeSpecificPart();
                        int bangIndex = spec.indexOf("!/");
                        if (bangIndex != -1) spec = spec.substring(0, bangIndex);
                        paths.add(Paths.get(new URI(spec)).toAbsolutePath().toString());
                    }
                    // Standard loose files / un-nested jars
                    else if ("file".equals(uri.getScheme())) {
                        paths.add(path.toAbsolutePath().toString());
                    }
                } catch (Exception ignored) {
                    // Ignore unparseable paths safely
                }
            }
        }
        return String.join(File.pathSeparator, paths);
    }
}