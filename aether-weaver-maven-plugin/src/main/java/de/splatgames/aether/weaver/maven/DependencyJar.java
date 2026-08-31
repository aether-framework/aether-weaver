package de.splatgames.aether.weaver.maven;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One dependency artefact, as {@code DependencyWeaver} needs to read it.
 *
 * <p>Two questions are asked of it: whether it is signed, which decides whether it may be woven at
 * all, and what classes it contains. Nothing here writes; the artefact in the local repository is
 * left bit for bit as its publisher shipped it, and the woven copies go elsewhere.
 *
 * <p>The archive is opened and closed once per call, so asking three questions opens it three
 * times. Nothing is cached between calls.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class DependencyJar {

    /** The only directory a signature is recognised in, matched case-sensitively. */
    private static final String META_INF = "META-INF/";

    /** The extension of the signature manifest, matched without regard to case. */
    private static final String SIGNATURE_FILE = ".SF";

    /** The extensions of the signature block, one per signature algorithm this recognises. */
    private static final List<String> SIGNATURE_BLOCKS = List.of(".DSA", ".RSA", ".EC");

    /** The artefact, which is opened afresh by every method that reads it. */
    private final Path file;

    /**
     * Wraps a dependency artefact.
     *
     * @param file the jar to read; must not be {@code null}
     * @throws NullPointerException if {@code file} is {@code null}
     */
    DependencyJar(@NotNull final Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Reports whether the artefact carries a signature.
     *
     * <p>An artefact counts as signed when it holds both a {@code .SF} entry and one of
     * {@code .DSA}, {@code .RSA} or {@code .EC}, each of them immediately inside {@code META-INF/}
     * rather than in a directory below it. The two are looked for independently: the base names are
     * never compared, so a {@code .SF} left behind by one signer and a block from another still
     * answer {@code true}. The extensions are matched without regard to case, the {@code META-INF/}
     * prefix exactly.
     *
     * @return {@code true} when both halves of a signature are present
     * @throws IOException if the archive cannot be opened or its entries cannot be listed
     */
    boolean isSigned() throws IOException {
        final List<String> names = new ArrayList<>();
        try (ZipFile archive = new ZipFile(this.file.toFile())) {
            final Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names.stream().anyMatch(DependencyJar::isSignatureFile)
                && names.stream().anyMatch(DependencyJar::isSignatureBlock);
    }

    /**
     * Names whoever signed the artefact.
     *
     * <p>The name is taken from the signature manifest's file name rather than from any certificate
     * in it, so it is the base name the signing tool was told to use and not a distinguished name.
     * The first such entry in the archive's own order wins, which matters only for an artefact that
     * was signed twice.
     *
     * @return the base name of the first signature manifest, or {@code an unknown signer} when the
     *         archive holds none, which is what an unsigned artefact yields
     * @throws IOException if the archive cannot be opened or its entries cannot be listed
     */
    @NotNull
    String signer() throws IOException {
        try (ZipFile archive = new ZipFile(this.file.toFile())) {
            final Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (isSignatureFile(name)) {
                    final String base = name.substring(META_INF.length());
                    return base.substring(0, base.length() - SIGNATURE_FILE.length());
                }
            }
        }
        return "an unknown signer";
    }

    /**
     * Hands every class in the archive to a consumer, one at a time.
     *
     * <p>Entries are sorted by name before any of them is read, so two runs over the same artefact
     * report their findings in the same order and one run's diagnostics can be diffed against the
     * next. Only the class's own bytes are passed; the consumer is not told which entry they came
     * from beyond the internal name.
     *
     * @param each what to hand each class to; must not be {@code null}
     * @throws NullPointerException if {@code each} is {@code null}
     * @throws IOException          if the archive cannot be opened or one of its entries cannot be
     *                              read
     */
    void forEachClass(@NotNull final ClassConsumer each) throws IOException {
        Objects.requireNonNull(each, "each");
        try (ZipFile archive = new ZipFile(this.file.toFile())) {
            final List<ZipEntry> classes = new ArrayList<>();
            final Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    classes.add(entry);
                }
            }
            // Sorted, so that the diagnostics a run produces are diffable against the last one.
            classes.sort(java.util.Comparator.comparing(ZipEntry::getName));

            for (final ZipEntry entry : classes) {
                final String name = entry.getName();
                try (InputStream stream = archive.getInputStream(entry)) {
                    each.accept(name.substring(0, name.length() - ".class".length()),
                            stream.readAllBytes());
                }
            }
        }
    }

    /**
     * Names the artefact as it appears in a diagnostic.
     *
     * @return the file name alone, without the directory it sits in
     */
    @Contract(pure = true)
    @NotNull
    String name() {
        return this.file.getFileName().toString();
    }

    /**
     * Reports whether an entry name is a signature manifest.
     *
     * @param name the entry name to test
     * @return {@code true} for a name directly inside {@code META-INF/} whose extension is
     *         {@code .SF} in any case
     */
    @Contract(pure = true)
    private static boolean isSignatureFile(@NotNull final String name) {
        return name.startsWith(META_INF)
                && name.toUpperCase(Locale.ROOT).endsWith(SIGNATURE_FILE)
                && name.indexOf('/', META_INF.length()) < 0;
    }

    /**
     * Reports whether an entry name is a signature block.
     *
     * @param name the entry name to test
     * @return {@code true} for a name directly inside {@code META-INF/} whose extension is one of
     *         the recognised block extensions, in any case
     */
    @Contract(pure = true)
    private static boolean isSignatureBlock(@NotNull final String name) {
        if (!name.startsWith(META_INF) || name.indexOf('/', META_INF.length()) >= 0) {
            return false;
        }
        final String upper = name.toUpperCase(Locale.ROOT);
        return SIGNATURE_BLOCKS.stream().anyMatch(upper::endsWith);
    }

    /**
     * Returns a description naming the artefact.
     *
     * @return the full path wrapped in the class's own name
     */
    @Override
    @NotNull
    public String toString() {
        return "DependencyJar[" + this.file + ']';
    }

    /**
     * What a caller of {@link DependencyJar#forEachClass(ClassConsumer)} does with each class.
     *
     * <p>Called once per class entry, on the calling thread, while the archive is still open. The
     * array is the entry's own bytes and is not reused between calls.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @FunctionalInterface
    interface ClassConsumer {

        /**
         * Accepts one class of the archive.
         *
         * @param internalName the entry name with {@code .class} removed, which is the class's
         *                     internal name whenever the archive stores it under its own name
         * @param bytes        the class as the archive holds it
         */
        void accept(@NotNull String internalName, byte[] bytes);
    }
}
