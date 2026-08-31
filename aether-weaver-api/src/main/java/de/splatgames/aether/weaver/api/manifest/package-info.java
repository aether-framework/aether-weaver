/**
 * The weave manifest: the document an artefact carries so that its weaves can be found without
 * loading every class on the classpath.
 *
 * <p>One resource per classpath root, at {@code META-INF/aether/weaves.json}, listing the weave
 * classes that root declares, the classes they target, what each of them declares, and every extension
 * the root contributes. It is written once, at compile time, by the annotation processor, and read
 * much later — possibly by an older release than the one that wrote it — by whatever assembles a
 * weaving plan. Discovery looks for that exact path, so a manifest anywhere else is not found at all.
 *
 * <p>Three types are the whole of the surface. {@link WeaveManifest} is the parsed document,
 * {@link ManifestWriter} renders one and {@link ManifestReader} parses one; the two are inverses for
 * any manifest whose version this release reads. The JSON parser underneath is package-private and is
 * not part of what a caller may name.
 *
 * <h2>The document</h2>
 *
 * <p>The root is a JSON object with four keys, written in this order: {@code "version"}, the schema
 * version as an integer; {@code "generator"}, free text naming what produced the document, which is
 * carried through parsing and used in nothing else; {@code "weaves"}; and {@code "extensions"}. Every
 * nested object has a fixed key order too, given in full on {@link WeaveManifest}, which also carries a
 * complete rendered example.
 *
 * <p>The nesting mirrors what a weave declares. A weave entry holds its class, kind, priority,
 * require, phase, tags and targets, then its {@link WeaveManifest.Member} entries — every field and
 * method of the weave that is <em>not</em> a handler — and its {@link WeaveManifest.Injector} entries,
 * one per injection declaration, each holding its own {@link WeaveManifest.Point} entries. A handler is
 * recorded as an injector and never also as a member, so no method appears twice under two spellings
 * that could drift apart.
 *
 * <p>The strings inside a weave entry that spell out an enumeration constant — a weave's kind, require
 * and phase, a point's shift and access, an injector's kind — are carried as text and are not checked
 * against any enumeration here. A manifest may therefore name a constant this release does not know,
 * and what happens then is decided by whoever consumes the entry.
 * A {@link WeaveManifest.Extension} entry is the exception: its kind and its three policies are typed,
 * and {@link ManifestReader} decides what an unknown value means.
 *
 * <h2>Reading tolerantly</h2>
 *
 * <p>A manifest is read from whatever happens to be on the classpath, so reading is tolerant by
 * default and refusing by exception: an unknown field costs nothing, an unusable entry costs that
 * entry, and only a document that cannot be understood as a whole costs the whole document.
 *
 * <ul>
 *   <li><b>An unknown key is ignored</b> at every level — root, weave, member, injector, point and
 *       extension alike — with no diagnostic.
 *   <li><b>A key of the wrong JSON type is treated as absent.</b> Each field is read by type and falls
 *       back to its default otherwise, so a JSON {@code null} reads as absent too.
 *   <li><b>An array entry that is not a JSON object is dropped silently</b>, and so is an element of
 *       {@code "tags"} or {@code "targets"} that is not a string.
 *   <li><b>Omitting a key and writing its default are the same statement.</b> An entry that leaves out
 *       {@code "priority"} reads back equal to one that writes {@code 0}. The defaults are listed in
 *       full on {@link ManifestReader}.
 * </ul>
 *
 * <p>Two documents are refused whole, and {@link ManifestReader#read(String, String,
 * de.splatgames.aether.weaver.api.spi.Reporter)} then answers {@code null}: one that is not JSON or not
 * a JSON object, reported as {@code AW2300}, and one stating a {@code "version"} above
 * {@link WeaveManifest#VERSION}, reported as {@code AW2301}. Everything else is entry by entry: a
 * weave naming no class, an injector naming no handler or no target method, an extension missing part
 * of its identity, and an extension naming a {@code "kind"} this version does not know are each
 * {@code AW2300} against that entry alone. An extension naming a {@code "require"}, {@code "nulls"} or
 * {@code "scope"} this version does not know is also {@code AW2300} and is <em>kept</em>, with the
 * policy read as its default: a policy this release cannot enforce is one it can only decline to
 * apply, whereas an unknown kind would decide how a call site is rewritten.
 *
 * <p>Not everything comes back as a diagnostic. Three documents parse as JSON and then fail a record's
 * own invariant, and the resulting {@link IllegalArgumentException} propagates to the caller: a
 * version that narrows to a negative value, a member whose {@code "name"} is absent, not a string or
 * blank, and a point whose {@code "point"} is present and blank. A caller reading manifests off a
 * classpath it does not control should be prepared for that as well as for a {@code null} return.
 *
 * <p>Every diagnostic names the {@code origin} the reader was given, so a caller that passes the path
 * of the jar or directory being read makes the failing artefact identifiable. What a driver does about
 * a manifest it could not use is the driver's decision; the built-in discovery skips that artefact's
 * weaves and continues, so one stale library cannot switch off every weave in an application.
 *
 * <h2>What may change without breaking a reader</h2>
 *
 * <ul>
 *   <li><b>Adding a key</b> is compatible in both directions. An older reader ignores it, and a newer
 *       reader supplies its default when an older document omits it.
 *   <li><b>Removing a key</b> is compatible only where the reader's default for it means what the
 *       omitted value meant.
 *   <li><b>Adding a value to a field spelling out an enumeration constant</b> is compatible for the
 *       extension policies, which cost an {@code AW2300} and keep the entry, and is not compatible for
 *       an extension kind, which costs that entry.
 *   <li><b>Giving an existing key a new meaning</b> requires raising {@link WeaveManifest#VERSION},
 *       which costs every older reader the whole document.
 * </ul>
 *
 * <h2>Order, equality and determinism</h2>
 *
 * <p>Every list keeps the order it was given, the writer emits arrays in list order and iterates no
 * hash-ordered collection, and rendering consults nothing outside the manifest — no clock and no
 * environment. The same manifest therefore renders to the same bytes on every run and on every
 * machine, which is what allows a jar carrying one to be reproducible.
 *
 * <p>Order is part of equality as well. These are records, so equality compares every component of
 * every nested entry and list equality is order-sensitive: two manifests holding the same weaves in a
 * different order are not equal.
 *
 * <h2>Absent, and empty</h2>
 *
 * <p>An absent array and an empty one parse to the same empty list and cannot be told apart
 * afterwards. Nor does an empty manifest survive as a distinct state at all: the processor writes no
 * resource for a module that declares neither a weave nor an extension, so "no resource" is exactly
 * what such a module produces. A missing resource therefore does not distinguish a module that was
 * compiled without the processor from one that simply had nothing to say.
 *
 * <h2>Merging</h2>
 *
 * <p>{@link WeaveManifest#merge(WeaveManifest)} combines two manifests, letting the argument win, and
 * is how an incremental build keeps the weaves it did not recompile. Weaves are matched by class name.
 * Extensions are matched by holder class only, and at that granularity: if the argument declares any
 * extension on a holder, every extension the receiver declares on that same holder is dropped first.
 * All extensions of one holder therefore belong in one manifest.
 *
 * <h2>Immutability</h2>
 *
 * <p>{@link WeaveManifest} and all of its nested records are immutable. Every list component is copied
 * on construction, so a list handed in afterwards cannot be changed through the manifest and the
 * returned lists reject modification. Instances may be shared between threads without synchronisation,
 * and both {@link ManifestReader} and {@link ManifestWriter} are stateless and keep nothing between
 * calls.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * WeaveManifest manifest = WeaveManifest.of("acme-build/1.0",
 *         List.of(new WeaveManifest.Weave(
 *                 "com.acme.audit.PaymentAudit",
 *                 "INSTANCE", 100, "REQUIRED", "DEFAULT",
 *                 List.of("audit"),
 *                 List.of("com.acme.PaymentService"),
 *                 List.of(),
 *                 List.of(new WeaveManifest.Injector(
 *                         "INJECT", "onCharge",
 *                         "onCharge(Ljava/math/BigDecimal;)V",
 *                         "com.acme.PaymentService.charge(java.math.BigDecimal)",
 *                         List.of(new WeaveManifest.Point("HEAD", "", -1, "NONE", 0, "ANY", "")),
 *                         1, 0, "")))));
 *
 * String document = ManifestWriter.write(manifest);          // the text of the resource
 * WeaveManifest back = ManifestReader.read(document, "target/classes", Reporter.NOOP);
 * assert manifest.equals(back);                              // the two are inverses
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.manifest;
