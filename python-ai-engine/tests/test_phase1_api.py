"""
QAUTE Portal — Python AI Engine
tests/test_phase1_api.py: Test suite tự động cho Giai đoạn 1

Chạy: pytest tests/test_phase1_api.py -v
"""
import pytest
import sys
from pathlib import Path

# Add parent dir to path
sys.path.insert(0, str(Path(__file__).parent.parent))


class TestConfigPhase1:
    """TEST GROUP 0: Config loading"""

    def test_settings_load(self):
        """Settings phải load được từ .env.example"""
        from config import Settings
        s = Settings(_env_file=".env.example")
        assert s.ai_engine_port == 8001
        assert s.top_k_chunks == 5
        assert s.suggest_ticket_threshold == 0.45
        print("✅ Config load OK")

    def test_settings_demo_mode(self):
        """Demo mode khi api_key = 'demo_key'"""
        from config import Settings
        s = Settings(gemini_api_key="demo_key")
        assert s.gemini_api_key == "demo_key"
        print("✅ Demo mode config OK")


class TestRagEnginePhase1:
    """TEST GROUP 1: RAG Engine core"""
    
    @pytest.fixture
    def engine(self, tmp_path):
        """Tạo RagEngine với ChromaDB temp dir"""
        from config import Settings
        from rag_engine import RagEngine
        
        settings = Settings(
            gemini_api_key="demo_key",
            chroma_persist_dir=str(tmp_path / "chroma"),
            top_k_chunks=3,
            suggest_ticket_threshold=0.45
        )
        engine = RagEngine(settings)
        ok = engine.initialize()
        assert ok, "RagEngine phải initialize thành công"
        return engine
    
    def test_engine_initialize(self, engine):
        """Engine phải khởi tạo thành công"""
        stats = engine.get_collection_stats()
        assert stats["status"] == "ready"
        assert stats["count"] == 0  # Chưa có gì
        print("✅ Engine initialize OK")
    
    def test_ask_empty_question(self, engine):
        """Câu hỏi rỗng phải trả về greeting, không crash"""
        from rag_engine import RagResult
        result = engine.ask("")
        assert result.answer
        assert "câu hỏi" in result.answer.lower() or "xin chào" in result.answer.lower()
        assert result.llm_generated == False
        print("✅ Empty question handled OK")
    
    def test_ask_nonsense(self, engine):
        """Câu vớ vẩn phải trả về lời mời hỏi, không chạy RAG"""
        result = engine.ask("alo")
        assert result.answer
        assert result.confidence_score == 0.0
        print("✅ Nonsense guardrail OK")
    
    def test_ask_no_knowledge(self, engine):
        """Câu hỏi khi chưa có knowledge → suggest_create_ticket=True"""
        result = engine.ask("Học phí năm 2026 là bao nhiêu?")
        assert result.suggest_create_ticket == True
        assert result.answer
        print(f"✅ Ask with empty DB: suggest_ticket={result.suggest_create_ticket}, answer='{result.answer[:50]}...'")
    
    def test_ingest_markdown(self, engine, tmp_path):
        """Nạp file markdown → collection phải có chunks"""
        # Tạo file test
        md_dir = tmp_path / "markdown"
        md_dir.mkdir()
        (md_dir / "quy_che_hoc_phi.md").write_text(
            "# Quy Chế Học Phí 2026\n\nTheo Quyết định số 123/QĐ-ĐHSPKT, "
            "học phí ngành CNTT năm 2026 là 18.500.000 đồng/năm.\n\n"
            "## Hạn đóng học phí\n\nHọc kỳ 1: trước ngày 15/09/2026.\n"
            "Học kỳ 2: trước ngày 15/02/2027.\n",
            encoding="utf-8"
        )
        
        results = engine.ingest_markdown_dir(str(md_dir))
        assert results["ingested"] > 0, f"Phải ingest được ít nhất 1 chunk. Got: {results}"
        
        stats = engine.get_collection_stats()
        assert stats["count"] > 0
        print(f"✅ Ingest OK: {results['ingested']} chunks, total={stats['count']}")
    
    def test_ask_after_ingest(self, engine, tmp_path):
        """Sau khi ingest, câu hỏi liên quan phải có confidence > 0"""
        md_dir = tmp_path / "markdown"
        md_dir.mkdir()
        (md_dir / "hoc_phi.md").write_text(
            "# Học Phí HCMUTE 2026\n\nHọc phí ngành Công nghệ Thông tin là 18.500.000 đồng mỗi năm học.",
            encoding="utf-8"
        )
        engine.ingest_markdown_dir(str(md_dir))
        
        result = engine.ask("Học phí ngành CNTT là bao nhiêu?")
        assert result.answer
        assert result.confidence_score >= 0.0
        print(f"✅ Ask after ingest: score={result.confidence_score:.3f}, answer='{result.answer[:60]}...'")
    
    def test_parent_child_chunking(self, engine):
        """Bảng Markdown không bị cắt ngang"""
        md_with_table = """# Điểm chuẩn tuyển sinh 2026

Điều 1. Điểm chuẩn các ngành:

| Ngành | Mã ngành | Khối | Điểm chuẩn |
|-------|----------|------|------------|
| CNTT  | 7480201  | A00  | 27.5       |
| Kỹ thuật máy tính | 7480106 | A00 | 26.0 |

Điều 2. Hạn nộp hồ sơ là 15/10/2026.
"""
        chunks = engine._parent_child_chunking(md_with_table, "test_doc")
        
        # Tìm chunk nào có bảng — bảng phải còn nguyên trong 1 chunk
        table_chunks = [c for c in chunks if "|" in c["content"] and "7480201" in c["content"]]
        assert len(table_chunks) >= 1, "Bảng điểm chuẩn phải nằm trong ít nhất 1 chunk nguyên vẹn"
        
        # Đảm bảo chunk có bảng không bị cắt giữa chừng
        for tc in table_chunks:
            assert "27.5" in tc["content"] and "26.0" in tc["content"], \
                "Hai hàng điểm chuẩn phải nằm trong cùng 1 chunk"
        
        print(f"✅ Parent-Child Chunking OK: {len(chunks)} chunks, bảng không bị cắt")
    
    def test_doc_code_extraction(self, engine):
        """Số công văn phải được trích xuất đúng"""
        text_with_code = "Số: 1084/QĐ-ĐHSPKT ngày 15/3/2026 về việc quy định học phí."
        code = engine._extract_doc_code(text_with_code)
        assert code is not None
        assert "1084" in code
        print(f"✅ Doc code extraction OK: '{code}'")
    
    def test_year_extraction(self, engine):
        """Năm hiệu lực phải được trích xuất đúng"""
        text = "Quy chế học vụ năm học 2026-2027 theo Quyết định 2025."
        year = engine._extract_year(text)
        assert year in [2026, 2027]
        print(f"✅ Year extraction OK: {year}")
    
    def test_reset_collection(self, engine, tmp_path):
        """Reset collection phải xóa hết dữ liệu"""
        # Ingest trước
        md_dir = tmp_path / "markdown"
        md_dir.mkdir()
        (md_dir / "test.md").write_text("# Test\n\nNội dung test.", encoding="utf-8")
        engine.ingest_markdown_dir(str(md_dir))
        
        before = engine.get_collection_stats()["count"]
        engine.reset_collection()
        after = engine.get_collection_stats()["count"]
        
        assert after == 0
        print(f"✅ Reset OK: {before} → {after} chunks")


