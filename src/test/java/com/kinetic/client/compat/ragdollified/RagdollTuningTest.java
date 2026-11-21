package com.kinetic.client.compat.ragdollified;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RagdollTuningTest {
    @Test
    void readsIntegerAndDecimalValuesFromConfigSuppliers() {
        assertEquals(480, RagdollTuning.readConfigNumber((Supplier<?>) () -> 480).intValue());
        assertEquals(96.5, RagdollTuning.readConfigNumber((Supplier<?>) () -> 96.5).doubleValue(), 1.0e-9);
    }

    @Test
    void ignoresMissingNonnumericAndFailingConfigValues() {
        assertNull(RagdollTuning.readConfigNumber(null));
        assertNull(RagdollTuning.readConfigNumber((Supplier<?>) () -> "not numeric"));
        assertNull(RagdollTuning.readConfigNumber((Supplier<?>) () -> {
            throw new IllegalStateException("config not loaded");
        }));
    }
}
