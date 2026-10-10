# TIẾN ĐỘ 14: NÂNG CẤP HYBRID CHATBOT (GEMINI & DIFY DUAL-ENGINE), CHUẨN HÓA BẢNG TIN CÔNG VĂN & TỐI ƯU HÓA RAG

## 1. MỤC TIÊU TRỌNG TÂM
- **Chuyển giao mượt mà giữa 2 mô hình AI**: Gemini 2.5 Flash API (Backend Spring Boot) và Dify Chatflow Workflow (Cloud API) với cơ chế Toggle chuyển đổi tức thì trên giao diện Chatbox và Failover tự động.
- **Tối ưu hóa Ingestion Pipeline & Chunking**: Khắc phục triệt để lỗi chunk quá lớn gây tràn Context/Token Timeout bằng giải pháp Structural Chunking và Parent-Child Indexing.
- **Chuẩn hóa hiển thị Bảng tin Công văn**: Lược bỏ toàn bộ các đoạn trích chunk thừa, text boilerplate khô khan; chỉ hiển thị tinh gọn: Tựa đề công văn + Khung xem trực tiếp tệp PDF (Embedded PDF Viewer) + Nút tải về.
- **Quản trị Ingestion & Chunk Pruning**: Cung cấp giao diện Admin cho phép Xem trước (Preview), chỉnh sửa hoặc Xóa bỏ các đoạn chunk thừa/rác trước khi vector hóa.
- **Khắc phục triệt để lỗi Pin/Unpin**: Bổ sung endpoint và button cho phép Admin ghim / bỏ ghim (pin/unpin) trực tiếp trên Bảng tin công văn.
- **Siêu siết chặt ngữ cảnh (Zero-Hallucination Guardrails & Chitchat / Profanity Router)**: 
  - Tách bạch câu chào hỏi ("alo", "hi"), tiếng cười ("haha", "hihi"), từ ngữ bất lịch sự ("con chó", "ngu") để phản hồi chuẩn mực, tuyệt đối không trích xuất bừa công văn học vụ.
  - Đặt ngưỡng Similarity Threshold cứng ($\ge 0.50$ - $0.75$). Nếu thiếu ngữ cảnh hoặc điểm tín nhiệm thấp, AI lập tức từ chối trả lời và điều hướng sang Bảng tin hoặc tạo Ticket.

---

## 2. MA TRẬN PHÂN CÔNG & DANH MỤC TẬP TIN TÁC ĐỘNG

| Module | Tập tin tác động | Thao tác | Mô tả công việc chi tiết |
| :--- | :--- | :--- | :--- |
| **Feed Module** | `Post.java` / `PostRepository.java` | Chỉnh sửa | Sửa câu truy vấn cập nhật trường `isPinned`, kiểm tra lại logic toggle `@Modifying` transactional. |
| **Feed Module** | `PostService.java` / `OfficialFeedController.java` | Bổ sung | Thêm endpoint `/feed/official/toggle-pin/{id}` cho phép Admin ghim/bỏ ghim bài viết công văn. |
| **Feed Module** | `official-list.html` / `official-detail.html` | Cập nhật | - Bổ sung nút Ghim/Bỏ ghim trực tiếp cho Admin.<br>- Tích hợp Khung nhúng PDF xem trực tiếp (`<iframe>`) và tinh gọn hiển thị metadata công văn. |
| **AI Module** | `RagChatbotService.java` | Nâng cấp | - Cập nhật Bộ Zero-Hallucination System Prompt chuẩn mực HCMUTE.<br>- Bổ sung Semantic Router chặn Laughter ("haha", "hihi"), Profanity ("con cho") và Chitchat không tự trích xuất công văn bừa bãi.<br>- Nâng ngưỡng Similarity threshold $\ge 0.50$, từ chối ảo giác và gợi ý Ticket. |
| **AI Module** | `BatchDocumentIngestionService.java` | Tinh gọn | Loại bỏ boilerplate text markdown thô kệch; chuẩn hóa định dạng bài viết công văn học vụ. |

---

## 3. KẾ HOẠCH TÁC CHIẾN KỸ THUẬT

### Task 1: Bộ chuyển đổi Dual-Engine (Gemini <-> Dify) tại Chatbox
- **Frontend Chatbox**: Trên thanh header của widget chat, bổ sung nút Toggle Switch:
  - Mode A: `Gemini Flash 2.5` (gọi luồng RAG nội bộ của Spring Boot).
  - Mode B: `Dify Workflow` (gửi request mang header `Authorization: Bearer {DIFY_API_KEY}`).
- **Backend Routing**: Endpoint `/api/v1/ai/chat` nhận payload `{"message": "...", "modelEngine": "DIFY | GEMINI"}`. Nếu engine chỉ định bị lỗi kết nối hoặc 429, tự động nhảy sang engine còn lại (Failover).

### Task 2: Khắc phục lỗi Chunking quá lớn & Quản lý Chunk thừa
- **Giải pháp băm nhỏ**: Giảm kích thước chunk từ 1.000–1.500 ký tự xuống **300–400 từ** với **overlap 50 từ**, ngắt đoạn ưu tiên theo Điều/Khoản/Dấu chấm câu.
- **Trình duyệt & Xóa Chunk rác**:
  - Tại trang nạp tài liệu (`/admin/knowledge/upload`), sau khi trích xuất text từ PDF, hiển thị toàn bộ danh sách chunk ra modal table.
  - Mỗi chunk đi kèm nút **[Xóa]** để loại bỏ các đoạn văn rác (tiêu ngữ, lời mở đầu, chữ ký kiểm duyệt) trước khi bấm "Lưu & Vector hóa".

