"""
QAUTE Portal — Python AI Engine
rag_engine.py: Lõi RAG với ChromaDB + LangChain + Gemini (Giai đoạn 1)

Kiến trúc:
  - ChromaDB: Vector store persistent (lưu trên disk, tải nhanh khi restart)
  - Gemini Embedding: text-embedding-004 (1536 chiều)
  - Gemini Chat: gemini-2.5-flash (generation)
  - Parent-Child Chunking: Giữ nguyên bảng biểu không bị cắt ngang
  - Hybrid Search: Cosine similarity vector + keyword boost
"""
import os
import re
import time
import logging
import unicodedata
from pathlib import Path
from typing import Optional
from dataclasses import dataclass, field

import abbreviations

logger = logging.getLogger(__name__)


@dataclass
class ChunkMatch:
    """Kết quả một chunk khớp với câu hỏi."""
    content: str
    source: str
    score: float
    page_number: Optional[int] = None
    document_code: Optional[str] = None
    effective_year: Optional[int] = None
    chunk_id: Optional[str] = None


@dataclass
class RagResult:
    """Kết quả đầy đủ trả về từ RAG Engine."""
    answer: str
    source_type: str = "REGULATION"
    confidence_score: float = 0.0
    matched_chunks: list[ChunkMatch] = field(default_factory=list)
    faithfulness_score: Optional[int] = None
    faithfulness_review: Optional[str] = None
    suggest_create_ticket: bool = False
    llm_generated: bool = False
    execution_time_ms: int = 0
    needs_historical_warning: bool = False


