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

### Ràng buộc an toàn Git:
- **Tuyệt đối KHÔNG dùng cờ `--force`** khi push.
- Nếu gặp lỗi push (conflict hoặc non-fast-forward rejected), AI phải **dừng lại ngay lập tức và báo cáo lỗi cho người dùng**, không tự ý reset hay ghi đè lịch sử git.

---

## 🏛️ 2. QUY CHUẨN KIẾN TRÚC & CODING RULES

1. **Hierarchy of Truth:** Luôn ưu tiên theo thứ tự:
   `docs/requirements/brief.md` > `docs/team/engineering-rules.md` > task files.
2. **Kỷ luật DTO (OSIV = false):** Do cấu hình `spring.jpa.open-in-view: false`, 100% dữ liệu truyền sang View Thymeleaf bắt buộc phải là DTO/Record được ánh xạ trong Service, tránh ném lỗi `LazyInitializationException`.
3. **Hibernate Collection Safety:** Tuyệt đối không gọi `entity.setList(newList)` trên quan hệ `@OneToMany(orphanRemoval = true)` của Entity đang được quản lý (managed).
4. **Giao diện chuẩn Học đường / Doanh nghiệp:** Tối giản, trang nhã, không màu mè sặc sỡ, ưu tiên tương tác mượt mà qua AJAX / Fetch API (Zero Full-Page Refresh) và thông báo Toast góc phải.
