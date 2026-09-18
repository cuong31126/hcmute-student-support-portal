package com.school.counseling.module.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcademicAbbreviationUtilsTest {

    @Test
    @DisplayName("TDD-RAG-08: Tự động mở rộng các từ viết tắt teencode học vụ HCMUTE")
    void expandAbbreviations_shouldReplaceCommonTeencode() {
        String input = "Bao giờ nộp avđr để xét tn ạ? đrl 85 có đc hb k?";
        String expected = "Bao giờ nộp anh văn đầu ra để xét tốt nghiệp ạ? điểm rèn luyện 85 có đc học bổng k?";

        String actual = AcademicAbbreviationUtils.expand(input);

        assertEquals(expected, actual);
    }
}