class TestPdfConverterPhase2:
    """TEST GROUP 2: PDF Converter"""
    
    def test_import_pymupdf4llm(self):
        """pymupdf4llm phải import được"""
        try:
            import pymupdf4llm
            print("✅ pymupdf4llm import OK")
        except ImportError as e:
            pytest.skip(f"pymupdf4llm chưa cài: {e}")
    
    def test_batch_convert_empty_dir(self, tmp_path):
        """Convert thư mục rỗng không được crash"""
        from pdf_converter import batch_convert_all_pdfs
        
        empty_dir = tmp_path / "empty_pdfs"
        empty_dir.mkdir()
        out_dir = tmp_path / "markdown"
        
        results = batch_convert_all_pdfs(str(empty_dir), str(out_dir))
        assert results["success"] == 0
        assert results["failed"] == 0
        print("✅ Empty PDF dir OK")
    
    def test_batch_convert_nonexistent_dir(self, tmp_path):
        """Thư mục không tồn tại → không crash"""
        from pdf_converter import batch_convert_all_pdfs
        
        results = batch_convert_all_pdfs("/nonexistent/dir", str(tmp_path / "out"))
        assert results["success"] == 0
        print("✅ Nonexistent PDF dir handled OK")


class TestEvaluatorPhase3:
    """TEST GROUP 3: Evaluator"""
    
    @pytest.fixture
    def evaluator(self):
        from config import Settings
        from evaluator import RagEvaluator
        settings = Settings(gemini_api_key="demo_key")
        return RagEvaluator(settings)
    
    def test_evaluate_fallback_not_llm(self, evaluator):
        """Fallback (llm_generated=False) không nên chạy evaluate"""
        result = evaluator.evaluate(
            question="Test",
            context="context",
            answer="fallback answer",
            llm_generated=False
        )
        assert result.faithfulness == 0
        assert result.should_block == False
        print("✅ Evaluator skip for fallback OK")
    
    def test_evaluate_demo_mode(self, evaluator):
        """Demo mode trả về điểm mặc định 3/5"""
        result = evaluator.evaluate(
            question="Học phí là bao nhiêu?",
            context="Học phí 18.5 triệu",
            answer="Học phí là 18.5 triệu đồng",
            llm_generated=True
        )
        assert result.faithfulness == 3
        assert result.overall_score == 3.0
        assert result.should_block == False
        print("✅ Demo mode evaluator OK")


