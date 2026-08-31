package de.splatgames.aether.weaver.perf;

import com.sun.management.ThreadMXBean;
import de.splatgames.aether.weaver.engine.Weaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NoMatchAllocationTest {

    private static final int CALLS = 200_000;

    private static final double TOLERANCE = 0.1;

    private static volatile Object sink;

    @Nested
    @DisplayName("the no-match path")
    class NoMatch {

        @Test
        @DisplayName("the byte[] overload — the agent's hot path — allocates nothing")
        void theAgentPathAllocatesNothing() {
            final Weaver weaver = weaver();
            final byte[] bytes = compiled("bench.Unmatched");

            assertThat(bytesPerCall(() -> {
                weaver.weave("bench/Unmatched", bytes);
                return 0L;
            }))
                    .as("this is what a ClassFileTransformer calls for every class an application "
                            + "loads; a capturing lambda here cost 16 bytes each and was found by "
                            + "this measurement rather than by anything failing")
                    .isLessThan(TOLERANCE);
        }

        @Test
        @DisplayName("the supplier overload allocates nothing either")
        void theSupplierPathAllocatesNothing() {
            final Weaver weaver = weaver();
            final byte[] bytes = compiled("bench.Unmatched");
            final Weaver.ByteSupplier supplier = () -> bytes;

            assertThat(bytesPerCall(() -> {
                weaver.weave("bench/Unmatched", supplier);
                return 0L;
            })).isLessThan(TOLERANCE);
        }

        @Test
        @DisplayName("counter-probe: the measurement notices an allocation of one small object")
        void theMeasurementHasTeeth() {
            assertThat(bytesPerCall(() -> {
                sink = new long[1];
                return 1L;
            }))
                    .as("without this, a measurement that always answered zero would pass both "
                            + "tests above and defend nothing")
                    .isGreaterThan(TOLERANCE);
        }
    }

    // -------------------------------------------------------------------------------------

    private static double bytesPerCall(final LongSupplier call) {
        final ThreadMXBean threads = threads();
        final long id = Thread.currentThread().threadId();

        // Warm up first: the interpreter allocates where compiled code does not, and measuring
        // before the JIT has seen the loop would measure the interpreter.
        long sink = 0;
        for (int i = 0; i < CALLS; i++) {
            sink += call.getAsLong();
        }

        final long before = threads.getThreadAllocatedBytes(id);
        for (int i = 0; i < CALLS; i++) {
            sink += call.getAsLong();
        }
        final long after = threads.getThreadAllocatedBytes(id);

        assertThat(sink).as("the loop must not be optimised away entirely").isNotNegative();
        return (after - before) / (double) CALLS;
    }

    private static ThreadMXBean threads() {
        // com.sun.management, not java.lang.management: per-thread allocation counting is a HotSpot
        // extension. A JVM without it skips this test rather than pretending to measure.
        //
        // JUnit's assumptions, not AssertJ's. AssertJ builds its assumptions from Byte Buddy
        // proxies, and Byte Buddy is excluded from every classpath in this project — a bytecode
        // framework has no business carrying a second bytecode library. The exclusion announced
        // itself here as a NoClassDefFoundError, which is exactly what it is for.
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean,
                "per-thread allocation counting is a HotSpot extension");
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation measurement is switched off in this JVM");
        return bean;
    }

    private static Weaver weaver() {
        return Weaver.builder().build();
    }

    private static byte[] compiled(final String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.return_()));
    }
}
