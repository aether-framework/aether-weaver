/**
 * Makes the text this framework prints survive a console that cannot encode all of it.
 *
 * <p>The engine's diagnostics, its explain report and its plan listing are written for a person and
 * use characters a person expects: an em dash between a subject and its elaboration, an arrow from a
 * weave to its target, an ellipsis where a listing was cut. A stream whose charset cannot represent
 * one of those prints a substitute chosen by the encoder, which collapses every unrepresentable
 * character onto the same replacement and loses the distinction between a character that had a
 * sensible ASCII equivalent and one that had none.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.text.ConsoleText} chooses the substitute instead, from
 * a fixed table: an em dash becomes {@code -}, a rightwards arrow becomes {@code ->}, an ellipsis
 * becomes {@code ...}, and most other characters with no entry become {@code ?} — except a lone low
 * surrogate, which has no entry and is not itself a surrogate pair, and so is dropped with no
 * replacement at all. Text the charset already accepts is returned unchanged, which is what a UTF-8
 * terminal gets, though a new encoder is still built on every call to decide that.
 *
 * <p>The package holds that one class and no state. It is called by the drivers that own a console —
 * the agent and the Maven goals — rather than from inside weaving, because only a driver knows which
 * stream the text is going to and therefore which charset decides.
 *
 * <p>Nothing here formats, wraps or colours anything, and nothing here decides what to print. The
 * text arrives already written; this package only makes it representable.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.text;