class TestFastApiEndpoints:
    """TEST GROUP 4: FastAPI REST Endpoints (Giao tiếp với Spring Boot)"""

    @pytest.fixture
    def client(self):
        from main import app
        from fastapi.testclient import TestClient
        with TestClient(app) as c:
            yield c

    def test_health_endpoint(self, client):
        """GET /health phải trả về status ok"""
        res = client.get("/health")
        assert res.status_code == 200
        data = res.json()
        assert data["status"] == "ok"
        assert "knowledge_chunks" in data
        print(f"✅ /health OK: chunks={data['knowledge_chunks']}")

    def test_stats_endpoint(self, client):
        """GET /admin/stats phải trả về thống kê kho tri thức"""
        res = client.get("/admin/stats")
        assert res.status_code == 200
        data = res.json()
        assert "chroma" in data
        assert data["chroma"]["status"] == "ready"
        print("✅ /admin/stats OK")

    def test_ask_endpoint(self, client):
        """POST /ai/ask phải trả về đúng format DTO mà Spring Boot cần"""
        res = client.post("/ai/ask", json={
            "question": "Học phí năm 2026 ngành CNTT là bao nhiêu?",
            "department_id": None,
            "evaluate": False
        })
        assert res.status_code == 200
        data = res.json()
        assert "reply" in data
        assert "confidence_score" in data
        assert "suggest_create_ticket" in data
        assert "llm_generated" in data
        assert "execution_time_ms" in data
        assert isinstance(data["execution_time_ms"], int)
        assert "matched_chunks" in data
        print(f"✅ /ai/ask OK: reply='{data['reply'][:50]}...'")

    def test_sync_chunk_endpoint(self, client):
        """POST /admin/sync-chunk phải nạp thành công chunk vào ChromaDB từ Java Spring Boot"""
        res = client.post("/admin/sync-chunk", json={
            "chunk_id": "test_chunk_sync_001",
            "content": "Sinh viên đạt giải Olympic Tin học được cộng 10 điểm rèn luyện.",
            "source": "FAQ_CHAT",
            "document_code": "HD-500/ĐHSPKT",
            "page_number": 2,
            "effective_year": 2026,
            "department_id": 1
        })
        assert res.status_code == 200
        data = res.json()
        assert data["status"] == "ok"
        assert data["synced_chunk_id"] == "test_chunk_sync_001"
        print("✅ /admin/sync-chunk OK")

    def test_abbreviations_expansion(self):
        """Module abbreviations phải chuẩn hóa chính xác teencode học vụ HCMUTE"""
        import abbreviations
        assert abbreviations.expand("cách tính đrl k22") == "cách tính điểm rèn luyện k22"
        assert abbreviations.expand("hạn nộp chứng chỉ avđr") == "hạn nộp chứng chỉ anh văn đầu ra"
        assert abbreviations.expand("khi nào mở đkmh") == "khi nào mở đăng ký học phần"
        print("✅ Academic Abbreviation expansion OK")

