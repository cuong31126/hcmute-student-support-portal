"""
QAUTE Portal — Python AI Engine
config.py: Cấu hình tập trung dùng Pydantic Settings (đọc từ .env tự động)
"""
from pydantic_settings import BaseSettings
from pydantic import Field
from functools import lru_cache


class Settings(BaseSettings):
    # Gemini API
    gemini_api_key: str = Field(default="demo_key")
    gemini_chat_model: str = Field(default="gemini-2.5-flash")
    gemini_embedding_model: str = Field(default="models/text-embedding-004")

    # Server
    ai_engine_port: int = Field(default=8001)
    ai_engine_host: str = Field(default="127.0.0.1")

    # Storage paths
    pdf_source_dir: str = Field(default="../docs/knowledge_base")
    chroma_persist_dir: str = Field(default="./data/chroma_db")
    markdown_output_dir: str = Field(default="./data/markdown_docs")

    # RAG parameters
    context_token_budget_chars: int = Field(default=3000)
    suggest_ticket_threshold: float = Field(default=0.45)
    top_k_chunks: int = Field(default=5)

    # Debug
    debug: bool = Field(default=False)

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8", "extra": "ignore"}


@lru_cache()
def get_settings() -> Settings:
    return Settings()
