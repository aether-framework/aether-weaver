package com.acme.payments;

/**
 * Weaving target whose body shape is the point: exactly one {@code return}, and it is the last
 * statement.
 *
 * <p>That is the narrow condition under which the plugin resolves {@code Point.TAIL} from the
 * target's source. A body with no {@code return} at all resolves to its closing brace and a body
 * with one that is also the last statement resolves to it; any other body yields nothing, because
 * the last exit in bytecode order is not necessarily the last one in the text. {@link Silent} is
 * the counter-case, with two returns and therefore no tail the editor will draw.
 *
 * <p>The engine is less conservative here than the editor: {@code Point.TAIL} keeps the last return
 * in body order whatever the body looks like, so a target the editor draws nothing for is still
 * woven.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public class Router {

    /**
     * Resolves a key, substituting a default for a blank answer.
     *
     * <p>One {@code return}, written as the body's last statement, so the tail is a position the
     * editor can find; and one call to the package-private {@code lookup}, which is the operation
     * {@code RouterWeave} names with {@code Point.INVOKE_AFTER}.
     *
     * @param key the key to resolve
     * @return the trimmed key, or {@code "default"} when it trims to nothing
     */
    public String route(final String key) {
        final String resolved = lookup(key);
        return resolved.isBlank() ? "default" : resolved;
    }

    /**
     * Trims a key.
     *
     * <p>Package-private and called exactly once from {@link #route(String)}, so a call point that
     * names it needs no ordinal to resolve to one position.
     *
     * @param key the key to trim, which may be {@code null}
     * @return the trimmed key, or an empty string when the key is {@code null}
     */
    String lookup(final String key) {
        return key == null ? "" : key.trim();
    }
}
