package com.kinetic.molang.runtime;

import com.kinetic.molang.MolangEngine;
import com.kinetic.molang.parser.ast.Expression;
import com.kinetic.molang.runtime.binding.ObjectBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MolangEvaluationBenchmarkTest {
    private static final String SOURCE = "query.life_time * 0.5 + 2.0";
    private static final int PARSE_WARMUP = 2_000;
    private static final int PARSE_ITERATIONS_PER_BATCH = 20_000;
    private static final int EVALUATE_WARMUP = 100_000;
    private static final int EVALUATE_ITERATIONS_PER_BATCH = 200_000;
    private static final int BATCHES = 5;

    @Test
    void evaluatesDynamicQueryBindingFromCurrentContext() throws Exception {
        var input = new Inputs();
        var expression = engine().parse(SOURCE).get(0);
        var evaluator = ExpressionEvaluator.evaluator(input);

        input.lifeTime = 2.0f;
        assertEquals(3.0f, ((Number) evaluator.evalSingleExpressionUnsafe(expression)).floatValue(),
                0.000001f);
        input.lifeTime = 4.0f;
        assertEquals(4.0f, ((Number) evaluator.evalSingleExpressionUnsafe(expression)).floatValue(),
                0.000001f);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KINETIC_BENCH_MOLANG", matches = "1")
    void measuresParsingAndRepeatedEvaluation() throws Exception {
        var engine = engine();
        var input = new Inputs();
        var expression = engine.parse(SOURCE).get(0);
        var evaluator = ExpressionEvaluator.evaluator(input);

        System.out.println("=== KINETIC Molang diagnostic benchmark ===");
        System.out.println("cpu             = " + cpuModel());
        System.out.println("java            = " + System.getProperty("java.version"));
        System.out.println("os              = " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " / " + System.getProperty("os.arch"));
        System.out.println("expression      = " + SOURCE);
        measureParsing(engine);
        measureEvaluation(expression, evaluator, input);
    }

    private static void measureParsing(MolangEngine engine) throws Exception {
        for (var i = 0; i < PARSE_WARMUP; i++) {
            engine.parse(SOURCE);
        }

        var allocatedBefore = currentThreadAllocatedBytes();
        var batches = new double[BATCHES];
        var parsedCount = 0;
        for (var batch = 0; batch < BATCHES; batch++) {
            var start = System.nanoTime();
            for (var i = 0; i < PARSE_ITERATIONS_PER_BATCH; i++) {
                parsedCount += engine.parse(SOURCE).size();
            }
            batches[batch] = (double) (System.nanoTime() - start)
                    / PARSE_ITERATIONS_PER_BATCH;
        }
        var allocatedAfter = currentThreadAllocatedBytes();
        var totalIterations = (long) PARSE_ITERATIONS_PER_BATCH * BATCHES;
        System.out.printf("parse            = %8.1f ns/expression | %7.1f B/expression"
                        + " | batch ns [%s] | sink=%d%n",
                average(batches), bytesPerOperation(allocatedBefore, allocatedAfter, totalIterations),
                format(batches), parsedCount);
    }

    private static void measureEvaluation(Expression expression,
                                         ExpressionEvaluator<Inputs> evaluator,
                                         Inputs input) {
        for (var i = 0; i < EVALUATE_WARMUP; i++) {
            input.lifeTime = i & 255;
            evaluator.evalSingleExpressionUnsafe(expression);
        }

        var allocatedBefore = currentThreadAllocatedBytes();
        var batches = new double[BATCHES];
        var sink = 0.0f;
        for (var batch = 0; batch < BATCHES; batch++) {
            var start = System.nanoTime();
            for (var i = 0; i < EVALUATE_ITERATIONS_PER_BATCH; i++) {
                input.lifeTime = (i + batch) & 255;
                sink += ((Number) evaluator.evalSingleExpressionUnsafe(expression)).floatValue();
            }
            batches[batch] = (double) (System.nanoTime() - start)
                    / EVALUATE_ITERATIONS_PER_BATCH;
        }
        var allocatedAfter = currentThreadAllocatedBytes();
        var totalIterations = (long) EVALUATE_ITERATIONS_PER_BATCH * BATCHES;
        System.out.printf("evaluate         = %8.1f ns/expression | %7.1f B/expression"
                        + " | batch ns [%s] | sink=%.1f%n",
                average(batches), bytesPerOperation(allocatedBefore, allocatedAfter, totalIterations),
                format(batches), sink);
    }

    private static double average(double[] values) {
        var total = 0.0;
        for (var value : values) {
            total += value;
        }
        return total / values.length;
    }

    private static String format(double[] values) {
        var result = new StringBuilder();
        for (var value : values) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append(String.format("%.1f", value));
        }
        return result.toString();
    }

    private static double bytesPerOperation(long before, long after, long operations) {
        return before < 0 || after < before ? Double.NaN : (double) (after - before) / operations;
    }

    private static long currentThreadAllocatedBytes() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return -1;
        }
        try {
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
            return allocationBean.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (SecurityException | UnsupportedOperationException ignored) {
            return -1;
        }
    }

    private static String cpuModel() {
        var processorIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        return processorIdentifier == null || processorIdentifier.isBlank()
                ? System.getProperty("os.arch")
                : processorIdentifier;
    }

    private static MolangEngine engine() {
        ObjectBinding query = name -> "life_time".equals(name)
                ? (Variable) context -> ((Inputs) context.entity()).lifeTime
                : null;
        ObjectBinding root = name -> "query".equals(name) ? query : null;
        return MolangEngine.fromCustomBinding(root);
    }

    private static final class Inputs {
        private float lifeTime;
    }
}
