package com.thinkaurelius.titan.diskstorage.util;

import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreFeatures;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class StorageFeaturesTest {

    @Test
    public void testFeaturesImplementation() {
        StoreFeatures features;
        try {
            features = new StoreFeatures();
            features.supportsBatchMutation();
            fail();
        } catch (IllegalArgumentException e) {
        }

        try {
            features = new StoreFeatures();
            features.hasLocalKeyPartition = true;
            features.hasLocalKeyPartition();
            fail();
        } catch (IllegalArgumentException e) {
        }
        features = new StoreFeatures();
        features.supportsScan = false;
        features.supportsBatchMutation = true;
        features.supportsTransactions = false;
        features.supportsConsistentKeyOperations = true;
        features.supportsLocking = false;
        features.isKeyOrdered = false;
        features.isDistributed = true;
        features.hasLocalKeyPartition = false;
        assertNotNull(features);
        assertFalse(features.supportsScan());
        assertFalse(features.supportsTransactions());
        assertTrue(features.isDistributed());
    }


}
