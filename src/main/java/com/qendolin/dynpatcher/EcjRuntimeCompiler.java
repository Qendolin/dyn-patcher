package com.qendolin.dynpatcher;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import javax.tools.*;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class EcjRuntimeCompiler {
    private static Map<String, List<String>> lastSourceToClasses = Collections.emptyMap();

    public static Map<String, List<String>> getLastSourceToClasses() {
        return lastSourceToClasses;
    }

    public static Map<String, byte[]> compile(List<Path> javaFiles) throws Exception {
        URL ecjUrl = EclipseCompiler.class.getProtectionDomain().getCodeSource().getLocation();

        try (URLClassLoader isolatedLoader = new URLClassLoader(new URL[]{ecjUrl}, ClassLoader.getPlatformClassLoader())) {

            Class<?> compilerClass = isolatedLoader.loadClass("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler");
            JavaCompiler compiler = (JavaCompiler) compilerClass.getDeclaredConstructor().newInstance();

            StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(null, null, null);
            MemoryJavaFileManager memoryFileManager = new MemoryJavaFileManager(standardFileManager);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            List<File> files = javaFiles.stream().map(Path::toFile).collect(Collectors.toList());
            Iterable<? extends JavaFileObject> units = standardFileManager.getJavaFileObjectsFromFiles(files);

            // You can safely upgrade "-21" to "-25" when compiling strictly on Java 25
            List<String> options = Arrays.asList("-21", "-proc:none", "-err:-publicType", "-classpath", buildFabricClasspath());

            JavaCompiler.CompilationTask task = compiler.getTask(null, memoryFileManager, diagnostics, options, null, units);

            boolean success = task.call();

            // Store the compiled source-to-class mappings
            lastSourceToClasses = memoryFileManager.getSourceToClasses();

            for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                if (diag.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println("[DynPatcher Syntax Error] " + diag.getMessage(null));
                    if (diag.getSource() != null) {
                        System.err.println("   -> Line " + diag.getLineNumber() + " in " + new File(diag.getSource().toUri()).getName());
                    }
                } else {
                    System.out.println("[DynPatcher Info] " + diag.getMessage(null));
                }
            }

            if (!success) {
                System.err.println("[DynPatcher] Compilation completed with errors.");
            }

            return memoryFileManager.getCompiledClasses();
        }
    }

    private static String buildFabricClasspath() {
        Set<String> paths = new HashSet<>();
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path path : mod.getRootPaths()) {
                try {
                    URI uri = path.toUri();
                    if ("jar".equals(uri.getScheme())) {
                        String spec = uri.getRawSchemeSpecificPart();
                        int bangIndex = spec.indexOf("!/");
                        paths.add(Paths.get(new URI(bangIndex != -1 ? spec.substring(0, bangIndex) : spec)).toAbsolutePath().toString());
                    } else if ("file".equals(uri.getScheme())) {
                        paths.add(path.toAbsolutePath().toString());
                    }
                } catch (Exception ignored) {}
            }
        }
        return String.join(File.pathSeparator, paths);
    }
}