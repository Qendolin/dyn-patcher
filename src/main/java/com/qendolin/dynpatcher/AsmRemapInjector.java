package com.qendolin.dynpatcher;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.*;

import java.util.function.Consumer;

/**
 * ASM-based utilities for inspecting and patching compiled Mixin bytecode.
 */
public class AsmRemapInjector {

    private static final String MIXIN_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String MIXIN_PACKAGE_PREFIX = "Lorg/spongepowered/asm/mixin/";
    private static final String ENVIRONMENT_DESCRIPTOR = "Lnet/fabricmc/api/Environment;";
    private static final String ENV_TYPE_DESCRIPTOR = "Lnet/fabricmc/api/EnvType;";

    private AsmRemapInjector() {}

    /**
     * Returns {@code true} if the given class bytecode is a Mixin class (annotated with {@code @Mixin})
     * and is applicable for the current Fabric environment (either unannotated with {@code @Environment}
     * or annotated with a matching {@link EnvType}).
     */
    public static boolean shouldLoadMixin(byte[] classBytes) {
        return shouldLoadMixin(classBytes, FabricLoader.getInstance().getEnvironmentType());
    }

    /**
     * Returns {@code true} if the given class bytecode is a Mixin class (annotated with {@code @Mixin})
     * and is applicable for the specified {@link EnvType} (either unannotated with {@code @Environment}
     * or annotated with a matching {@link EnvType}).
     */
    public static boolean shouldLoadMixin(byte[] classBytes, EnvType currentEnv) {
        ClassReader cr = new ClassReader(classBytes);
        MixinMetadataVisitor visitor = new MixinMetadataVisitor();
        cr.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (!visitor.isMixin) {
            return false;
        }

        return visitor.targetEnv == null || visitor.targetEnv == currentEnv;
    }

    /**
     * Injects {@code remap = false} into all SpongePowered Mixin annotations on the class,
     * its methods, and its fields that do not already explicitly set {@code remap}.
     */
    public static byte[] injectRemapFalse(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new RemapInjectorVisitor(cw), 0);
        return cw.toByteArray();
    }

    // ── Internal visitors ────────────────────────────────────────────────────

    private static final class MixinMetadataVisitor extends ClassVisitor {
        boolean isMixin = false;
        EnvType targetEnv = null;

        MixinMetadataVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (MIXIN_DESCRIPTOR.equals(descriptor)) {
                isMixin = true;
            } else if (ENVIRONMENT_DESCRIPTOR.equals(descriptor)) {
                return new EnvironmentAnnotationVisitor(env -> this.targetEnv = env);
            }
            return null;
        }
    }

    private static final class EnvironmentAnnotationVisitor extends AnnotationVisitor {
        private final Consumer<EnvType> envConsumer;

        EnvironmentAnnotationVisitor(Consumer<EnvType> envConsumer) {
            super(Opcodes.ASM9);
            this.envConsumer = envConsumer;
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            if ("value".equals(name) && ENV_TYPE_DESCRIPTOR.equals(descriptor)) {
                try {
                    envConsumer.accept(EnvType.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                }
            }
            super.visitEnum(name, descriptor, value);
        }
    }

    private static final class RemapInjectorVisitor extends ClassVisitor {
        RemapInjectorVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return wrapAnnotation(super.visitAnnotation(descriptor, visible), descriptor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            return new RemapAnnotationMethodVisitor(
                    super.visitMethod(access, name, descriptor, signature, exceptions));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            return new RemapAnnotationFieldVisitor(
                    super.visitField(access, name, descriptor, signature, value));
        }
    }

    private static final class RemapAnnotationMethodVisitor extends MethodVisitor {
        RemapAnnotationMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return wrapAnnotation(super.visitAnnotation(descriptor, visible), descriptor);
        }
    }

    private static final class RemapAnnotationFieldVisitor extends FieldVisitor {
        RemapAnnotationFieldVisitor(FieldVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return wrapAnnotation(super.visitAnnotation(descriptor, visible), descriptor);
        }
    }

    /**
     * Returns an annotation visitor that injects {@code remap = false} before visiting end,
     * if the annotation belongs to the SpongePowered Mixin package and has no explicit
     * {@code remap} value. Returns the original visitor unchanged for all other annotations.
     */
    private static AnnotationVisitor wrapAnnotation(AnnotationVisitor original, String descriptor) {
        if (!descriptor.startsWith(MIXIN_PACKAGE_PREFIX)) {
            return original;
        }
        return new RemapInjectingAnnotationVisitor(original);
    }

    private static final class RemapInjectingAnnotationVisitor extends AnnotationVisitor {
        private boolean hasRemap = false;

        RemapInjectingAnnotationVisitor(AnnotationVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visit(String name, Object value) {
            if ("remap".equals(name)) {
                hasRemap = true;
            }
            super.visit(name, value);
        }

        @Override
        public void visitEnd() {
            if (!hasRemap) {
                super.visit("remap", Boolean.FALSE);
            }
            super.visitEnd();
        }
    }
}