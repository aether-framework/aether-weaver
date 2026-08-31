package de.splatgames.aether.weaver.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the weave classes that a {@link Weaving} parameter is planned from.
 *
 * <p>Read by {@link WeaverExtension} at the moment it resolves a {@link Weaving} parameter, and by
 * nothing else. It has no effect on a test that never asks for a {@link Weaving}.
 *
 * <h2>Which declaration wins</h2>
 *
 * <p>The extension walks outwards from the context the parameter belongs to — the test method,
 * then its class, then each enclosing class — and stops at the first element carrying this
 * annotation. A declaration on a test method therefore <em>replaces</em> the one on its class
 * rather than adding to it, which is how one test asks for a single weave out of the set the rest
 * of the class uses. Because {@link Inherited} is in force, a test class also carries the
 * declaration of its superclass.
 *
 * <h2>When resolution fails instead</h2>
 *
 * <p>The extension throws a {@link org.junit.jupiter.api.extension.ParameterResolutionException}
 * when the walk reaches the top without finding a declaration, and again when the first
 * declaration it finds is {@code @Weaves({})}. An empty set is refused rather than planned,
 * because a weaver with nothing planned weaves nothing and every assertion that nothing was
 * applied would then pass.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @ExtendWith(WeaverExtension.class)
 * @Weaves(AuditWeave.class)
 * class LedgerWeavingTest {
 *
 *     @Test
 *     void auditApplies(Weaving weaving) {
 *         assertThatWoven(weaving.weave(Ledger.class)).wasWoven();
 *     }
 *
 *     @Test
 *     @Weaves(TotalWeave.class)      // this method sees TotalWeave alone, not AuditWeave as well
 *     void totalsOnly(Weaving weaving) {
 *         assertThatWoven(weaving.weave(Ledger.class)).satisfiesEveryInvariant();
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaverExtension
 * @see Weaving#of(Class[])
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Weaves {

    /**
     * The weave classes to plan.
     *
     * <p>Each is passed to {@link Weaving#of(Class[])}, which reads its class file from its own
     * class loader and refuses anything that does not parse as a weave. The array must not be
     * empty; an empty one is reported as a failed parameter resolution rather than producing a
     * weaver that plans nothing.
     *
     * @return the weave classes, in the order they are handed to the planner
     */
    Class<?>[] value();
}
