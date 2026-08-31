package de.splatgames.aether.weaver.perf;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class WeavingBenchmark {

    private static final int WEAVE_COUNT = 100;

    private Weaver weaver;

    private byte[] target;

    private byte[] unmatched;

    private List<WeaveClass> hundred;

    public WeavingBenchmark() {
        // State is built in setUp, which JMH calls outside the measured region.
    }

    @Setup(Level.Trial)
    public void setUp() {
        this.weaver = Weaver.builder()
                .weaves(List.of(weave("bench.Audit", "bench.Target", 0)))
                .build();
        this.target = compiled("bench.Target");
        this.unmatched = compiled("bench.Unmatched");

        this.hundred = new ArrayList<>(WEAVE_COUNT);
        for (int i = 0; i < WEAVE_COUNT; i++) {
            this.hundred.add(weave("bench.Audit" + i, "bench.Target" + i, i));
        }
    }

    @Benchmark
    public void applyNoMatch(final Blackhole blackhole) {
        blackhole.consume(this.weaver.weave("bench/Unmatched", this.unmatched));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void applySingleInjection(final Blackhole blackhole) {
        // A fresh weaver per invocation would measure planning; the same one would meet the
        // idempotence gate only if the input were stamped, and it is not — the original bytes are
        // handed over each time, so this measures the apply path and nothing else.
        blackhole.consume(this.weaver.weave("bench/Target", this.target));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void prepare100Weaves(final Blackhole blackhole) {
        blackhole.consume(Weaver.builder().weaves(this.hundred).build());
    }

    private static WeaveClass weave(final String name, final String target, final int priority) {
        final InjectorSpec spec = new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of(name), "onWork",
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of(AccessFlag.STATIC)),
                "work()", MemberSelector.parse("work()"),
                List.of(PointSpec.builtIn(Point.HEAD).build()), List.of(),
                "onWork", 0, 0, "", List.of());

        return new WeaveClass(ClassDesc.of(name),
                List.of(TargetRef.ofClassLiteral(ClassDesc.of(target))),
                Weave.Kind.STATIC, priority, Require.REQUIRED, Phase.DEFAULT,
                Set.of(), List.of(), List.of(), List.of(spec), Origin.of("benchmark", null));
    }

    private static byte[] compiled(final String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.return_()));
    }
}