class RagEngine:
    """
    Lõi RAG Engine dùng ChromaDB + Gemini.
    
    Lifecycle:
      1. initialize() — kết nối ChromaDB, tải embeddings
      2. ingest_markdown_docs() — nạp tài liệu Markdown vào ChromaDB
      3. ask() — nhận câu hỏi → trả RagResult
    """
    
    def __init__(self, settings):
        self.settings = settings
        self._chroma_client = None
        self._collection = None
        self._embedding_function = None
        self._is_ready = False
        
    def initialize(self) -> bool:
        """
        Khởi tạo ChromaDB và Gemini Embedding.
        Trả về True nếu thành công.
        """
        try:
            import chromadb
            from chromadb.utils import embedding_functions
            
            # Tạo ChromaDB persistent client
            chroma_dir = Path(self.settings.chroma_persist_dir)
            chroma_dir.mkdir(parents=True, exist_ok=True)
            
            self._chroma_client = chromadb.PersistentClient(path=str(chroma_dir))
            
            # Chọn embedding function phù hợp với API key
            if self.settings.gemini_api_key and self.settings.gemini_api_key != "demo_key":
                try:
                    self._embedding_function = embedding_functions.GoogleGenerativeAiEmbeddingFunction(
                        api_key=self.settings.gemini_api_key,
                        model_name=self.settings.gemini_embedding_model
                    )
                    logger.info(f"[RAG] Sử dụng Gemini Embedding: {self.settings.gemini_embedding_model}")
                except Exception as e:
                    logger.warning(f"[RAG] Gemini embedding lỗi: {e}. Fallback sang DefaultEmbeddingFunction.")
                    self._embedding_function = embedding_functions.DefaultEmbeddingFunction()
            else:
                logger.info("[RAG] Demo mode: Dùng DefaultEmbeddingFunction (sentence-transformers)")
                self._embedding_function = embedding_functions.DefaultEmbeddingFunction()
            
            # Lấy hoặc tạo collection
            self._collection = self._chroma_client.get_or_create_collection(
                name="qaute_knowledge",
                embedding_function=self._embedding_function,
                metadata={"hnsw:space": "cosine"}
            )
            
            count = self._collection.count()
            logger.info(f"[RAG] ✅ ChromaDB sẵn sàng. Collection 'qaute_knowledge' có {count} chunks.")
            self._is_ready = True
            return True
            
        except Exception as e:
            logger.error(f"[RAG] ❌ Khởi tạo thất bại: {e}")
            self._is_ready = False
            return False
    
    def ingest_markdown_dir(self, markdown_dir: str | Path) -> dict:
        """
        Nạp tất cả file .md trong thư mục vào ChromaDB.
        Dùng Parent-Child Chunking: không bao giờ cắt ngang bảng Markdown.
        
        Returns:
            dict: {"ingested": int, "skipped": int, "failed": int}
        """
        if not self._is_ready:
            raise RuntimeError("RagEngine chưa được khởi tạo. Gọi initialize() trước.")
        
        markdown_dir = Path(markdown_dir)
        if not markdown_dir.exists():
            logger.warning(f"[RAG Ingest] Thư mục markdown không tồn tại: {markdown_dir}")
            return {"ingested": 0, "skipped": 0, "failed": 0}
        
        md_files = sorted(markdown_dir.glob("*.md"))
        logger.info(f"[RAG Ingest] Tìm thấy {len(md_files)} file .md")
        
        results = {"ingested": 0, "skipped": 0, "failed": 0}
        
        for md_file in md_files:
            try:
                source_name = md_file.stem
                # Kiểm tra xem đã ingest chưa (dùng metadata doc_source làm key)
                existing = self._collection.get(
                    where={"doc_source": source_name},
                    limit=1
                )
                if existing and existing["ids"]:
                    results["skipped"] += 1
                    continue
                
                text = md_file.read_text(encoding="utf-8")
                chunks = self._parent_child_chunking(text, source_name)
                
                if not chunks:
                    results["skipped"] += 1
                    continue
                
                # Batch upsert vào ChromaDB (mỗi batch 50 chunks)
                batch_size = 50
                for i in range(0, len(chunks), batch_size):
                    batch = chunks[i:i + batch_size]
                    self._collection.upsert(
                        ids=[c["id"] for c in batch],
                        documents=[c["content"] for c in batch],
                        metadatas=[c["metadata"] for c in batch]
                    )
                
                results["ingested"] += len(chunks)
                logger.info(f"[RAG Ingest] ✅ '{md_file.name}': {len(chunks)} chunks")
                
            except Exception as e:
                logger.error(f"[RAG Ingest] ❌ Lỗi '{md_file.name}': {e}")
                results["failed"] += 1
        
        logger.info(f"[RAG Ingest] Hoàn thành: {results}")
        return results

    def upsert_single_chunk(self, chunk_id: str, content: str, metadata: dict) -> bool:
        """
        Nạp hoặc cập nhật một chunk đơn lẻ vào ChromaDB (hỗ trợ đồng bộ hai chiều tức thì từ Java).
        """
        if not self._is_ready or self._collection is None:
            return False
        try:
            self._collection.upsert(
                ids=[chunk_id],
                documents=[content],
                metadatas=[metadata]
            )
            logger.info(f"[RAG Sync] Đã đồng bộ thành công chunk '{chunk_id}' vào ChromaDB")
            return True
        except Exception as e:
            logger.error(f"[RAG Sync] Lỗi khi đồng bộ chunk '{chunk_id}': {e}")
            return False
    
    def _parent_child_chunking(self, text: str, source_name: str) -> list[dict]:
        """
        Chiến lược Parent-Child Chunking cho văn bản học thuật Việt Nam.
        
        Nguyên tắc:
          - Không bao giờ cắt ngang bảng Markdown (| ... |)
          - Điều/Khoản/Mục là ranh giới tự nhiên → tạo Parent chunk
          - Đoạn văn nhỏ trong mỗi Điều → Child chunks (size ~450 chars)
          - Child chunk kế thừa metadata từ Parent (tiêu đề Điều, số công văn)
        """
        chunks = []
        
        # Tách document thành các section theo Điều/Mục/Chương
        sections = re.split(
            r'(?=^(?:#{1,3}\s+)?(?:Điều|ĐIỀU|Mục|MỤC|Chương|CHƯƠNG)\s+\d)',
            text,
            flags=re.MULTILINE
        )
        
        if len(sections) <= 1:
            # Không có cấu trúc Điều/Mục → chunking đơn giản theo đoạn
            sections = re.split(r'\n{2,}', text)
        
        # Tìm số công văn trong toàn bộ văn bản
        doc_code = self._extract_doc_code(text)
        # Tìm năm hiệu lực
        effective_year = self._extract_year(text)
        
        chunk_idx = 0
        current_table_buffer = []
        in_table = False
        
        for section in sections:
            section = section.strip()
            if not section:
                continue
            
            # Kiểm tra xem section có bảng không
            lines = section.split('\n')
            
            # Nếu section ngắn (<= 600 chars) và không có bảng → 1 chunk
            table_lines = [l for l in lines if l.strip().startswith('|')]
            has_table = len(table_lines) > 2
            
            if has_table or len(section) <= 600:
                # Giữ nguyên toàn bộ section (bảng không bị cắt)
                chunk_id = f"{source_name}_chunk_{chunk_idx:04d}"
                chunks.append({
                    "id": chunk_id,
                    "content": section,
                    "metadata": {
                        "doc_source": source_name,
                        "doc_code": doc_code or "",
                        "effective_year": effective_year or 0,
                        "chunk_index": chunk_idx,
                        "has_table": has_table,
                        "char_count": len(section)
                    }
                })
                chunk_idx += 1
            else:
                # Section dài → chia thành sub-chunks 450 chars với overlap 60
                sub_chunks = self._sliding_window_chunks(section, size=450, overlap=60)
                for sub in sub_chunks:
                    chunk_id = f"{source_name}_chunk_{chunk_idx:04d}"
                    chunks.append({
                        "id": chunk_id,
                        "content": sub,
                        "metadata": {
                            "doc_source": source_name,
                            "doc_code": doc_code or "",
                            "effective_year": effective_year or 0,
                            "chunk_index": chunk_idx,
                            "has_table": False,
                            "char_count": len(sub)
                        }
                    })
                    chunk_idx += 1
        
        return chunks
    
    def _sliding_window_chunks(self, text: str, size: int = 450, overlap: int = 60) -> list[str]:
        """Chia text thành chunks với sliding window, ưu tiên cắt tại ranh giới câu."""
        if len(text) <= size:
            return [text]
        
        chunks = []
        start = 0
        while start < len(text):
            end = min(start + size, len(text))
            # Tìm điểm cắt tự nhiên (cuối câu)
            if end < len(text):
                for sep in ['. ', '.\n', '\n', ' ']:
                    idx = text.rfind(sep, start + size // 2, end)
                    if idx > start:
                        end = idx + len(sep)
                        break
            chunks.append(text[start:end].strip())
            start = max(start + 1, end - overlap)
        
        return [c for c in chunks if c.strip()]
    
    def _extract_doc_code(self, text: str) -> Optional[str]:
        """Trích xuất số công văn từ văn bản (vd: 1084/QĐ-ĐHSPKT)."""
        pattern = re.compile(
            r'(?:Số|Số:)?\s*(\d{1,5}(?:\.\d+)?/[A-ZĐa-z0-9_.\-]+)',
            re.IGNORECASE
        )
        match = pattern.search(text[:500])  # Tìm ở đầu văn bản
        return match.group(1) if match else None
    
    def _extract_year(self, text: str) -> Optional[int]:
        """Trích xuất năm hiệu lực từ văn bản."""
        # Ưu tiên tìm theo cụm "năm học 2026" hoặc "năm 2026"
        academic_match = re.search(r'(?:năm\s+học|năm)\s*(202[0-9]|203[0-9])', text[:1000], re.IGNORECASE)
        if academic_match:
            return int(academic_match.group(1))
        # Tìm năm 4 chữ số trong range hợp lý (chọn năm mới nhất)
        matches = re.findall(r'\b(202[0-9]|203[0-9])\b', text[:1000])
        if matches:
            return max(int(y) for y in matches)
        return None
    
    def ask(self, question: str, department_id: Optional[int] = None) -> RagResult:
        """
        Trả lời câu hỏi học vụ dùng RAG pipeline.
        
        Pipeline:
          1. Guardrails: Lọc câu vớ vẩn
          2. Vector Search trong ChromaDB
          3. Build context từ chunks (token budget)
          4. Gemini sinh câu trả lời
          5. Đánh giá confidence score
        """
        start_ms = int(time.time() * 1000)
        
        if not self._is_ready:
            return RagResult(
                answer="Hệ thống AI đang khởi động. Vui lòng thử lại sau.",
                suggest_create_ticket=True
            )
        
        # Guardrail: Câu hỏi rỗng
        if not question or not question.strip():
            return RagResult(
                answer="Xin chào! Bạn có thể đặt câu hỏi về học vụ, học phí, xét tốt nghiệp để tôi hỗ trợ.",
                llm_generated=False
            )
        
        # Guardrail: Chitchat, lời chào & Câu hỏi ngoài lề (Off-topic)
        normalized_q = unicodedata.normalize('NFC', question.strip().lower())
        clean_q = re.sub(r'[\s\W_]+', ' ', normalized_q).strip()

        academic_pattern = r'.*(đkmh|đrl|avđr|cđr|ctđt|gdqp|học phí|hoc phi|học bổng|hoc bong|đăng ký|dang ky|môn học|mon hoc|học phần|hoc phan|tốt nghiệp|tot nghiep|chuyển điểm|chuyen diem|điểm|diem|cảnh cáo|canh cao|thôi học|thoi hoc|bảo lưu|bao luu|nghỉ học|nghi hoc|quy chế|quy che|công văn|cong van|thông báo|thong bao|khoa|phòng ban|phong ban|tín chỉ|tin chi|nguyện vọng|nguyen vong|xét tuyển|xet tuyen|spk|hcmute|học lại|hoc lai|cải thiện|cai thien).*'
        has_academic = bool(re.match(academic_pattern, clean_q))

        # 1. Lời chào thuần túy (không chứa từ khóa học vụ)
        if not has_academic:
            greeting_pattern = r'^(alo|alô|chào|chao|xin chào|xin chao|hi|hello|hey|hé lô|he lo|good morning)(\s+(bot|ad|admin|bạn|ban|thầy|thay|cô|co|ơi|oi|nha|nhé|nhe|ạ|a))*$'
            if re.match(greeting_pattern, clean_q) or clean_q in ["bạn là ai", "ban la ai", "bot là gì", "bot la gi", "trợ giúp", "hướng dẫn"]:
                return RagResult(
                    answer="Chào bạn! Tôi là Trợ lý Cố vấn Học vụ AI của Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE) 🎓.\n\nTôi sẵn sàng hỗ trợ bạn tra cứu và giải đáp các vấn đề về Quy chế học vụ, Đăng ký môn học, Điểm rèn luyện, Học phí và Học bổng. Bạn đang cần hỗ trợ vấn đề gì, hãy nhập câu hỏi bên dưới nhé!",
                    llm_generated=False,
                    confidence_score=0.0
                )
            
            # 2. Câu hỏi ngoài lề (Off-topic: chuyện cười, thời tiết, giải trí, yêu đương...)
            off_topic_pattern = r'.*(kể chuyện|chuyện cười|hát|làm thơ|chơi game|người yêu|yêu không|thời tiết|ăn gì|uống gì|xem phim|nghe nhạc|tán gái|tán gẫu|chém gió|bói toán|xem bói|tử vi|bao nhiêu tuổi|ở đâu đấy|mấy tuổi).*'
            if re.match(off_topic_pattern, clean_q):
                return RagResult(
                    answer="Dạ tôi là Trợ lý Cố vấn Học vụ AI của HCMUTE 🎓. Tôi chỉ hỗ trợ giải đáp các vấn đề liên quan đến quy chế học vụ, học phí, điểm rèn luyện, đăng ký môn học và thủ tục đào tạo của Trường.\n\nBạn vui lòng đặt câu hỏi cụ thể về học vụ để tôi hỗ trợ chính xác nhất nhé!",
                    llm_generated=False,
                    confidence_score=0.0
                )
        
        # 0. Mở rộng từ viết tắt học vụ (ĐRL -> Điểm rèn luyện, ĐKMH -> Đăng ký học phần...)
        expanded_q = abbreviations.expand(question)

        # 1. Vector Search
        try:
            results = self._collection.query(
                query_texts=[expanded_q],
                n_results=min(self.settings.top_k_chunks, self._collection.count() or 1),
                include=["documents", "metadatas", "distances"]
            )
        except Exception as e:
            logger.error(f"[RAG] ChromaDB query lỗi: {e}")
            return RagResult(
                answer="Hệ thống tìm kiếm tri thức tạm thời không khả dụng. Vui lòng thử lại.",
                suggest_create_ticket=True
            )
        
        # 2. Parse kết quả search & Tính điểm Re-ranking
        matched_chunks = []
        if results and results["documents"] and results["documents"][0]:
            docs = results["documents"][0]
            metas = results["metadatas"][0]
            distances = results["distances"][0]
            ids = results.get("ids", [[]])[0] if results.get("ids") else [None] * len(docs)
            
            for doc, meta, dist, cid in zip(docs, metas, distances, ids):
                # ChromaDB cosine distance [0,2] → similarity [0,1]
                similarity = max(0.0, 1.0 - dist / 2.0)

                # Re-ranking A: Hệ số suy giảm theo thời gian Time-Decay
                year_val = meta.get("effective_year")
                time_weight = 1.0
                if year_val is not None:
                    try:
                        y = int(year_val)
                        if y >= 2026:
                            time_weight = 1.00
                        elif y == 2025:
                            time_weight = 0.85
                        elif y == 2024:
                            time_weight = 0.70
                        else:
                            time_weight = 0.60
                    except (ValueError, TypeError):
                        time_weight = 0.70

                # Re-ranking B: Ưu tiên theo Khoa/Phòng (Department Scope Boost 1.25x)
                dept_weight = 1.0
                if department_id is not None and meta.get("department_id") is not None:
                    try:
                        if int(meta.get("department_id")) == int(department_id):
                            dept_weight = 1.25
                    except (ValueError, TypeError):
                        pass

                final_score = round(similarity * time_weight * dept_weight, 4)

                page_num = meta.get("page_number")
                if page_num is not None:
                    try:
                        page_num = int(page_num)
                    except (ValueError, TypeError):
                        page_num = None

                matched_chunks.append(ChunkMatch(
                    content=doc,
                    source=meta.get("doc_source", "unknown"),
                    score=final_score,
                    document_code=meta.get("doc_code") or None,
                    page_number=page_num,
                    effective_year=int(year_val) if year_val and str(year_val).isdigit() else None,
                    chunk_id=str(cid) if cid else None
                ))
            
            # Sắp xếp lại theo điểm số Re-rank từ cao xuống thấp
            matched_chunks.sort(key=lambda c: c.score, reverse=True)
        
        # 3. Confidence score = max similarity của chunk tốt nhất
        confidence = matched_chunks[0].score if matched_chunks else 0.0
        
        # 4. Build context string (token budget)
        context = self._build_context(matched_chunks)
        
        # 5. Gọi Gemini để sinh câu trả lời
        answer, llm_generated = self._generate_answer(question, context)
        
        # 6. Decide suggest ticket
        suggest_ticket = (
            confidence < self.settings.suggest_ticket_threshold
            or not matched_chunks
        )
        
        exec_ms = int(time.time() * 1000) - start_ms
        
        logger.info(
            f"[RAG] Q='{question[:50]}...' score={confidence:.3f} "
            f"llm={llm_generated} ticket={suggest_ticket} time={exec_ms}ms"
        )
        
        return RagResult(
            answer=answer,
            source_type="REGULATION",
            confidence_score=confidence,
            matched_chunks=matched_chunks,
            suggest_create_ticket=suggest_ticket,
            llm_generated=llm_generated,
            execution_time_ms=exec_ms
        )
    
    def _build_context(self, chunks: list[ChunkMatch]) -> str:
        """Build context string từ chunks với token budget."""
        if not chunks:
            return "Không tìm thấy văn bản quy chế trực tiếp liên quan."
        
        ctx_parts = []
        total_chars = 0
        
        for chunk in chunks:
            header = f"[Nguồn: {chunk.source}"
            if chunk.document_code:
                header += f" | Số: {chunk.document_code}"
            header += "]"
            
            entry = f"{header}\n{chunk.content}\n"
            
            if total_chars + len(entry) > self.settings.context_token_budget_chars and ctx_parts:
                break
            
            ctx_parts.append(entry)
            total_chars += len(entry)
        
        return "\n".join(ctx_parts)
    
    def _generate_answer(self, question: str, context: str) -> tuple[str, bool]:
        """
        Gọi Gemini Flash để tổng hợp câu trả lời.
        Trả về (answer, is_llm_generated).
        """
        if not self.settings.gemini_api_key or self.settings.gemini_api_key == "demo_key":
            # Demo mode: trả về context trực tiếp
            return (
                f"📋 *[Demo Mode - Chưa có Gemini API Key]* Thông tin tìm thấy:\n\n{context}\n\n"
                f"💡 Cài đặt GEMINI_API_KEY trong .env để nhận câu trả lời đầy đủ.",
                True
            )
        
        system_prompt = f"""Bạn là Cố vấn Học vụ chính thức của Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE).
Tuyệt đối không thoát vai, không bàn luận các chủ đề ngoài quy chế học vụ.

[THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:
{context}

[NGUYÊN TẮC TRẢ LỜI]:
1. Trả lời rõ ràng, ngắn gọn, lịch sự, chuẩn mực môi trường đại học.
2. Chỉ căn cứ vào thông tin ngữ cảnh được cung cấp.
3. Nếu ngữ cảnh không có thông tin, khuyên sinh viên gửi Ticket hỗ trợ.
4. Nếu thông tin có ngày tháng cụ thể, hãy trích dẫn chính xác."""

        user_msg = f"Câu hỏi của sinh viên: {question}"
        
        try:
            import httpx
            
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.settings.gemini_chat_model}:generateContent"
            
            response = httpx.post(
                url,
                params={"key": self.settings.gemini_api_key},
                json={
                    "contents": [{"parts": [{"text": system_prompt + "\n\n" + user_msg}]}]
                },
                timeout=30.0
            )
            response.raise_for_status()
            data = response.json()
            
            candidates = data.get("candidates", [])
            if candidates:
                parts = candidates[0].get("content", {}).get("parts", [])
                if parts:
                    return parts[0].get("text", ""), True
        
        except Exception as e:
            logger.warning(f"[RAG] Gemini API lỗi: {e}. Rơi về Smart Fallback.")
        
        # Smart Fallback: Trả thông báo súc tích kèm trích dẫn nguồn, không dump raw text dài
        fallback = (
            "📋 **Quy chế học vụ liên quan trích xuất từ văn bản chính thức của Nhà trường:**\n\n"
            "Hệ thống đã xác định công văn quy chế điều chỉnh nội dung bạn hỏi. Vui lòng xem thông tin chi tiết qua **Huy hiệu Nguồn** hoặc mở bài viết trên **Bảng tin** bên dưới.\n\n"
            "💡 *Nếu cần giải thích thêm, bạn có thể gửi Phiếu hỗ trợ (Ticket) để Cán bộ Phòng ban chuyên trách hỗ trợ trực tiếp.*"
        )
        return fallback, False
    
    def get_collection_stats(self) -> dict:
        """Trả về thống kê về ChromaDB collection."""
        if not self._is_ready or not self._collection:
            return {"status": "not_initialized", "count": 0}
        return {
            "status": "ready",
            "count": self._collection.count(),
            "chroma_dir": str(self.settings.chroma_persist_dir)
        }
    
    def reset_collection(self) -> bool:
        """Xóa toàn bộ dữ liệu trong collection (dùng khi cần re-ingest)."""
        try:
            self._chroma_client.delete_collection("qaute_knowledge")
            self._collection = self._chroma_client.get_or_create_collection(
                name="qaute_knowledge",
                embedding_function=self._embedding_function,
                metadata={"hnsw:space": "cosine"}
            )
            logger.info("[RAG] ✅ Collection đã được reset.")
            return True
        except Exception as e:
            logger.error(f"[RAG] Reset collection lỗi: {e}")
            return False
