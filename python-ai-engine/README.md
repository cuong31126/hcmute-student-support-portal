# 🤖 Python AI Engine — QAUTE Portal

> **Lõi AI Python** — RAG microservice chạy song song với Spring Boot (cổng 8001)  
> Spring Boot giữ nguyên 100% | Giao diện không đổi 1 dòng nào

## 🏛️ Kiến trúc

```
[ Trình duyệt Sinh viên ]
        │
        ▼ POST /api/v1/ai/chat
┌─────────────────────────────────────────┐
│     SPRING BOOT (cổng 8080)             │
│  RagChatbotService.ask()                │
│     ├─ [python-ai.enabled=true]         │
│     │   └─► PythonAiEngineClient        │
│     │         └─► POST /ai/ask ──┐      │
│     │                            │      │
│     └─ [Fallback tự động]        │      │
│         └─► Java RAG (legacy)    │      │
└─────────────────────────────────────────┘
                            │
                            ▼ HTTP 127.0.0.1:8001
┌─────────────────────────────────────────┐
│     PYTHON AI ENGINE (cổng 8001)        │
│  FastAPI + ChromaDB + Gemini            │
│  ├─ /ai/ask       — Trả lời RAG        │
│  ├─ /admin/ingest — Nạp tài liệu      │
│  ├─ /admin/convert-pdfs — PDF → MD    │
│  ├─ /admin/stats  — Thống kê           │
│  └─ /health       — Health check       │
└─────────────────────────────────────────┘
```

## 🚀 Hướng dẫn khởi động nhanh

### Bước 1: Cài đặt dependencies
```powershell
cd python-ai-engine
pip install -r requirements.txt
```

### Bước 2: Cấu hình API Key
```powershell
# Chỉnh sửa file .env (đã có sẵn, copy từ .env.example)
# Điền GEMINI_API_KEY thật vào
notepad .env
```

### Bước 3: Chạy pipeline tự động (test + ingest + check)
```powershell
python run_pipeline.py
```

### Bước 4: Khởi động server
```powershell
python run_pipeline.py --phase 4
# hoặc
python main.py
```

### Bước 5: Kích hoạt trong Spring Boot
Thêm vào file `.env` của Spring Boot (cùng thư mục gốc):
```properties
PYTHON_AI_ENABLED=true
PYTHON_AI_BASE_URL=http://127.0.0.1:8001
```

---

## 📂 Cấu trúc thư mục

```
python-ai-engine/
├── main.py              # FastAPI application (entry point)
├── config.py            # Cấu hình tập trung (Pydantic Settings)
├── rag_engine.py        # Lõi RAG (ChromaDB + Gemini + chunking)
├── pdf_converter.py     # PDF → Markdown converter
├── evaluator.py         # LLM-as-a-Judge (RAG Triad metrics)
├── run_pipeline.py      # Script tự động chạy tất cả giai đoạn
├── requirements.txt     # Python dependencies
├── .env                 # Cấu hình môi trường (không commit!)
├── .env.example         # Template cấu hình
├── data/
│   ├── chroma_db/       # Vector store persistent (ChromaDB)
│   └── markdown_docs/   # File .md đã convert từ PDF
└── tests/
    └── test_phase1_api.py  # Test suite đầy đủ
```

---

## 📖 Quy trình nạp tài liệu PDF

### Phase 2: PDF → Markdown (giải quyết lỗi font)
```powershell
# Đặt file PDF vào thư mục (mặc định: ../docs/knowledge_base)
# Chạy convert
python run_pipeline.py --phase 2
# Output: python-ai-engine/data/markdown_docs/*.md
```

**Tại sao cần convert?**
- PDF tiếng Việt hay dùng font TCVN3/VNTIME → mất dấu khi đọc bằng PDFBox
- Bảng điểm tuyển sinh bị vỡ khi extract text thường
- `pymupdf4llm` render PDF như mắt người → bảo toàn bảng biểu dạng Markdown table

### Phase 3: Ingest → ChromaDB
```powershell
python run_pipeline.py --phase 3
```

**Parent-Child Chunking:**
- Bảng Markdown KHÔNG bao giờ bị cắt ngang
- Điều/Khoản/Mục là ranh giới tự nhiên (Parent chunk)
- Sliding window 450 chars với overlap 60 cho đoạn văn dài

---

## 🔌 API Reference

### POST /ai/ask
Request (từ Spring Boot):
```json
{
  "question": "Học phí ngành CNTT năm 2026 là bao nhiêu?",
  "department_id": null,
  "evaluate": false
}
```

Response:
```json
{
  "reply": "Theo Quyết định số 123/QĐ-ĐHSPKT...",
  "source_type": "REGULATION",
  "confidence_score": 0.8542,
  "suggest_create_ticket": false,
  "llm_generated": true,
  "execution_time_ms": 1250,
  "faithfulness_score": 5,
  "eval_review": "Câu trả lời hoàn toàn trung thực theo công văn gốc."
}
```

### GET /health
```json
{
  "status": "ok",
  "knowledge_chunks": 342,
  "demo_mode": false
}
```

---

## 🧪 Chạy Tests

```powershell
# Chạy tất cả tests
pytest tests/test_phase1_api.py -v

# Chạy 1 group test cụ thể
pytest tests/test_phase1_api.py::TestRagEnginePhase1 -v
pytest tests/test_phase1_api.py::TestPdfConverterPhase2 -v
pytest tests/test_phase1_api.py::TestEvaluatorPhase3 -v
```

---

## 🔧 Bật/Tắt Python Engine (Zero-Downtime)

| Cấu hình | Chế độ |
|----------|--------|
| `PYTHON_AI_ENABLED=false` (mặc định) | Java RAG — hoạt động như cũ |
| `PYTHON_AI_ENABLED=true` | Python Engine — RAG xịn hơn |

Nếu Python Engine tắt hoặc crash → Spring Boot **tự động fallback** về Java RAG ngay lập tức, không có downtime.

---

## 📊 Đánh giá chất lượng (LLM-as-a-Judge)

Gọi `/ai/ask` với `"evaluate": true` để nhận điểm đánh giá:

| Chỉ số | Mô tả | Thang |
|--------|-------|-------|
| `context_relevance_score` | Context khớp với câu hỏi | 0-5 |
| `faithfulness_score` | Câu trả lời trung thực theo tài liệu | 0-5 |
| `answer_relevance_score` | Trả lời đúng trọng tâm | 0-5 |

Nếu `faithfulness_score < 3` → câu trả lời bị block, `suggest_create_ticket=true`.
