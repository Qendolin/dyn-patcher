package com.qendolin.dynpatcher;

import org.objectweb.asm.*;

public class AsmRemapInjector {

    /**
     * Lightly parses the bytecode to check if it's annotated with @Mixin.
     */
    public static boolean isMixinClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        final boolean[] isMixin = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                    isMixin[0] = true;
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return isMixin[0];
    }

    public static byte[] injectRemapFalse(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return wrapAnnotation(super.visitAnnotation(descriptor, visible), descriptor);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        return wrapAnnotation(super.visitAnnotation(desc, visible), desc);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
                return new FieldVisitor(Opcodes.ASM9, fv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        return wrapAnnotation(super.visitAnnotation(desc, visible), desc);
                    }
                };
            }

            private AnnotationVisitor wrapAnnotation(AnnotationVisitor original, String descriptor) {
                // If it's a Mixin annotation (@Mixin, @Inject, @Redirect, etc.)
                if (descriptor.startsWith("Lorg/spongepowered/asm/mixin/")) {
                    return new AnnotationVisitor(Opcodes.ASM9, original) {
                        private boolean hasRemap = false;

                        @Override
                        public void visit(String name, Object value) {
                            if ("remap".equals(name)) hasRemap = true;
                            super.visit(name, value);
                        }

                        @Override
                        public void visitEnd() {
                            // If the user didn't specify remap manually, we inject remap = false
                            if (!hasRemap) {
                                super.visit("remap", Boolean.FALSE);
                            }
                            super.visitEnd();
                        }
                    };
                }
                return original;
            }
        }, 0);

        return cw.toByteArray();
    }
}