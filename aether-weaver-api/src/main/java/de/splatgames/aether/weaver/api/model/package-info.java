/**
 * What a weave declares, parsed: the form the annotations take once a weave class has been read and
 * before anything has been matched against a target.
 *
 * <p>Two steps separate an annotation from a change to a class file. First a weave class is read and
 * its declarations become the records here; then each of those is resolved against a particular target
 * and emitted. This package is the boundary between the two, and everything in it has the same
 * property: <b>nothing here has been resolved.</b> A selector has been parsed but not matched, a point
 * names a search that has not been run, and a count is the declaration's own claim about how many
 * positions it expects. Every one of those claims can still turn out to be false of a particular
 * target, and each way it can is reported with a diagnostic rather than an exception.
 *
 * <p>A caller meets these types through
 * {@link de.splatgames.aether.weaver.api.spi}: a
 * {@link de.splatgames.aether.weaver.api.spi.WeaveSource} answers with {@link WeaveCandidate}s, an
 * {@link de.splatgames.aether.weaver.api.spi.InjectionPoint} is handed a {@link PointSpec}, and
 * {@link de.splatgames.aether.weaver.api.spi.PlanEntryView} pairs an {@link InjectorSpec} with one
 * class it is to be applied to.
 *
 * <h2>Discovery: what exists</h2>
 *
 * <p>{@link WeaveCandidate} is a class that has been <em>named</em> as a weave and not yet read. It
 * carries the binary name, a {@link de.splatgames.aether.weaver.api.spi.ClassSource} to fetch the
 * bytes from, and an {@link Origin} saying what named it. Being a candidate implies nothing about the
 * class: it may not exist in the artefact that named it, it may carry no {@code @Weave} annotation,
 * and it may be a weave the configuration in force switches off. Each of those is decided after the
 * bytes are read.
 *
 * <p>{@link Origin} is provenance rather than identity. Nothing keys on it; it exists so that a
 * diagnostic about a weave can name the artefact to go and fix rather than only the class that is
 * wrong, and it is printed as {@code source (location)} through {@link Origin#describe()}.
 *
 * <h2>Declaration: what is to be done</h2>
 *
 * <p>{@link InjectorSpec} is the unit everything downstream works in — one handler, one target method,
 * and one or more positions inside it. {@link de.splatgames.aether.weaver.api.Inject},
 * {@link de.splatgames.aether.weaver.api.Redirect} and {@link de.splatgames.aether.weaver.api.Wrap}
 * each become one, and a repeated annotation is flattened back into its occurrences so that a handler
 * carrying one and a handler carrying three are not structurally different. The rest of the package
 * are its parts:
 *
 * <ul>
 *   <li>{@link InjectorKind} — what happens at the matched position, and thereby which injector is
 *       asked to emit it.
 *   <li>{@link HandlerRef} — the method control is handed to: owner, name, erased descriptor and
 *       access flags. Nothing is resolved, loaded or reflected upon; the flags travel with the
 *       reference because they choose the invocation opcode.
 *   <li>{@link PointSpec} — one {@code @At}, in the form the search runs against.
 *   <li>{@link SliceSpec} — a region of the body a point may be narrowed to, joined to a point by
 *       identifier rather than by position.
 *   <li>{@link LocalSpec} — one handler parameter bound to a local variable of the target.
 *   <li>{@link GroupSpec} — a bound on the matches of several declarations taken together.
 * </ul>
 *
 * <h2>The two sentinels</h2>
 *
 * <p>Two numbers here do not read the way they look, and both are deliberate.
 *
 * <p>{@link PointSpec#ordinal()} of {@code -1} keeps <em>every</em> match, which is what lets one
 * declaration weave several positions; {@code 0} and above select exactly one, counted within the
 * slice. Defaulting to {@code 0} would silently bind a declaration meant for all of them to the first.
 * A point used as a {@link SliceSpec} bound is the one place the default is {@code 0} instead, because
 * a bound has to resolve to exactly one position — {@link SliceSpec} refuses a bound that keeps every
 * match.
 *
 * <p>{@link InjectorSpec#allow()} of {@code 0} imposes no upper bound rather than permitting no match.
 * {@link InjectorSpec#require()} of {@code 0} genuinely requires none, and a specification parsed from
 * a class file carries it only when the author wrote it explicitly: a class file records only the
 * elements that were written, so an omitted {@code require} is distinguishable there and becomes
 * {@code 1}. The annotation processor's compile-time reading does not make that distinction and
 * records both as {@code 0}, and a caller building a specification directly gets no treatment either
 * — the value passed to the constructor is the value used.
 *
 * <h2>Refused on construction, or reported later</h2>
 *
 * <p>The records check what can be decided without a target, and they throw for it. A bound that no
 * count could satisfy — a negative {@code require} or {@code allow}, or a non-zero {@code allow} below
 * {@code require} — is an {@link IllegalArgumentException} from {@link InjectorSpec}, because
 * reporting it later would name a target that has nothing to do with the mistake. So are a
 * specification with no points at all, a blank identifier, and two slices of one declaration sharing an
 * identifier, which would leave a reference to that identifier with two answers.
 *
 * <p>Everything that depends on a target is a diagnostic instead. A selector matching no method is
 * {@code AW1020} and one matching several is {@code AW1021}; a point identifier nothing registered is
 * {@code AW1101}; a declaration matching fewer positions than it requires is {@code AW1043} and one
 * matching more than it allows is {@code AW1044}; a slice bound that cannot be located is
 * {@code AW1120} or {@code AW1121}, and a region running backwards is {@code AW1122}; a capture that
 * resolves to no live variable is {@code AW1050}, to more than one is {@code AW1051}, and one needing
 * a {@code LocalVariableTable} the target was compiled without is {@code AW1052}. A declaration whose
 * {@link InjectorKind} nothing registered an injector for is {@code AW4090} and is skipped — which is
 * what a user sees when the plugin owning a namespaced kind is missing from the classpath.
 *
 * <h2>Two ways a group silently checks nothing</h2>
 *
 * <p>A {@link GroupSpec} is checked only if it is declared. A declaration naming a group the weave
 * class does not declare has its matches added to a total nothing inspects, and its own
 * {@link InjectorSpec#require()} is skipped as well because it is in a group — so a misspelt group
 * name leaves that declaration entirely unaccounted, with no diagnostic. The converse holds too: a
 * group that no declaration joins is still checked, against a total of zero, which the default
 * {@link GroupSpec#min()} of one then fails.
 *
 * <h2>A kind is an identifier, not an enumeration</h2>
 *
 * <p>{@link InjectorKind} is a record rather than an enum so that a plugin can contribute a kind this
 * release never heard of and still be a first-class participant: the engine looks an injector up by
 * identifier alone and has no list of permitted values to extend. An unqualified identifier belongs to
 * the built-in namespace — which is why {@code inject}, {@code redirect} and {@code wrap} carry no
 * colon — and {@link InjectorKind#of(String)} refuses one outright for exactly that reason. A plugin's
 * {@link de.splatgames.aether.weaver.api.spi.InjectorFactory} offering a kind not prefixed with its
 * own namespace is reported as {@code AW3110} and the whole factory is dropped.
 *
 * <h2>Immutability</h2>
 *
 * <p>Everything here is a record, immutable, comparable by value and safe to share. Every list
 * component is copied on construction and the copy rejects a {@code null} element.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * InjectorSpec spec = new InjectorSpec(
 *         InjectorKind.INJECT,
 *         new HandlerRef(ClassDesc.of("com.acme.LedgerWeave"), "onCharge",
 *                 MethodTypeDesc.ofDescriptor("()V"), Set.of(AccessFlag.STATIC)),
 *         "charge(java.math.BigDecimal)",                       // the selector as written
 *         MemberSelector.parse("charge(java.math.BigDecimal)", MemberKind.METHOD),
 *         List.of(PointSpec.builtIn(Point.HEAD).build()),
 *         List.of(),                                            // no slices
 *         "audit-charge",
 *         1,                                                    // require: at least one match
 *         0,                                                    // allow: no upper bound
 *         "",                                                   // accounted on its own
 *         List.of());                                           // no captured locals
 *
 * spec.isBounded();      // false — allow is the no-upper-bound sentinel
 * spec.isInAGroup();     // false
 * spec.accepts(3);       // true
 * spec.accepts(0);       // false
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.model;
