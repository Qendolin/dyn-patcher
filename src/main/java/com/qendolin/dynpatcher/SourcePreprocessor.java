package com.qendolin.dynpatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprocesses Mixin source files in-memory before they are passed to the compiler.
 *
 * <p>Two transforms are applied without modifying the file on disk:</p>
 *
 * <h2>1 — Default-package remapping</h2>
 * <p>SpongePowered Mixin refuses to load classes from the default package
 * (a {@code package} value of {@code ""} in the config JSON is silently skipped).
 * If a source file has no {@code package} declaration, {@code package dynpatcher.generated;}
 * is prepended and a warning is logged.</p>
 *
 * <h2>2 — Public-modifier stripping</h2>
 * <p>ECJ requires that a {@code public} top-level type be declared in a file whose name
 * matches the type name. If a user renames a {@code .java} file but the class declaration
 * keeps its original name (or vice versa), ECJ rejects the file. This preprocessor
 * detects the mismatch by checking whether any {@code public class/interface/…} name
 * matches the file stem. If none match, {@code public} is stripped from all such
 * declarations and a warning is logged.</p>
 *
 * <p><b>Limitation:</b> detection is regex-based and may produce false positives for
 * {@code public} keywords inside comments or string literals. For typical user-authored
 * Mixin files this is not a concern.</p>
 */
public class SourcePreprocessor {

    static final String GENERATED_PACKAGE = "dynpatcher.generated";

    /**
     * Matches a {@code package} declaration.
     * {@code MULTILINE} ensures {@code ^} anchors to a line start.
     */
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * Matches a {@code public} type declaration:
     * {@code public class|interface|enum|record|@interface <Name>}.
     */
    private static final Pattern PUBLIC_TYPE_PATTERN =
            Pattern.compile("\\bpublic\\s+(class|interface|enum|record|@interface)\\s+(\\w+)");

    private SourcePreprocessor() {}

    /**
     * Reads and preprocesses the given Java source file.
     *
     * @param javaFile path to the source file
     * @return a {@link MixinSource} containing the (possibly modified) source text
     * @throws IOException if the file cannot be read
     */
    public static MixinSource process(Path javaFile) throws IOException {
        String source = Files.readString(javaFile, StandardCharsets.UTF_8);
        String fileName = javaFile.getFileName().toString();
        String fileStem = fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - 5)
                : fileName;

        boolean modified = false;

        // ── 1. Default-package remapping ─────────────────────────────────────
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
        String effectivePackage;
        if (pkgMatcher.find()) {
            effectivePackage = pkgMatcher.group(1);
        } else {
            source = "package " + GENERATED_PACKAGE + ";\n" + source;
            effectivePackage = GENERATED_PACKAGE;
            modified = true;
            DynamicPatcher.LOGGER.warn(
                    "[DynPatcher] '{}' has no package declaration; placing it in '{}'. "
                    + "Add an explicit package statement to suppress this warning.",
                    fileName, GENERATED_PACKAGE);
        }

        // ── 2. Public-modifier stripping ──────────────────────────────────────
        // Only strip if NO public type has a name matching the file stem.
        // This preserves inner public types when the outer class is correctly named.
        Matcher checkMatcher = PUBLIC_TYPE_PATTERN.matcher(source);
        boolean hasMatchingPublicType = false;
        boolean hasAnyPublicType = false;
        while (checkMatcher.find()) {
            hasAnyPublicType = true;
            if (fileStem.equals(checkMatcher.group(2))) {
                hasMatchingPublicType = true;
                break;
            }
        }

        if (hasAnyPublicType && !hasMatchingPublicType) {
            // Strip 'public' from all type declarations since none match the file name
            Matcher stripMatcher = PUBLIC_TYPE_PATTERN.matcher(source);
            StringBuilder sb = new StringBuilder();
            while (stripMatcher.find()) {
                String keyword = stripMatcher.group(1);
                String typeName = stripMatcher.group(2);
                // "public class Foo" → "class Foo"
                stripMatcher.appendReplacement(sb, Matcher.quoteReplacement(keyword + " " + typeName));
            }
            stripMatcher.appendTail(sb);
            source = sb.toString();
            modified = true;
            DynamicPatcher.LOGGER.warn(
                    "[DynPatcher] '{}' contains public type declarations that don't match the filename. "
                    + "Stripped 'public' modifier to allow compilation. "
                    + "Rename the file to match the class name to suppress this warning.",
                    fileName);
        }

        return new MixinSource(javaFile, source, effectivePackage, modified);
    }
}
