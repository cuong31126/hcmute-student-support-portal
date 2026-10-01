"""
QAUTE Portal — Python AI Engine
main.py: FastAPI Application (Cổng 8001)

Endpoints:
  POST /ai/ask          - Trả lời câu hỏi học vụ (giao tiếp chính với Spring Boot)
  POST /admin/ingest    - Nạp tài liệu Markdown vào ChromaDB
  POST /admin/convert-pdfs - Convert PDF → Markdown
  GET  /health          - Health check
  GET  /admin/stats     - Thống kê ChromaDB
"""
import sys
import logging
import time
from contextlib import asynccontextmanager
from typing import Optional

# Fix Windows console UTF-8 output
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from config import get_settings
from rag_engine import RagEngine
from evaluator import RagEvaluator

# ── Logging setup ──────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("qaute.ai")

# ── Global state ───────────────────────────────────────────────────────────────
settings = get_settings()
rag_engine = RagEngine(settings)
evaluator = RagEvaluator(settings)


# ── Lifespan: Startup / Shutdown ───────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Khởi tạo RAG Engine khi server start."""
    logger.info("=" * 60)
    logger.info("  QAUTE Portal — Python AI Engine đang khởi động...")
    logger.info(f"  Gemini Model: {settings.gemini_chat_model}")
    logger.info(f"  ChromaDB   : {settings.chroma_persist_dir}")
    logger.info("=" * 60)
    
    ok = rag_engine.initialize()
    if ok:
        stats = rag_engine.get_collection_stats()
        logger.info(f"  ✅ ChromaDB sẵn sàng: {stats['count']} chunks đã nạp")
    else:
        logger.warning("  ⚠️ ChromaDB khởi tạo thất bại — server vẫn chạy nhưng /ai/ask sẽ trả fallback")
    
    logger.info("  🚀 AI Engine đang lắng nghe tại cổng 8001")
    logger.info("=" * 60)
    
    yield  # Application is running
    
    logger.info("[AI Engine] Đang shutdown...")


# ── FastAPI App ────────────────────────────────────────────────────────────────
app = FastAPI(
    title="QAUTE Portal — Python AI Engine",
    description="RAG-based academic advisory AI for HCMUTE students",
    version="1.0.0",
    lifespan=lifespan
)

# CORS: Cho phép Spring Boot (localhost:8080) gọi
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080", "http://127.0.0.1:8080"],
    allow_credentials=True,
    allow_methods=["POST", "GET"],
    allow_headers=["*"],
)


# ── Pydantic Schemas ───────────────────────────────────────────────────────────

class ChatRequest(BaseModel):
    """Yêu cầu từ Spring Boot — tương thích với RagQueryRequest.java"""
    question: str = Field(..., min_length=1, description="Câu hỏi của sinh viên")
    department_id: Optional[int] = Field(None, description="ID phòng ban (tùy chọn)")
    evaluate: bool = Field(False, description="True nếu muốn chạy LLM-as-a-Judge")


class ChunkMatchResponse(BaseModel):
    content: str
    source: str
    score: float
    document_code: Optional[str] = None


class ChatResponse(BaseModel):
    """Response trả về cho Spring Boot — tương thích với AiResponseDto.java"""
    reply: str
    source_type: str = "REGULATION"
    confidence_score: float = 0.0
    suggest_create_ticket: bool = False
    llm_generated: bool = False
    execution_time_ms: int = 0
    needs_historical_warning: bool = False
    # Evaluation (optional)
    faithfulness_score: Optional[int] = None
    context_relevance_score: Optional[int] = None
    answer_relevance_score: Optional[int] = None
    eval_review: Optional[str] = None
    # Matched chunks
    matched_chunks: list[ChunkMatchResponse] = []


class IngestRequest(BaseModel):
    markdown_dir: Optional[str] = None
    reset_first: bool = False


class IngestResponse(BaseModel):
    status: str
    ingested: int
    skipped: int
    failed: int
    message: str


class ConvertPdfRequest(BaseModel):
    pdf_dir: Optional[str] = None
    output_dir: Optional[str] = None


# ── Endpoints ──────────────────────────────────────────────────────────────────

@app.get("/health")
async def health_check():
    """
    Health check endpoint — Spring Boot gọi để kiểm tra AI Engine còn sống không.
    """
    stats = rag_engine.get_collection_stats()
    return {
        "status": "ok",
        "engine": "python-ai-engine",
        "version": "1.0.0",
        "chroma_status": stats.get("status", "unknown"),
        "knowledge_chunks": stats.get("count", 0),
        "gemini_model": settings.gemini_chat_model,
        "demo_mode": settings.gemini_api_key == "demo_key"
    }


