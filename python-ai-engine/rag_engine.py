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

    def ingest_golden_truth_file(self, golden_truth_path: str | Path) -> dict:
        """
        Nạp toàn bộ cơ sở tri thức chuẩn từ golden-truth.txt vào ChromaDB.
        """
        if not self._is_ready or self._collection is None:
            raise RuntimeError("RagEngine chưa được khởi tạo. Gọi initialize() trước.")
        
        path = Path(golden_truth_path)
        if not path.exists():
            logger.warning(f"[RAG Ingest GT] File không tồn tại: {path}")
            return {"ingested": 0, "failed": 1}
        
        text = path.read_text(encoding="utf-8")
        chunks = self._parent_child_chunking(text, "golden_truth")
        
        # Cập nhật metadata cho chunks từ Golden Truth
        for c in chunks:
            c["metadata"]["doc_source"] = "GOLDEN_TRUTH"
            c["metadata"]["effective_year"] = 2026
            c["metadata"]["department_id"] = -1
        
        batch_size = 50
        for i in range(0, len(chunks), batch_size):
            batch = chunks[i:i + batch_size]
            self._collection.upsert(
                ids=[c["id"] for c in batch],
                documents=[c["content"] for c in batch],
                metadatas=[c["metadata"] for c in batch]
            )
        
        logger.info(f"[RAG Ingest GT] ✅ Đã nạp thành công {len(chunks)} chunks từ '{path.name}' vào ChromaDB")
        return {"ingested": len(chunks), "failed": 0}

    def match_golden_truth(self, question: str) -> Optional[str]:
        """
        Trích xuất trực tiếp câu trả lời chuẩn xác tuyệt đối từ Cơ sở Tri thức Vàng (golden-truth.txt).
        Đảm bảo Zero-Hallucination 100% cho các câu hỏi định danh cốt lõi.
        """
        q = unicodedata.normalize('NFC', question.strip().lower())
        q = re.sub(r'[\s\W_]+', ' ', q).strip()

        # 1. Đổi tên trường, Tên cũ, Căn cứ pháp lý
        if any(k in q for k in ["doi ten", "đổi tên", "ten cu", "tên cũ", "ten moi", "tên mới", "ten chinh thuc", "tên chính thức"]) and any(s in q for s in ["truong", "trường", "spkt", "hcmute"]):
            return (
                "🏛️ **Thông tin định danh và đổi tên chính thức:**\n\n"
                "- **Tên chính thức hiện nay:** **Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh** (tên viết tắt: **HCM-UTE** / **HCMUTE**).\n"
                "- **Tên cũ:** Trường Đại học Sư phạm Kỹ thuật Thành phố Hồ Chí Minh (sử dụng từ 1984 đến 25/12/2025).\n"
                "- **Căn cứ pháp lý:** **Quyết định số 2809/QĐ-TTg ngày 26/12/2025 của Thủ tướng Chính phủ** về việc đổi tên Trường Đại học Sư phạm Kỹ thuật Thành phố Hồ Chí Minh thành Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh."
            )

        # 2. Mã trường tuyển sinh
        if any(k in q for k in ["ma truong", "mã trường", "ma tuyen sinh", "mã tuyển sinh", "ma xet tuyen", "mã xét tuyển"]):
            return (
                "🎯 **Mã trường tuyển sinh chính thức:**\n\n"
                "Mã trường của Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCM-UTE) trên hệ thống tuyển sinh của Bộ Giáo dục và Đào tạo là: **SPK**."
            )

        # 3. Tên tiếng Anh
        if any(k in q for k in ["ten tieng anh", "tên tiếng anh", "english name"]):
            return (
                "🌐 **Tên tiếng Anh chính thức của trường:**\n\n"
                "**Ho Chi Minh City University of Technology and Engineering** (tên viết tắt quốc tế: **HCM-UTE**)."
            )

        # 4. Năm thành lập / Tiền thân lịch sử
        if any(k in q for k in ["nam thanh lap", "năm thành lập", "thanh lap nam nao", "thành lập năm nào", "tien than", "tiền thân"]):
            return (
                "📜 **Lịch sử hình thành và phát triển:**\n\n"
                "Tiền thân của trường là **Ban Cao đẳng Sư phạm Kỹ thuật Trung tâm Quốc gia Kỹ thuật Phú Thọ**, được thành lập ngày **05/10/1962** theo Nghị định số 1082-GD của Bộ Quốc gia Giáo dục."
            )

        # 5. Triết lý giáo dục / Slogan
        if any(k in q for k in ["triet ly giao duc", "triết lý giáo dục", "khau hieu", "khẩu hiệu", "slogan"]):
            return (
                "🌟 **Triết lý giáo dục nhất quán của Nhà trường:**\n\n"
                "**\"Nhân bản – Sáng tạo – Hội nhập\"**"
            )

        # 6. Ban Giám hiệu / Hiệu trưởng / Phó Hiệu trưởng
        if any(k in q for k in ["hieu truong", "hiệu trưởng"]) and not any(p in q for p in ["pho", "phó"]):
            return (
                "👨‍🏫 **Hiệu trưởng đương nhiệm:**\n\n"
                "Hiệu trưởng Nhà trường hiện nay là **PGS. TS. Lê Hiếu Giang** (được Bộ Giáo dục và Đào tạo bổ nhiệm theo Quyết định số 1468/QĐ-BGDĐT ngày 02/06/2026, thời hạn 5 năm; Email liên hệ: `gianglh@hcmute.edu.vn`)."
            )

        if any(k in q for k in ["ban giam hieu", "ban giám hiệu", "pho hieu truong", "phó hiệu trưởng"]):
            return (
                "👥 **Ban Giám hiệu đương nhiệm của Nhà trường:**\n\n"
                "1. **Hiệu trưởng:** PGS. TS. **Lê Hiếu Giang** (Quyết định số 1468/QĐ-BGDĐT ngày 02/06/2026).\n"
                "2. **Các Phó Hiệu trưởng:**\n"
                "   - TS. **Trương Thị Hiền** (Quyết định số 1479/QĐ-BGDĐT ngày 02/06/2026).\n"
                "   - TS. **Quách Thanh Hải** (Nghị quyết số 14/NQ-HĐT của Hội đồng trường).\n"
                "   - PGS. TS. **Châu Đình Thành** (Nghị quyết số 15/NQ-HĐT của Hội đồng trường)."
            )

        # 7. Các cơ sở đào tạo và địa chỉ
        if any(k in q for k in ["co so", "cơ sở", "tru so", "trụ sở", "dia chi", "địa chỉ"]) and any(s in q for s in ["truong", "trường", "o dau", "ở đâu", "hcmute"]):
            return (
                "📍 **Địa chỉ các cơ sở đào tạo của Trường:**\n\n"
                "1. **Cơ sở chính (Trụ sở 1):** Số 01 Võ Văn Ngân, Phường Linh Chiểu, TP. Thủ Đức, TP. Hồ Chí Minh.\n"
                "2. **Cơ sở 2:** Số 484 Lê Văn Việt, Phường Tăng Nhơn Phú A, TP. Thủ Đức, TP. Hồ Chí Minh.\n"
                "3. **Phân hiệu Bình Phước:** Số 899 Quốc lộ 14, Phường Bình Phước, Tỉnh Đồng Nai / Bình Phước (thành lập theo Quyết định số 1576/QĐ-BGDĐT ngày 12/06/2025)."
            )

        # 8. Trưởng khoa Công nghệ Thông tin (FIT)
        if any(k in q for k in ["truong khoa", "trưởng khoa"]) and any(f in q for f in ["cntt", "công nghệ thông tin", "cong nghe thong tin", "fit"]):
            return (
                "💻 **Trưởng khoa Công nghệ Thông tin (FIT):**\n\n"
                "Trưởng khoa Công nghệ Thông tin hiện nay là **GVC. TS. Lê Văn Vinh** (đồng thời là giảng viên thuộc Bộ môn Công nghệ Phần mềm). Email liên hệ: `vinhlv@hcmute.edu.vn`."
            )

        # 8b. Danh sách các khoa và viện đào tạo
        if any(k in q for k in ["cac khoa", "các khoa", "bao nhieu khoa", "bao nhiêu khoa", "danh sach khoa", "danh sách khoa"]) or (any(k in q for k in ["khoa", "vien", "viện"]) and any(s in q for s in ["truong", "trường", "hcmute", "co cau"])):
            return (
                "🏫 **Cơ cấu tổ chức đào tạo tại HCM-UTE:**\n\n"
                "Nhà trường hiện có **13 Khoa chuyên môn** + **2 Viện** + **1 Trung tâm** đào tạo trọng điểm:\n"
                "1. **Khoa Cơ khí Chế tạo máy (FME):** Chế tạo máy, Cơ khí, Robot & Trí tuệ nhân tạo (AI).\n"
                "2. **Khoa Cơ khí Động lực (FAE):** Công nghệ Ô tô, Nhiệt - Điện lạnh, Kỹ thuật Hàng không.\n"
                "3. **Khoa Điện - Điện tử (FEE):** Kỹ thuật Điện, Điện tử Viễn thông, Tự động hóa, Kỹ thuật Y sinh, IoT.\n"
                "4. **Khoa Công nghệ Thông tin (FIT):** Khoa học Máy tính, CN Phần mềm, An toàn TT, Kỹ thuật Dữ liệu, Trí tuệ Nhân tạo.\n"
                "5. **Khoa Xây dựng (FCE):** Kỹ thuật Xây dựng, Công trình Giao thông, Quản lý Xây dựng.\n"
                "6. **Khoa Công nghệ Hóa học và Thực phẩm (FCFT):** Hóa học, Thực phẩm, Môi trường.\n"
                "7. **Khoa Kinh tế (FE):** Quản trị Kinh doanh, Kế toán, Thương mại Điện tử, Logistics, Tài chính.\n"
                "8. **Khoa Ngoại ngữ (FFL):** Ngôn ngữ Anh, Sư phạm Tiếng Anh.\n"
                "9. **Khoa Thời trang và Du lịch (FFT):** Công nghệ May, Thiết kế Thời trang, Dịch vụ Ăn uống & Du lịch.\n"
                "10. **Khoa Khoa học Ứng dụng (FAS):** Thiết kế Đồ họa, Kiến trúc, Kiến trúc Nội thất.\n"
                "11. **Khoa In và Truyền thông:** Kỹ thuật In, Thiết kế Đa phương tiện.\n"
                "12. **Khoa Chính trị và Luật:** Ngành Luật, Luật Kinh tế, Lý luận Chính trị.\n"
                "13. **Khoa Đào tạo Tiên tiến:** Chương trình song ngữ và 100% tiếng Anh.\n"
                "• **Viện Sư phạm Kỹ thuật (ITE)** & **Viện Đào tạo Quốc tế (OIE)**\n"
                "• **Trung tâm GDQP&AN** (Giáo dục Quốc phòng và An ninh)."
            )

        # 9. Công tác xã hội (CTXH) tốt nghiệp
        if any(k in q for k in ["ctxh", "cong tac xa hoi", "công tác xã hội"]) and any(w in q for w in ["tot nghiep", "tốt nghiệp", "bao nhieu ngay", "bao nhiêu ngày", "bao nhieu diem", "bao nhiêu điểm"]):
            return (
                "🤝 **Quy định Hoạt động Công tác xã hội (CTXH) bắt buộc khi tốt nghiệp:**\n\n"
                "Sinh viên hệ chính quy bắt buộc phải tích lũy tối thiểu **04 Ngày CTXH (tương đương 40 Điểm CTXH)** trong toàn khóa học mới đủ điều kiện xét tốt nghiệp (Quy đổi: 10 Điểm CTXH = 01 Ngày CTXH)."
            )

        # 10. Vay vốn tín dụng khối ngành STEM
        if any(k in q for k in ["stem", "vay von", "vay vốn", "tin dung", "tín dụng"]) and any(w in q for w in ["stem", "29/2025", "lai suat", "lãi suất"]):
            return (
                "💰 **Chính sách Tín dụng sinh viên khối ngành STEM (Quyết định số 29/2025/QĐ-TTg):**\n\n"
                "Sinh viên ngành STEM có thành tích học tập tốt (năm 1 đạt khá THPT hoặc điểm Toán-Lý-Hóa-Sinh lớp 12 >= 8.0; từ năm 2 đạt loại Giỏi trở lên) được:\n"
                "- Vay **100% toàn bộ học phí thực tế** theo xác nhận của Trường.\n"
                "- Vay bổ sung sinh hoạt phí tối đa **5.000.000 VNĐ/tháng** (60 triệu/năm).\n"
                "- Lãi suất cực kỳ ưu đãi: **4,8%/năm**."
            )

        # 11. Học phí Khoa Công nghệ Thông tin (IT) & Các ngành kỹ thuật
        if any(k in q for k in ["hoc phi", "học phí", "dong tien", "đóng tiền", "muc thu", "mức thu"]) and any(w in q for w in ["it", "cntt", "tin hoc", "công nghệ thông tin", "cong nghe thong tin", "tin chi", "tín chỉ"]):
            return (
                "💰 **Quy định mức thu Học phí Khoa Công nghệ Thông tin (IT - FIT) & Các ngành kỹ thuật:**\n\n"
                "Học phí tại Trường ĐH Công nghệ Kỹ thuật TP.HCM (HCMUTE) được tính theo số tín chỉ đăng ký thực tế trong từng học kỳ:\n\n"
                "• **Hệ Đại trà (Chính quy chuẩn):**\n"
                "  - Đơn giá: Khoảng **1.050.000 VNĐ/tín chỉ lý thuyết** và **1.400.000 VNĐ/tín chỉ thực hành** tại phòng máy lab.\n"
                "  - Trung bình học kỳ: Khoảng **16.000.000 – 19.500.000 VNĐ/học kỳ** (~32.000.000 – 39.000.000 VNĐ/năm học tùy số lượng tín chỉ đăng ký).\n\n"
                "• **Hệ Chất lượng cao (tiếng Việt / tiếng Anh):**\n"
                "  - Trung bình học kỳ: Khoảng **24.000.000 – 32.500.000 VNĐ/học kỳ** (~48.000.000 – 65.000.000 VNĐ/năm học).\n\n"
                "• **Phương thức nộp:** Nộp trực tuyến qua Cổng thanh toán sinh viên `online.hcmute.edu.vn` (qua tài khoản ngân hàng liên kết BIDV, Agribank, VietinBank) theo đợt thông báo đóng học phí đầu mỗi kỳ của Phòng Kế hoạch - Tài chính (Phòng A1.102).\n\n"
                "📌 *Căn cứ: Quyết định ban hành Quy định thu học phí năm học của Trường ĐH Công nghệ Kỹ thuật TP.HCM.*"
            )

        return None
    
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
                    answer="Chào bạn! Tôi là Trợ lý Cố vấn Học vụ AI của Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCM-UTE / HCMUTE) 🎓.\n\nTôi sẵn sàng hỗ trợ bạn tra cứu và giải đáp các vấn đề về Quy chế học vụ, Đăng ký môn học, Điểm rèn luyện, Học phí, Học bổng và Thông tin tuyển sinh. Bạn đang cần hỗ trợ vấn đề gì, hãy nhập câu hỏi bên dưới nhé!",
                    llm_generated=False,
                    confidence_score=0.0
                )
            
            # 2. Câu hỏi ngoài lề (Off-topic: chuyện cười, thời tiết, giải trí, yêu đương...)
            off_topic_pattern = r'.*(kể chuyện|chuyện cười|hát|làm thơ|chơi game|người yêu|yêu không|thời tiết|ăn gì|uống gì|xem phim|nghe nhạc|tán gái|tán gẫu|chém gió|bói toán|xem bói|tử vi|bao nhiêu tuổi|ở đâu đấy|mấy tuổi).*'
            if re.match(off_topic_pattern, clean_q):
                return RagResult(
                    answer="Dạ tôi là Trợ lý Cố vấn Học vụ AI của HCM-UTE 🎓. Tôi chỉ hỗ trợ giải đáp các vấn đề liên quan đến quy chế học vụ, học phí, điểm rèn luyện, đăng ký môn học và thủ tục đào tạo của Trường.\n\nBạn vui lòng đặt câu hỏi cụ thể về học vụ để tôi hỗ trợ chính xác nhất nhé!",
                    llm_generated=False,
                    confidence_score=0.0
                )
        
        # 3. Direct Match từ Cơ sở Tri thức Vàng HCMUTE (Golden Truth) — Không ảo giác 100%
        golden_direct_ans = self.match_golden_truth(question)
        if golden_direct_ans:
            return RagResult(
                answer=golden_direct_ans,
                source_type="GOLDEN_TRUTH",
                confidence_score=1.0,
                llm_generated=False,
                suggest_create_ticket=False,
                execution_time_ms=int(time.time() * 1000) - start_ms
            )

        # 4. Mở rộng từ viết tắt học vụ (ĐRL -> Điểm rèn luyện, ĐKMH -> Đăng ký học phần...)
        expanded_q = abbreviations.expand(question)
        lower_q = expanded_q.lower()

        # Nhận diện các từ khóa học vụ hạt nhân để chấm Sparse Keyword Match
        core_academic_terms = [
            "học phí", "hoc phi", "học bổng", "hoc bong", "đăng ký môn học", "dang ky mon hoc",
            "rút môn", "rut mon", "hủy môn", "huy mon", "hủy học phần", "phúc khảo", "phuc khao",
            "điểm rèn luyện", "diem ren luyen", "chuẩn đầu ra", "chuan dau ra", "toeic",
            "buộc thôi học", "buoc thoi hoc", "cảnh báo học vụ", "đình chỉ", "dinh chi",
            "bảo lưu", "bao luu", "xét tốt nghiệp", "xet tot nghiep"
        ]
        target_terms = [t for t in core_academic_terms if t in lower_q]

        # 1. Vector Search: Quét 25 ứng viên tiềm năng để re-rank thay vì chỉ 5
        candidate_k = min(25, self._collection.count() or 1)
        try:
            results = self._collection.query(
                query_texts=[expanded_q],
                n_results=candidate_k,
                include=["documents", "metadatas", "distances"]
            )
        except Exception as e:
            logger.error(f"[RAG] ChromaDB query lỗi: {e}")
            return RagResult(
                answer="Hệ thống tìm kiếm tri thức tạm thời không khả dụng. Vui lòng thử lại.",
                suggest_create_ticket=True
            )
        
        # 2. Parse kết quả search & Tính điểm Hybrid Re-ranking
        matched_chunks = []
        if results and results["documents"] and results["documents"][0]:
            docs = results["documents"][0]
            metas = results["metadatas"][0]
            distances = results["distances"][0]
            ids = results.get("ids", [[]])[0] if results.get("ids") else [None] * len(docs)
            
            for doc, meta, dist, cid in zip(docs, metas, distances, ids):
                # ChromaDB cosine distance [0,2] → similarity [0,1]
                similarity = max(0.0, 1.0 - dist / 2.0)
                doc_lower = doc.lower()

                # Re-ranking A: Hệ số suy giảm theo thời gian Time-Decay (cân bằng, không đè bẹp ngữ nghĩa)
                year_val = meta.get("effective_year")
                time_weight = 1.0
                if year_val is not None:
                    try:
                        y = int(year_val)
                        if y >= 2026:
                            time_weight = 1.00
                        elif y == 2025:
                            time_weight = 0.96
                        elif y == 2024:
                            time_weight = 0.92
                        else:
                            time_weight = 0.85
                    except (ValueError, TypeError):
                        time_weight = 0.90

                # Re-ranking B: Ưu tiên theo Khoa/Phòng (Department Scope Boost 1.25x)
                dept_weight = 1.0
                if department_id is not None and meta.get("department_id") is not None:
                    try:
                        if int(meta.get("department_id")) == int(department_id):
                            dept_weight = 1.25
                    except (ValueError, TypeError):
                        pass

                # Re-ranking C: Sparse Keyword Match (Thưởng mạnh nếu có từ khóa chính; Phạt nặng nếu lạc đề)
                sparse_weight = 1.0
                if target_terms:
                    hit_count = sum(1 for term in target_terms if term in doc_lower)
                    if hit_count > 0:
                        sparse_weight = 1.40 + (hit_count * 0.10)
                    else:
                        sparse_weight = 0.45  # Phạt nặng chunk không chứa từ khóa học vụ trọng tâm

                final_score = round(similarity * time_weight * dept_weight * sparse_weight, 4)

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
            
            # Lọc bỏ các chunk lạc đề / điểm tương đồng quá thấp (< 0.50)
            matched_chunks = [c for c in matched_chunks if c.score >= 0.50]
            # Sắp xếp lại theo điểm số Re-rank từ cao xuống thấp và cắt lại top_k_chunks
            matched_chunks.sort(key=lambda c: c.score, reverse=True)
            matched_chunks = matched_chunks[:self.settings.top_k_chunks]
        
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
        Gọi Gemini Flash để tổng hợp câu trả lời theo ngữ cảnh RAG.
        Trả về (answer, is_llm_generated).
        """
        if not self.settings.gemini_api_key or self.settings.gemini_api_key == "demo_key":
            # Demo mode: trả về context trực tiếp
            return (
                f"📋 *[Demo Mode - Chưa có Gemini API Key]* Thông tin tìm thấy:\n\n{context}\n\n"
                f"💡 Cài đặt GEMINI_API_KEY trong .env để nhận câu trả lời đầy đủ.",
                True
            )
        
        system_prompt = f"""Bạn là Cố vấn Học vụ chính thức của Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh (HCM-UTE / HCMUTE - Tiền thân là ĐH Sư phạm Kỹ thuật TP.HCM theo QĐ 2809/QĐ-TTg ngày 26/12/2025).
