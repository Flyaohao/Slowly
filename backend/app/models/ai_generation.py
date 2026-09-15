from sqlalchemy import BigInteger, ForeignKey, Index, JSON, String, Text
from sqlalchemy.orm import Mapped, mapped_column
from typing import Optional

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class AiGeneration(BigIntPKMixin, TimestampMixin, Base):
    """非会话型 AI 生成结果的持久化。

    ## 为什么不能只有 ai_chat_message

    `ai_chat_message` 挂在会话下，服务的是「AI 翻译官」这种一来一回的多轮对话。
    但产品里还有一大批**单次触发的分析类 AI**：解读一封信、改写一封信、
    生成回信、改写表达、生成画像报告。它们没有会话概念，此前的结果一律
    只存在于客户端 ViewModel 的内存里，退出页面即蒸发——用户想再看一眼
    刚才的解读，就得再花一次模型调用、再等十几秒。

    这张表就是给这类结果一个落脚点：按 `(generation_kind, target_type, target_id)`
    定位「某封信的 AI 理解」这类单一目标。

    ## 为什么是「最新一条覆盖」而不是「历史留档」

    同一个目标（如某封信的解读）重复生成时**更新同一条记录**，不追加新行。
    用户要的是「重新进来还能看到上次的解读」，不是「翻阅历次解读的版本史」；
    后者会让详情页多出一套版本切换 UI，收益远不抵复杂度。真要回溯，
    每一天的完整生成记录也还在服务端日志里。

    ## 状态机的意义

    `status` 记录了生成是怎么结束的，这直接决定了客户端该怎么展示：

    - `streaming`   正在生成。客户端刷新页面时看到它，应当继续等或干脆重试
    - `done`        正常完成，可以放心展示
    - `interrupted` 用户中途点了「停止生成」。**内容依然保留**——已经吐出来的
                    那半段对用户仍然有价值，丢掉等于白等
    - `failed`      模型侧失败。通常 content 为空，客户端应提示重试
    """

    __tablename__ = "ai_generation"
    # 定位「谁，针对哪个目标，做的哪一类生成」。查询都是等值匹配这几个列，
    # 因此建一条联合索引即可覆盖；单独给 target_id 建索引没有意义。
    __table_args__ = (
        Index(
            "ix_ai_generation_lookup",
            "user_id",
            "generation_kind",
            "target_type",
            "target_id",
        ),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    #: 归属关系。信件类生成挂关系；画像报告等个人维度生成为 NULL
    #: （v2.2 起可空，见迁移 a8b9c0d1e2f3）
    relation_id: Mapped[Optional[int]] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=True
    )
    #: 生成类型，如 letter_analysis / letter_rewrite / letter_reply
    generation_kind: Mapped[str] = mapped_column(String(50), nullable=False)
    #: 目标实体类型，如 letter / questionnaire / profile / none
    target_type: Mapped[str] = mapped_column(String(30), default="none", nullable=False)
    #: 目标实体主键。`none` 类型可以为空
    target_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    #: 生成时使用的场景键，便于按场景统计与排查
    scene_key: Mapped[str] = mapped_column(String(50), nullable=False)
    #: streaming / done / interrupted / failed
    status: Mapped[str] = mapped_column(String(20), default="streaming", nullable=False)
    #: 面向用户的自然语言正文（流式期间逐字下推的那一份）
    content: Mapped[str] = mapped_column(Text, nullable=False)
    #: 推理模型的思考过程，供「深度思考」面板回看
    thinking: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    #: 结构化字段（Function Calling / 分隔符后的 JSON 解析结果）
    structured_output: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    risk_level: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    #: 实际产出该结果的模型名，便于回溯降级链走到了哪一级
    model: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)
