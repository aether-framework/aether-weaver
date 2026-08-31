package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.SelectorSyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import java.util.Objects;

/**
 * Parses the target-method selector of an injection annotation and reports what it cannot parse.
 *
 * <p>One annotation element goes through here: the {@code method} of an {@code @Inject}, a
 * {@code @Redirect} or a {@code @Wrap}. The {@code target} of an {@code @At} does not; it is
 * carried through the specification as the text the author wrote and parsed by the injection point
 * that uses it, so a malformed one is not reported at this stage.
 *
 * <p>Every report is anchored on the selector literal rather than on the handler, so the caret
 * lands on the text that has to change and a handler carrying two injections says which of the two
 * is wrong.
 *
 * <p>A failed parse is reported under the parser's own code — {@code AW1015}, {@code AW1017},
 * {@code AW1018} or {@code AW1019} — rather than one chosen here, so a selector cannot be accepted
 * at compile time and refused at weave time with a different explanation. Two conditions are
 * decided before the parser is reached: {@code AW1015} for a blank selector and {@code AW1016} for
 * one carrying type arguments.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class SelectorChecks {

    /**
     * Refuses instantiation; the single entry point is static.
     *
     * @throws AssertionError always
     */
    private SelectorChecks() {
        throw new AssertionError("no instances");
    }

    /**
     * Parses one selector, reporting against the annotation element it was written in.
     *
     * <p>A blank selector is refused as {@code AW1015} without reaching the parser, the message
     * saying that the selector is empty and the remedy giving both an unqualified and an
     * owner-qualified example. Write the member to inject into.
     *
     * <p>Text containing a {@code <} anywhere is reported as {@code AW1016}, an informational
     * notice that parsing continues past: selectors match erased signatures, so the type arguments
     * had no effect and deleting them makes the selector say what it means. The test is the
     * character and not a parsed type argument list, so {@code <init>()} is reported as carrying
     * type arguments as well, and is then parsed as the constructor selector its text names — a
     * {@code MethodSelector} named {@code <init>}. Against the source model it is never matched:
     * that model only considers enclosed elements of kind {@code METHOD}, so a constructor is
     * never a candidate there and {@code <init>()} is refused as {@code AW1020}, "declares no
     * method matching '&lt;init&gt;()'". A compiled target's class file is read differently — the
     * method list {@code PointChecks} resolves against includes {@code <init>} — so the same
     * selector is matched there, and its points are resolved against the constructor's own body.
     *
     * <p>Anything the parser refuses is reported under the code the
     * {@link SelectorSyntaxException} carries, with that exception's message as the diagnostic's
     * message and its suggestion, where it offers one, as the remedy — which is what makes
     * {@code AW1017} a one-step fix: the suggestion is the text with any leading {@code src:}
     * prefix stripped and {@code desc:} prepended in its place, so {@code "src:(I)V"} suggests
     * {@code "desc:(I)V"}.
     *
     * <p>{@link MemberSelector#parse(String, MemberKind)} also throws
     * {@link IllegalArgumentException} for two selectors the grammar accepts but cannot build —
     * an array of {@code void}, {@code v:void[]}, and a {@code desc:} method selector with a blank
     * name, {@code desc: ()V}. That exception is not caught here and reaches the compiler, which
     * ends the compilation with {@code An annotation processor threw an uncaught exception} and no
     * position.
     *
     * @param text     the selector as written; must not be {@code null}
     * @param expected the member kind a bare name is taken to name; must not be {@code null}
     * @param owner    the declaration the annotation sits on, which the report is anchored to;
     *                 must not be {@code null}
     * @param mirror   the annotation carrying the selector, used to position the report inside it;
     *                 must not be {@code null}
     * @param name     the annotation element the selector was written in; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the parsed selector, or {@code null} when the text was blank or did not parse — in
     *         both cases a diagnostic has already been reported
     * @throws NullPointerException     if {@code text}, {@code expected}, {@code owner},
     *                                  {@code name} or {@code reporter} is {@code null}
     * @throws IllegalArgumentException if the text parses as an array of {@code void} or as a
     *                                  {@code desc:} method selector with a blank name
     */
    @Nullable
    static MemberSelector parse(@NotNull final String text,
                                @NotNull final MemberKind expected,
                                @NotNull final Element owner,
                                @NotNull final AnnotationMirror mirror,
                                @NotNull final String name,
                                @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(reporter, "reporter");

        final AnnotationValue value = Anchors.valueOf(mirror, name);
        final Anchor anchor = Anchor.at(owner, mirror, value);

        if (text.isBlank()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.SELECTOR_SYNTAX_ERROR)
                    .message("the selector is empty")
                    .remedy("name the member to inject into, for example \"run()\" or "
                            + "\"com.acme.Session#run()\"")
                    .build(), anchor);
            return null;
        }
        if (text.indexOf('<') >= 0) {
            reporter.report(Diagnostic.builder(DiagnosticCode.SELECTOR_TYPE_ARGUMENTS_IGNORED)
                    .message("the selector '" + text + "' carries type arguments, which are "
                            + "ignored")
                    .detail("selectors match erased signatures, because that is what a class file "
                            + "records — List<String> and List<Integer> are one method there")
                    .remedy("nothing needs doing; delete them to say what the selector means")
                    .build(), anchor);
        }

        try {
            return MemberSelector.parse(text, expected);
        } catch (final SelectorSyntaxException malformed) {
            // The parser's own code, not one chosen here: the engine reports the same code for
            // the same text, so a selector cannot be accepted at build time and refused at run time.
            final Diagnostic.Builder builder = Diagnostic.builder(malformed.code())
                    .message(malformed.getMessage());
            malformed.suggestion().ifPresent(builder::remedy);
            reporter.report(builder.build(), anchor);
            return null;
        }
    }
}
