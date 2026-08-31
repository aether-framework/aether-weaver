package de.splatgames.aether.weaver.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The record a woven class carries, saying which weaver modified it and what it did.
 *
 * <p>This annotation is written onto a target class by the weaver, never by hand. It is retained at
 * run time so that a class can be asked what happened to it without reading its bytes:
 * {@link WovenInfo#of(Class)} is the supported way to do so, and this type is the contract that
 * accessor exposes.
 *
 * <h2>When it is written</h2>
 *
 * <p>Stamping is the last step of weaving a class, after the injections have been emitted and after
 * the verifier has accepted the result. A class the verifier refuses is handed back unmodified and
 * carries no annotation, so the presence of one means the class both changed and verified.
 *
 * <p>Whether it is written at all is the weaver's configured detail level, and
 * {@link Detail#NONE} suppresses it entirely. A class with no {@code @Woven} is therefore not
 * necessarily an unwoven class — it may have been woven by a weaver configured to say nothing.
 * The engine's own idempotence check does not rely on this annotation: it reads a separate class
 * file attribute, which is written whatever the detail level, so removing or suppressing the
 * annotation does not make a class look unwoven to the weaver.
 *
 * <h2>What the values are guaranteed to be</h2>
 *
 * <p>Everything here is written in one place by one writer, so the shapes are fixed rather than
 * merely typical.
 *
 * <ul>
 *   <li>{@link #schema()} is written as {@code 1}.
 *   <li>{@link #weaver()} and {@link #fingerprint()} are always present and never blank.
 *   <li>{@link #weaves()} and {@link #plugins()} are sorted and free of duplicates.
 *   <li>{@link #entries()} is empty unless {@link #detail()} is {@link Detail#FULL}, and is capped
 *       at 32 elements even then.
 *   <li>{@link #extra()} holds {@code key=value} strings, ordered by key.
 * </ul>
 *
 * <p>Nothing is per-weave-class: one annotation describes one target, and it lists every weave that
 * was planned against that target rather than one annotation per contributor. A weave counts as
 * planned once it produces a plan entry for the target, whether or not that entry goes on to
 * resolve a site or pass injector validation — {@link #weaves()} and {@link #entries()} say more.
 *
 * <h2>Reading it defensively</h2>
 *
 * <p>{@link #schema()} is the version of this record's shape, and a reader that does not recognise
 * the number should read no further than the fields it can prove the meaning of. Everything else is
 * additive by design: {@link #flags()} is a bit set whose unassigned bits are reserved, and
 * {@link #extra()} is where a value with no element of its own is carried. A reader meeting a flag
 * bit it does not know, or an {@link #extra()} key it does not know, should ignore it rather than
 * treat the record as malformed; neither can change what the elements it does understand mean.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // What the weaver writes onto a target, shown as source. Nothing writes this by hand.
 * @Woven(schema = 1,
 *        weaver = "0.1.0",
 *        fingerprint = "9f1c...a3",             // 64 lowercase hex characters
 *        detail = Woven.Detail.FULL,
 *        flags = 0,
 *        weaves = {"com.acme.LedgerAudit"},
 *        plugins = {},
 *        entries = {@Woven.Entry(weave = "com.acme.LedgerAudit",
 *                                kind = "inject",
 *                                handler = "onCharge(Ljava/math/BigDecimal;)V",
 *                                target = "charge(java.math.BigDecimal)")},
 *        extra = {})
 * public class Ledger {
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WovenInfo
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Woven {

    /**
     * The version of this record's shape.
     *
     * <p>Written as {@code 1}. A reader that does not recognise the number should not assume the
     * meaning of the other elements; a reader that does may rely on every guarantee stated here.
     *
     * @return the record schema version
     */
    int schema() default 1;

    /**
     * The version of the weaver that produced the class.
     *
     * <p>Never blank. The value identifies the engine, not the plan and not the weaves; two classes
     * woven by different plans of the same build report the same string here.
     *
     * @return the weaver's version
     */
    String weaver();

    /**
     * The identity of the plan that was applied.
     *
     * <p>64 lowercase hexadecimal characters: a digest over the whole plan — every ordered entry
     * and every registered plugin — rather than over this class alone. Every class woven in one run
     * therefore carries the same value, and two runs producing the same value applied the same
     * modifications in the same order.
     *
     * <p>This is what makes weaving idempotent: a class already carrying a plan's fingerprint is
     * not woven again by that plan, and one carrying a different plan's is refused with
     * {@code AW2201} at build time or reported as {@code AW2202} at load time.
     *
     * @return the plan fingerprint
     */
    String fingerprint();

    /**
     * How much the weaver was configured to record.
     *
     * <p>The value describes the annotation the reader is holding, so a {@link Detail#SUMMARY}
     * record with an empty {@link #entries()} is complete rather than truncated. The annotation is
     * absent altogether under {@link Detail#NONE}.
     *
     * @return the detail level this record was written at
     */
    Detail detail() default Detail.SUMMARY;

    /**
     * Facts about the weaving that are single bits rather than values.
     *
     * <p>Three bits are assigned. {@code 0x0001} says a policy override was active, {@code 0x0002}
     * that the class was changed structurally, and {@code 0x0004} that {@link #entries()} was
     * truncated at 32. Every other bit is reserved and is written as zero.
     *
     * <p>{@link WovenInfo#usedPolicyOverride()}, {@link WovenInfo#isStructural()} and
     * {@link WovenInfo#entriesTruncated()} decode the three rather than requiring a reader to know
     * the constants. The stamping path passes {@code false} for the first two, so a class this
     * version writes has neither of those bits set.
     *
     * @return the bit set
     */
    int flags() default 0;

    /**
     * The binary names of the weave classes that contributed to this class.
     *
     * <p>Sorted and free of duplicates: one name per weave class however many of its declarations
     * were planned against this class. A weave class is listed once any of its declarations
     * produces a plan entry for this target, whether or not that declaration goes on to resolve a
     * site or pass injector validation, so a declaration that ultimately matched nothing or was
     * refused can still leave its weave class here. Conversely, an instance weave that only merges
     * members onto the target and declares no handler of its own produces no plan entry and does
     * not appear, even though it changed the class.
     *
     * @return the weave classes planned against this class
     */
    String[] weaves() default { };

    /**
     * The plugins that were registered with the weaver, written as {@code namespace:version}.
     *
     * <p>Sorted. Every plugin the weaver was given is listed, whether or not it contributed
     * anything to this particular class, because a plugin can affect a plan without emitting an
     * entry of its own.
     *
     * @return the registered plugin coordinates
     */
    String[] plugins() default { };

    /**
     * One element per declaration that was planned against this class.
     *
     * <p>Empty unless {@link #detail()} is {@link Detail#FULL}, and capped at 32 elements when it
     * is; a class with more planned declarations than that carries the first 32 in plan order and
     * sets the {@code 0x0004} bit of {@link #flags()}. The cap exists because an annotation is part
     * of the class file, and an unbounded list of them would grow every woven class in proportion to
     * how much was planned against it.
     *
     * <p>An entry is written for every declaration the plan carries for this target, whether or not
     * it went on to resolve a site or pass injector validation; a declaration that ultimately
     * matched nothing, or was refused, still appears here. An instance weave's own member merges
     * produce no plan entry and so contribute nothing to this list, even though they changed the
     * class.
     *
     * @return the planned declarations, in plan order
     */
    Entry[] entries() default { };

    /**
     * Metadata contributed by plugins, as {@code key=value} strings.
     *
     * <p>Ordered by key. The key is everything before the first {@code =} and the value is
     * everything after it, so a value may contain {@code =} and a key may not. An entry with no
     * {@code =}, or one whose {@code =} is the first character, has no key and is skipped by
     * {@link WovenInfo#metadata()}.
     *
     * <p>This is the extension point of the record: a plugin that has something to say about a
     * woven class says it here rather than in an element of its own, and a reader that does not
     * recognise a key should ignore it.
     *
     * @return the flattened metadata
     */
    String[] extra() default { };

    /**
     * One declaration the weaver planned against the class.
     *
     * <p>Written only under {@link Detail#FULL}. Every component is present and non-blank, and the
     * four together identify the declaration well enough to find it in the weave's source — not
     * necessarily one that produced a change: a declaration that matched no site, or that injector
     * validation refused, is still recorded here.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ })
    @interface Entry {

        /**
         * The binary name of the weave class that declared the modification.
         *
         * @return the weave class name
         */
        String weave();

        /**
         * What kind of modification it was.
         *
         * <p>The built-in kinds are written unqualified: {@code inject}, {@code redirect},
         * {@code wrap}, {@code merge}, {@code accessor} and {@code invoker}. A kind contributed by
         * a plugin carries its namespace, as {@code namespace:name}.
         *
         * @return the injector kind's identifier
         */
        String kind();

        /**
         * The handler the modification calls, as its name followed by its JVM descriptor.
         *
         * <p>Written without a separator, in the form {@code onCharge(Ljava/math/BigDecimal;)V},
         * and without the declaring class, which is {@link #weave()}.
         *
         * @return the handler's name and descriptor
         */
        String handler();

        /**
         * The target-method selector the declaration named, exactly as it was written.
         *
         * <p>The unparsed text of {@link Inject#method()}, {@link Redirect#method()} or
         * {@link Wrap#method()}, not the method it resolved to, so a wildcard selector appears here
         * as a wildcard.
         *
         * @return the target selector as written
         */
        String target();
    }

    /**
     * How much a weaver records on the classes it writes.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum Detail {

        /**
         * No annotation at all.
         *
         * <p>The class is still woven and still carries the class file attribute the weaver's own
         * idempotence check reads; what is suppressed is the reflective record and nothing else.
         */
        NONE,

        /**
         * The annotation without its per-modification entries.
         *
         * <p>The default. Everything but {@link Woven#entries()} is written, which is enough to
         * answer which weaver, which plan and which weaves, at a cost that does not grow with how
         * much was done to the class.
         */
        SUMMARY,

        /**
         * The annotation including one {@link Entry} per modification.
         *
         * <p>Capped at 32 entries, with the {@code 0x0004} bit of {@link Woven#flags()} set when
         * there were more.
         */
        FULL
    }
}
