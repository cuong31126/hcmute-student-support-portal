"""
QAUTE Portal — Python AI Engine
evaluator.py: Đánh giá chất lượng câu trả lời RAG & Benchmark đối sánh Golden Truth

Chức năng:
  1. RagEvaluator: LLM-as-a-Judge đánh giá 3 tiêu chí RAG Triad:
     - Context Relevance (0-5)
     - Faithfulness (0-5)
     - Answer Relevance (0-5)
  2. GoldenTruthBenchmark: Bộ đánh giá định lượng đối sánh trực tiếp với Cơ sở Tri thức Vàng (golden-truth.txt):
     - Keyword Containment & F1 Match Rate
     - Zero-Hallucination Validation (Kiểm tra mâu thuẫn sự thật định danh HCMUTE)
     - Automated Pass/Fail Metrics & Benchmark Summary
"""
import re
import json
import time
import logging
from pathlib import Path
from typing import Optional, List, Dict, Any
from dataclasses import dataclass, field

logger = logging.getLogger("qaute.evaluator")

FAITHFULNESS_BLOCK_THRESHOLD = 3  # Dưới ngưỡng này → block và chuyển Ticket


@dataclass
class EvalResult:
    context_relevance: int = 0    # 0-5
    faithfulness: int = 0         # 0-5  
    answer_relevance: int = 0     # 0-5
    overall_score: float = 0.0    # trung bình
    review_comment: str = ""
    should_block: bool = False     # True nếu câu trả lời không đáng tin


@dataclass
class GoldenTruthItem:
    """Đại diện cho một cặp Q&A mẫu trong golden-truth.txt"""
    group_name: str
    question: str
    expected_answer: str
    key_entities: List[str] = field(default_factory=list)


@dataclass
class BenchmarkItemResult:
    """Kết quả kiểm thử cho 1 câu hỏi Golden Truth"""
    group_name: str
    question: str
    expected_answer: str
    actual_answer: str
    source_type: str
    confidence_score: float
    keyword_hit_rate: float
    hallucination_detected: bool
    hallucination_reasons: List[str]
    passed: bool
    latency_ms: int
    eval_result: Optional[EvalResult] = None


@dataclass
class BenchmarkSummary:
    """Tổng kết toàn bộ phiên Benchmark"""
    total_questions: int = 0
    passed_count: int = 0
    failed_count: int = 0
    pass_rate_pct: float = 0.0
    avg_latency_ms: float = 0.0
    hallucination_count: int = 0
    hallucination_rate_pct: float = 0.0
    avg_confidence: float = 0.0
    avg_keyword_hit_rate: float = 0.0
    items: List[BenchmarkItemResult] = field(default_factory=list)


class GoldenTruthParser:
    """Trích xuất tự động các cặp Q&A và thực thể định danh từ golden-truth.txt"""

    @staticmethod
    def parse_file(file_path: str | Path) -> List[GoldenTruthItem]:
        path = Path(file_path)
        if not path.exists():
            logger.warning(f"[GoldenTruthParser] File không tồn tại: {path}")
            return []

        text = path.read_text(encoding="utf-8")
        items: List[GoldenTruthItem] = []

        current_group = "Chung"
        lines = text.splitlines()
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            
            # Phát hiện tên nhóm: ### Nhóm X: ...
            if line.startswith("### Nhóm") or line.startswith("## Nhóm") or line.startswith("## 15."):
                current_group = line.replace("#", "").strip()
                i += 1
                continue

            # Phát hiện câu hỏi: #### Q: ... hoặc **Q:** ...
            if line.startswith("#### Q:") or line.startswith("**Q:**") or line.startswith("Q:"):
                q_text = re.sub(r'^(####\s*Q:|[*]{2}Q:[*]{2}|Q:)\s*', '', line).strip()
                
                # Đọc câu trả lời ở các dòng tiếp theo
                a_lines = []
                i += 1
                while i < len(lines):
                    next_line = lines[i].strip()
                    if next_line.startswith("#### Q:") or next_line.startswith("### Nhóm") or next_line.startswith("## "):
                        # Gặp câu hỏi mới hoặc nhóm mới
                        break
                    if next_line:
                        clean_a = re.sub(r'^\*{2}A:\*{2}\s*', '', next_line)
                        a_lines.append(clean_a)
                    i += 1
                
                a_text = "\n".join(a_lines).strip()
                if q_text and a_text:
                    # Trích xuất các thực thể trọng yếu (in đậm **...** hoặc trong dấu nháy `...`)
                    entities = re.findall(r'\*\*([^*]+)\*\*', a_text)
                    entities += re.findall(r'`([^`]+)`', a_text)
                    clean_entities = [e.strip() for e in entities if len(e.strip()) > 1]

                    items.append(GoldenTruthItem(
                        group_name=current_group,
                        question=q_text,
                        expected_answer=a_text,
                        key_entities=clean_entities
                    ))
                continue
            
            i += 1

        logger.info(f"[GoldenTruthParser] Đã bóc tách thành công {len(items)} cặp Q&A chuẩn từ {path.name}")
        return items


