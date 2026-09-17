package com.saaspaymentsolutions.axion.dependencies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.D8Command;

import org.junit.Test;

import java.lang.reflect.Method;

public class D8ParallelismTunerTest {
    @Test
    public void estimatesR8Defaults() {
        assertEquals(1, D8ParallelismTuner.estimateR8DefaultThreads(1));
        assertEquals(2, D8ParallelismTuner.estimateR8DefaultThreads(2));
        assertEquals(2, D8ParallelismTuner.estimateR8DefaultThreads(4));
        assertEquals(4, D8ParallelismTuner.estimateR8DefaultThreads(8));
        assertEquals(6, D8ParallelismTuner.estimateR8DefaultThreads(12));
        assertEquals(8, D8ParallelismTuner.estimateR8DefaultThreads(16));
    }

    @Test
    public void experimentalPolicyUsesUpToEightThreads() {
        assertEquals(2, D8ParallelismTuner.chooseExperimentalThreads(2));
        assertEquals(4, D8ParallelismTuner.chooseExperimentalThreads(4));
        assertEquals(8, D8ParallelismTuner.chooseExperimentalThreads(8));
        assertEquals(8, D8ParallelismTuner.chooseExperimentalThreads(12));
    }

    @Test
    public void appliesThreadCountToR8BuilderWhenSupported() throws Exception {
        D8Command.Builder builder = D8Command.builder();
        D8ParallelismTuner.Result result = D8ParallelismTuner.apply(builder);
        Method getter = builder.getClass().getSuperclass().getDeclaredMethod("getThreadCount");
        getter.setAccessible(true);
        int actual = (Integer) getter.invoke(builder);
        if (result.applied) {
            assertEquals(result.effectiveThreads, actual);
            assertTrue(actual >= result.estimatedDefaultThreads);
        } else {
            assertEquals(-1, actual);
        }
    }
}