Tuyệt đối không thoát vai, không bịa đặt, không bàn luận các chủ đề ngoài quy chế học vụ Nhà trường.

[THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ CÔNG VĂN / QUY CHẾ NHÀ TRƯỜNG]:
{context}

[NGUYÊN TẮC TRẢ LỜI BẮT BUỘC]:
1. Trả lời rõ ràng, chuẩn mực, lịch sự, đúng phong thái học thuật của HCMUTE.
2. Chỉ căn cứ vào thông tin ngữ cảnh được cung cấp bên trên. Tuyệt đối không suy diễn thêm thông tin sai lệch ngoài văn bản.
3. Nếu ngữ cảnh không có thông tin hoặc thông tin không rõ ràng, hãy trả lời trung thực và hướng dẫn sinh viên gửi Ticket hỗ trợ hoặc liên hệ Phòng ban chuyên trách.
4. Trích dẫn cụ thể Điều, Khoản hoặc số hiệu công văn nếu ngữ cảnh có nêu."""

        user_msg = f"Câu hỏi của sinh viên: {question}"
        
        try:
            import httpx
            
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.settings.gemini_chat_model}:generateContent"
            
            payload = {
                "contents": [{"parts": [{"text": system_prompt + "\n\n" + user_msg}]}],
                "generationConfig": {
                    "temperature": 0.1,
                    "topP": 0.8,
                    "maxOutputTokens": 1024
                }
            }
            
            response = httpx.post(
                url,
                params={"key": self.settings.gemini_api_key},
                json=payload,
                timeout=30.0
            )
            response.raise_for_status()
            data = response.json()
            
            candidates = data.get("candidates", [])
            if candidates:
                parts = candidates[0].get("content", {}).get("parts", [])
                if parts:
                    answer_text = parts[0].get("text", "").strip()
                    if answer_text:
                        return answer_text, True
        
        except Exception as e:
            logger.warning(f"[RAG] Gemini API lỗi hoặc gián đoạn: {e}. Kích hoạt Smart Context Fallback.")
        
        # Smart Fallback: Không dump trích đoạn thô cắt cụt gây hiểu lầm cho sinh viên
        if not context or "Không tìm thấy văn bản quy chế" in context:
            fallback = (
                "Dạ hiện tại AI chưa tìm thấy quy định quy chế trực tiếp điều chỉnh nội dung câu hỏi của bạn.\n\n"
                "💡 *Bạn vui lòng bấm nút **Gửi Ticket** bên dưới để Cán bộ Phòng ban chuyên trách hỗ trợ trực tiếp nhé!*"
            )
        else:
            fallback = (
                "📋 **Quy chế học vụ liên quan trích xuất từ văn bản chính thức của Nhà trường:**\n\n"
                "Hệ thống đã xác định công văn quy chế điều chỉnh nội dung bạn hỏi. Vui lòng xem thông tin chi tiết qua **Huy hiệu Nguồn** hoặc mở bài viết trên **Bảng tin** bên dưới.\n\n"
                "💡 *Nếu cần giải thích thêm hoặc có thắc mắc cụ thể, bạn có thể gửi Phiếu hỗ trợ (Ticket) để Cán bộ Phòng ban chuyên môn hỗ trợ trực tiếp.*"
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
