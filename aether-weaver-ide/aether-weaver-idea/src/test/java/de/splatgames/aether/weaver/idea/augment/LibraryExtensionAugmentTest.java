package de.splatgames.aether.weaver.idea.augment;

import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LibraryExtensionAugmentTest extends BasePlatformTestCase {

    private static final ClassDesc EXTENSION =
            ClassDesc.of("de.splatgames.aether.weaver.api.experimental.Extension");

    private static final ClassDesc RECEIVER =
            ClassDesc.of("de.splatgames.aether.weaver.api.experimental.Receiver");

    private static final String EXTENSION_SOURCE = """
            package de.splatgames.aether.weaver.api.experimental;

            public @interface Extension {
                Class<?> value() default void.class;
            }
            """;

    private static final String RECEIVER_SOURCE = """
            package de.splatgames.aether.weaver.api.experimental;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;

            @Target(ElementType.PARAMETER)
            public @interface Receiver {
            }
            """;


    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java",
                EXTENSION_SOURCE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java",
                RECEIVER_SOURCE);
        attachLibrary("libwith", true);
        attachLibrary("libwithout", false);
        // Without this the first test to run finds nothing: a library attached moments ago is
        // not yet indexed, and findClass answers null. The suite still went green, because the
        // second method saw what the first had attached — a pass that depended entirely on which
        // method the runner happened to pick first.
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }

    public void testACompiledReceiverGainsALibraryExtension() throws Exception {
        final PsiClass receiver = findClass("libwith.Greeting");
        assertNotNull("the library root was not attached", receiver);
        assertTrue("the receiver has to be a compiled class for this test to mean anything; a "
                        + "source fixture would prove only what the source tests already do",
                receiver instanceof PsiCompiledElement);

        assertTrue("a published extension is invisible in the editor unless this works, and its "
                        + "call site is red on code that compiles and runs: " + methodsOf(receiver),
                methodsOf(receiver).contains("shout"));
    }

    public void testTheReceiverParameterIsStillDropped() throws Exception {
        final PsiMethod shout = methodOf("libwith.Greeting", "shout");
        assertNotNull(shout);
        assertEquals(1, shout.getParameterList().getParametersCount());
    }

    public void testALibraryWithoutItsManifestContributesNothing() throws Exception {
        final PsiClass receiver = findClass("libwithout.Greeting");
        assertNotNull(receiver);
        assertFalse("the class files are identical; only the manifest is missing. If the method "
                        + "still appeared, something other than the documented mechanism is "
                        + "finding it — and that something reads every class file of every jar: "
                        + methodsOf(receiver),
                methodsOf(receiver).contains("shout"));
    }

    public void testACompiledReceiverGainsAStaticLibraryExtension() {
        final PsiClass receiver = findClass("libwith.Greeting");
        assertNotNull(receiver);

        final List<PsiMethod> parse = List.of(receiver.findMethodsByName("parse", false));
        assertEquals("Greeting.parse(\"x\") is written on the type, and the manifest says the jar "
                + "contributes it: " + methodsOf(receiver), 1, parse.size());
        assertTrue("the call site is a static call, and an instance method there would be reported "
                        + "as referenced from a static context",
                parse.getFirst().hasModifierProperty(PsiModifier.STATIC));
        assertEquals("nothing is dropped: a static contribution has no receiver among its "
                + "parameters", 1, parse.getFirst().getParameterList().getParametersCount());
    }

    public void testASourceHolderReachesACompiledReceiver() throws Exception {
        myFixture.addFileToProject("probe/Shouts.java", """
                package probe;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Shouts {
                    public static String yell(@Receiver libwithout.Greeting self, int times) {
                        return self.greet();
                    }
                }
                """);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());

        final PsiClass receiver = findClass("libwithout.Greeting");
        assertNotNull(receiver);
        assertTrue("the receiver must be compiled for this to mean anything",
                receiver instanceof PsiCompiledElement);
        assertTrue("this is the shape the sample has and the one a user writes first: an extension "
                        + "of their own on a type they cannot edit: " + methodsOf(receiver),
                methodsOf(receiver).contains("yell"));
    }

    // --- building and attaching the library ------------------------------------------------------

    private void attachLibrary(final String packageName, final boolean withManifest)
            throws Exception {
        final Path root = Files.createTempDirectory("aether-extension-" + packageName);
        root.toFile().deleteOnExit();

        final ClassDesc receiver = ClassDesc.of(packageName + ".Greeting");
        final ClassDesc holder = ClassDesc.of(packageName + ".Strings");
        emitInto(root, receiver, holder);

        if (withManifest) {
            final Path manifest = root.resolve(WeaveManifest.RESOURCE);
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, ManifestWriter.write(WeaveManifest.of("test", List.of(),
                    List.of(new WeaveManifest.Extension(packageName + ".Strings",
                                    packageName + ".Greeting", "shout", "(I)Ljava/lang/String;"),
                            new WeaveManifest.Extension(packageName + ".Strings",
                                    packageName + ".Greeting", "parse",
                                    "(Ljava/lang/String;)L" + packageName.replace('.', '/')
                                            + "/Greeting;",
                                    WeaveManifest.Extension.Kind.STATIC)))));
        }

        final VirtualFile attached = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(root);
        assertNotNull("the emitted library did not appear in the virtual file system", attached);
        attached.refresh(false, true);
        ModuleRootModificationUtil.addModuleLibrary(myFixture.getModule(), attached.getUrl());
    }

    private static void emitInto(final Path root, final ClassDesc receiver, final ClassDesc holder)
            throws IOException {
        final ClassFile classFile = ClassFile.of();

        write(root, receiver, classFile.build(receiver, builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withMethodBody("greet", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC, code -> code.aconst_null().areturn())));

        write(root, holder, classFile.build(holder, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            builder.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(EXTENSION)));
            builder.withMethod("shout",
                    MethodTypeDesc.of(ConstantDescs.CD_String, receiver, ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    method -> {
                        // The receiver is parameter zero and is marked there; parameter one is an
                        // ordinary argument and carries nothing.
                        method.with(RuntimeVisibleParameterAnnotationsAttribute.of(
                                List.of(List.of(Annotation.of(RECEIVER)), List.of())));
                        method.withCode(code -> code.aconst_null().areturn());
                    });
            // The other form, and the one only a class file can prove reaches the IDE: the receiver
            // is named on the method, as a class literal, and survives into a compiled annotation.
            builder.withMethod("parse",
                    MethodTypeDesc.of(receiver, ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    method -> {
                        method.with(RuntimeVisibleAnnotationsAttribute.of(
                                Annotation.of(RECEIVER,
                                        AnnotationElement.of("value",
                                                AnnotationValue.ofClass(receiver)))));
                        method.withCode(code -> code.aconst_null().areturn());
                    });
        }));
    }

    private static void write(final Path root, final ClassDesc type, final byte[] bytes)
            throws IOException {
        final Path file = root.resolve(
                type.packageName().replace('.', '/') + '/' + type.displayName() + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static List<String> methodsOf(final PsiClass owner) {
        final List<String> names = new ArrayList<>();
        for (final PsiMethod method : owner.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private PsiMethod methodOf(final String qualified, final String name) {
        final PsiClass owner = findClass(qualified);
        assertNotNull(owner);
        for (final PsiMethod method : owner.getMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private PsiClass findClass(final String qualified) {
        return JavaPsiFacade.getInstance(getProject())
                .findClass(qualified, GlobalSearchScope.allScope(getProject()));
    }
}
