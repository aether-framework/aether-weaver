package de.splatgames.aether.weaver.idea.library;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.idea.toolwindow.WeavesModel;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class LibraryWeavesTest extends BasePlatformTestCase {

    @NotNull
    private static String manifest() {
        final WeaveManifest.Injector injector = new WeaveManifest.Injector(
                "INJECT", "audit", "onCharge", "charge",
                List.of(), 0, 0, "");
        final WeaveManifest.Weave weave = new WeaveManifest.Weave(
                "com.acme.lib.AuditWeave", "INSTANCE", 50, "DEFAULT", "BUILD",
                List.of(),
                List.of("com.acme.lib.Gateway", "com.acme.lib.Outer$Inner"),
                List.of(), List.of(injector));
        return ManifestWriter.write(
                WeaveManifest.of("aether-weaver-processor/0.1.0", List.of(weave)));
    }

    private static Path jar;

    @Override
    @NotNull
    protected LightProjectDescriptor getProjectDescriptor() {
        return new LightProjectDescriptor() {
            @Override
            public void configureModule(@NotNull final Module module,
                                        @NotNull final ModifiableRootModel model,
                                        @NotNull final ContentEntry entry) {
                final Path built = jarWith(manifest());
                PsiTestUtil.addLibrary(model, "acme-lib",
                        built.getParent().toString(), built.getFileName().toString());
            }
        };
    }

    public void testAWeaveInADependencyIsFound() {
        final List<LibraryWeaves.Declared> found = LibraryWeaves.of(getProject());
        final List<String> roots = new ArrayList<>();
        for (final com.intellij.openapi.vfs.VirtualFile root
                : com.intellij.openapi.roots.OrderEnumerator.orderEntries(getProject())
                        .librariesOnly().classes().getRoots()) {
            final com.intellij.openapi.vfs.VirtualFile contents = root.isDirectory()
                    ? root
                    : com.intellij.openapi.vfs.JarFileSystem.getInstance()
                            .getJarRootForLocalFile(root);
            roots.add(root.getUrl() + " dir=" + root.isDirectory() + " contents="
                    + (contents == null ? "null" : contents.getUrl()) + " manifest="
                    + (contents != null
                            && contents.findFileByRelativePath(WeaveManifest.RESOURCE) != null));
        }
        assertFalse("no library roots at all — the fixture never attached the jar: " + roots,
                roots.isEmpty());

        assertEquals("a weave shipped in a jar is invisible by construction; this is the only thing "
                        + "that makes it visible. roots=" + roots + " jar=" + jar,
                1, found.size());
        final WeaveManifest.Weave weave = found.getFirst().declared();
        assertEquals("com.acme.lib.AuditWeave", weave.className());
        assertEquals(50, weave.priority());
        assertEquals(1, weave.injectors().size());
        assertEquals("onCharge", weave.injectors().getFirst().handler());
    }

    public void testANestedTargetIsMatchedByItsSourceName() {
        assertEquals("a manifest records binary names, so a nested target reads Outer$Inner where "
                        + "PSI says Outer.Inner — comparing them directly answers 'no weaves' for "
                        + "every nested class",
                1, LibraryWeaves.targeting(getProject(), "com.acme.lib.Outer.Inner").size());
        assertEquals(1, LibraryWeaves.targeting(getProject(), "com.acme.lib.Gateway").size());
    }

    public void testAnUnwovenTargetGetsNothing() {
        assertEquals(List.of(),
                LibraryWeaves.targeting(getProject(), "com.acme.lib.Untouched"));
    }

    public void testTheToolWindowListsIt() {
        final List<String> listed = new ArrayList<>();
        for (final WeavesModel.Weave weave : WeavesModel.of(getProject())) {
            listed.add(weave.name());
        }

        assertTrue("the window is the answer to 'which weaves modify this project that I did not "
                        + "write': " + listed,
                listed.contains("com.acme.lib.AuditWeave"));
    }

    public void testItsHandlersAreTakenFromTheManifest() {
        for (final WeavesModel.Weave weave : WeavesModel.of(getProject())) {
            if (!"com.acme.lib.AuditWeave".equals(weave.name())) {
                continue;
            }
            assertEquals(1, weave.handlers().size());
            assertEquals(WeavesModel.Binding.FROM_MANIFEST,
                    weave.handlers().getFirst().binding());
            assertNull("there is no source to navigate to, and pretending otherwise would send the "
                    + "reader nowhere", weave.handlers().getFirst().element());
            return;
        }
        fail("the library weave was not listed at all");
    }

    public void testAShippedHandlerMarksTheTarget() {
        myFixture.addFileToProject("com/acme/lib/AuditWeave.java", """
                package com.acme.lib;

                public final class AuditWeave {
                    void onCharge() { }
                }
                """);
        myFixture.configureByText("Gateway.java", """
                package com.acme.lib;

                public class Gateway {
                    public void charge() { }
                }
                """);

        final List<String> tooltips = new ArrayList<>();
        for (final com.intellij.codeInsight.daemon.GutterMark mark : myFixture.findAllGutters()) {
            if (mark.getTooltipText() != null) {
                tooltips.add(mark.getTooltipText());
            }
        }

        assertTrue("a weave in a jar is the one a reader cannot otherwise discover: " + tooltips,
                tooltips.stream().anyMatch(t -> t.contains("onCharge")
                        && t.contains("from a dependency")));
    }

    @NotNull
    private static Path jarWith(@NotNull final String manifest) {
        if (jar != null) {
            return jar;
        }
        try {
            final Path directory = Files.createTempDirectory("aether-library");
            directory.toFile().deleteOnExit();
            final Path built = directory.resolve("acme-lib.jar");
            try (OutputStream out = Files.newOutputStream(built);
                 JarOutputStream jarOut = new JarOutputStream(out)) {
                jarOut.putNextEntry(new JarEntry(WeaveManifest.RESOURCE));
                jarOut.write(manifest.getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
            built.toFile().deleteOnExit();
            // The jar is created after the VFS has taken its view of the world, so it has to be
            // announced. Without this the library root exists in the model and resolves to nothing.
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(built.toFile());
            jar = built;
            return built;
        } catch (final IOException unusable) {
            throw new AssertionError("cannot build the fixture jar", unusable);
        }
    }
}
