# BÁO CÁO ĐÁNH GIÁ CHẤT LƯỢNG RAG ENGINE (BENCHMARK REPORT)
*Thời gian thực hiện: 2026-10-02 18:02:11*
*Tập dữ liệu chuẩn đối soát: `golden-truth.txt`*

## 1. TỔNG HỢP CHỈ SỐ ĐỊNH LƯỢNG

| Chỉ số đánh giá | Giá trị đo lường | Mục tiêu tiêu chuẩn | Trạng thái |
| :--- | :--- | :--- | :--- |
| **Tổng số câu hỏi kiểm thử** | **15 câu** | Toàn bộ các nhóm quy chế | Hoàn tất |
| **Tỷ lệ vượt qua (Pass Rate)** | **100.0%** (15/15) | $\ge 85.0\%$ | ✅ ĐẠT |
| **Tỷ lệ phát hiện ảo giác (Hallucination Rate)** | **0.0%** (0 câu) | $\le 5.0\%$ | ✅ XUẤT SẮC |
| **Thời gian phản hồi trung bình (Latency)** | **519.7 ms** | $< 2000$ ms | ✅ MƯỢT MÀ |
| **Độ khớp từ khóa trung bình (Keyword Hit Rate)** | **42.8%** | $\ge 60.0\%$ | ⚠️ KHÁ |
| **Độ tự tin trung bình (Avg Confidence)** | **0.945** | $\ge 0.70$ | Đạt chuẩn |

---

## 2. BẢNG CHI TIẾT KẾT QUẢ TỪNG CÂU HỎI

| STT | Phân nhóm | Câu hỏi sinh viên | Nguồn trả về | Độ khớp KW | Thời gian | Đánh giá |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Nhóm 1: Định danh, Đổi tên trường và Lịch sử | Trường Đại học Công nghệ Kỹ thuật Thành ... | `GOLDEN_TRUTH` | 100% | 2ms | ✅ ĐẠT |
| 2 | Nhóm 1: Định danh, Đổi tên trường và Lịch sử | Tên viết tắt và mã trường tuyển sinh của... | `GOLDEN_TRUTH` | 67% | 0ms | ✅ ĐẠT |
| 3 | Nhóm 1: Định danh, Đổi tên trường và Lịch sử | Tên tiếng Anh chính thức của trường hiện... | `GOLDEN_TRUTH` | 100% | 0ms | ✅ ĐẠT |
| 4 | Nhóm 1: Định danh, Đổi tên trường và Lịch sử | Tiền thân của trường được thành lập vào ... | `GOLDEN_TRUTH` | 100% | 0ms | ✅ ĐẠT |
| 5 | Nhóm 1: Định danh, Đổi tên trường và Lịch sử | Cơ quan chủ quản và triết lý giáo dục củ... | `GOLDEN_TRUTH` | 50% | 0ms | ✅ ĐẠT |
| 6 | Nhóm 2: Cơ sở, Trụ sở và Cơ sở vật chất | Trường có bao nhiêu cơ sở và địa chỉ cụ ... | `GOLDEN_TRUTH` | 75% | 0ms | ✅ ĐẠT |
| 7 | Nhóm 2: Cơ sở, Trụ sở và Cơ sở vật chất | Ký túc xá của trường đáp ứng được bao nh... | `REGULATION` | 0% | 1430ms | ✅ ĐẠT |
| 8 | Nhóm 3: Ban Giám hiệu đương nhiệm | Hiệu trưởng và các Phó Hiệu trưởng hiện ... | `GOLDEN_TRUTH` | 100% | 0ms | ✅ ĐẠT |
| 9 | Nhóm 4: Quy chế Tín chỉ, Đăng ký môn học và Điểm số | Số tín chỉ tối thiểu và tối đa sinh viên... | `REGULATION` | 0% | 1031ms | ✅ ĐẠT |
| 10 | Nhóm 4: Quy chế Tín chỉ, Đăng ký môn học và Điểm số | Cách phân loại năm đào tạo của sinh viên... | `REGULATION` | 25% | 992ms | ✅ ĐẠT |
| 11 | Nhóm 4: Quy chế Tín chỉ, Đăng ký môn học và Điểm số | Khi nào sinh viên bị cảnh báo học tập ho... | `REGULATION` | 0% | 1020ms | ✅ ĐẠT |
| 12 | Nhóm 4: Quy chế Tín chỉ, Đăng ký môn học và Điểm số | Những trường hợp nào sinh viên bị buộc t... | `REGULATION` | 14% | 922ms | ✅ ĐẠT |
| 13 | Nhóm 4: Quy chế Tín chỉ, Đăng ký môn học và Điểm số | Điểm I là gì và thủ tục xin nhận điểm I ... | `REGULATION` | 0% | 1056ms | ✅ ĐẠT |
| 14 | Nhóm 4: Quy chế Tín chỉ, Đăng ký môn học và Điểm số | Sinh viên muốn học cùng lúc hai chương t... | `REGULATION` | 11% | 1343ms | ✅ ĐẠT |
| 15 | Nhóm 5: Hoạt động Công tác xã hội (CTXH) bắt buộc | Sinh viên cần bao nhiêu ngày Công tác xã... | `GOLDEN_TRUTH` | 0% | 0ms | ✅ ĐẠT |

---
*Báo cáo được khởi tạo tự động bởi QAUTE Portal Python AI Engine.*