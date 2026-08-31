package de.splatgames.aether.weaver.processor;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.Objects;

/**
 * The place in the source a compile-time diagnostic is printed against.
 *
 * <p>An anchor names three nested things, each narrower than the last: the declaration, the
 * annotation written on it, and the annotation element written inside that annotation. Only the
 * declaration is required. The narrowest part present is the one the caret lands under, so an
 * anchor carrying a value puts the underline on the literal the author would edit rather than on
 * the method that carries it.
 *
 * <p>Every check in this package builds one of these and hands it to
 * {@link MessagerReporter#report(de.splatgames.aether.weaver.api.diagnostic.Diagnostic, Anchor)}.
 * Nothing here decides severity or wording; an anchor is position and nothing else.
 *
 * @param element    the declaration the diagnostic belongs to
 * @param annotation the annotation on {@code element} to narrow to, or {@code null} to stay on the
 *                   declaration
 * @param value      the element inside {@code annotation} to narrow to, or {@code null} to stay on
 *                   the annotation
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record Anchor(@NotNull Element element,
                     @Nullable AnnotationMirror annotation,
                     @Nullable AnnotationValue value) {

    /**
     * Checks that the three parts nest.
     *
     * <p>An annotation value has no position of its own — it is located by the annotation that
     * contains it — so a value given without an annotation names nowhere and is refused rather
     * than quietly downgraded to the element. The static factories normalise instead of throwing.
     *
     * @throws NullPointerException     if {@code element} is {@code null}
     * @throws IllegalArgumentException if {@code value} is given and {@code annotation} is
     *                                  {@code null}
     */
    public Anchor {
        Objects.requireNonNull(element, "element");
        if (value != null && annotation == null) {
            throw new IllegalArgumentException(
                    "an annotation value is positioned within its annotation, so naming one "
                            + "without the other cannot produce a position");
        }
    }

    /**
     * Anchors on a declaration, with no annotation to narrow to.
     *
     * @param element the declaration to underline; must not be {@code null}
     * @return an anchor naming that declaration alone
     * @throws NullPointerException if {@code element} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static Anchor at(@NotNull final Element element) {
        return new Anchor(element, null, null);
    }

    /**
     * Anchors on an annotation written on a declaration.
     *
     * @param element    the declaration carrying the annotation; must not be {@code null}
     * @param annotation the annotation to underline, or {@code null} to fall back to the
     *                   declaration
     * @return an anchor naming the annotation, or the declaration alone when {@code annotation} is
     *         {@code null}
     * @throws NullPointerException if {@code element} is {@code null}
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static Anchor at(@NotNull final Element element,
                            @Nullable final AnnotationMirror annotation) {
        return new Anchor(element, annotation, null);
    }

    /**
     * Anchors on an element written inside an annotation.
     *
     * <p>{@code value} is discarded when {@code annotation} is {@code null}, so this factory never
     * fails the nesting rule the canonical constructor enforces. That is what makes it safe to
     * pass the result of a lookup that may find nothing: a check whose annotation element was
     * never written out lands on the annotation, or on the declaration, instead of failing the
     * build with an exception from the processor itself.
     *
     * @param element    the declaration carrying the annotation; must not be {@code null}
     * @param annotation the annotation containing {@code value}, or {@code null} to fall back to
     *                   the declaration
     * @param value      the annotation element to underline, or {@code null} to stay on the
     *                   annotation
     * @return an anchor naming the narrowest of the three parts that is present
     * @throws NullPointerException if {@code element} is {@code null}
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    @NotNull
    public static Anchor at(@NotNull final Element element,
                            @Nullable final AnnotationMirror annotation,
                            @Nullable final AnnotationValue value) {
        return new Anchor(element, annotation, annotation == null ? null : value);
    }

    /**
     * Prints one message through the {@link Messager}, positioned at this anchor.
     *
     * <p>The narrowest part present decides which {@link Messager} overload is called. The text is
     * passed through unchanged: line breaks inside it are the caller's, and a build log that shows
     * them indented is showing what
     * {@link MessagerReporter#render(de.splatgames.aether.weaver.api.diagnostic.Diagnostic)} put
     * there.
     *
     * <p>All three parts are position hints, which a host compiler is free to ignore; {@code javac}
     * honours every one of them and puts the caret under the annotation element itself.
     *
     * @param messager the messager to print through; must not be {@code null}
     * @param kind     the diagnostic kind, which is what decides whether the compilation still
     *                 succeeds; must not be {@code null}
     * @param message  the text to print, already rendered; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public void print(@NotNull final Messager messager,
                      @NotNull final Diagnostic.Kind kind,
                      @NotNull final String message) {
        Objects.requireNonNull(messager, "messager");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        // The three-argument and four-argument overloads are not interchangeable with nulls: passing
        // a null mirror to the four-argument form is permitted but passing a null value to the
        // five-argument form is not, so the call has to be chosen rather than the arguments padded.
        if (this.value != null) {
            messager.printMessage(kind, message, this.element, this.annotation, this.value);
        } else if (this.annotation != null) {
            messager.printMessage(kind, message, this.element, this.annotation);
        } else {
            messager.printMessage(kind, message, this.element);
        }
    }

    /**
     * Names which of the three parts this anchor narrows to.
     *
     * @return {@code "value"}, {@code "annotation"} or {@code "element"}, whichever is the
     *         narrowest part present
     */
    @Contract(pure = true)
    @NotNull
    public String level() {
        if (this.value != null) {
            return "value";
        }
        return this.annotation != null ? "annotation" : "element";
    }
}