@app.post("/ai/ask", response_model=ChatResponse)
async def ask_ai(req: ChatRequest):
    """
    Endpoint chính: Nhận câu hỏi từ Spring Boot, trả về câu trả lời RAG.
    
    Spring Boot gọi: POST http://127.0.0.1:8001/ai/ask
    Body: {"question": "...", "department_id": null}
    """
    logger.info(f"[/ai/ask] Q='{req.question[:60]}...' dept={req.department_id}")
    
    # RAG pipeline
    result = rag_engine.ask(req.question, req.department_id)
    
    # Build context string cho evaluator (nếu cần)
    context_for_eval = ""
    if req.evaluate and result.matched_chunks:
        context_for_eval = "\n".join([
            f"[{c.source}]\n{c.content}" 
            for c in result.matched_chunks[:3]
        ])
    
    # Evaluation (chỉ chạy khi được yêu cầu)
    eval_result = None
    final_answer = result.answer
    final_suggest_ticket = result.suggest_create_ticket
    
    if req.evaluate and result.llm_generated:
        eval_result = evaluator.evaluate(
            question=req.question,
            context=context_for_eval,
            answer=result.answer,
            llm_generated=result.llm_generated
        )
        
        # Nếu faithfulness thấp → block và chuyển sang suggest ticket
        if eval_result.should_block:
            final_answer = (
                "⚠️ Câu trả lời tự động không đủ độ tin cậy để hiển thị. "
                "Vui lòng gửi Ticket để Cán bộ Phòng/Khoa hỗ trợ trực tiếp."
            )
            final_suggest_ticket = True
    
    # Serialize matched_chunks
    chunks_resp = [
        ChunkMatchResponse(
            content=c.content[:300],  # Giới hạn để không bloat response
            source=c.source,
            score=c.score,
            document_code=c.document_code
        )
        for c in result.matched_chunks
    ]
    
    return ChatResponse(
        reply=final_answer,
        source_type=result.source_type,
        confidence_score=round(result.confidence_score, 4),
        suggest_create_ticket=final_suggest_ticket,
        llm_generated=result.llm_generated,
        execution_time_ms=result.execution_time_ms,
        needs_historical_warning=result.needs_historical_warning,
        faithfulness_score=eval_result.faithfulness if eval_result else None,
        context_relevance_score=eval_result.context_relevance if eval_result else None,
        answer_relevance_score=eval_result.answer_relevance if eval_result else None,
        eval_review=eval_result.review_comment if eval_result else None,
        matched_chunks=chunks_resp
    )


@app.post("/admin/ingest", response_model=IngestResponse)
async def ingest_documents(req: IngestRequest, background_tasks: BackgroundTasks):
    """
    Nạp tài liệu Markdown vào ChromaDB.
    Có thể reset toàn bộ trước khi ingest (khi cần re-index lại).
    """
    markdown_dir = req.markdown_dir or settings.markdown_output_dir
    
    if req.reset_first:
        logger.info("[/admin/ingest] Đang reset ChromaDB collection...")
        rag_engine.reset_collection()
    
    try:
        results = rag_engine.ingest_markdown_dir(markdown_dir)
        return IngestResponse(
            status="success",
            ingested=results["ingested"],
            skipped=results["skipped"],
            failed=results["failed"],
            message=f"Đã nạp {results['ingested']} chunks mới vào kho tri thức."
        )
    except Exception as e:
        logger.error(f"[/admin/ingest] Lỗi: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/admin/convert-pdfs")
async def convert_pdfs(req: ConvertPdfRequest, background_tasks: BackgroundTasks):
    """
    Convert tất cả PDF sang Markdown (chạy trong background).
    Giải quyết lỗi font TCVN3/VNTIME và vỡ bảng biểu.
    """
    from pdf_converter import batch_convert_all_pdfs
    
    pdf_dir = req.pdf_dir or settings.pdf_source_dir
    out_dir = req.output_dir or settings.markdown_output_dir
    
    def run_conversion():
        results = batch_convert_all_pdfs(pdf_dir, out_dir)
        logger.info(f"[/admin/convert-pdfs] Kết quả: {results}")
    
    background_tasks.add_task(run_conversion)
    
    return {
        "status": "started",
        "message": f"Đang convert PDF trong '{pdf_dir}' → '{out_dir}' (chạy ngầm).",
        "check_logs": "Xem log server để theo dõi tiến độ."
    }


@app.get("/admin/stats")
async def get_stats():
    """Thống kê về ChromaDB và cấu hình engine."""
    stats = rag_engine.get_collection_stats()
    return {
        "chroma": stats,
        "settings": {
            "chat_model": settings.gemini_chat_model,
            "embedding_model": settings.gemini_embedding_model,
            "top_k_chunks": settings.top_k_chunks,
            "context_budget_chars": settings.context_token_budget_chars,
            "suggest_ticket_threshold": settings.suggest_ticket_threshold,
            "demo_mode": settings.gemini_api_key == "demo_key"
        }
    }


@app.delete("/admin/reset-knowledge")
async def reset_knowledge():
    """
    Xóa toàn bộ vector store (dùng khi cần re-index lại từ đầu).
    ⚠️ Không thể hoàn tác!
    """
    success = rag_engine.reset_collection()
    return {
        "status": "success" if success else "error",
        "message": "Đã xóa toàn bộ ChromaDB collection." if success else "Lỗi khi reset."
    }


# ── Main entry ─────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.ai_engine_host,
        port=settings.ai_engine_port,
        reload=settings.debug,
        log_level="info"
    )
