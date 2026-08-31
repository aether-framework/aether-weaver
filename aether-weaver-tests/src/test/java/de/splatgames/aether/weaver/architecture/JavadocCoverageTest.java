package de.splatgames.aether.weaver.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JavadocCoverageTest {

    private static final Pattern TYPE = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
                    + "(class|interface|enum|record|@interface)\\s+(\\w+)");

    // The modifier run is optional. Requiring it left every package-private declaration --
    // `void run() {`, `int count;` -- outside the walk entirely, which is the opposite of a
    // policy that documents a member whatever its visibility. Members are only ever matched at
    // the type's own body depth, so relaxing this cannot reach a statement inside a method.
    private static final Pattern MEMBER = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|default|synchronized"
                    + "|transient|volatile|native)\\s+)*[\\w<>\\[\\],.?\\s@]+?\\s(\\w+)\\s*[({;=]");

    // A constructor carries no return type, so MEMBER -- which wants one, and the whitespace
    // before the name -- never matched one. The test's own name has claimed constructors since
    // it was written.
    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "^\\s*(?:(?:public|protected|private)\\s+)?([A-Z]\\w*)\\s*\\(");

    private static final Pattern ANNOTATION_ELEMENT = Pattern.compile(
            "^\\s*[\\w<>\\[\\].?,\\s]+\\s(\\w+)\\s*\\(\\s*\\)");

    private static final Pattern INITIALISER = Pattern.compile("^\\s*(?:static\\s+)?\\{");

    // `{` closes the set: a constant that opens a class body is still a constant.
    private static final Pattern ENUM_CONSTANT = Pattern.compile(
            "^\\s*([A-Z][A-Z0-9_]*)\\s*(?:[,;({]|$)");

    private static final List<String> KEYWORDS =
            List.of("if", "for", "while", "switch", "return", "new", "catch", "try");

    @Test
    @DisplayName("every type carries JavaDoc with @since")
    void everyTypeIsDocumented() {
        final List<String> undocumented = new ArrayList<>();
        final List<String> withoutSince = new ArrayList<>();

        for (final Path source : publishedSources()) {
            scan(source, undocumented, withoutSince, new ArrayList<>());
        }

        assertThat(undocumented).as("types with no JavaDoc at all").isEmpty();
        assertThat(withoutSince)
                .as("types whose JavaDoc lacks @since — the tag belongs on types, and only on "
                        + "types, because a member cannot predate its declaring type")
                .isEmpty();
    }

    @Test
    @DisplayName("every field, method and constructor carries JavaDoc")
    void everyMemberIsDocumented() {
        final List<String> undocumented = new ArrayList<>();

        for (final Path source : publishedSources()) {
            scan(source, new ArrayList<>(), new ArrayList<>(), undocumented);
        }

        assertThat(undocumented)
                .as("members with no JavaDoc; the engine is held to the same standard as the API")
                .isEmpty();
    }

    @Test
    @DisplayName("@since appears only on types")
    void sinceOnlyOnTypes() {
        final List<String> misplaced = new ArrayList<>();

        for (final Path source : publishedSources()) {
            final List<String> lines = read(source);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains("@since")) {
                    continue;
                }
                int end = i;
                while (end < lines.size() && !lines.get(end).contains("*/")) {
                    end++;
                }
                // Skip annotations, including ones whose argument list spans several lines:
                // an unbalanced '(' means the next line continues the same annotation. An
                // `@interface` declaration also starts with '@' and must not be skipped as one.
                int decl = end + 1;
                int open = 0;
                while (decl < lines.size()) {
                    final String candidate = lines.get(decl).strip();
                    final boolean isAnnotation =
                            candidate.startsWith("@") && !TYPE.matcher(candidate).find();
                    if (open == 0 && !candidate.isBlank() && !isAnnotation) {
                        break;
                    }
                    open += (int) candidate.chars().filter(c -> c == '(').count();
                    open -= (int) candidate.chars().filter(c -> c == ')').count();
                    decl++;
                }
                if (decl < lines.size() && !TYPE.matcher(lines.get(decl)).find()) {
                    misplaced.add(relative(source) + ':' + (i + 1) + " -> "
                            + lines.get(decl).strip());
                }
            }
        }

        assertThat(misplaced)
                .as("@since on a member whose declaring type carries the same value states "
                        + "something true by construction, and dilutes the tag where it matters")
                .isEmpty();
    }

    // -------------------------------------------------------------------------------------

    private static void scan(final Path source,
                             final List<String> typeNoDoc,
                             final List<String> typeNoSince,
                             final List<String> memberNoDoc) {
        final List<String> lines = read(source);
        final Deque<String> enclosing = new ArrayDeque<>();
        int depth = 0;
        int typeDepth = 0;
        boolean inComment = false;

        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            final String stripped = line.strip();

            if (inComment) {
                if (stripped.contains("*/")) {
                    inComment = false;
                }
                continue;
            }
            if (stripped.startsWith("/*")) {
                if (!stripped.contains("*/")) {
                    inComment = true;
                }
                continue;
            }
            if (stripped.startsWith("//")) {
                continue;
            }

            final String code = stripped.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"")
                    .replaceAll("'(\\\\.|[^'\\\\])*'", "''")
                    .split("//")[0];

            final Matcher type = TYPE.matcher(line);
            final boolean isType = type.find() && depth == typeDepth;

            if (isType) {
                final String doc = javadocAbove(lines, i);
                if (doc == null) {
                    typeNoDoc.add(relative(source) + ':' + (i + 1) + ' ' + type.group(2));
                } else if (!doc.contains("@since")) {
                    typeNoSince.add(relative(source) + ':' + (i + 1) + ' ' + type.group(2));
                }
            } else if (depth == typeDepth && depth >= 1
                    && isUndocumentedMember(lines, i, line, enclosing.peek())) {
                memberNoDoc.add(relative(source) + ':' + (i + 1) + ' ' + truncate(stripped));
            }

            final long opens = code.chars().filter(c -> c == '{').count();
            final long closes = code.chars().filter(c -> c == '}').count();
            if (isType && opens > 0) {
                typeDepth++;
                enclosing.push(type.group(1));
            }
            depth += (int) (opens - closes);
            if (typeDepth > Math.max(depth, 0)) {
                typeDepth = Math.max(depth, 0);
            }
            while (enclosing.size() > typeDepth) {
                enclosing.pop();
            }
        }
    }

    /**
     * Reports whether a line opens a declaration rather than continuing one.
     *
     * <p>The scan is line-based, so the only evidence is the line before: a declaration begins
     * after something closed. Blank lines, comments and annotations are stepped over, since any
     * of them may sit between a member and whatever precedes it.
     *
     * @param lines the file
     * @param index the line to judge
     * @return whether the previous line of code closed something
     */
    private static boolean startsDeclaration(final List<String> lines, final int index) {
        for (int i = index - 1; i >= 0; i--) {
            final String previous = lines.get(i).strip();
            if (previous.isEmpty() || previous.startsWith("//") || previous.startsWith("*")
                    || previous.startsWith("/*") || previous.startsWith("@")) {
                continue;
            }
            return previous.endsWith(";") || previous.endsWith("{") || previous.endsWith("}")
                    || previous.endsWith("*/");
        }
        return true;
    }

    private static boolean isUndocumentedMember(final List<String> lines,
                                                final int index,
                                                final String line,
                                                final String enclosing) {
        final List<Pattern> applicable = new ArrayList<>();
        if ("@interface".equals(enclosing)) {
            applicable.add(ANNOTATION_ELEMENT);
        } else if ("enum".equals(enclosing)) {
            applicable.add(ENUM_CONSTANT);
        }
        applicable.add(CONSTRUCTOR);
        applicable.add(MEMBER);

        // An initialiser block is not a field, a constructor or a method, so the policy does not
        // reach it -- and `static {` matches MEMBER once the modifier run is optional.
        if (INITIALISER.matcher(line).find()) {
            return false;
        }
        // Without the modifier run to anchor it, MEMBER matches the continuation lines of a
        // multi-line declaration as readily as the line that opens one: `throws IOException {`,
        // `implements WeaveMember {`, and every argument line of a multi-line `new`. Nineteen of
        // those were reported the moment the run became optional, and not one was a member.
        if (!startsDeclaration(lines, index)) {
            return false;
        }

        for (final Pattern pattern : applicable) {
            final Matcher matcher = pattern.matcher(line);
            if (matcher.find() && !KEYWORDS.contains(matcher.group(1))) {
                return javadocAbove(lines, index) == null;
            }
        }
        return false;
    }

    private static String javadocAbove(final List<String> lines, final int index) {
        int i = index - 1;
        int pending = 0;
        while (i >= 0) {
            final String s = lines.get(i).strip();
            if (s.isEmpty()) {
                i--;
                continue;
            }
            if (s.endsWith("*/")) {
                break;
            }
            final int delta = count(s, ')') - count(s, '(');
            if (pending > 0) {
                pending += delta;
                i--;
                continue;
            }
            if (s.startsWith("@")) {
                i--;
                continue;
            }
            // tail of a multi-line annotation such as @Mojo(name = "weave",\n  threadSafe = true)
            if (delta > 0) {
                pending = delta;
                i--;
                continue;
            }
            break;
        }
        if (i < 0 || !lines.get(i).strip().endsWith("*/")) {
            return null;
        }
        int start = i;
        while (start >= 0 && !lines.get(start).contains("/**")) {
            start--;
        }
        return start < 0 ? null : String.join("\n", lines.subList(start, i + 1));
    }

    private static List<Path> publishedSources() {
        final Path root = repositoryRoot();
        final List<Path> sources = new ArrayList<>();
        for (final String module : List.of("aether-weaver-api", "aether-weaver-engine",
                "aether-weaver-runtime", "aether-weaver-agent", "aether-weaver-processor",
                "aether-weaver-testkit", "aether-weaver-maven-plugin")) {
            final Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(main)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                        .forEach(sources::add);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(sources).as("the scan must find sources at all").isNotEmpty();
        return sources;
    }

    private static List<String> read(final Path path) {
        try {
            return Files.readAllLines(path);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(final Path path) {
        return repositoryRoot().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static int count(final String text, final char character) {
        return (int) text.chars().filter(c -> c == character).count();
    }

    private static String truncate(final String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("aether-weaver-api"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the reactor root");
    }
}
