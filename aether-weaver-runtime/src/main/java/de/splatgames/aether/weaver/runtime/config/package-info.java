/**
 * The configuration layer: what a run was asked to do, and which source asked for it.
 *
 * <p>Three shapes, and the difference between them is the whole design.
 * {@link de.splatgames.aether.weaver.runtime.config.ConfigLayer} is what one source said, with {@code null} kept
 * for a scalar that source did not mention; the two override maps are empty and the policy is
 * {@link de.splatgames.aether.weaver.runtime.config.PolicyConfig#STRICT} rather than {@code null} when the source
 * said nothing about them. {@link de.splatgames.aether.weaver.runtime.config.ConfigLayers} is
 * the ordered stack of those layers, which is what lets a run say who decided a setting rather than only what it
 * is. {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig} is the result of folding the stack and
 * applying the defaults once, at the end, and it is the only shape a driver is given.
 *
 * <p>A layer is deliberately not a {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig}. Substituting a
 * default for a component a source never mentioned would make silence indistinguishable from a deliberate
 * setting, and a higher layer's silence would then quietly undo the layer beneath it.
 *
 * <h2>Assembling one</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.runtime.config.ConfigParser} turns one source into one layer, and
 * precedence is the order of the {@link de.splatgames.aether.weaver.runtime.config.ConfigLayers#add(
 * java.lang.String, de.splatgames.aether.weaver.runtime.config.ConfigLayer)} calls rather than anything about a
 * source's name, which is used only in the report, is checked against nothing and need not be unique. The
 * load-time agent arranges it this way, so an argument given to one run beats a property set for the whole
 * machine:
 *
 * <pre>{@code
 * Reporter reporter = diagnostic -> System.out.println(diagnostic.code().code());
 *
 * ConfigLayers layers = ConfigLayers.of()
 *         .add("system properties", ConfigParser.ofSystemProperties(System.getProperties(), reporter))
 *         .add("agent arguments", ConfigParser.ofAgentArguments(arguments, reporter));
 *
 * WeaverConfig config = layers.resolve();
 * }</pre>
 *
 * <p>Three entry points read three sources, and each names its own source in the diagnostics {@code parse} raises
 * for it. That name is fixed by the entry point and is a separate thing from the one a setting is attributed to
 * in the report, which is whatever string the caller passed to {@code add}. One diagnostic is the exception: an
 * agent argument with no {@code =} is reported before {@code parse} is reached and names no source at all.
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.runtime.config.ConfigParser#ofProperties(java.util.Properties,
 *       de.splatgames.aether.weaver.api.spi.Reporter)} — every key carries the prefix, and one that does not is
 *       reported as {@code AW2310} rather than passed over. Diagnostics name it {@code weaver.properties}.
 *       Nothing here opens a file: the {@link java.util.Properties} object is the caller's to supply, and nothing
 *       in this project's own main sources calls this method.
 *   <li>{@link de.splatgames.aether.weaver.runtime.config.ConfigParser#ofSystemProperties(java.util.Properties,
 *       de.splatgames.aether.weaver.api.spi.Reporter)} — a key without the prefix is passed over in silence,
 *       because the system properties hold everything the JVM and the application ever set and complaining about
 *       them would bury this framework's own diagnostics. Diagnostics name it {@code system properties}.
 *   <li>{@link de.splatgames.aether.weaver.runtime.config.ConfigParser#ofAgentArguments(java.lang.String,
 *       de.splatgames.aether.weaver.api.spi.Reporter)} — comma-separated {@code key=value} pairs with the prefix
 *       implied; {@code null} or blank yields {@link de.splatgames.aether.weaver.runtime.config.ConfigLayer#EMPTY}.
 *       Diagnostics name it {@code agent arguments}.
 * </ul>
 *
 * <p>Both properties entry points read their keys in sorted order rather than in a hash table's, so two runs over
 * the same source report their problems in the same order. An argument string is read in the order the pairs
 * were written, but a fragment with no {@code =} is reported from that same left-to-right pass, before the
 * collected entries are parsed in map order — so for {@code enabled=ture,metrics} the diagnostic for
 * {@code metrics}, written second, comes out before the one for {@code enabled}, written first.
 *
 * <p>The comma separates pairs and nothing escapes it, so an agent argument's value cannot contain one. That
 * makes {@code tags.include=audit,metrics} parse as {@code tags.include=audit} followed by a fragment
 * {@code metrics} with no {@code =}, which is reported as {@code AW2310}; repeating the key instead does not
 * accumulate either, since the pairs are collected into a map and the last value given wins.
 *
 * <h2>The keys</h2>
 *
 * <p>Written below without the {@link de.splatgames.aether.weaver.runtime.config.ConfigParser#PREFIX}, which is
 * {@code aether.weaver.} everywhere except an agent argument string, where it is implied. Every key is flat, so
 * the same setting is written the same way wherever it is given, and a value is trimmed before it is read.
 *
 * <ul>
 *   <li>{@code enabled}, {@code explain} — {@code true} or {@code false}, without regard to case.
 *   <li>{@code verification} — the name of a
 *       {@link de.splatgames.aether.weaver.engine.verify.VerificationPolicy} constant, without regard to case.
 *   <li>{@code onError} — the name of a {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy} constant,
 *       without regard to case.
 *   <li>{@code phase} — the name of a {@link de.splatgames.aether.weaver.api.Phase} constant, without regard to
 *       case.
 *   <li>{@code dump} — the directory the original and woven bytes are written to.
 *   <li>{@code tags.include}, {@code tags.exclude} — comma-separated tag lists, which together describe one
 *       {@link de.splatgames.aether.weaver.runtime.config.TagFilter}. Exclusion is tested first and settles it:
 *       one excluded tag rejects a weave whatever else it carries. Failing that, an empty include list accepts
 *       everything and a non-empty one accepts only a weave sharing a tag with it — which makes a weave declaring
 *       no tags at all rejected as soon as anything is included by name.
 *   <li>{@code policy.allowSigned} — {@code true} or {@code false}, without regard to case.
 *   <li>{@code policy.allowPackage} — a comma-separated list of package names.
 *   <li>{@code weave[<binary name>].enabled}, {@code weave[<binary name>].priority} — a
 *       {@link de.splatgames.aether.weaver.runtime.config.WeaveOverride} for one weave class, as a boolean and an
 *       integer. The two components are independent: setting only the priority leaves the tag filter deciding
 *       whether that weave runs at all.
 *   <li>{@code injector[<name>].enabled} — an
 *       {@link de.splatgames.aether.weaver.runtime.config.InjectorOverride} for one injection.
 * </ul>
 *
 * <p>Whatever stands between the brackets becomes a map key verbatim and is compared by string equality against
 * the weave's binary name or the injection's name. It is never checked against anything that exists, so a
 * misspelt subject is not an error, draws no diagnostic, and simply never applies.
 *
 * <h2>How two layers combine</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.runtime.config.ConfigLayer#merge(
 * de.splatgames.aether.weaver.runtime.config.ConfigLayer)} does not treat every component the same way, and the
 * difference is what a deployment turns on. Reading "the higher layer wins" as the rule for all of it is wrong in
 * two places.
 *
 * <ul>
 *   <li><b>Each scalar</b> is taken from the higher layer unless that layer left it {@code null}. This includes
 *       the tag filter: a layer mentioning either tag key replaces the whole filter of the layers below rather
 *       than adding to it, because the merge treats it as one value. Within a single source the two tag keys do
 *       combine, whichever order they arrive in.
 *   <li><b>The two override maps</b> are merged key by key, and where both layers name the same weave or
 *       injection the two overrides are merged component-wise. A layer naming one weave therefore leaves
 *       everything said about the others intact, and a higher layer setting only a priority does not withdraw an
 *       {@code enabled} set below.
 *   <li><b>The relaxations in {@link de.splatgames.aether.weaver.runtime.config.PolicyConfig}</b> accumulate:
 *       {@code allowSigned} is the disjunction and the package names the union, so a relaxation granted below
 *       cannot be withdrawn from above. {@link de.splatgames.aether.weaver.runtime.config.PolicyConfig#STRICT} is
 *       also what a layer carries when its source said nothing about policy, and treating that as a revocation
 *       would let any silent layer undo a deliberate exception. Withdrawing a relaxation means removing the key
 *       that granted it.
 * </ul>
 *
 * <p>The operation is associative and {@link de.splatgames.aether.weaver.runtime.config.ConfigLayer#EMPTY} is its
 * identity from either side, so folding a stack gives the same result however it is grouped.
 *
 * <p>Two settings can be written out and still record nothing, which makes them indistinguishable from a source
 * that never mentioned them. A {@code dump} of {@code off}, or an empty one, leaves the layer with no directory,
 * so a directory named by a lower-precedence layer survives the merge; there is no way to write "dump nothing"
 * over a layer that asked for a dump. An explicit {@code policy.allowSigned=false}, with no
 * {@code policy.allowPackage} beside it in the same source, leaves the layer at
 * {@link de.splatgames.aether.weaver.runtime.config.PolicyConfig#STRICT}, which says nothing rather than
 * contradicting a layer below. Both are what
 * {@link de.splatgames.aether.weaver.runtime.config.ConfigLayer#saysNothing()} counts as silence.
 *
 * <h2>What is left when nobody configured anything</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.runtime.config.ConfigLayer#resolve()} applies the defaults to everything
 * unsaid: weaving on, {@link de.splatgames.aether.weaver.engine.verify.VerificationPolicy#STRICT},
 * {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL},
 * {@link de.splatgames.aether.weaver.api.Phase#DEFAULT},
 * {@link de.splatgames.aether.weaver.runtime.config.TagFilter#ALL}, no dump directory, no explain report and no
 * relaxed policy. {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#defaults()} is exactly that
 * resolution of an empty layer. Only the dump directory may still be {@code null} in the result.
 *
 * <h2>What a value that cannot be read does</h2>
 *
 * <p>{@code AW2310} is the only diagnostic this package raises, and it covers an unknown key, an unrecognised
 * setting after a known bracketed family, an agent argument with no {@code =}, a boolean that is neither
 * {@code true} nor {@code false}, a priority that is not an integer, and an enumerated setting given a value it
 * does not take. In each of those the setting is left unset rather than guessed at, so a lower-precedence layer
 * goes on deciding it and the default applies if none did. Nothing coerces: {@code enabled=ture} is reported, not
 * read as {@code false}. An unknown key is answered with the nearest of the non-bracketed keys where one is
 * within three edits and with nothing where none is, ties going to the alphabetically first so that the same typo
 * always draws the same suggestion.
 *
 * <p>A {@code dump} value is the one exception, and it does not fail quietly. It is handed to
 * {@link java.nio.file.Path#of(java.lang.String, java.lang.String...)} uncaught, so a value naming no valid path
 * throws {@link java.nio.file.InvalidPathException} out of the parser: nothing is reported, nothing is ignored,
 * and every entry after it in that source goes unread along with its own diagnostics. A caller reading a source
 * it does not control should be prepared for that as well as for a layer full of {@code null}s.
 *
 * <h2>Attribution</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.runtime.config.ConfigLayers#settings()} reports eight settings, as
 * {@link de.splatgames.aether.weaver.engine.explain.ExplainReport.Setting} values — {@code enabled},
 * {@code verification}, {@code onError}, {@code phase}, {@code tags}, {@code dump}, {@code explain} and
 * {@code policy.allowSigned} — in a fixed order, so two runs' reports can be compared line by line. Each carries
 * the value the resolved configuration uses and the name of the highest layer that said anything about it, or
 * {@code default} when none did, which is the same rule the merge applies read from the other end.
 * {@code policy.allowSigned} is credited only to a layer that switched it on, since a layer that said nothing at
 * all also carries {@code false}. The name of an entry is not always a configuration key: the two tag keys are
 * reported as one {@code tags} entry.
 *
 * <p>Everything outside those eight is still resolved and still in force, and simply not attributed: the
 * per-weave and per-injection overrides, and the package names from {@code policy.allowPackage}.
 * {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#summary()} is the one line a driver prints
 * instead. It always names the state of weaving, the verification policy, the error policy and the phase; it adds
 * the tag filter, the dump directory and the explain flag only where they are not at their defaults; and it omits
 * the two override maps altogether. Two things in it are shouted rather than spelled normally, because a run that
 * weaves nothing and a run with a safety rule relaxed are the two an operator must not read past: weaving being
 * off reads {@code DISABLED}, and a policy that is not strict appends {@code POLICY RELAXED} and the policy.
 *
 * <h2>Which settings anything acts on</h2>
 *
 * <p>Resolving a setting and acting on one are different things, and not every key this package resolves is read
 * by anything.
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#isEnabled(java.lang.String,
 *       java.util.Set)} is called by {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery}, which is on both
 *       runtime drivers' path. It is where the per-weave override and the tag filter meet: an override naming the
 *       weave and setting {@code enabled} decides it in whichever direction that flag points, and the filter
 *       decides wherever no override names the weave or the one that does sets only a priority.
 *   <li>{@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#policy()} is acted on in one place,
 *       {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader}, and only for its {@code allowSigned}
 *       component: without it a class from a signed artefact is reported as {@code AW3002} and defined unwoven.
 *   <li>The verification policy and the explain flag are handed to the weaver both runtime drivers build. The
 *       dump directory decides whether the original and woven bytes are written beside each class, and the
 *       error policy decides what becomes of a class whose handling threw.
 *   <li>{@code enabled} is read by the load-time agent, which installs no transformer when it is {@code false}.
 *       {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} does not consult it, so switching weaving
 *       off does not disarm that driver.
 *   <li>Nothing in this project's main sources calls
 *       {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#priorityOf(java.lang.String)} or
 *       {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#isInjectionEnabled(java.lang.String)}, and
 *       nothing selects weaves by {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig#phase()}. Those
 *       keys parse, merge and resolve; the phase is reported in the settings and the summary, and the other two
 *       are reported nowhere.
 *   <li>The package names in {@link de.splatgames.aether.weaver.runtime.config.PolicyConfig#allowPackages()}
 *       decide nothing on their own. A non-empty set makes the policy count as relaxed, which is what puts
 *       {@code POLICY RELAXED} and the policy itself into the summary.
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><b>No source is found on this package's own initiative.</b> No file is opened, no environment variable is
 *       read and the system properties are not fetched; every source arrives as an argument.
 *   <li><b>No setting is validated against the run it configures.</b> A weave's binary name, an injection's name
 *       and a tag are matched by string equality alone: no pattern, no case folding, and no diagnostic for one
 *       that matches nothing.
 *   <li><b>No weaving decision is made here.</b>
 *       {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy} in particular is not handed to the weaver
 *       by anything: it governs the failure path afterwards, and the failure is reported as {@code AW4090} before
 *       the policy is consulted, so the diagnostic reaches the listener whichever constant is in force.
 *   <li><b>Nothing here is mutable except the builder.</b>
 *       {@link de.splatgames.aether.weaver.runtime.config.ConfigLayer},
 *       {@link de.splatgames.aether.weaver.runtime.config.ConfigLayers} and the records are immutable, and each
 *       one holding a collection takes an unmodifiable copy of it. Only
 *       {@link de.splatgames.aether.weaver.runtime.config.ConfigLayer.Builder} accumulates, and it is not
 *       thread-safe. It is not spent by building: it may be added to and built again, which is what lets the
 *       parser read back the tag filter accumulated so far part-way through a source.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.runtime.config;
