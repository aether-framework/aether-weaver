/**
 * The vocabulary of a failing build: the codes, the messages that carry them, and the exception thrown
 * when weaving is abandoned.
 *
 * <p>Everything Aether Weaver has to say about a run arrives as a {@link Diagnostic}. The annotation
 * processor turns one into a message on the compiler's messager, the engine hands one to the
 * {@link de.splatgames.aether.weaver.api.spi.DiagnosticListener} its driver installed, and a build
 * plugin prints one. The engine writes to no stream of its own, so what a user sees is entirely a
 * function of what the driver does with these objects.
 *
 * <h2>A code is the stable part</h2>
 *
 * <p>A message is written per reporting site and changes with the class being woven; a code identifies
 * the condition and does not. {@link DiagnosticCode} is the catalogue of the conditions the framework
 * itself reports, one constant per code, and a code is never reused for a different condition and
 * never renumbered. This is why every constraint stated elsewhere in this API names its code: a user
 * reading {@code AW1043} in build output has a string that appears in one place on earth, and
 * {@link DiagnosticCode#of(String)} turns it back into the catalogue entry that explains it.
 *
 * <p>Two wire forms exist and they are disjoint by construction. A {@link DiagnosticCode} is
 * {@code AW} followed by exactly four digits and contains no colon; a {@link PluginDiagnosticId} is
 * always {@code namespace:IDENTIFIER}. {@link DiagnosticCode#of(String)} therefore answers empty for
 * every plugin code, and a reader can tell the two apart without a lookup.
 *
 * <p>{@link DiagnosticId} is what the two have in common and what {@link Diagnostic} is built from: a
 * code, a default severity, a category and a one-line summary.
 *
 * <h2>Reading a code before looking it up</h2>
 *
 * <p>{@link DiagnosticCode#category()} follows from the four digits read as a number, in the fixed
 * ranges tabulated on {@link DiagnosticCode} itself. That is what lets a reader place a code at a
 * glance, and it is why a new condition is numbered into the block that already holds its kind rather
 * than appended at the end. Everything below {@code 1100} is
 * {@link DiagnosticCode.Category#DECLARATION} — the shape of a weave class, its members and its
 * handlers, which is the largest category and the one most diagnostics a user meets belong to — the
 * block from {@code 1100} to {@code 1199} is {@link DiagnosticCode.Category#INJECTION_POINT}, and
 * everything from {@code 4000} upwards is {@link DiagnosticCode.Category#ENGINE}. The categories are
 * shared with {@link PluginDiagnosticId}, so a plugin's condition is grouped with the framework's
 * conditions of the same kind rather than into a category of its own.
 *
 * <p>Five constants are named {@code RESERVED_} followed by their number and summarised as
 * {@code (reserved)}. They hold a number against reuse; no code reports them and no build can produce
 * one.
 *
 * <h2>Severity, and what it decides</h2>
 *
 * <p>{@link Severity} has four constants, declared from least to most serious, and that order is the
 * whole of their comparison contract: {@link Severity#isAtLeast(Severity)} compares
 * {@link Enum#ordinal()}. {@link Severity#isSuppressible()} is derived rather than declared — true for
 * everything except {@link Severity#ERROR} — and it is the single place that rule lives; the
 * {@code isSuppressible} methods on {@link DiagnosticId}, {@link DiagnosticCode} and
 * {@link Diagnostic} all delegate to it. Nothing outside those delegations calls it and nothing acts
 * on the answer, so no driver reads it to filter, silence or threshold a report.
 *
 * <p>What a severity does decide is what a driver makes of the run. A reporting site may raise or
 * lower the severity of one report with {@link Diagnostic.Builder#severity(Severity)}, and
 * {@link Diagnostic#isSuppressible()} then follows the severity the diagnostic actually carries rather
 * than the code's default; what the catalogue declares for the code itself is unchanged.
 *
 * <h2>Building one</h2>
 *
 * <p>{@link Diagnostic#of(DiagnosticId, String)} is a code and a message. {@link Diagnostic#builder(DiagnosticId)}
 * adds what makes a report actionable: a severity override, a {@link Location}, any number of detail
 * lines, and a remedy. Only the code has no default. The severity falls back to
 * {@link DiagnosticId#defaultSeverity()} and the message to {@link DiagnosticId#summary()} — a summary
 * describes the condition in general and names no class, member or position, so a report that keeps
 * the default says markedly less than one that says what happened here; the fallback exists so that a
 * report is never blank.
 *
 * <p>Details accumulate rather than replace, and are what a reader acts on: the candidates that were
 * considered, the descriptors that did not match, the variables that were live at a position. A remedy
 * is one line of prose saying what to write instead, and it is the only part that answers that
 * question.
 *
 * <p>{@link Diagnostic#format()} renders the whole of it — code, position, message, one indented line
 * per detail, and the remedy last — which is what a build log should print.
 * {@link Diagnostic#toString()} is deliberately shorter and stays on one line.
 *
 * <h2>Saying where</h2>
 *
 * <p>{@link Location} carries up to three independent descriptions of one place: a source position
 * that an editor can jump to, the weave class and handler at fault, and the target class and method
 * being changed. All three may be set at once, and {@link Location#format()} then renders exactly one
 * of them, by a fixed precedence: source position first, then target, then weave, and failing all
 * three the literal text {@code <unknown location>}. Nothing is validated — this is a carrier for text
 * a reporting site already has.
 *
 * <h2>When weaving is abandoned</h2>
 *
 * <p>Most conditions are reported and the run continues: the class is either woven or left alone, and
 * what an error means for the build is the driver's decision. {@link WeaveException} is for the cases
 * where continuing would produce a class that must not exist. It carries the diagnostics that explain
 * the refusal, offers {@link WeaveException#errors()} and {@link WeaveException#hasCode(DiagnosticCode)}
 * for acting on them, and {@link WeaveException#report()} for printing them.
 *
 * <p>It extends {@link RuntimeException} because it is thrown from inside class transformation, where
 * the call stack belongs to the JVM or to a build plugin. Its diagnostics are held in a
 * {@code transient} field with no {@code readObject} and no initialiser, so an instance restored from
 * a stream carries its message and its cause and nothing else; a consumer that needs the detail across
 * a process boundary serialises {@link WeaveException#report()} instead.
 *
 * <h2>What a plugin does here</h2>
 *
 * <p>A plugin defining conditions of its own constructs a {@link PluginDiagnosticId} rather than
 * reusing a framework code or writing a fresh {@link DiagnosticId} implementation. The record enforces
 * the shapes that keep the two code spaces apart — a namespace matching {@code [a-z][a-z0-9-]*}, an
 * identifier matching {@code [A-Z][A-Z0-9_]*}, a non-blank summary — and refuses the namespace
 * {@code aether} outright. The engine reports a plugin claiming a reserved namespace as
 * {@code AW3101} and a malformed one as {@code AW3100}; a contribution registered under a namespace
 * other than the plugin's own is {@code AW3110}.
 *
 * <p>An implementation written by hand instead must satisfy the same rules and, in addition, be pure,
 * never return {@code null}, compare by value, and carry a non-blank summary.
 * {@link Diagnostic#equals(Object)} compares codes with {@link Object#equals(Object)} rather than with
 * {@code ==} precisely so that two separately constructed identities describing the same condition
 * produce equal reports.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // Turning a code read out of build output back into its catalogue entry.
 * DiagnosticCode.of("AW1043")
 *         .ifPresent(code -> System.out.println(code.category() + ": " + code.summary()));
 * // prints: DECLARATION: No injection point matched
 *
 * // Reporting one, from inside an injector or an injection point.
 * reporter.report(Diagnostic.builder(DiagnosticCode.TOO_MANY_INJECTION_POINTS)
 *         .message("onCharge matched 3 positions in charge(BigDecimal), and allows at most 1")
 *         .location(Location.builder()
 *                 .weave("com.acme.AuditWeave", "onCharge")
 *                 .target("com.acme.Ledger", "charge")
 *                 .build())
 *         .detail("injection: audit-charge")
 *         .remedy("narrow it with an ordinal or a slice")
 *         .build());
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.diagnostic;
