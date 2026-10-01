package com.school.counseling.module.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorReductionUtilsTest {

    @Test
    @DisplayName("TDD-PCA-01: Huấn luyện PCA và chiếu vector 768 chiều về tọa độ 3 chiều [x, y, z]")
    void fitAndProject_validVectors_returnsValid3DCoordinates() {
        int dim = 768;
        List<float[]> sampleVectors = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            float[] vec = new float[dim];
            for (int j = 0; j < dim; j++) {
                vec[j] = (float) Math.sin(i * 10 + j);
            }
            sampleVectors.add(vec);
        }

        VectorReductionUtils.PcaModel model = VectorReductionUtils.fit(sampleVectors);
        assertNotNull(model);
        assertEquals(dim, model.meanVector().length);
        assertEquals(3, model.components().length);

        double[] coords = VectorReductionUtils.project(sampleVectors.get(0), model);
        assertNotNull(coords);
        assertEquals(3, coords.length);
        assertFalse(Double.isNaN(coords[0]));
        assertFalse(Double.isNaN(coords[1]));
        assertFalse(Double.isNaN(coords[2]));
    }
}
