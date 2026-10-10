# TIẾN ĐỘ 15: NÂNG CẤP CHATBOT RAG PHÂN TẦNG TUYỆT ĐỐI, TỐI ƯU HÓA TOKEN & ĐIỀU HƯỚNG BẢNG TIN

## 1. MỤC TIÊU & QUY TẮC CỐT LÕI
- **Ưu tiên Gemini Flash làm Engine mặc định**: Loại bỏ việc phụ thuộc vào Python cổng 8001 khi chạy cục bộ.
- **Tiêu chuẩn "Cửa hẹp" gọi API (Strict Cost-Saving Guardrail)**:
  - Chỉ gọi LLM khi Score >= 0.90 hoặc Golden Truth khớp chính xác.
  - Mọi trường hợp khác: KHÔNG gọi LLM, điều hướng sang Bảng tin / Ticket.
- **Chặn đứng câu đùa cợt/chitchat (Tầng 0)**: Bắt brainrot, profanity, noise (0ms, 0 token).

---

## 2. ROOT CAUSE ANALYSIS

### Lỗi 1 — Nghẽn kết nối Python Engine (cổng 8001)
- **Nguyên nhân:** python-ai.enabled=true khiến Spring Boot chờ hết 3s connect-timeout mỗi request.
- **Fix:** Đặt app.python-ai.enabled: false (default an toàn).

> **Lưu ý:** Cổng 8001 là Python FastAPI riêng biệt, KHÔNG phải Spring Boot.
> Spring Boot = cổng 8080. Khi python-ai.enabled=false, chỉ cần mvn spring-boot:run là đủ.

### Lỗi 2 — Hở tầng lọc (câu rác lọt vào RAG)
- **Nguyên nhân:** isLaughterOrEmotion/isProfanityOrInappropriate chưa bao phủ brainrot Gen Z.
- **Fix:** Mở rộng regex trong RagChatbotService.

### Lỗi 3 — Ngưỡng similarity quá thấp
- **Nguyên nhân:** Threshold = 0.50 → quá dễ lọt.
- **Fix:** Nâng lên 0.90.

---

## 3. DANH SÁCH FILE ĐÃ THAY ĐỔI

| File | Thay đổi |
|------|----------|
| application.yml | python-ai.enabled: false; similarity-threshold: 0.90 |
| RagChatbotService.java | Nâng threshold 0.50→0.90; mở rộng bộ lọc brainrot/noise |
| golden-truth.txt | Bổ sung: drl tính thế nào, học phí đóng ở đâu, hcmute là gì |

---

## 4. CÂU HỎI VỀ CỔNG 8001

| Cổng | Tiến trình | Điều kiện cần |
|------|-----------|---------------|
| 8080 | Spring Boot (bắt buộc) | mvn spring-boot:run |
| 8001 | Python FastAPI (tùy chọn) | Chỉ khi python-ai.enabled=true |

**Kết luận:** Khi python-ai.enabled=false, chỉ cần mvn spring-boot:run. Python không cần chạy.