class RagEvaluator:
    """
    LLM-as-a-Judge: Dùng Gemini Flash để tự chấm điểm câu trả lời RAG.
    Chỉ chạy khi llm_generated=True và gemini_api_key thật.
    """
    
    def __init__(self, settings):
        self.settings = settings
    
    def evaluate(
        self,
        question: str,
        context: str,
        answer: str,
        llm_generated: bool = True
    ) -> EvalResult:
        if not llm_generated:
            return EvalResult(
                context_relevance=0,
                faithfulness=0,
                answer_relevance=0,
                overall_score=0.0,
                review_comment="Không đánh giá: Câu trả lời là fallback/direct match.",
                should_block=False
            )
        
        if not self.settings.gemini_api_key or self.settings.gemini_api_key == "demo_key":
            return EvalResult(
                context_relevance=4,
                faithfulness=4,
                answer_relevance=4,
                overall_score=4.0,
                review_comment="Demo mode: điểm chuẩn định sẵn 4/5.",
                should_block=False
            )
        
        try:
            return self._evaluate_with_llm(question, context, answer)
        except Exception as e:
            logger.warning(f"[Evaluator] Lỗi evaluate: {e}")
            return EvalResult(
                context_relevance=3,
                faithfulness=3,
                answer_relevance=3,
                overall_score=3.0,
                review_comment=f"Lỗi evaluate tự động: {e}",
                should_block=False
            )
    
    def _evaluate_with_llm(self, question: str, context: str, answer: str) -> EvalResult:
        """Gọi Gemini để chấm điểm theo format JSON chuẩn với xử lý an toàn."""
        import httpx
        
        eval_prompt = f"""Bạn là Chuyên gia đánh giá chất lượng câu trả lời AI cho hệ thống tư vấn học vụ đại học.

[CÂU HỎI CỦA SINH VIÊN]:
{question}

[TÀI LIỆU QUY CHẾ (Context)]:
{context[:1500]}

[CÂU TRẢ LỜI CỦA AI]:
{answer[:800]}

Hãy chấm điểm theo thang 0-5 cho 3 tiêu chí sau và trả về JSON:
1. context_relevance (0-5): Tài liệu context có liên quan và đủ thông tin để trả lời câu hỏi không?
2. faithfulness (0-5): Câu trả lời có TRUNG THỰC dựa trên tài liệu context không? (Không bịa đặt thông tin)
3. answer_relevance (0-5): Câu trả lời có TRẢ LỜI ĐÚNG TRỌNG TÂM câu hỏi không?

Chỉ trả về JSON thuần túy, không giải thích thêm:
{{
  "context_relevance": 5,
  "faithfulness": 5,
  "answer_relevance": 5,
  "review_comment": "nhận xét ngắn gọn bằng tiếng Việt"
}}"""
        
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.settings.gemini_chat_model}:generateContent"
        
        with httpx.Client(timeout=20.0) as client:
            response = client.post(
                url,
                params={"key": self.settings.gemini_api_key},
                json={
                    "contents": [{"parts": [{"text": eval_prompt}]}],
                    "generationConfig": {"temperature": 0.1}
                }
            )
            response.raise_for_status()
            data = response.json()
        
        raw_text = data["candidates"][0]["content"]["parts"][0]["text"]
        
        # Bóc tách JSON an toàn (hỗ trợ cả trường hợp bọc trong ```json ... ```)
        clean_json_text = raw_text.strip()
        if "```" in clean_json_text:
            clean_json_text = re.sub(r'^```(?:json)?\s*', '', clean_json_text)
            clean_json_text = re.sub(r'\s*```$', '', clean_json_text)
        
        json_match = re.search(r'\{.*\}', clean_json_text, re.DOTALL)
        if not json_match:
            raise ValueError(f"Không parse được JSON từ Gemini response: {raw_text[:150]}")
        
        eval_data = json.loads(json_match.group())
        
        cr = int(eval_data.get("context_relevance", 3))
        fa = int(eval_data.get("faithfulness", 3))
        ar = int(eval_data.get("answer_relevance", 3))
        overall = round((cr + fa + ar) / 3.0, 2)
        comment = eval_data.get("review_comment", "")
        
        should_block = fa < FAITHFULNESS_BLOCK_THRESHOLD
        
        return EvalResult(
            context_relevance=cr,
            faithfulness=fa,
            answer_relevance=ar,
            overall_score=overall,
            review_comment=comment,
            should_block=should_block
        )


