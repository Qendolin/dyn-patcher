package com.qendolin.dynpatcher;

import java.nio.file.Path;

/**
 * Represents a Mixin source file after in-memory preprocessing.
 *
 * @param path            the original on-disk path of the {@code .java} file
 * @param processedSource the source text after preprocessing (may differ from disk content)
 * @param effectivePackage the package that will be used for compilation; may differ from the
 *                        declared package if the file had none and was remapped to
 *                        {@code dynpatcher.generated}
 * @param wasModified     {@code true} if the preprocessor changed the source text
 */
public record MixinSource(
        Path path,
        String processedSource,
        String effectivePackage,
        boolean wasModified
) {}
