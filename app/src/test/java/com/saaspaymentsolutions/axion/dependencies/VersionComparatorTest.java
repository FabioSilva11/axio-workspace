package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VersionComparatorTest {

    private int compare(String left, String right) {
        return VersionComparator.INSTANCE.compare(left, right);
    }

    @Test
    public void testNumericSegmentsCompareNumerically() {
        assertTrue(compare("4.10", "4.9") > 0);
        assertTrue(compare("4.12", "4.10") > 0);
        assertTrue(compare("2.9.0", "2.10.0") < 0);
        assertTrue(compare("1.0", "1.0") == 0);
    }

    @Test
    public void testQualifiersOrderBelowRelease() {
        assertTrue(compare("1.0-alpha", "1.0") < 0);
        assertTrue(compare("1.0-beta", "1.0-alpha") > 0);
        assertTrue(compare("1.0-rc1", "1.0-beta") > 0);
        assertTrue(compare("1.0-SNAPSHOT", "1.0") < 0);
        assertTrue(compare("1.0.0-rc1", "1.0.0") < 0);
    }

    @Test
    public void testTrailingZerosAreEqual() {
        assertEquals(0, compare("1.0", "1.0.0"));
        assertEquals(0, compare("2.0.0", "2.0"));
    }

    @Test
    public void testRealWorldVersions() {
        assertTrue(compare("2.11.0", "2.9.0") > 0);
        assertTrue(compare("1.6.1", "1.5.1") > 0);
        assertTrue(compare("33.0.0", "32.5.0") > 0);
        assertTrue(compare("4.12.0", "4.10.0") > 0);
    }

    @Test
    public void testDeterministicOrdering() {
        List<String> versions = Arrays.asList("1.0", "2.0", "1.5", "1.10", "1.9");
        versions.sort(VersionComparator.INSTANCE);
        assertEquals(Arrays.asList("1.0", "1.5", "1.9", "1.10", "2.0"), versions);
    }

    @Test
    public void testNullHandling() {
        assertTrue(compare(null, "1.0") < 0);
        assertTrue(compare("1.0", null) > 0);
        assertEquals(0, compare(null, null));
    }
}
