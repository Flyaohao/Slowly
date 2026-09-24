"""军师判断依据的统一载体（P0-5）。

一次组装，两条出口：
  - 组装 prompt（内容不变，仍走 build_chat_messages 的既有入参）
  - 随 /ai/chat 的 data.evidence 与 SSE 末帧 event:evidence 返回给前端

**零新增 LLM 调用**：所有字段都是 _preprocess 里已经算好的局部数据，
这里只做「顺便带出去」。to_display() 只含可 JSON 序列化的基本类型——
流式生成器不持请求级 db，evidence 必须在端点内拍平后才能进生成器。
"""
from dataclasses import dataclass, field
from typing import Any, Dict, List

#: voice_style → 展示用中文（与 prompt_builder.VOICE_STYLE_INSTRUCTIONS 同枚举）
VOICE_STYLE_LABELS = {
    "gentle": "温柔",
    "calm": "冷静",
    "direct": "直接",
    "cute": "可爱",
    "mature": "成熟",
}


@dataclass
class AdvisorContext:
    """军师的完整判断依据。"""

    scene_key: str
    # ① 人是什么样的
    self_profile_card: str = ""
    partner_profile_card: str = ""
    relationship_pattern: str = ""
    # ② 你们经历过什么：每项 {content, source, created_at}
    recalled_memories: List[Dict[str, Any]] = field(default_factory=list)
    # ③ 理论支撑：每项 {title, snippet, score}
    theory_chunks: List[Dict[str, Any]] = field(default_factory=list)
    # ④ 军师是谁
    avatar_name: str = ""
    voice_style: str = ""

    def to_display(self) -> dict:
        """给客户端的展示结构（纯基本类型，无 ORM / 无 datetime 对象）。"""
        return {
            "scene_key": self.scene_key,
            "self_profile_card": self.self_profile_card or "",
            "partner_profile_card": self.partner_profile_card or "",
            "relationship_pattern": self.relationship_pattern or "",
            "recalled_memories": [
                {
                    "content": m.get("content", ""),
                    "source": m.get("source", ""),
                    "created_at": m.get("created_at"),
                }
                for m in (self.recalled_memories or [])
            ],
            "theory_chunks": [
                {
                    "title": c.get("title", ""),
                    "snippet": c.get("snippet", ""),
                    "score": c.get("score", 0),
                }
                for c in (self.theory_chunks or [])
            ],
            "avatar_name": self.avatar_name or "",
            "voice_style": self.voice_style or "",
            "voice_style_label": VOICE_STYLE_LABELS.get(self.voice_style, ""),
        }