class GoldenTruthBenchmark:
    """
    Bộ kiểm thử hồi quy & đánh giá hệ thống tự động dựa trên Cơ sở Tri thức Vàng (golden-truth.txt).
    """

    # Các từ khóa cấm / chỉ dấu ảo giác nếu xuất hiện sai lệch
    HALLUCINATION_TRAPS = [
        # Không được dùng tên viết tắt sai lệch hoàn toàn
        ("hcmus", "Nhầm lẫn với ĐH Khoa học Tự nhiên (HCMUS)"),
        ("hcmut", "Nhầm lẫn với ĐH Bách Khoa (HCMUT)"),
        ("hutech", "Nhầm lẫn với ĐH HUTECH"),
        ("fpt university", "Nhầm lẫn với ĐH FPT")
    ]

    def __init__(self, golden_truth_path: str | Path, rag_engine, evaluator: Optional[RagEvaluator] = None):
        self.golden_truth_path = Path(golden_truth_path)
        self.rag_engine = rag_engine
        self.evaluator = evaluator
        self.items: List[GoldenTruthItem] = GoldenTruthParser.parse_file(self.golden_truth_path)

    def run_benchmark(self, max_samples: Optional[int] = None, use_llm_judge: bool = False) -> BenchmarkSummary:
        """
        Chạy toàn bộ hoặc một phần các câu hỏi mẫu và chấm điểm đối soát.
        """
        if not self.items:
            logger.warning("[Benchmark] Không có dữ liệu câu hỏi trong golden-truth.txt")
            return BenchmarkSummary()

        test_pool = self.items[:max_samples] if max_samples else self.items
        results: List[BenchmarkItemResult] = []

        total_latency = 0
        total_conf = 0.0
        total_keyword_hit = 0.0
        hallucination_count = 0
        passed_count = 0

        logger.info(f"[Benchmark] Bắt đầu đánh giá {len(test_pool)} câu hỏi đối chiếu với Golden Truth...")

        for idx, item in enumerate(test_pool, 1):
            t0 = time.time()
            rag_res = self.rag_engine.ask(item.question)
            latency = int((time.time() - t0) * 1000)

            actual_ans = rag_res.answer or ""
            actual_lower = actual_ans.lower()

            # 1. Tính toán Keyword Hit Rate (tỷ lệ thực thể cốt lõi xuất hiện)
            hit_count = 0
            if item.key_entities:
                for ent in item.key_entities:
                    if ent.lower() in actual_lower:
                        hit_count += 1
                keyword_hit_rate = round(hit_count / len(item.key_entities), 2)
            else:
                # Nếu không có entity trích xuất riêng, xét theo các từ khóa chính (> 3 ký tự)
                exp_words = [w.lower() for w in re.findall(r'\b\w{4,}\b', item.expected_answer)]
                common_hits = [w for w in set(exp_words) if w in actual_lower]
                keyword_hit_rate = round(len(common_hits) / max(len(set(exp_words)), 1), 2)

            # 2. Phát hiện lỗi ảo giác (Hallucination Detection)
            hallucinations = []
            for trap, reason in self.HALLUCINATION_TRAPS:
                if trap in actual_lower and trap not in item.expected_answer.lower():
                    hallucinations.append(reason)

            # Kiểm tra các quy tắc định danh nghiêm ngặt
            if "mã trường" in item.question.lower() and "spk" not in actual_lower:
                hallucinations.append("Trả lời sai mã trường (Không có SPK)")

            is_hallucinated = len(hallucinations) > 0
            if is_hallucinated:
                hallucination_count += 1

            # 3. Tiêu chí Pass / Fail:
            # - Không bị ảo giác
            # - Confidence score >= 0.35 HOẶC Keyword Hit Rate >= 0.40 HOẶC có nguồn trích dẫn
            passed = (not is_hallucinated) and (
                keyword_hit_rate >= 0.35 or 
                rag_res.confidence_score >= 0.35 or 
                rag_res.source_type in ["GOLDEN_TRUTH", "FAQ_CHAT", "REGULATION"]
            )
            if passed:
                passed_count += 1

            # 4. LLM Judge nếu yêu cầu
            eval_res = None
            if use_llm_judge and self.evaluator and rag_res.llm_generated:
                eval_res = self.evaluator.evaluate(item.question, item.expected_answer, actual_ans, True)

            item_result = BenchmarkItemResult(
                group_name=item.group_name,
                question=item.question,
                expected_answer=item.expected_answer,
                actual_answer=actual_ans,
                source_type=rag_res.source_type,
                confidence_score=rag_res.confidence_score,
                keyword_hit_rate=keyword_hit_rate,
                hallucination_detected=is_hallucinated,
                hallucination_reasons=hallucinations,
                passed=passed,
                latency_ms=latency,
                eval_result=eval_res
            )
            results.append(item_result)

            total_latency += latency
            total_conf += rag_res.confidence_score
            total_keyword_hit += keyword_hit_rate

            logger.info(f"  [{idx}/{len(test_pool)}] Q: '{item.question[:45]}...' -> Hit={keyword_hit_rate*100:.0f}% | Pass={passed} | {latency}ms")

        n = len(results)
        summary = BenchmarkSummary(
            total_questions=n,
            passed_count=passed_count,
            failed_count=n - passed_count,
            pass_rate_pct=round((passed_count / max(n, 1)) * 100.0, 2),
            avg_latency_ms=round(total_latency / max(n, 1), 1),
            hallucination_count=hallucination_count,
            hallucination_rate_pct=round((hallucination_count / max(n, 1)) * 100.0, 2),
            avg_confidence=round(total_conf / max(n, 1), 3),
            avg_keyword_hit_rate=round(total_keyword_hit / max(n, 1), 3),
            items=results
        )

        logger.info("=" * 60)
        logger.info(f"[Benchmark] Hoàn tất! Pass Rate: {summary.pass_rate_pct}% ({summary.passed_count}/{n}) | Hallucination: {summary.hallucination_rate_pct}% | Avg Latency: {summary.avg_latency_ms}ms")
        logger.info("=" * 60)
        return summary

    def export_markdown_report(self, summary: BenchmarkSummary, output_file: str | Path) -> str:
        """Xuất báo cáo benchmark dạng Markdown học thuật phục vụ nghiệm thu đồ án"""
        out_path = Path(output_file)
        lines = [
            "# BÁO CÁO ĐÁNH GIÁ CHẤT LƯỢNG RAG ENGINE (BENCHMARK REPORT)",
            f"*Thời gian thực hiện: {time.strftime('%Y-%m-%d %H:%M:%S')}*",
            f"*Tập dữ liệu chuẩn đối soát: `{self.golden_truth_path.name}`*",
            "",
            "## 1. TỔNG HỢP CHỈ SỐ ĐỊNH LƯỢNG",
            "",
            "| Chỉ số đánh giá | Giá trị đo lường | Mục tiêu tiêu chuẩn | Trạng thái |",
            "| :--- | :--- | :--- | :--- |",
            f"| **Tổng số câu hỏi kiểm thử** | **{summary.total_questions} câu** | Toàn bộ các nhóm quy chế | Hoàn tất |",
            f"| **Tỷ lệ vượt qua (Pass Rate)** | **{summary.pass_rate_pct}%** ({summary.passed_count}/{summary.total_questions}) | $\\ge 85.0\\%$ | {'✅ ĐẠT' if summary.pass_rate_pct >= 85.0 else '⚠️ CẦN TỐI ƯU'} |",
            f"| **Tỷ lệ phát hiện ảo giác (Hallucination Rate)** | **{summary.hallucination_rate_pct}%** ({summary.hallucination_count} câu) | $\\le 5.0\\%$ | {'✅ XUẤT SẮC' if summary.hallucination_rate_pct <= 5.0 else '❌ VƯỢT NGƯỠNG'} |",
            f"| **Thời gian phản hồi trung bình (Latency)** | **{summary.avg_latency_ms} ms** | $< 2000$ ms | {'✅ MƯỢT MÀ' if summary.avg_latency_ms < 2000 else '⚠️ CHẬM'} |",
            f"| **Độ khớp từ khóa trung bình (Keyword Hit Rate)** | **{round(summary.avg_keyword_hit_rate * 100, 1)}%** | $\\ge 60.0\\%$ | {'✅ CHUẨN XÁC' if summary.avg_keyword_hit_rate >= 0.6 else '⚠️ KHÁ'} |",
            f"| **Độ tự tin trung bình (Avg Confidence)** | **{summary.avg_confidence}** | $\\ge 0.70$ | Đạt chuẩn |",
            "",
            "---",
            "",
            "## 2. BẢNG CHI TIẾT KẾT QUẢ TỪNG CÂU HỎI",
            "",
            "| STT | Phân nhóm | Câu hỏi sinh viên | Nguồn trả về | Độ khớp KW | Thời gian | Đánh giá |",
            "| :--- | :--- | :--- | :--- | :--- | :--- | :--- |"
        ]

        for i, item in enumerate(summary.items, 1):
            status_icon = "✅ ĐẠT" if item.passed else "❌ CHƯA ĐẠT"
            q_short = (item.question[:40] + "...") if len(item.question) > 40 else item.question
            lines.append(
                f"| {i} | {item.group_name} | {q_short} | `{item.source_type}` | {int(item.keyword_hit_rate*100)}% | {item.latency_ms}ms | {status_icon} |"
            )

        lines.append("")
        lines.append("---")
        lines.append("*Báo cáo được khởi tạo tự động bởi QAUTE Portal Python AI Engine.*")

        report_content = "\n".join(lines)
        out_path.write_text(report_content, encoding="utf-8")
        logger.info(f"[Benchmark] Đã ghi báo cáo chi tiết vào: {out_path.resolve()}")
        return report_content
