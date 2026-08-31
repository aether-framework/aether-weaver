package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.model.WeaveCandidate;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;

/**
 * Answers the question of which weave classes exist.
 *
 * <p>Discovery comes before everything else: before a plan exists and long before a class is woven,
 * something has to say which classes carry {@code @Weave} declarations. A source is one way of
 * saying it. It is handed a {@link DiscoveryContext} — a loader to search and a reporter to
 * complain to — and answers with {@link WeaveCandidate}s, each of which names a class and knows
 * where to read its bytes from.
 *
 * <p>A candidate is a name and a way to read bytes, not a parsed weave. That division is the point
 * of the interface: a source decides what exists, and parsing, validating and switching a weave on
 * or off according to configuration all happen afterwards, in one place, whatever the weaves were
 * found by. A source that parsed would have to reimplement all of it.
 *
 * <h2>How a source is reached</h2>
 *
 * <p>By being called. There is no service-loader lookup of this interface anywhere in this project:
 * the built-in source, {@code ManifestWeaveSource} in the runtime, is constructed directly by the
 * discovery that the agent and the load-time class loader both run, so a source of a caller's own
 * is one that caller constructs and calls itself. What it gets back is a stream of candidates it
 * can hand to the same parsing that the built-in source's candidates go through.
 *
 * <h2>When it is called, and what the caller does with the answer</h2>
 *
 * <p>The built-in discovery calls {@link #candidates(DiscoveryContext)} once and consumes the
 * stream immediately, so laziness buys a source nothing and holding a classpath root open across
 * the return is a cost paid on the class-loading path. The built-in source reads every manifest
 * eagerly for that reason and returns a stream over a list it has already built.
 *
 * <p>For each candidate the caller then reads the class file, parses it, and keeps the weave if
 * configuration has not switched it off. A candidate whose bytes cannot be read is reported as
 * {@code AW2300} and skipped, and the rest of the run continues: one artefact whose manifest has
 * outlived its classes must not cost the application every other weave.
 *
 * <h2>Failing</h2>
 *
 * <p>Through {@link DiscoveryContext#reporter()}, and by returning the candidates that were found.
 * Discovery is expected to survive a broken artefact rather than abort, and a source that reports
 * and returns an empty stream loses that artefact's weaves and nothing more. The built-in source
 * works this way throughout: {@code AW2302} when no manifest is on the classpath at all,
 * {@code AW2300} for a manifest that cannot be read, opened or parsed, and {@code AW2303} when two
 * artefacts declare the same weave class.
 *
 * <p>A throw is not contained. The built-in discovery path wraps neither the call nor the
 * consumption of the stream, so an exception from a source — including one raised lazily while the
 * stream is being consumed — propagates out of the driver's discovery call, before any class has
 * been woven and with nothing to say which artefact caused it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class ResourceWeaveSource implements WeaveSource {
 *
 *     @Override
 *     public String name() {
 *         return "acme:listing";
 *     }
 *
 *     @Override
 *     public Stream<WeaveCandidate> candidates(DiscoveryContext context) {
 *         URL listing = context.loader().getResource("META-INF/acme-weaves.txt");
 *         if (listing == null) {
 *             context.reporter().report(DiagnosticCode.MANIFEST_NOT_FOUND,
 *                     "no acme weave listing on the classpath");
 *             return Stream.of();
 *         }
 *         ClassSource bytes = ClassSource.ofClassLoader(context.loader());
 *         Origin origin = Origin.of("acme listing", listing.toString());
 *         return read(listing).stream()
 *                 .map(className -> new WeaveCandidate(className, bytes, origin));
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see DiscoveryContext
 * @see WeaveCandidate
 */
public interface WeaveSource {

    /**
     * Returns the name this source is known by.
     *
     * <p>An identity for a human reader rather than a key anything looks up: nothing in this
     * project's discovery, planning or weaving consults it. The built-in source answers
     * {@code aether:manifest}, and a namespaced name of that shape keeps two parties' sources
     * distinguishable wherever they are listed together.
     *
     * @return the source's name
     */
    @NotNull
    String name();

    /**
     * Returns every weave class this source can find.
     *
     * <p>Called once per discovery. The stream may be empty, which is the ordinary answer for a
     * source whose artefacts are simply not on this classpath, and it must not be {@code null}.
     * Duplicates are not filtered out for a source: two candidates naming one class are two
     * candidates, and reporting that as a problem — {@code AW2303} in the built-in source — is the
     * source's own decision, since only it knows which artefacts they came from.
     *
     * <p>The context is valid for the duration of the call and is not retained by the caller, so a
     * source that keeps it holds a loader beyond the point where the driver stops guaranteeing
     * anything about it.
     *
     * @param context the loader to search and the reporter to complain to; never {@code null}
     * @return the candidates found, in the order the caller should consider them; empty when there
     *         are none
     */
    @NotNull
    Stream<WeaveCandidate> candidates(@NotNull DiscoveryContext context);
}
