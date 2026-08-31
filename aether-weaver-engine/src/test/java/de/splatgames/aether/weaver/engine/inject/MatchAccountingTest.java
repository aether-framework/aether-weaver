package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchAccountingTest {

    private static final ClassDesc OWNER = ClassDesc.of("accounting.Weave");

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    @Test
    @DisplayName("AW1044 — matching more than allow permits is refused")
    void matchingMoreThanAllowedIsRefused() {
        assertThat(check(spec(1, 1), 3))
                .as("an unsatisfied bound stops the weave rather than being noted")
                .isFalse();

        assertThat(codes())
                .as("""
                        The only place AW1044 is reported, and nothing asserted it. `allow` \
                        exists so that a target gaining a second matching call is an error rather \
                        than a silent doubling of whatever the handler does — an upper bound that \
                        never fires is indistinguishable from no upper bound at all.""")
                .containsExactly("AW1044");
        assertThat(this.reported.getFirst().message())
                .as("the author needs both numbers to decide whether to narrow or to raise it")
                .contains("matched 3").contains("at most 1");
    }

    @Test
    @DisplayName("matching exactly what allow permits is not refused")
    void matchingTheBoundExactlyIsAccepted() {
        assertThat(check(spec(1, 2), 2)).isTrue();

        assertThat(this.reported)
                .as("the boundary case, without which the test above would pass against an "
                        + "accounting that refused every count above zero")
                .isEmpty();
    }

    @Test
    @DisplayName("an unbounded injection may match as much as it likes")
    void anUnboundedInjectionIsNeverTooMany() {
        assertThat(check(spec(1, 0), 12)).isTrue();

        assertThat(this.reported)
                .as("allow is opt-in; leaving it out means 'however many', not 'none'")
                .isEmpty();
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private boolean check(final InjectorSpec spec, final int matched) {
        final Map<InjectorSpec, Integer> counts = new LinkedHashMap<>();
        counts.put(spec, matched);
        return MatchAccounting.check(counts, List.<GroupSpec>of(), this.reporter);
    }

    private static InjectorSpec spec(final int require, final int allow) {
        return new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(OWNER, "onWork", MethodTypeDesc.of(ConstantDescs.CD_void),
                        Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                "accounting", require, allow, "", List.<LocalSpec>of());
    }
}
