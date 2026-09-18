package com.school.counseling.module.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorMathUtilsTest {

    @Test
    @DisplayName("TDD-RAG-01: Hai vector giống hệt nhau -> Cosine Similarity = 1.0")
    void identicalVectors_shouldReturnOne() {
        float[] v1 = {1.0f, 2.0f, 3.0f};
        float[] v2 = {1.0f, 2.0f, 3.0f};

        double similarity = VectorMathUtils.cosineSimilarity(v1, v2);

        assertEquals(1.0, similarity, 0.0001);
    }

    @Test
    @DisplayName("TDD-RAG-02: Hai vector vuông góc hoàn toàn -> Cosine Similarity = 0.0")
    void orthogonalVectors_shouldReturnZero() {
        float[] v1 = {1.0f, 0.0f};
        float[] v2 = {0.0f, 1.0f};

        double similarity = VectorMathUtils.cosineSimilarity(v1, v2);

        assertEquals(0.0, similarity, 0.0001);
    }

    @Test
    @DisplayName("TDD-RAG-03: Vector rỗng hoặc độ dài không khớp -> Ném IllegalArgumentException an toàn")
    void mismatchedOrEmptyVectors_shouldThrowException() {
        float[] v1 = {1.0f, 2.0f};
        float[] v2 = {1.0f, 2.0f, 3.0f};

        assertThrows(IllegalArgumentException.class, () -> VectorMathUtils.cosineSimilarity(v1, v2));
        assertThrows(IllegalArgumentException.class, () -> VectorMathUtils.cosineSimilarity(null, v2));
    }
}
