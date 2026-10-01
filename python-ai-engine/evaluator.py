"""
QAUTE Portal — Python AI Engine
evaluator.py: Tự động đánh giá chất lượng câu trả lời RAG (Giai đoạn 3)

Chỉ số đánh giá (RAG Triad):
  1. Context Relevance  : Tài liệu có khớp với câu hỏi không? (0-5)
  2. Faithfulness       : Câu trả lời có trung thực theo tài liệu không? (0-5)
  3. Answer Relevance   : Câu trả lời có trả lời đúng trọng tâm không? (0-5)

Câu trả lời có Faithfulness < 3 sẽ tự động bị gắn cờ suggest_create_ticket=True.
"""
import logging
from typing import Optional
from dataclasses import dataclass

logger = logging.getLogger(__name__)

FAITHFULNESS_BLOCK_THRESHOLD = 3  # Dưới ngưỡng này → block và chuyển Ticket


@dataclass
class EvalResult:
    context_relevance: int = 0    # 0-5
    faithfulness: int = 0         # 0-5  
    answer_relevance: int = 0     # 0-5
    overall_score: float = 0.0    # trung bình
    review_comment: str = ""
    should_block: bool = False     # True nếu câu trả lời không đáng tin


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
        """
        Tự động chấm điểm câu trả lời.
        
        Args:
            question: Câu hỏi gốc của sinh viên
            context: Context chunks được đưa vào LLM
            answer: Câu trả lời của LLM
            llm_generated: Chỉ evaluate nếu True
        
        Returns:
            EvalResult với điểm và nhận xét
        """
        # Không evaluate nếu là fallback hoặc demo mode
        if not llm_generated:
            return EvalResult(
                context_relevance=0,
                faithfulness=0,
                answer_relevance=0,
                overall_score=0.0,
                review_comment="Không đánh giá: Câu trả lời là fallback, không phải từ LLM.",
                should_block=False
            )
        
        if not self.settings.gemini_api_key or self.settings.gemini_api_key == "demo_key":
            return EvalResult(
                context_relevance=3,
                faithfulness=3,
                answer_relevance=3,
                overall_score=3.0,
                review_comment="Demo mode: điểm mặc định 3/5.",
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
        """Gọi Gemini để chấm điểm theo format JSON chuẩn."""
        import httpx
        import json
        
        eval_prompt = f"""Bạn là Chuyên gia đánh giá chất lượng câu trả lời AI cho hệ thống tư vấn học vụ đại học.

[CÂU HỎI CỦA SINH VIÊN]:
{question}

[TÀI LIỆU QUY CHẾ (Context)]:
{context[:1500]}

[CÂU TRẢ LỜI CỦA AI]:
{answer[:800]}

Hãy chấm điểm theo thang 0-5 cho 3 tiêu chí sau và trả về JSON:

1. context_relevance (0-5): Tài liệu context có liên quan và đủ thông tin để trả lời câu hỏi không?
   - 5: Context hoàn toàn liên quan, đầy đủ thông tin
   - 3: Context liên quan một phần
   - 0: Context không liên quan

2. faithfulness (0-5): Câu trả lời có TRUNG THỰC dựa trên tài liệu context không? (Không bịa đặt thông tin)
   - 5: 100% trung thực, mọi thông tin đều có trong context
   - 3: Phần lớn trung thực, có vài điểm suy luận
   - 0: Bịa đặt thông tin không có trong context

3. answer_relevance (0-5): Câu trả lời có TRẢ LỜI ĐÚNG TRỌNG TÂM câu hỏi không?
   - 5: Trả lời đúng và đầy đủ
   - 3: Trả lời một phần
   - 0: Lạc đề hoàn toàn

Chỉ trả về JSON thuần túy, không giải thích thêm:
{{
  "context_relevance": <số 0-5>,
  "faithfulness": <số 0-5>,
  "answer_relevance": <số 0-5>,
  "review_comment": "<nhận xét ngắn gọn bằng tiếng Việt, tối đa 100 từ>"
}}"""
        
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.settings.gemini_chat_model}:generateContent"
        
        response = httpx.post(
            url,
            params={"key": self.settings.gemini_api_key},
            json={
                "contents": [{"parts": [{"text": eval_prompt}]}],
                "generationConfig": {"temperature": 0.1}  # Low temp để nhất quán
            },
            timeout=20.0
        )
        response.raise_for_status()
        data = response.json()
        
        raw_text = data["candidates"][0]["content"]["parts"][0]["text"]
        
        # Parse JSON từ response (đôi khi Gemini wrap trong ```json ... ```)
        import re
        json_match = re.search(r'\{.*\}', raw_text, re.DOTALL)
        if not json_match:
            raise ValueError(f"Không parse được JSON từ Gemini: {raw_text[:200]}")
        
        eval_data = json.loads(json_match.group())
        
        cr = int(eval_data.get("context_relevance", 3))
        fa = int(eval_data.get("faithfulness", 3))
        ar = int(eval_data.get("answer_relevance", 3))
        overall = round((cr + fa + ar) / 3.0, 2)
        comment = eval_data.get("review_comment", "")
        
        should_block = fa < FAITHFULNESS_BLOCK_THRESHOLD
        
        if should_block:
            logger.warning(f"[Evaluator] ⚠️ Câu trả lời bị BLOCK do faithfulness={fa} < {FAITHFULNESS_BLOCK_THRESHOLD}")
        
        return EvalResult(
            context_relevance=cr,
            faithfulness=fa,
            answer_relevance=ar,
            overall_score=overall,
            review_comment=comment,
            should_block=should_block
        )
