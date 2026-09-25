"""Agent 工具定义（LangChain @tool）

设计要点
--------
1. **description 是模型的唯一线索**：模型靠 description 判断"要不要调这个工具"，
   靠 args_schema 判断"参数怎么填"。因此两处都必须写清"什么时候用"。
2. **工具内部自管数据库会话**：Agent 执行时脱离 HTTP 请求上下文，
   不能复用请求级的 `db`，所以每个工具自行开闭 `SessionLocal`。
3. **返回值统一为字符串**：工具结果会作为 ToolMessage 回填给模型，
   文本形式最通用，也便于模型二次加工。

注意：LangChain 1.x 已移除 `AgentExecutor` / `create_tool_calling_agent`，
改用 `create_agent`（基于 langgraph），详见 `executor.py`。
"""

import logging
from typing import List, Optional

from langchain_core.tools import tool
from pydantic import BaseModel, Field

from app.core.database import SessionLocal

logger = logging.getLogger("couple.agent.tools")


# ---------------------------------------------------------------------- #
# 工具一：查询关系画像
# ---------------------------------------------------------------------- #
class ProfileQuerySchema(BaseModel):
    """查询关系画像的参数"""

    user_id: int = Field(..., description="发起请求的用户 ID")
    include_partner: bool = Field(True, description="是否同时返回伴侣的画像，默认 True")


@tool("get_relation_profile", args_schema=ProfileQuerySchema)
def get_relation_profile(user_id: int, include_partner: bool = True) -> str:
    """查询用户的关系画像：依恋类型、11 维关系维度分数、双方的冲突循环模式。

    当需要根据用户的具体性格特征（如焦虑型/回避型）给出个性化建议时调用本工具。
    当用户询问"我是什么类型""我们为什么总是这样吵"时也应调用。
    """
    from app.repositories import couple_repo, profile_repo
    from app.services.astrology_service import build_personality_block

    def personality_of(uid: int) -> str:
        """星座·星盘 + MBTI 辅助块；没填就返回空串（不塞占位）。"""
        from app.repositories import user_repo

        basic = user_repo.get_profile_by_user_id(db, uid)
        if basic is None:
            return ""
        return build_personality_block(
            basic.birthday, basic.birth_hour, basic.mbti, basic.birth_place
        )

    db = SessionLocal()
    try:
        profile = profile_repo.get_latest_profile(db, user_id)
        personality = personality_of(user_id)
        if not profile:
            if personality:
                return (
                    "该用户尚未完成关系画像问卷，只有以下性格辅助信息：\n" + personality
                )
            return "该用户尚未完成关系画像问卷，没有可用的画像数据。"

        dims = profile_repo.get_dimension_scores(db, profile.id)
        dim_text = ", ".join("%s=%s" % (d.dimension_key, d.score) for d in dims) or "无"

        type_names = {
            "secure": "安全型",
            "anxious": "焦虑依恋型",
            "dismissive": "疏离回避型",
            "fearful": "恐惧回避型",
            "mixed": "混合型依恋",
        }
        lines = [
            "【用户画像】依恋类型: %s（置信度 %s）"
            % (type_names.get(profile.profile_type, profile.profile_type), profile.confidence),
            "维度分数: %s" % dim_text,
        ]
        if personality:
            lines.append(personality)

        if include_partner:
            relation = couple_repo.get_active_relation_by_user(db, user_id)
            if relation:
                partner_id = (
                    relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id
                )
                partner_profile = profile_repo.get_latest_profile(db, partner_id)
                if partner_profile:
                    p_dims = profile_repo.get_dimension_scores(db, partner_profile.id)
                    p_text = ", ".join("%s=%s" % (d.dimension_key, d.score) for d in p_dims) or "无"
                    lines.append(
                        "【伴侣画像】依恋类型: %s（置信度 %s）"
                        % (
                            type_names.get(partner_profile.profile_type, partner_profile.profile_type),
                            partner_profile.confidence,
                        )
                    )
                    lines.append("伴侣维度分数: %s" % p_text)

                p_personality = personality_of(partner_id)
                if p_personality:
                    lines.append(
                        p_personality.replace(
                            "【性格辅助信息】", "【伴侣性格辅助信息】", 1
                        )
                    )

                couple_profile = profile_repo.get_latest_couple_profile(db, relation.id)
                if couple_profile:
                    lines.append("【双方冲突循环】%s" % (couple_profile.conflict_pattern or "未确定"))
            else:
                lines.append("（当前未绑定伴侣，仅有个人画像）")

        return "\n".join(lines)
    except Exception as exc:
        logger.warning("[TOOL] get_relation_profile 失败: %s", exc)
        return "查询画像失败：%s" % exc
    finally:
        db.close()


# ---------------------------------------------------------------------- #
# 工具二：检索心理学理论库
# ---------------------------------------------------------------------- #
class TheorySearchSchema(BaseModel):
    """理论检索参数"""

    query: str = Field(..., description="检索关键词或问题描述，例如'冷战''追逃模式''怎么道歉'")


@tool("search_theory", args_schema=TheorySearchSchema)
def search_theory(query: str) -> str:
    """检索心理学理论库，返回相关的理论片段作为建议依据。

    库内包含：成人依恋理论、Gottman 冲突四骑士、Demand-Withdraw 追逃模式、
    非暴力沟通(NVC)、I-Statement 表达模板、道歉表达结构、情绪安抚表达模板、冷战修复模板。

    当用户询问"这是怎么回事""为什么会这样"，或需要为建议提供理论依据时调用。
    """
    from app.services.rag_service import build_rag_context, retrieve_chunks

    db = SessionLocal()
    try:
        chunks = retrieve_chunks(db, query, top_k=3)
        if not chunks:
            return "理论库中未检索到相关片段，请基于通用沟通原则回答。"
        titles = "、".join(c.get("doc_title", "") for c in chunks)
        return "检索命中: %s\n\n%s" % (titles, build_rag_context(chunks))
    except Exception as exc:
        logger.warning("[TOOL] search_theory 失败: %s", exc)
        return "检索理论库失败：%s" % exc
    finally:
        db.close()


# ---------------------------------------------------------------------- #
# 工具三：查询历史 AI 记忆
# ---------------------------------------------------------------------- #
class MemoryQuerySchema(BaseModel):
    """记忆查询参数"""

    user_id: int = Field(..., description="用户 ID")
    relation_id: int = Field(..., description="情侣关系 ID，单身模式可传 0")
    # §A 可选项：无 query 时走近因降级，恰恰是"你之前说过的"最需要相关性的场景
    query: str = Field("", description="用当前问题提炼的关键词，用于相关性召回")


@tool("get_ai_memory", args_schema=MemoryQuerySchema)
def get_ai_memory(user_id: int, relation_id: int = 0, query: str = "") -> str:
    """查询此前对话中沉淀下来的用户偏好与关系记忆。

    当用户问到"你之前说过的""我们以前聊过的"，或需要延续此前建议时调用。
    """
    from app.services.memory_service import get_memory_context

    if not relation_id:
        return "当前没有可用的关系记忆（未绑定伴侣）。"

    db = SessionLocal()
    try:
        context = get_memory_context(db, user_id, relation_id, query=query)
        return context or "暂无沉淀的记忆内容。"
    except Exception as exc:
        logger.warning("[TOOL] get_ai_memory 失败: %s", exc)
        return "查询记忆失败：%s" % exc
    finally:
        db.close()


#: 暴露给 Agent 的工具集合
ALL_TOOLS: List = [get_relation_profile, search_theory, get_ai_memory]
