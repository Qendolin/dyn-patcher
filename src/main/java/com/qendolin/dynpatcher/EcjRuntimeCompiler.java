package com.qendolin.dynpatcher;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import javax.tools.*;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Compiles Mixin source files at runtime using the Eclipse Compiler for Java (ECJ).
 */
public class EcjRuntimeCompiler {

    private EcjRuntimeCompiler() {}

    public record CompilationResult(
            Map<String, byte[]> compiledClasses,
            Map<String, List<String>> sourceToClasses
    ) {}

    public static CompilationResult compile(List<MixinSource> sources) throws Exception {
        URL ecjUrl = EclipseCompiler.class.getProtectionDomain().getCodeSource().getLocation();

        try (URLClassLoader isolatedLoader = new URLClassLoader(
                new URL[]{ecjUrl}, ClassLoader.getPlatformClassLoader())) {

            Class<?> compilerClass = isolatedLoader.loadClass(
                    "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler");
            JavaCompiler compiler = (JavaCompiler) compilerClass.getDeclaredConstructor().newInstance();

            StandardJavaFileManager standardManager = compiler.getStandardFileManager(null, null, null);
            MemoryJavaFileManager memoryManager = new MemoryJavaFileManager(standardManager);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            String javaOption = JavaVersionUtil.getEcjOption();
            List<String> options = Arrays.asList(
                    javaOption, "-proc:none",
                    "-classpath", buildClasspath());

            DynamicPatcher.LOGGER.info("[DynPatcher] Compiling with ECJ options: {}", options);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, memoryManager, diagnostics, options, null,
                    buildSourceObjects(sources));

            boolean success = task.call();
            reportDiagnostics(diagnostics, success);

            return new CompilationResult(
                    memoryManager.getCompiledClasses(),
                    memoryManager.getSourceToClasses());
        }
    }

    private static List<JavaFileObject> buildSourceObjects(List<MixinSource> sources) {
        List<JavaFileObject> objects = new ArrayList<>(sources.size());
        for (MixinSource source : sources) {
            URI uri = source.path().toAbsolutePath().normalize().toUri();
            String content = source.processedSource();
            objects.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return content;
                }
            });
        }
        return objects;
    }

    private static void reportDiagnostics(
            DiagnosticCollector<JavaFileObject> diagnostics, boolean success) {
        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            switch (diag.getKind()) {
                case ERROR -> {
                    String location = diag.getSource() != null
                            ? " at line " + diag.getLineNumber()
                              + " in " + new File(diag.getSource().toUri()).getName()
                            : "";
                    DynamicPatcher.LOGGER.error(
                            "[DynPatcher] Compiler error{}: {}", location, diag.getMessage(null));
                }
                case WARNING, MANDATORY_WARNING ->
                        DynamicPatcher.LOGGER.warn(
                                "[DynPatcher] Compiler warning: {}", diag.getMessage(null));
                default -> {}
            }
        }
        if (!success) {
            DynamicPatcher.LOGGER.error("[DynPatcher] Compilation completed with errors.");
        }
    }

    private static String buildClasspath() {
        Set<String> paths = new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                try {
                    URI uri = root.toUri();
                    String scheme = uri.getScheme();
                    if ("jar".equals(scheme)) {
                        String spec = uri.getRawSchemeSpecificPart();
                        int bang = spec.indexOf("!/");
                        paths.add(Paths.get(new URI(bang != -1 ? spec.substring(0, bang) : spec))
                                .toAbsolutePath().toString());
                    } else if ("file".equals(scheme)) {
                        paths.add(root.toAbsolutePath().toString());
                    } else {
                        Path temp = Files.createTempFile("dynpatcher-classpath-", ".jar");
                        temp.toFile().deleteOnExit();

                        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp));
                            Stream<Path> files = Files.walk(root)) {
                            for (Path file : files.filter(Files::isRegularFile).toList()) {
                                zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                                Files.copy(file, zip);
                                zip.closeEntry();
                            }
                        }

                        paths.add(temp.toString());
                        continue;
                    }
                } catch (Exception ignored) {}
            }
        }

        return String.join(File.pathSeparator, paths);
    }
}