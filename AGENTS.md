# 🤖 AI AGENT CODING & GIT WORKFLOW RULES (QAUTE PORTAL)

> **MỤC ĐÍCH:** Mọi AI Coding Assistant (Antigravity, Gemini, Claude, Cursor, Copilot) khi làm việc trong repository này BẮT BUỘC phải đọc và tự động tuân thủ các quy tắc dưới đây.

---

## 🚀 1. QUY TẮC BẮT BUỘC: TỰ ĐỘNG COMMIT & PUSH (AUTO GIT WORKFLOW)

Sau khi hoàn thành và xác minh (test-compile / verify) xong **MỖI chức năng, module hoặc sửa lỗi**:
AI **PHẢI TỰ ĐỘNG CHẠY TERMINAL** chuỗi lệnh sau mà không cần chờ người dùng nhắc:
```bash
git add .
git commit -m "<type>(<scope>): <mô tả ngắn gọn chức năng vừa hoàn thành>"
git push origin <tên-nhánh-hiện-tại>
```

### Quy định Commit Message (Chuẩn Conventional Commits):
- `feat(<module>)`: Tính năng mới (ví dụ: `feat(forum): restructure to facebook feed style with ajax and top-right toast`)
- `fix(<module>)`: Sửa lỗi (ví dụ: `fix(post): resolve hibernate orphan removal 500 error`)
- `refactor(<module>)`: Tái cấu trúc mã nguồn (ví dụ: `refactor(ticket): optimize chatbox upload ui`)
- `docs(<module>)`: Cập nhật tài liệu (ví dụ: `docs(analysis): add beginner guide for java core`)
- `style(<module>)`: Điều chỉnh CSS / Giao diện

### Quy định Nhánh Làm Việc (Feature Branch Workflow):
- **Đối với chức năng mới hoặc cải tổ lớn (Major Overhaul / Feature):**
  1. AI **BẮT BUỘC** phải rẽ ra một nhánh feature riêng biệt từ `main`:
     ```bash
     git checkout -b feature/<tên-chức-năng>
     ```
  2. Toàn bộ quá trình code, chỉnh sửa và xác minh được thực hiện và commit trên nhánh feature này.
  3. Chỉ khi kiểm thử xác nhận chạy OK (ví dụ: `mvn test` đạt 100% BUILD SUCCESS), AI mới tiến hành checkout về `main`, merge nhánh feature vào và push lên origin:
     ```bash
     git checkout main
     git merge feature/<tên-chức-năng>
     git push origin main
     git push origin feature/<tên-chức-năng>
     ```

### Ràng buộc an toàn Git:
- **Tuyệt đối KHÔNG dùng cờ `--force`** khi push.
- Nếu gặp lỗi push (conflict hoặc non-fast-forward rejected), AI phải **dừng lại ngay lập tức và báo cáo lỗi cho người dùng**, không tự ý reset hay ghi đè lịch sử git.

---

## 🏛️ 2. QUY CHUẨN KIẾN TRÚC & CODING RULES

1. **Hierarchy of Truth:** Luôn ưu tiên theo thứ tự:
   `docs/requirements/brief.md` > `docs/team/engineering-rules.md` > task files.
2. **Kỷ luật DTO (OSIV = false):** Do cấu hình `spring.jpa.open-in-view: false`, 100% dữ liệu truyền sang View Thymeleaf bắt buộc phải là DTO/Record được ánh xạ trong Service, tránh ném lỗi `LazyInitializationException`.
3. **Hibernate Collection Safety:** Tuyệt đối không gọi `entity.setList(newList)` trên quan hệ `@OneToMany(orphanRemoval = true)` của Entity đang được quản lý (managed).
4. **Giao diện chuẩn Học đường / Doanh nghiệp:** BẮT BUỘC tuân thủ tài liệu `.agents/skills/academic-ui-ux/SKILL.md`. Tối giản, trang nhã, không màu mè sặc sỡ, ưu tiên tương tác mượt mà qua AJAX / Fetch API (Zero Full-Page Refresh) và thông báo Toast góc phải.
5. **Ràng Buộc Thiết Kế UI Bắt Buộc (Strict UI Rules):**
   - **Anti-Boxed Layout:** Tuyệt đối không đóng khung lơ lửng hình ảnh nền hoặc khối visual (Hero, Login, Register) vào một thẻ bo góc lọt thỏm giữa trang. Hero và Auth bắt buộc dùng layout tràn viền Fullscreen / Split-Screen (`100vw`).
   - **Bảng Màu Cấm:** Cấm tuyệt đối màu vàng chói lóa (`#FFC107`, `#FFD700`, v.v.), cấm gradient đỏ đen dày đặc che khuất ảnh trường học. Chỉ sử dụng bộ màu chuẩn: Harvard Crimson (`#A51C30`), Deep Slate (`#111827`), Light Gray (`#F8F9FA`), và White (`#FFFFFF`).
   - **Form & Typography:** Sử dụng font `Inter` cho nội dung thông thường, `Playfair Display` cho tiêu đề trang trọng, độ tương phản chữ phải đạt chuẩn WCAG AA.
