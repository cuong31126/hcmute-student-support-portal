package com.school.counseling.module.ai.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tiện ích thuật toán toán học PCA (Principal Component Analysis)
 * Chiếu giảm chiều dữ liệu không gian ngữ nghĩa từ vector nhúng 768 chiều xuống 3 chiều (X, Y, Z).
 * Phục vụ trực quan hóa 3D Vector Space & Similarity Search trên WebGL.
 */
@Slf4j
public final class VectorReductionUtils {

    private VectorReductionUtils() {}

    /**
     * Mô hình PCA đã huấn luyện chứa Mean Vector và 3 Principal Components
     */
    public record PcaModel(
            float[] meanVector,
            float[][] components // [3][dim]
    ) {}

    /**
     * Huấn luyện mô hình PCA trên tập hợp các vector đa chiều bằng thuật toán Power Iteration
     *
     * @param vectors Danh sách các vector nhúng (ví dụ 768 chiều)
     * @return PcaModel sẵn sàng chiếu giảm chiều
     */
    public static PcaModel fit(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("Tập vector huấn luyện không được để trống");
        }

        int n = vectors.size();
        int dim = vectors.get(0).length;

        // 1. Tính vector trung bình (Mean Vector)
        float[] mean = new float[dim];
        for (float[] vec : vectors) {
            for (int j = 0; j < dim; j++) {
                mean[j] += vec[j];
            }
        }
        for (int j = 0; j < dim; j++) {
            mean[j] /= n;
        }

        // 2. Chuẩn hóa dữ liệu bằng cách trừ vector trung bình (Center the data)
        float[][] centered = new float[n][dim];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < dim; j++) {
                centered[i][j] = vectors.get(i)[j] - mean[j];
            }
        }

        // 3. Trích xuất 3 thành phần chính bằng Power Iteration
        float[][] components = new float[3][dim];
        Random rnd = new Random(42); // Seed cố định để kết quả chiếu ổn định, tái lập được

        for (int c = 0; c < 3; c++) {
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) {
                v[j] = rnd.nextFloat() - 0.5f;
            }
            normalize(v);

            // 15 vòng lặp Power Iteration hội tụ nhanh
            for (int iter = 0; iter < 15; iter++) {
                float[] nextV = new float[dim];

                // Nhân ma trận X^T * (X * v)
                for (int i = 0; i < n; i++) {
                    float dot = 0.0f;
                    for (int j = 0; j < dim; j++) {
                        dot += centered[i][j] * v[j];
                    }
                    for (int j = 0; j < dim; j++) {
                        nextV[j] += centered[i][j] * dot;
                    }
                }

                // Trừ trực giao với các thành phần đã tìm trước đó (Gram-Schmidt)
                for (int prev = 0; prev < c; prev++) {
                    float proj = dotProduct(nextV, components[prev]);
                    for (int j = 0; j < dim; j++) {
                        nextV[j] -= proj * components[prev][j];
                    }
                }

                normalize(nextV);
                v = nextV;
            }

            components[c] = v;

            // Loại bỏ phương sai của thành phần vừa tìm khỏi tập centered (Deflation)
            for (int i = 0; i < n; i++) {
                float dot = dotProduct(centered[i], components[c]);
                for (int j = 0; j < dim; j++) {
                    centered[i][j] -= dot * components[c][j];
                }
            }
        }

        return new PcaModel(mean, components);
    }

    /**
     * Chiếu một vector 768 chiều về tọa độ 3 chiều (X, Y, Z)
     */
    public static double[] project(float[] vector, PcaModel model) {
        if (vector == null || model == null) {
            return new double[]{0.0, 0.0, 0.0};
        }

        int dim = Math.min(vector.length, model.meanVector().length);
        float[] centered = new float[dim];
        for (int j = 0; j < dim; j++) {
            centered[j] = vector[j] - model.meanVector()[j];
        }

        double[] coords = new double[3];
        for (int c = 0; c < 3; c++) {
            double sum = 0.0;
            for (int j = 0; j < dim; j++) {
                sum += centered[j] * model.components()[c][j];
            }
            // Nhân hệ số phóng đại để tọa độ trực quan đẹp mắt trên canvas 3D (-100 đến 100)
            coords[c] = Math.round(sum * 120.0 * 100.0) / 100.0;
        }

        return coords;
    }

    private static void normalize(float[] v) {
        float norm = 0.0f;
        for (float val : v) norm += val * val;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-6f) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
    }

    private static float dotProduct(float[] a, float[] b) {
        float sum = 0.0f;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) sum += a[i] * b[i];
        return sum;
    }
}
