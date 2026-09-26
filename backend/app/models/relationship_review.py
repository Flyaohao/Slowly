from datetime import datetime
from typing import Optional

from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, JSON, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class AiRelationshipReview(BigIntPKMixin, TimestampMixin, Base):
    """关系复盘的历史留档（整改契约 §8.7）。

    ## 为什么不能复用 ai_generation

    `ai_generation` 的定位是「某个目标的最新一次生成」——`(user_id, kind,
    target_type, target_id)` 命中就**覆盖**。复盘原先用 `target_type="none"` /
    `target_id=None`，于是同一用户永远只有一行：第二次复盘把第一次抹掉，
    用户回看不到任何历史，也无法把「上次复盘时说的话」和「后来的实际结果」对上。
    契约 §8.7 明确禁止覆盖式只存最新一次，所以复盘需要一张**追加式**的表。

    `ai_generation` 仍然参与：每次复盘在那边占一行 `target_type="review"` /
    `target_id=本行 id`，流式引擎与落库路径一行不用改，只是天然不再互相覆盖。

    ## 为什么 description / context 也要存

    「重新复盘一次」必须**真正清理结果并恢复输入状态**（同一节）。要恢复输入态
    就得拿回用户上次写的那段经过；否则只能给一张空表单，用户得从头重打一遍。
    这两列只属于本人（所有查询都带 `user_id`），不进入任何共享/伴侣可见路径。
    """

    __tablename__ = "ai_relationship_review"
    # 历史列表固定按「我的复盘、倒序」取，联合索引一次覆盖。
    __table_args__ = (
        Index("ix_ai_relationship_review_owner", "user_id", "relation_id", "id"),
        # 回访任务扫描：recall_at 到点且尚无结果
        Index("ix_ai_relationship_review_recall", "user_id", "recall_at"),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("user.id"), nullable=False)
    relation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("couple_relation.id"), nullable=False
    )

    # ---- 输入侧（用于恢复输入状态）----
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    context: Mapped[Optional[str]] = mapped_column(Text, nullable=True)

    # ---- 契约 §8.7 要求「至少记录」的六项，全部是真实列而非塞进 JSON ----
    #
    # 注意：这几列**只有 Python 侧 default，不能加 server_default**。
    # MySQL 8.0 起禁止 TEXT/BLOB/JSON 列带字面量默认值（1101），DDL 会直接失败；
    # 而插入语句由 ORM 生成、本来就带值，服务端默认值对写入路径没有任何作用。
    #: 事件摘要
    summary: Mapped[str] = mapped_column(Text, nullable=False, default="")
    #: 触发点
    trigger: Mapped[str] = mapped_column(Text, nullable=False, default="")
    #: 我方真实需求
    own_need: Mapped[str] = mapped_column(Text, nullable=False, default="")
    #: 对方真实需求
    partner_need: Mapped[str] = mapped_column(Text, nullable=False, default="")
    #: 建议表达（取模型给的「下次可以提前说」首条；完整分组见 structured_output）
    suggested_expression: Mapped[str] = mapped_column(Text, nullable=False, default="")
    #: 事件发生时间。用户没填则回落为创建时间（发生时间不可能凭空缺席）
    event_time: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)

    #: 后续结果（用户回来填的「后来怎么样了」）。NULL = 尚未回填
    outcome: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    outcome_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    #: 稍后回访任务到点时间（§8.7「复盘可产生稍后回访任务」）。NULL = 不排回访
    recall_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)

    # ---- 生成侧 ----
    #: streaming / done / interrupted / failed（与 ai_generation 同枚举）
    status: Mapped[str] = mapped_column(String(20), default="streaming", nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False, default="")
    #: 结构化字段全量（误解处 / 升级话术 / 降温话术 / 下次表达等分组），供卡片忠实渲染
    structured_output: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    risk_level: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