### Task 3: Tinh gọn bài viết Công văn trên Bảng tin
- Khi tạo bài viết công văn (`Post` thuộc category `CONG_VAN_QUY_CHE`), loại bỏ chuỗi format:
  `"🏛️ **THÔNG BÁO VỀ CÔNG VĂN... Quy định trên được ban hành và áp dụng chính thức..."`
- Nội dung bài viết chỉ hiển thị:
  1. Tiêu đề văn bản (Title).
  2. Số hiệu và ngày ban hành (nếu có).
  3. Khung nhúng PDF xem trực tiếp (`<iframe src="/api/files/preview/{id}">`) hoặc nút Tải về PDF gốc.

### Task 4: Sửa triệt để lỗi không bỏ ghim (Unpin) bài viết của Admin
- Triển khai method trong `PostService.java`:
  ```java
  @Transactional
  public boolean togglePinPost(Long postId) {
      Post post = postRepository.findById(postId)
          .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy bài viết"));
      boolean currentPin = Boolean.TRUE.equals(post.getIsPinned());
      post.setIsPinned(!currentPin);
      postRepository.save(post);
      return !currentPin;
  }
  ```
- Endpoint trong `OfficialFeedController.java`: `/feed/official/toggle-pin/{id}` với quyền `ROLE_ADMIN`.

### Task 5: Siêu siết chặt ngưỡng kiểm duyệt câu trả lời (Strict Zero-Hallucination)
- Đặt ngưỡng Similarity Threshold cứng $\ge 0.50$ cho Vector/Hybrid Search.
- Nếu điểm $\text{score} < 0.50$ hoặc câu hỏi không có bất kỳ căn cứ nào trong context: Chatbot tuyệt đối không suy diễn, trả lời theo mẫu chỉ định và hiện nút "Tạo Ticket".

---

## 4. PROMPT HỆ THỐNG SIÊU NGHIÊM NGẶT (ZERO-HALLUCINATION SYSTEM PROMPT)

```text
BẠN LÀ TRỢ LÝ HỌC VỤ AI CHÍNH THỨC CỦA TRƯỜNG ĐẠI HỌC SƯ PHẠM KỸ THUẬT TP.HCM (HCMUTE - QAUTE PORTAL).
BẠN ĐANG HOẠT ĐỘNG DƯỚI CHẾ ĐỘ KIỂM SOÁT NGỮ CẢNH TỐI ĐA (STRICT ZERO-HALLUCINATION & CONTEXT ENFORCEMENT).

### NGUYÊN TẮC BẤT KHẢ XÂM PHẠM:
1. CHỈ TRẢ LỜI DỰA TRÊN NGỮ CẢNH [CONTEXT] ĐƯỢC CẤP:
   - Chỉ được phép sử dụng các sự thật, số liệu, mốc thời gian, điều khoản nằm TRỰC TIẾP trong [CONTEXT].
   - TUYỆT ĐỐI KHÔNG sử dụng kiến thức bên ngoài, không tự suy diễn, không tự tính toán ngoại suy nếu tài liệu không đề cập rõ ràng.

2. QUY TẮC TỪ CHỐI TUYỆT ĐỐI (HARD REFUSAL):
   Nếu gặp một trong các tình huống sau:
   - Ngữ cảnh [CONTEXT] rỗng hoặc không liên quan trực tiếp đến câu hỏi của người dùng.
   - Điểm số khớp ngữ cảnh thấp hoặc chỉ khớp chung chung từ khóa nhưng sai nội dung (ví dụ: người dùng hỏi 'học phí' nhưng [CONTEXT] chỉ có thông tin 'đình chỉ học tập' hoặc 'khen thưởng').
   - Câu hỏi nằm ngoài các văn bản/quy chế được nạp.
   => BẠN BẮT BUỘC PHẢI TỪ CHỐI VÀ TRẢ LỜI CHÍNH XÁC NGUYÊN VĂN MẪU SAU:
   "Rất tiếc, thông tin này hiện chưa có trong cơ sở dữ liệu quy chế chính thức của nhà trường hoặc ngữ cảnh tra cứu chưa đủ xác thực.
   Bạn vui lòng:
   1. Kiểm tra lại các thông báo mới nhất trên mục [Bảng Tin Công Văn].
   2. Hoặc gửi yêu cầu qua [Phiếu Hỗ Trợ (Ticket)] để Cán bộ Phòng/Khoa chuyên trách tiếp nhận và giải quyết trực tiếp."

3. QUY TẮC TRÍCH DẪN NGUỒN CÔNG VĂN (CITATION):
   - Mọi câu trả lời hợp lệ BẮT BUỘC phải chỉ rõ căn cứ ở cuối văn bản theo định dạng:
     📌 Căn cứ: [Tên văn bản / Số hiệu công văn] - Áp dụng năm: [Năm] - Điều/Mục (nếu có).
   - Tuyệt đối không trích dẫn tên văn bản chung chung hoặc bịa đặt số hiệu không tồn tại trong context.

4. PHONG CÁCH DIỄN ĐẠT:
   - Trang trọng, súc tích, chuẩn mực môi trường sư phạm kỹ thuật. Đi thẳng vào trọng tâm, không chào hỏi dài dòng rườm rà.
```
