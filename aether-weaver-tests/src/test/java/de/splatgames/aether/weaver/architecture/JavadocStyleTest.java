package de.splatgames.aether.weaver.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JavadocStyleTest {

    // Checkstyle enforces shape and JavadocCoverageTest enforces presence. Neither can see
    // what made the previous documentation unusable: decorative markers, an author writing
    // about the writing, and text pointing at a version of the project that no longer
    // exists. All three are mechanical, so they are caught here instead of being spent on
    // reviewer attention.

    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F000}-\\x{1F2FF}]");

    private static final Pattern FIRST_PERSON = Pattern.compile(
            "(?<![\\w-])(?:I|we|we'(?:ve|ll|d|re)|us|our|my|let's)(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern META = Pattern.compile(
            "(?<![\\w-])(?:this (?:document|documentation|javadoc|comment|section)"
                    + "|as (?:mentioned|noted|described|stated) (?:above|below|earlier)"
                    + "|see (?:above|below)|note to (?:self|the reader))(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    // A marker is written in upper case by every convention that defines one, so matching it
    // without regard to case also rejects the ordinary English word and any identifier that
    // carries it -- a record component named todo cannot otherwise be given its @param.
    private static final Pattern MARKER =
            Pattern.compile("(?<![\\w-])(?:TODO|FIXME|XXX)(?![\\w-])");

    private static final Pattern HISTORY = Pattern.compile(
            "(?<![\\w-])(?:used to (?:be|read|say|have)|previously|formerly|in an earlier"
                    + "|before this (?:commit|change|release)|no longer (?:reads|said)"
                    + "|has been (?:renamed|moved) from|was renamed)(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HEDGE = Pattern.compile(
            "(?<![\\w-])(?:probably|presumably|arguably|it seems|apparently|for some reason"
                    + "|somehow|might be why)(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    // An inline tag holds an identifier, and an identifier may legitimately contain a word
    // that is rejected in prose. Markup is dropped for the same reason.
    private static final Pattern INLINE_TAG =
            Pattern.compile("\\{@(?:code|link|linkplain|literal|snippet)[^}]*}");

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private static final List<String> IGNORED_DIRECTORIES =
            List.of(".git", "target", "build", "out", ".idea", "node_modules", "bin",
                    ".devcontainer");

    // A diagnostic code is the one string a user carries from a failing build into the
    // documentation, so a code that names nothing sends them looking for a page that does
    // not exist. Checking it is a set lookup, which is why it happens here rather than in a
    // review.
    private static final Pattern DIAGNOSTIC_CODE = Pattern.compile("AW\\d{4}");

    private static final Pattern DECLARED_CODE = Pattern.compile("\"(AW\\d{4})\"");

    private static final Path DIAGNOSTIC_CODES = Path.of("aether-weaver-api", "src", "main",
            "java", "de", "splatgames", "aether", "weaver", "api", "diagnostic",
            "DiagnosticCode.java");

    // Published pages are generated at protected visibility, so a link from one of them to a
    // package-private type resolves for doclint -- which reads the source path -- and renders
    // as dead text for the reader, who has no such page. The gates cannot see this because
    // nothing about it is malformed.
    private static final Pattern LINK_REFERENCE = Pattern.compile(
            "\\{@link(?:plain)?\\s+([A-Z]\\w*)\\b[^}]*}|@see\\s+([A-Z]\\w*)\\b");

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^((?:(?:public|final|abstract|sealed|non-sealed)\\s+)*)"
                    + "(?:class|interface|enum|record|@interface)\\s+(\\w+)");

    // Modifiers may sit behind any number of annotations, and an annotation may carry
    // arguments of its own.
    private static final Pattern PUBLISHED_DECLARATION =
            Pattern.compile("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:public|protected)\\b");

    @Test
    @DisplayName("no JavaDoc carries a decorative marker")
    void noEmoji() {
        assertThat(violations(EMOJI))
                .as("emphasis belongs in the sentence; a marker survives into generated HTML "
                        + "and into every terminal that cannot render it")
                .isEmpty();
    }

    @Test
    @DisplayName("no JavaDoc talks about its author")
    void noFirstPerson() {
        assertThat(violations(FIRST_PERSON))
                .as("documentation describes the code, not the person who wrote it")
                .isEmpty();
    }

    @Test
    @DisplayName("no JavaDoc refers to itself instead of to the code")
    void noMetaCommentary() {
        assertThat(violations(META))
                .as("a reader arrives at one member from a search result and sees no 'above'")
                .isEmpty();
        assertThat(violations(MARKER))
                .as("a marker records work that is owed, which a specification cannot carry")
                .isEmpty();
    }

    @Test
    @DisplayName("no JavaDoc describes a state the project has left")
    void noHistory() {
        assertThat(violations(HISTORY))
                .as("the past is not evidence; describe the code as it is, because a reader "
                        + "cannot check a claim about a version that is gone")
                .isEmpty();
    }

    @Test
    @DisplayName("no JavaDoc hedges a claim it cannot support")
    void noHedging() {
        assertThat(violations(HEDGE))
                .as("a claim that needs a hedge was not established from the code, and a "
                        + "reader has no way to tell which of the two it is")
                .isEmpty();
    }

    @Test
    @DisplayName("every diagnostic code named in JavaDoc is a code that exists")
    void everyDiagnosticCodeExists() {
        final List<String> declared = declaredCodes();
        assertThat(declared)
                .as("the codes are read from DiagnosticCode, so an empty set would make this "
                        + "test pass by finding nothing to check")
                .isNotEmpty();

        final List<String> found = new ArrayList<>();
        for (final Path source : allSources()) {
            scan(read(source), (line, body, after) -> {
                final Matcher matcher = DIAGNOSTIC_CODE.matcher(body);
                while (matcher.find()) {
                    if (!declared.contains(matcher.group())) {
                        found.add(relative(source) + ':' + lineOf(body, matcher.start(), line)
                                + " -> " + matcher.group());
                    }
                }
            });
        }

        assertThat(found)
                .as("a user meets a code in their build output and looks it up; a code that no "
                        + "longer exists, or was never issued, leaves them with a string that "
                        + "appears nowhere else")
                .isEmpty();
    }

    @Test
    @DisplayName("no published JavaDoc links to a type the reader cannot open")
    void noPublishedLinkToPackagePrivateType() {
        final Map<String, Map<String, Boolean>> types = topLevelTypes();
        final List<String> found = new ArrayList<>();

        for (final Path source : allSources()) {
            final String text = read(source);
            final Map<String, Boolean> siblings =
                    types.getOrDefault(packageOf(text), Map.of());

            scan(text, (line, body, after) -> {
                if (!PUBLISHED_DECLARATION.matcher(after).find()) {
                    return;
                }
                final Matcher matcher = LINK_REFERENCE.matcher(body);
                while (matcher.find()) {
                    final String target =
                            matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (Boolean.FALSE.equals(siblings.get(target))) {
                        found.add(relative(source) + ':' + lineOf(body, matcher.start(), line)
                                + " -> " + target);
                    }
                }
            });
        }

        assertThat(found)
                .as("published pages are generated at protected visibility, so a link to a "
                        + "package-private type renders as text and the reader is sent to a "
                        + "page that was never generated; name it in {@code} instead")
                .isEmpty();
    }

    @Nested
    @DisplayName("the scanner itself")
    class ScannerBehaviour {

        @Test
        @DisplayName("reads JavaDoc and nothing else")
        void onlyJavadocIsScanned() {
            final String source = """
                    package p;
                    /** Prose with we in it. */
                    class C {
                        // an ordinary comment saying we, which is not JavaDoc
                        String s = "a literal saying we, and /** not a comment */";
                        String t = \"""
                                a text block saying we
                                \""";
                    }
                    """;

            final List<String> blocks = new ArrayList<>();
            scan(source, (line, body, after) -> blocks.add(body));

            assertThat(blocks)
                    .as("one JavaDoc comment, and the literals left alone — one test in the "
                            + "engine hands an emoji to forCharset on purpose, and a scanner "
                            + "that read literals would demand its removal")
                    .hasSize(1);
            assertThat(blocks.get(0)).contains("Prose with we in it");
        }

        @Test
        @DisplayName("does not report an identifier inside an inline tag")
        void inlineTagsAreNotProse() {
            assertThat(prose(" * {@link #ourMethod()} and {@code weAreFine} "))
                    .as("a member may be named anything; only the prose around it is style")
                    .doesNotContain("our", "we");
        }

        @Test
        @DisplayName("separates a published declaration from one that is not")
        void publicationIsReadFromTheDeclaration() {
            assertThat(published("\npublic final class C {")).isTrue();
            assertThat(published("\n    protected void m() {")).isTrue();
            assertThat(published("\n@Documented\n@Retention(RetentionPolicy.RUNTIME)\n"
                    + "public @interface A {"))
                    .as("a modifier may sit behind any number of annotations, and an "
                            + "annotation may carry arguments of its own")
                    .isTrue();
            assertThat(published("\n    private static void m() {"))
                    .as("a private member is documented, but its page exists only at "
                            + "-private visibility, where the link resolves")
                    .isFalse();
            assertThat(published("\n    static String active() {")).isFalse();
        }

        @Test
        @DisplayName("reads a top-level type's visibility past its other modifiers")
        void visibilityIsReadPastModifiers() {
            assertThat(visibility("package p;\npublic final class C {}"))
                    .containsEntry("C", true);
            assertThat(visibility("package p;\nfinal class C {}"))
                    .as("the modifier run is captured whole; reading only the last one would "
                            + "make every 'public final' type look package-private")
                    .containsEntry("C", false);
            assertThat(visibility("package p;\npublic abstract sealed class C {}"))
                    .containsEntry("C", true);
        }

        @Test
        @DisplayName("finds a link target and a see target alike")
        void linkTargetsAreFound() {
            assertThat(targets(" * {@link Json#quote(String)} and {@link ManifestWriter}"))
                    .containsExactly("Json", "ManifestWriter");
            assertThat(targets(" * @see Json"))
                    .containsExactly("Json");
            assertThat(targets(" * {@link #ownMethod()} and {@code Json}"))
                    .as("a link to this type's own member names no type, and a code span is "
                            + "not a link at all")
                    .isEmpty();
        }

        private boolean published(final String after) {
            return PUBLISHED_DECLARATION.matcher(after).find();
        }

        private Map<String, Boolean> visibility(final String source) {
            final Map<String, Boolean> found = new HashMap<>();
            final Matcher matcher = TOP_LEVEL_TYPE.matcher(source);
            while (matcher.find()) {
                found.put(matcher.group(2), matcher.group(1).contains("public"));
            }
            return found;
        }

        private List<String> targets(final String block) {
            final List<String> found = new ArrayList<>();
            final Matcher matcher = LINK_REFERENCE.matcher(block);
            while (matcher.find()) {
                found.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
            return found;
        }
    }

    // -------------------------------------------------------------------------------------

    private interface BlockVisitor {
        void accept(int line, String body, String after);
    }

    private static List<String> violations(final Pattern pattern) {
        final List<String> found = new ArrayList<>();
        for (final Path source : allSources()) {
            final String text = read(source);
            scan(text, (line, body, after) -> {
                final String prose = prose(body);
                final Matcher matcher = pattern.matcher(prose);
                while (matcher.find()) {
                    found.add(relative(source) + ':' + lineOf(prose, matcher.start(), line)
                            + " -> " + matcher.group().strip());
                }
            });
        }
        return found;
    }

    private static int lineOf(final String block, final int index, final int firstLine) {
        return firstLine + (int) block.substring(0, index).chars()
                .filter(c -> c == '\n').count();
    }

    /**
     * Reads the diagnostic codes that exist, from the enum that declares them.
     *
     * @return every code {@code DiagnosticCode} declares, in declaration order
     */
    private static List<String> declaredCodes() {
        final Matcher matcher =
                DECLARED_CODE.matcher(read(repositoryRoot().resolve(DIAGNOSTIC_CODES)));
        final List<String> codes = new ArrayList<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }

    /**
     * Maps each package to its top-level types and whether each one is {@code public}.
     *
     * <p>A type is keyed by its simple name because that is how a doc comment in the same
     * package refers to it, which is the only reference this can resolve without a compiler.
     *
     * @return package name to simple type name to whether the type is {@code public}
     */
    private static Map<String, Map<String, Boolean>> topLevelTypes() {
        final Map<String, Map<String, Boolean>> byPackage = new HashMap<>();
        for (final Path source : allSources()) {
            final String text = read(source);
            final Matcher matcher = TOP_LEVEL_TYPE.matcher(text);
            while (matcher.find()) {
                final String modifiers = matcher.group(1) == null ? "" : matcher.group(1);
                byPackage.computeIfAbsent(packageOf(text), key -> new HashMap<>())
                        .put(matcher.group(2), modifiers.contains("public"));
            }
        }
        return byPackage;
    }

    private static String packageOf(final String text) {
        final Matcher matcher = PACKAGE_DECLARATION.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String prose(final String block) {
        final String withoutTags = INLINE_TAG.matcher(block).replaceAll(" ");
        return HTML_TAG.matcher(withoutTags).replaceAll(" ");
    }

    /**
     * Walks a compilation unit and hands every doc comment to the visitor.
     *
     * <p>String literals, character literals and text blocks are skipped, so a
     * {@code /**} held as data is never mistaken for documentation.</p>
     *
     * @param src   the source text; must not be {@code null}
     * @param visit called once per doc comment, with its 1-based starting line and the source
     *              that follows it, which is the declaration the comment documents
     */
    private static void scan(final String src, final BlockVisitor visit) {
        int i = 0;
        int line = 1;
        final int n = src.length();

        while (i < n) {
            final char c = src.charAt(i);

            if (c == '\n') {
                line++;
                i++;
            } else if (src.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !src.startsWith("\"\"\"", j)) {
                    if (src.charAt(j) == '\\') {
                        j++;
                    } else if (src.charAt(j) == '\n') {
                        line++;
                    }
                    j++;
                }
                i = Math.min(j + 3, n);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else if (src.startsWith("//", i)) {
                final int j = src.indexOf('\n', i);
                i = j == -1 ? n : j;
            } else if (src.startsWith("/*", i)) {
                final int found = src.indexOf("*/", i + 2);
                final int end = found == -1 ? n : found + 2;
                final String body = src.substring(i, end);
                if (body.startsWith("/**") && !"/**/".equals(body)) {
                    visit.accept(line, body, src.substring(end, Math.min(end + 300, n)));
                }
                line += (int) body.chars().filter(ch -> ch == '\n').count();
                i = end;
            } else {
                i++;
            }
        }
    }

    private static List<Path> allSources() {
        final Path root = repositoryRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        for (final Path part : root.relativize(p)) {
                            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve(".git"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }

    private static String relative(final Path source) {
        return repositoryRoot().relativize(source).toString().replace('\\', '/');
    }

    private static String read(final Path source) {
        try {
            return Files.readString(source);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
