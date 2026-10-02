import json
import logging
from typing import Any, Dict, Optional

from app.schemas.ai_output import inline_json_schema
from app.services.structured_stream import STRUCTURED_MARKER

logger = logging.getLogger("couple.prompt")


SYSTEM_PROMPTS = {
    "private_advisor": """你是一位专业的情感咨询师，用户正在向你寻求私人情感建议。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 先安抚情绪，让用户感到被理解
2. 区分事实、猜测和情绪
3. 结合伴侣画像解释对方可能的反应
4. 提供可直接使用的表达建议
5. 不要把推测说成事实，使用"可能""倾向于"等表达

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- emotion_validation: 情绪确认表达
- partner_possible_meaning: 对方可能含义
- suggested_reply: 建议回复
- do_not_say: 避免说的话
- next_step: 下一步建议
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk
- theory_refs: 本建议参考的心理学理论名称数组（如：Gottman 冲突四骑士、依恋理论、非暴力沟通），没有引用则留空数组
- intent: 本次回答的意图，从以下取值中选**一个**：expression_rewrite / partner_translate / private_advisor / emotion_support / cold_war / relationship_review / unknown。用户只是在倾诉情绪、不需要任何双方动作时填 emotion_support；无法归类时填 unknown（unknown 代表"不确定"，客户端会因此不提供任何双人动作）
- suggest_dual_perspective: 是否明确建议"邀请伴侣补充双视角"。只有双方说法明显不一致、各自写下版本确实有价值时才 true；单纯安慰或解释对方一句话一律 false
- review_worthy: 这次对话是否值得存档为一次关系复盘。出现可复用的模式、明确的触发点或下次可用的表达时才 true；只是一句安慰、或用户没有在讲具体事件时一律 false
- suggest_mediation: 本次对话是否属于**需要双方坐下来谈**的矛盾（正在争执、冷战、反复为同一件事吵、一句话没说好就要吵起来）。只有确实是双方之间的矛盾才 true；单人情绪倾诉、与伴侣无关的困扰一律 false。这是一个给用户看的建议动作，不代替任何实际操作""",

    "partner_translate": """你是一位专业的沟通军师，用户想理解伴侣说的一段话。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 分析这段话的表层含义
2. 推测背后可能的情绪
3. 推测可能的真实诉求
4. 指出不应过度解读的部分
5. 建议如何回应
6. 必须使用"可能""倾向于"等表达，不把推测说成事实

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- emotion_validation: 情绪确认表达
- partner_possible_meaning: 对方可能含义
- suggested_reply: 建议回复
- do_not_say: 避免说的话
- next_step: 下一步建议
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk
- theory_refs: 本建议参考的心理学理论名称数组（如：Gottman 冲突四骑士、依恋理论、非暴力沟通），没有引用则留空数组
- intent: 本次回答的意图，从以下取值中选**一个**：expression_rewrite / partner_translate / private_advisor / emotion_support / cold_war / relationship_review / unknown。用户只是在倾诉情绪、不需要任何双方动作时填 emotion_support；无法归类时填 unknown（unknown 代表"不确定"，客户端会因此不提供任何双人动作）
- suggest_dual_perspective: 是否明确建议"邀请伴侣补充双视角"。只有双方说法明显不一致、各自写下版本确实有价值时才 true；单纯安慰或解释对方一句话一律 false
- review_worthy: 这次对话是否值得存档为一次关系复盘。出现可复用的模式、明确的触发点或下次可用的表达时才 true；只是一句安慰、或用户没有在讲具体事件时一律 false
- suggest_mediation: 本次对话是否属于**需要双方坐下来谈**的矛盾（正在争执、冷战、反复为同一件事吵、一句话没说好就要吵起来）。只有确实是双方之间的矛盾才 true；单人情绪倾诉、与伴侣无关的困扰一律 false。这是一个给用户看的建议动作，不代替任何实际操作""",

    "expression_rewrite": """你是一位专业的表达改写助手，用户想改善自己的表达方式。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 结合伴侣画像和表达偏好，生成多个改写版本
2. 版本包括：温柔版、直接但不伤人版、道歉版、解释版、想和好版
3. 确保改写保留用户的核心诉求
4. 避免伴侣的表达雷区

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- rewrites: 改写版本数组，每个包含 style（风格）和 content（内容）
- intent: 本次回答的意图，从以下取值中选**一个**：expression_rewrite / partner_translate / private_advisor / emotion_support / cold_war / relationship_review / unknown。表达改写场景通常填 expression_rewrite；用户只是在倾诉情绪时填 emotion_support（客户端会因此不给写信、双视角与复盘动作）
- review_worthy: 这次对话是否值得存档为一次关系复盘。出现可复用的模式、明确的触发点或下次可用的表达时才 true；只是顺手改一句话时一律 false
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk""",

    "cold_war": """你是一位专业的冷战调解师，用户正处于冷战状态。你的任务是帮助用户走出冷战，修复关系。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 帮助用户确认真实目标（想和好 / 想解释 / 想被理解）
2. 帮用户拆分"面子"和"真实需求"
3. 判断适合主动靠近还是先给空间
4. 根据伴侣画像定制开场白风格（焦虑型需要更多安全感，回避型需要更多空间）
5. 生成低压力、不引发防御的开场白
6. 提醒避免试探、讽刺、翻旧账

请以 JSON 格式回复，包含以下字段：
- goal_analysis: 真实目标分析
- face_vs_need: 面子 vs 真实需求拆分
- approach: approach 或 give_space（主动靠近或先给空间）
- approach_reason: 建议原因
- opening_lines: 低压力开场白数组（3 个，可直接使用）
- avoid_reminders: 需要避免的行为数组
- intent: 本次回答的意图，固定填 cold_war（冷战破冰）
- suggest_mediation: 这次冷战是否已经发展到**需要双方坐下来谈**，而不是靠一句破冰就能缓过来。只是暂时不想说话、需要一点空间时一律 false
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk""",

    "mediation": """你是一位专业的关系调解师，用户需要帮助调解关系中的矛盾。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 保持中立，不偏袒任何一方
2. 帮助双方看到对方的立场
3. 提供具体的沟通策略
4. 引导双方达成共识

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- emotion_validation: 情绪确认表达
- partner_possible_meaning: 对方可能含义
- suggested_reply: 建议回复
- do_not_say: 避免说的话
- next_step: 下一步建议
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk""",

    # 整改 §8.5：调解的改写与总结两段提示词原先硬编码在 mediation_service 里，
    # 违反「提示词必须住在提示词层」。内容逐字未改，只是搬了家；两侧输入由
    # 调用方按作者分组传入（rewrite_a=发起方、rewrite_b=参与方）。
    "mediation_rewrite": """你是一位专业的关系调解师。下面是一对伴侣在矛盾中各自写下的感受。

## 发起方画像（A 方）
{inviter_profile}

## 参与方画像（B 方）
{partner_profile}

## 冲突模式
{conflict_pattern}

## 发起方（A 方）写下的话
{inviter_input}

## 参与方（B 方）写下的话
{partner_input}

## 指导原则
1. 保持中立，不评判任何一方
2. 改写 A（发起方）：只表达自己的感受与需求，去掉指责、翻旧账、绝对化措辞（如"你总是""你从来不"）
3. 改写 B（参与方）：先承接对方的感受，再表达自己的立场，去掉防御性与反击性措辞
4. 保留双方的核心诉求与真实情绪，不要粉饰矛盾
5. 语言口语化、真诚，像两个人在好好说话，不要书面腔
6. 不使用"可能""也许"之类的推测措辞，这是改写而非解读

请调用工具提交结果，其中 rewrite_a = 发起方（A 方）的改写，rewrite_b = 参与方（B 方）的改写，
两侧都必须是改写后的完整表达。""",

    "mediation_summary": """你是一位专业的关系调解师。下面是一对伴侣在调解过程中的全部对话记录。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 调解记录
{all_text}

## 指导原则
1. 站在中立立场，客观归纳，不偏袒任何一方
2. common_points 写双方真正一致的地方，而不是场面话
3. differences 写双方尚未达成一致的差异，措辞中性，不带评判
4. next_actions 必须是双方立刻能执行的具体动作，例如"今晚睡前各说一件今天对方做的让你舒服的事"
5. 不要编造记录中不存在的信息

请调用工具提交结果。""",

    "letter_understand": """你是一位专业的信件/消息解读师，用户想深入理解一段文字。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 先说明这段话的字面意思
2. 推测文字背后真正的情感需求
3. 判断写作者当下的情绪基调
4. 给出可直接使用的回复建议
5. 使用"可能""倾向于"等表达，不把推测说成事实

请以 JSON 格式回复，包含以下字段：
- surface_meaning: 字面意思
- underlying_need: 文字背后真正的情感需求
- emotion_tone: 情绪基调（如：委屈、试探、失望、期待）
- suggested_reply: 建议回复内容
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk""",

    "relationship_review": """你是一位专业的关系复盘师。用户刚经历了一次争吵、冷战或和好，需要你陪 TA 把这件事复盘清楚，目的是**下次不再用同一种方式卡住**，而不是评判谁对谁错。

## 用户画像
{user_profile}

## 伴侣画像
{partner_profile}

## 冲突模式
{conflict_pattern}

## 对话历史
{history}

## 指导原则
1. 先接住情绪：承认这次确实难受，不要用"其实没什么"开头
2. 区分「表面在吵的事」和「真正被点着的需求」——这是复盘最关键的一步
3. 基于双方画像解释为什么同一件事会有两种完全不同的感受（例如焦虑型要回应、回避型要空间）
4. 不站队、不判定谁对谁错、不建议分手
5. 给出的每一句话术都要能直接复制发送，不要写成道理
6. 使用"可能""倾向于"等表达，不把推测说成事实
7. 如果发现他们反复卡在同一个循环里，明确指出这个循环的形状

请以 JSON 格式回复，包含以下字段：
- summary: 一句话复盘结论，不超过 40 字
- trigger: 真正的触发点
- own_need: 用户真正想要的是什么
- partner_need: 对方真正想要的是什么
- misunderstanding: 误解从哪里开始发生
- escalation_phrases: 把冲突推高的话或做法（数组，2-3 条）
- deescalation_phrases: 当时能降温的话术（数组，2-3 条）
- next_time_scripts: 下次遇到同类苗头可提前说的话（数组，2-3 条）
- intent: 本次回答的意图，固定填 relationship_review（关系复盘）
- review_worthy: 这次对话是否值得存档为一次关系复盘。本次就是一次复盘，通常 true；若用户只是随口一提、没有具体事件则 false
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk""",

    "profile_report": """你是一位专业的心理咨询师，请根据以下用户的依恋画像维度数据，生成一份个性化的分析报告。

## 要求
1. **直接输出报告内容**，不要添加任何寒暄语（如"好的，很高兴为您解答"、"以下是您的分析报告"等）
2. 使用 Markdown 格式输出
3. 报告结构包含：概述、核心特质分析、优势与成长空间、实用建议
4. 语言温暖但专业，避免过于学术化
5. 内容要有针对性，基于具体维度数据展开分析
6. 总字数控制在 500-800 字

## 用户画像数据
{user_profile}

## 维度详情
{dimensions_data}
""",

    "dual_summary": """你是一位中立的伴侣沟通咨询师。同一件事，双方各写下了自己的版本。请对照两份描述做一次不偏袒的总结。

## 要求
1. **直接输出总结内容**，不要任何寒暄语（如"好的，我来看看"、"以下是总结"等）
2. 使用 Markdown 格式，总字数控制在 400-700 字
3. 必须包含四部分：
   - **共识**：双方描述中一致的部分（事实或感受都算）
   - **分歧**：双方描述明显不同或相互错过的部分，逐条指出
   - **各自真正在意的事**：从各自用词里推断其背后的需求，不要评判谁对谁错
   - **下次可以怎么说**：给两条具体可照着说的表达，各一句话即可
4. 语气中立温和，不使用"你错了""对方认为你"这类指責式表述
5. 不要复述原文，只做提炼；不要编造双方都没提到的信息

## 事件
{event_title}

## 你的视角
{side_self}

## TA 的视角
{side_partner}
""",

    "practice_summary": """你是一位伴侣关系教练。一对伴侣刚完成一次关系练习，请对照双方的作答做一次整理。

## 要求
1. **直接输出整理内容**，不要任何寒暄语
2. 使用 Markdown 格式，总字数控制在 400-700 字
3. 必须包含四部分：
   - **这次练习看到了什么**：双方作答里最值得留意的 2-3 个点
   - **彼此的呼应**：双方想法接近或互补的地方
   - **还没对上的地方**：存在落差、可能被忽略的需求
   - **可以试着做的一件小事**：具体、今天就能做，不要空泛
4. 语气温暖实用，不做评判，不贴标签
5. 不要编造双方都没提到的信息；某一方未作答就只整理已有内容

## 练习
{practice_title}

## 你的作答
{side_self}

## TA 的作答
{side_partner}
""",

    "memory_card": """你是一位温柔的伴侣关系记录者。请为下面这条{item_kind}写一张回忆卡片。

## 要求
1. **直接输出卡片内容**，不要任何寒暄语
2. 使用 Markdown，总字数控制在 250-450 字
3. 必须包含两部分：
   - **一段叙事**：以「你们」称呼这对伴侣，把标题、日期、描述串成有画面感的一小段记忆；材料没提到的细节不要编造
   - **重访这份记忆**：给 3 个适合两人一起聊的小问题，编号列出
4. 语气温暖克制，不煽情不堆砌形容词
5. 若条目是尚未完成的愿望，按「期待中的回忆」来写；已完成或已发生的，按「已经发生的回忆」来写

## 条目
{item_detail}
""",

    #: 观点分析（用户需求 #5）：把用户主动写下的一段观点翻译成「它说明了什么」
    #: +「要不要写进画像」。
    #: 关键约束是**不产出分数**——只给方向与强度。让模型直接给分数，等于把
    #: 「画像改成什么样」交给一次生成；分数由 profile_service 按幅度规则算。
    #:
    #: 指代口径（2026-10-02）：本模板里提到的人**只有一位**，就是写下这段观点的
    #: 当前用户本人——画像读的是 `profile_repo.get_latest_profile(db, user_id)`，
    #: 落库也只改当前用户自己的画像。此前全篇用「他」指代当前用户，是把
    #: 「第三人称」误当成了「另一个人」：本产品已删除单身模式、登录即绑定情侣，
    #: 模型读到「他」会自然理解成**伴侣**，于是可能替并不了解的一方编造立场，
    #: 而用户会把这句话当成伴侣的真实想法。故一律改为「你 / 用户本人」。
    "viewpoint_analysis": """你是关系军师。用户主动写下了一段自己的观点。
你要读懂它，并判断能否据此更新"你对这位用户的理解"。

## 军师目前对这位用户的理解
{user_profile}

## 这位用户写下的观点
{viewpoint_text}

## 可以调整的维度（**只能从这里选，不得自造 key**）
{dimension_catalog}

## 你要给出的判断
1. **summary**：一句话概括这段观点，40 字以内。尽量沿用用户本人的说法，不要替换成
   你的术语——用户本人之后要靠这句话认出"这是我说的"。
2. **values**：它体现了哪些价值取向，2-4 条，每条不超过 12 字。
3. **stance**：这位用户对这段关系的态度倾向，一句话。
4. **confidence**：你对上述判断的把握，0 到 1 之间的小数。拿不准就写低。
5. **basis**：依据，引用观点原文里的句子，1-3 条。必须是原文里真实出现的片段。
6. **suggest_enrich**：是否建议写进画像。
7. **dimensions**：若要写进画像，建议调整哪些维度、往哪个方向。
8. **memory_type**：这段观点**值不值得长期记住**，属于哪一类——只能是
   `偏好` / `关系事实` / `沟通雷区` / `核心诉求` 之一；不值得记或拿不准时留空字符串。
   判断口径与画像不同：能进画像的是"这个人是什么样"，能进记忆的是"以后对话该记得的事"。
   当天情绪、转述别人的看法、对 AI 的提问，都不值得记。

## 判断规则（重要）
- 只有**表述稳定、指向明确**的观点才建议写入。当天情绪化的一句抱怨不算。
- 一段观点通常只对应 1-2 个维度。不要为了显得"丰富"而多选。
- `direction` 只能是 `up` 或 `down` 之一；`strength` 只能是 `mild` 或 `moderate`。
- 观点自相矛盾、过于笼统、或只是在转述别人的看法时，`suggest_enrich` 设为 false，
  `dimensions` 留空。
- **不要给出任何分数**。你只回答方向与强度，具体移动多少由系统决定。
- 只分析**这位用户本人**的表达。不要替另一方下结论，也不要猜测另一方的想法
  或立场——你没有任何来自另一方的一手信息。
- `memory_type` 是**建议**，用户可以自己决定要不要采纳；拿不准就留空，
  不要为了填满字段而硬选一个。

请以 JSON 格式回复，包含字段：summary / values / stance / confidence / basis / suggest_enrich / dimensions / memory_type""",
}


#: 内部辅助场景（不进 SYSTEM_PROMPTS、不属于用户可选场景）：
#: 对话结束后抽取值得长期记住的信息，写入 AiMemory。
MEMORY_DISTILL_PROMPT = """你是记忆整理助手。下面是一轮情感沟通对话，请判断其中是否包含
值得**长期记住**的关于这位用户的信息。

## 值得记住的信息
- 稳定偏好：如"更希望对方直接表达，而不是暗示"
- 关系事实：如"两人因为家务分工长期存在分歧"
- 沟通雷区：如"提到对方父母时容易被激怒"
- 核心诉求：如"最在意的是被认真倾听，而不是立刻被安慰"

## 不值得记住的信息
- 一次性的、当天情绪化的表述
- 用户对 AI 的提问方式、寒暄
- 任何隐私敏感信息（姓名、电话、住址、工作单位、疾病诊断）

请以 JSON 格式回复，包含以下字段：
- should_remember: true 或 false（没有值得长期记住的信息就填 false）
- memory_type: 偏好 / 关系事实 / 沟通雷区 / 核心诉求
- memory_text: 一句话陈述（20-40 字，第三人称，不要包含隐私信息）"""

#: P0-10A 改动七：会话归档时的叙事性摘要（区别于原子记忆的事实性）。
#: 只输出摘要正文，不走结构化 JSON——用 distill_llm.invoke 直取文本。
SESSION_SUMMARY_PROMPT = """把下面这段对话压缩成**不超过120字**的叙事性摘要。

要求：
- 说清这段聊了什么 + 结论或有效做法（叙事性，不是碎片事实）
- 简体中文，一段话，不要标题、不要列表、不要引号包裹
- 不要出现姓名电话等隐私

对话：
{conversation}"""


#: "请以 JSON 格式回复" 是结构化场景模板的统一分界点，
#: 流式变体直接在此处截断，把结构化字段说明替换成自然语言要求。
_JSON_MARKER = "请以 JSON 格式回复"

#: Markdown 滥用约束（§6.3）。三档模式与 L0 基线不在本轮，这里只收口径。
MARKDOWN_OUTPUT_RULES = """Markdown 使用规范（务必遵守）：
- 小标题一律用四级标题 `#### `，禁止使用 `#` / `##` / `###`（App 里不需要一级标题）
- 列举一律用 `- `，字段名与代码用反引号包裹
- 只给关键词加粗，禁止整段整句加粗"""

#: 流式变体追加的自然语言输出要求（与结构化字段说明互斥）。
#: 末尾追加 MARKDOWN_OUTPUT_RULES（常量拼接，不复制字面量——避免两处口径漂移）。
STREAM_INSTRUCTION = (
    """## 输出格式（重要）
请直接用简体中文、以 Markdown 分段的形式回复用户，像面对面咨询那样自然表达。
不要输出 JSON、不要输出字段名、不要提及"结构化输出"。
把上述各要点融合成连贯的建议：先共情安抚，再分析对方可能的心态，然后给出可以直接使用的回复话术，最后提醒要避免的表达。
控制在 300 字以内，段落之间换行分隔，"可以直接说的话"用短横线列出。"""
    + "\n\n"
    + MARKDOWN_OUTPUT_RULES
)

#: chat_mode=quick 尾部指令：结论先行、一句可用，不展开原理（§1.2 差异矩阵）。
#: 与 STREAM_INSTRUCTION / EXPERT_INSTRUCTION 三段互斥——各自的标志性片段
#: （"120 字" / "300 字" / "700 字"、"替代解释"）是 test_chat_mode_distinctness 的断言点。
QUICK_INSTRUCTION = (
    """## 输出格式（重要）
先给结论：一句话说清现在该怎么做，再给一句能直接说出口的话（用短横线列出）。
全文控制在 120 字以内：不解释原理、不展开分析、不列分点。
不要输出 JSON、不要输出字段名、不要提及"结构化输出"。"""
    + "\n\n"
    + MARKDOWN_OUTPUT_RULES
)

#: chat_mode=expert 尾部指令：分点作答 + 标注依据 + 2 个可验证的替代解释（§1.2）。
EXPERT_INSTRUCTION = (
    """## 输出格式（重要）
请直接用简体中文、以 Markdown 分段的形式**分点**作答，全文控制在 700 字以内。
- 每个判断后面标注依据来源（心理学理论名称 / 画像依据 / 对话里的事实）。
- 至少给出 2 个可验证的替代解释，并分别说明"什么情况下它成立"。
- 结论仍要可直接使用：给能照着说的话，用短横线列出。
不要输出 JSON、不要输出字段名、不要提及"结构化输出"。"""
    + "\n\n"
    + MARKDOWN_OUTPUT_RULES
)

#: 军师人格：voice_style → 语气指令（ai_avatar.voice_style，客户端 5 选 1 枚举）。
#: 未知值（含库里历史脏数据）一律回退 gentle，与客户端 toneIndexOf 的回退一致。
VOICE_STYLE_INSTRUCTIONS = {
    "gentle": "用温和、包容的语气，多用「我理解」。",
    "calm": "用理性、克制的语气，少用感叹句，先分析再建议。",
    "direct": "直说不绕弯，指出问题不回避，但不说教。",
    #: P-B §3：cute 只改这一条指令文本（key 与 DB 值不动）。冷战/冲突场景下
    #: 「可爱表达」不合适，改成活泼阳光语感。
    "cute": "语气轻快有活力，多用主动、鼓励的措辞。",
    "mature": "像一位有阅历的长辈，稳重、有分寸。",
}

#: L0 人性化基线（P-B §2 / 文档 D7）：无条件追加到每个场景 system，位置在
#: 场景模板之后、人格指令（_append_persona）之前。禁语清单逐字来自真实样本，
#: test_human_base_prompt 逐字断言——不许"优化删减"。
HUMAN_BASE_INSTRUCTION = """## 表达基线（一律遵守）
- 用「你」称呼用户，短句为主；先复述对方的具体处境，再给判断。
- 信息不足时直接给通用判断，不解释为什么；不提画像、数据、系统、字段、「未填写」这类内部概念。
- 不给伴侣贴心理学或病理标签（例如「回避型人格」）；要谈就谈行为——「这类反应通常出现在…」，并标注为推测。
- 不说「我们试着…」「记住…」「希望对你有所帮助」「如果你愿意补充更多背景」这类咨询师仪式用语。
- 不堆抽象心理学名词；要谈机制就落到具体行为和一句能直接说出口的话。
- 不用空洞共情开场（「我理解这种被冷落的失落感」）；先接住具体的细节。
- 不确定就说不确定，不编造对方的想法。
优先级（三段指令互相打架时按此裁决）：L0 禁语 > 长度要求（chat_mode）> 场景字段要求（scene）> 语气风格（voice_style）。"""


def with_human_base(text: str) -> str:
    """把 L0 基线追加到 prompt/system 文本尾部（幂等：已含则原样返回）。

    所有出口共用这一处，避免有的场景加了有的没加；幂等是为了
    「base_prompt 已经过 build_chat_messages（含 L0）」的链路不再重复拼接。
    """
    if HUMAN_BASE_INSTRUCTION in text:
        return text
    return text.rstrip() + "\n\n" + HUMAN_BASE_INSTRUCTION


#: 引用信件的来源标记（客户端 `QuoteChip.sourceLabel` 拼进 message 的【引用·…】前缀）。
#: P-C4：两种来源作用不同——TA 写给用户的信用来「理解 TA」，
#: 用户写给 TA 的信用来「改进用户自己的表达」，提示词必须按来源区分。
QUOTE_PREFIX = "【引用·"

QUOTE_LETTER_INSTRUCTION = """## 用户引用了一封信
用户消息以【引用·…信《标题》】引用了一封信，先看来源再作答：
- 「TA写给你的信」：站在收信人（用户）视角解读——TA 这封信的情绪、真实需求、言外之意，以及用户怎么回应更合适；
- 「你写给TA的信」：站在写信人（用户）视角审阅——这封信哪里可能被误读、语气与表达哪里可以改进。"""


def has_letter_quote(user_input: str) -> bool:
    """用户输入是否引用了一封信（客户端来源标签必须含「信《》」）。"""
    text = user_input or ""
    return QUOTE_PREFIX in text and "信《" in text


def with_quote_letter_instruction(text: str, user_input: str) -> str:
    """输入引用了信件时把来源区分指令追加到 system 尾部（幂等）。

    与 [with_human_base] 同一追加模式：所有出口一处生效，
    重复拼接不会让指令出现两遍。
    """
    if not has_letter_quote(user_input) or QUOTE_LETTER_INSTRUCTION in text:
        return text
    return text.rstrip() + "\n\n" + QUOTE_LETTER_INSTRUCTION


#: chat_mode 三档规格（P-B §1.2 差异矩阵，逐项可断言）。
#: - 字段名必须是 chat_mode；**绝不能叫 mode**——lc_prompt_builder 的 mode 是
#:   输出通道（structured|stream），撞名会直接错乱。
#: - deep 与既有行为逐参数相同（max_tokens=1200 / budget=1024 / 记忆5 / 理论3 / 历史20），
#:   旧客户端不传 → 回落 deep → 零回归。
#: - thinking 的实际值由 llm_client 按档位实例承担（quick 关思考），这里只记规格。
#: - budget_* 六字段（P-C2 §2）：分层预算矩阵，`context_budget.fit_budget`
#:   的**唯一**配置来源（不在别处另建配置表）。单位 = 字符近似（1 汉字≈1token）。
#:   budget_h/e/m 分别与 history_limit/memory_limit/rag_top_k 同数——
#:   消费侧条数与预算侧条数是同一件事，两份数字会漂移。
CHAT_MODE_CONFIG: Dict[str, Dict[str, Any]] = {
    "quick": {
        "enable_thinking": False,
        "thinking_budget": 0,
        "max_tokens": 600,
        "memory_limit": 0,
        "rag_top_k": 0,
        "history_limit": 6,
        "instruction": QUICK_INSTRUCTION,
        "budget_total": 2000,
        "budget_s": 800,
        "budget_p": 800,
        "budget_h": 6,
        "budget_e": 0,
        "budget_m": 0,
    },
    "deep": {
        "enable_thinking": True,
        "thinking_budget": 1024,
        "max_tokens": 1200,
        "memory_limit": 5,
        "rag_top_k": 3,
        "history_limit": 20,
        "instruction": STREAM_INSTRUCTION,
        "budget_total": 6000,
        "budget_s": 1200,
        "budget_p": 1600,
        "budget_h": 20,
        # 文档矩阵此处写 3；取 5（=memory_limit）——evidence 同源断言要求
        # evidence 与直接召回 limit=5 同条数（case_evidence_same_as_prompt）
        "budget_e": 5,
        "budget_m": 3,
    },
    "expert": {
        "enable_thinking": True,
        "thinking_budget": 2048,
        "max_tokens": 2000,
        "memory_limit": 10,
        "rag_top_k": 5,
        "history_limit": 40,
        "instruction": EXPERT_INSTRUCTION,
        "budget_total": 12000,
        "budget_s": 1500,
        "budget_p": 2400,
        "budget_h": 40,
        # 文档矩阵此处写 8；取 10（=memory_limit），同上：同源同条数
        "budget_e": 10,
        "budget_m": 5,
    },
}

DEFAULT_CHAT_MODE = "deep"


def resolve_chat_mode(value: Optional[str]) -> str:
    """chat_mode 白名单：非法值（None/空串/脏请求/旧版本客户端）一律回落 deep。

    不抛 422——档位是体验参数，不该让脏请求打不开对话。
    """
    mode = (value or "").strip().lower()
    return mode if mode in CHAT_MODE_CONFIG else DEFAULT_CHAT_MODE

#: 无 avatar 记录时的默认人格（模型列默认 name 是「小爱」，但手册规定
#: 未创建形象时对外口径用「军师」——这里跟手册，不读模型默认值）
DEFAULT_AVATAR_NAME = "军师"


#: 军师设置四项（契约 §3.3）的 prompt 注入映射。默认值（standard/moderate/
#: show_evidence=True/address 空）一律不产出文本——老 avatar 行行为零变化。
_DETAIL_LEVEL_INSTRUCTIONS = {
    "brief": "回答尽量简短直接，抓住重点即可。",
    "detailed": "回答可以展开细节，给出更充分的分析。",
    # standard：基线行为，不注入
}
_PROACTIVITY_INSTRUCTIONS = {
    "passive": "保持克制，未经询问不要主动给建议。",
    "active": "可以主动提供建议与提醒。",
    # moderate：基线行为，不注入
}


def build_persona_instruction(
    name: Optional[str],
    voice_style: Optional[str],
    *,
    address_name: Optional[str] = None,
    detail_level: Optional[str] = None,
    proactivity: Optional[str] = None,
    show_evidence: Optional[bool] = None,
) -> str:
    """组装军师人格指令（P0-7）：名字 + 语气 + 设置项，注入 system 最后一段。

    前两参保持位置传参（既有测试与调用点兼容）；契约 §3.3 的四项设置走
    keyword-only：只在偏离基线时追加短句（默认 avatar 行为与旧版逐字节一致）。
    voice_style 不在 5 选 1 内（None / 空串 / 库里脏数据）→ gentle。
    与画像卡分开注入：人格是「军师是谁」，不是「用户是谁」。
    """
    clean_name = (name or "").strip() or DEFAULT_AVATAR_NAME
    if len(clean_name) > 12:
        clean_name = clean_name[:12]
    style = VOICE_STYLE_INSTRUCTIONS.get(
        (voice_style or "").strip(),
        VOICE_STYLE_INSTRUCTIONS["gentle"],
    )
    parts = [f"你的名字是「{clean_name}」，用户这样称呼你。{style}"]

    addr = (address_name or "").strip()
    if addr:
        if len(addr) > 12:
            addr = addr[:12]
        parts.append(f"称呼用户为「{addr}」。")

    detail_text = _DETAIL_LEVEL_INSTRUCTIONS.get((detail_level or "").strip())
    if detail_text:
        parts.append(detail_text)

    proactivity_text = _PROACTIVITY_INSTRUCTIONS.get((proactivity or "").strip())
    if proactivity_text:
        parts.append(proactivity_text)

    if show_evidence is False:
        parts.append("除非用户追问，否则不要展开你的判断依据。")

    return "".join(parts)


def truncate_at_json_marker(template: str) -> str:
    """把结构化 system 模板裁到「请以 JSON 格式回复」之前。

    结构化模板尾部那段字段清单只对 Function Calling 有意义；流式链路要的是
    一段自然语言，必须在同一处分界点截断，否则用户会读到「summary: …」这类
    字段名。本模块的文本投影与 `lc_prompt_builder` 的消息组装共用这一处实现，
    避免同一条分界规则写两遍、日后分叉。
    """
    cut = template.find(_JSON_MARKER)
    return template[:cut].rstrip() if cut != -1 else template.rstrip()


def messages_to_text(messages) -> str:
    """把 system / human 分层消息压平成单条文本（用于留档与人工对照）。"""
    return "\n\n".join(m.get("content", "") for m in messages)


def resolve_system_prompt(
    scene_key: str,
    db=None,
    user_id: Optional[int] = None,
) -> str:
    """解析该场景此刻真正生效的 system prompt 文本。

    取值优先级：

    1. `ai_prompt_template` 表中该场景 `status=active` 的模板。
       多个 active 版本并存时，由 `ai_repo.get_active_prompt_template` 按
       `user_id` 稳定分流——这就是「prompt 版本化 / A/B 灰度」的落地方式：
       换提示词不必改代码、不必重新发版，在库里加一版并置 active 即可。
    2. 代码内置的 `SYSTEM_PROMPTS[scene_key]`。

    两个必须成立的约束：

    - **DB 侧任何异常都静默回退静态模板**。prompt 组装是模型调用的前置步骤，
      不允许成为可用性故障点——库连不上时用户仍应拿到完整回答。
    - **模板必须还原花括号转义**。入库时按存储约定把 `{user_profile}` 写成
      `{{user_profile}}`（见 `scripts/seed_ai_scenes.py`），取用时不还原的话
      `.format()` 只会得到字面量的双花括号，占位符全部失效、画像静默丢失。
      同理，还原后若连 `{user_profile}` 都不含，说明这条模板被写坏了
      （例如误把渲染后的成品存进去），此时回退内置模板比硬用更安全。
    """
    fallback = SYSTEM_PROMPTS.get(scene_key, SYSTEM_PROMPTS["private_advisor"])
    if db is None:
        return fallback

    try:
        from app.repositories import ai_repo

        template = ai_repo.get_active_prompt_template(db, scene_key, user_id=user_id)
    except Exception as exc:  # noqa: BLE001 —— 退化为静态模板即可，不该影响主链路
        logger.warning("[PROMPT] 读取 DB 模板失败，回退内置模板 scene=%s: %s", scene_key, exc)
        return fallback

    if not template or not (template.template_content or "").strip():
        return fallback

    text = template.template_content.replace("{{", "{").replace("}}", "}")
    if "{user_profile}" not in text:
        logger.warning(
            "[PROMPT] DB 模板缺少 {user_profile} 占位符，回退内置模板 scene=%s version=%s",
            scene_key, getattr(template, "version", "?"),
        )
        return fallback

    logger.info(
        "[PROMPT] 采用 DB 模板 scene=%s version=%s user=%s",
        scene_key, getattr(template, "version", "?"), user_id,
    )
    return text


def _chat_messages(scene_key, user_profile, partner_profile, conflict_pattern,
                   user_input, history, rag_context, memory_context,
                   system_template, mode):
    """延迟导入并转调 `lc_prompt_builder`。

    本模块不能顶层 import `lc_prompt_builder`——后者要 import 本模块的
    `SYSTEM_PROMPTS` 与 `truncate_at_json_marker`，顶层互引会成环。
    """
    from app.services.lc_prompt_builder import build_chat_messages

    return build_chat_messages(
        scene_key=scene_key,
        user_profile=user_profile,
        partner_profile=partner_profile,
        conflict_pattern=conflict_pattern,
        user_input=user_input,
        history=history,
        rag_context=rag_context,
        memory_context=memory_context,
        mode=mode,
        system_template=system_template,
    )


def build_prompt(
    scene_key: str,
    user_profile: str,
    partner_profile: str,
    conflict_pattern: str,
    user_input: str,
    history: str,
    rag_context: str = "",
    memory_context: str = "",
    system_template: Optional[str] = None,
) -> str:
    """结构化 Prompt 的**文本形态**。

    主链路向模型提交的是 system / human 分层消息
    （`lc_prompt_builder.build_chat_messages`）；本函数把同一份消息压平成一条
    字符串，供仍按「单条 prompt」调用的场景、单测与人工对照使用。
    两者内容同源——压平不会有第二套拼装逻辑，因此不存在漂移风险。
    """
    return messages_to_text(_chat_messages(
        scene_key, user_profile, partner_profile, conflict_pattern,
        user_input, history, rag_context, memory_context,
        system_template, "structured",
    ))


def build_stream_prompt(
    scene_key: str,
    user_profile: str,
    partner_profile: str,
    conflict_pattern: str,
    user_input: str,
    history: str,
    rag_context: str = "",
    memory_context: str = "",
    system_template: Optional[str] = None,
) -> str:
    """流式变体 Prompt 的文本形态（`build_prompt` 的自然语言版）。

    为什么需要两个版本：
    - 结构化输出依赖 Function Calling，模型必须一次性回填完整 JSON，
      天然无法逐 token 流式返回（半截 JSON 对前端没有意义）。
    - 因此流式链路走"自然语言正文"这条通道，让用户先看到字；
      落库时再把正文与风险等级一起写入 structured_output，
      保证流式与非流式的历史记录结构一致。
    """
    return messages_to_text(_chat_messages(
        scene_key, user_profile, partner_profile, conflict_pattern,
        user_input, history, rag_context, memory_context,
        system_template, "stream",
    ))


def build_structured_stream_prompt(
    base_prompt: str,
    output_model,
    *,
    content_instruction: str = "自然、口语化，像面对面说话那样",
    max_content_chars: int = 500,
) -> str:
    """在结构化 Prompt 上叠加「先正文、后 JSON」的双出口流式协议。

    与 `build_stream_prompt` 的分工：

    - `build_stream_prompt`：**纯文本**流式。结构化字段一概不要，适合那些
      本来就只需要一段话的场景（AI 对话）。
    - 本函数：**双出口**流式。用户读正文、程序读 JSON，适合必须落库结构化
      字段的场景（信件解读/改写/回信）。它只调用一次模型——如果先流式调一次
      再结构化调一次，token 和等待都要翻倍。

    实现上把 base_prompt 里的「请以 JSON 格式回复…」及其后的字段说明整段
    截掉，换成由 Pydantic 模型现场生成的 JSON Schema。这样做的好处是
    **字段契约只有 Pydantic 一处定义**，不会再出现「prompt 里写的字段名
    和模型定义对不上、代码读不到值」这类漂移。
    """
    cut = base_prompt.find(_JSON_MARKER)
    head = base_prompt[:cut].rstrip() if cut != -1 else base_prompt.rstrip()

    schema = json.dumps(inline_json_schema(output_model), ensure_ascii=False, indent=2)
    tail = (
        "\n\n## 输出格式（必须严格遵守）\n"
        "输出分为两段，第一段在前、第二段在后。\n\n"
        "第一段：用简体中文把分析讲清楚，Markdown 分段，%s，控制在 %d 字以内。"
        "这一段是用户直接读到的内容，因此**不要**出现「JSON」「字段」「结构化」"
        "「Schema」这类字眼，也不要输出代码块。\n"
        "%s\n"
        "第二段：另起一行，先原样输出分隔符 %s，紧接着输出一个 JSON 对象。"
        "该对象必须严格符合下面的 Schema，字段一个都不能少。"
        "它由程序解析、用户看不到，所以**不要**用 ``` 代码块包裹：\n%s\n\n"
        "除这两段之外，不要输出任何多余内容（不要开场白、不要总结）。"
        % (content_instruction, max_content_chars, MARKDOWN_OUTPUT_RULES + "\n", STRUCTURED_MARKER, schema)
    )
    # L0 基线（P-B §2）：信件类双出口的正文段同样受禁语约束；head 常已经
    # 含 L0（base_prompt 走过 build_chat_messages），with_human_base 幂等。
    return with_human_base(head) + tail


def build_profile_report_prompt(
    profile_type: str,
    confidence: float,
    dimensions_data: str,
) -> str:
    """构建画像分析报告的 prompt"""
    from app.services.profile_service import _classify_attachment

    type_names = {
        "secure": "安全型依恋",
        "anxious": "焦虑依恋型",
        "dismissive": "疏离回避型",
        "fearful": "恐惧回避型",
        "mixed": "混合型依恋",
    }
    type_name = type_names.get(profile_type, "未知类型")
    user_profile = f"依恋类型：{type_name}，置信度：{confidence * 100:.0f}%"

    template = SYSTEM_PROMPTS["profile_report"]
    return with_human_base(template.format(
        user_profile=user_profile,
        dimensions_data=dimensions_data,
    ))


def build_memory_card_prompt(item_kind: str, item_detail: str) -> str:
    """构建纪念日/愿望回忆卡片的 prompt"""
    template = SYSTEM_PROMPTS["memory_card"]
    return with_human_base(template.format(
        item_kind=item_kind,
        item_detail=item_detail,
    ))


def build_viewpoint_analysis_prompt(
    profile_digest: str,
    viewpoint_text: str,
    dimension_catalog: str,
) -> str:
    """构建「观点分析」的 prompt（用户需求 #5）。

    与其它场景的两点不同，都是刻意的：

    1. 输入里带上**现有画像摘要**。不带的话，模型会把用户早就说过、画像里
       已经有的事当成新发现再建议写一次；带上之后它才知道「已知什么」。
    2. 提示词明确要求**不给分数**。分数由 `profile_service` 按幅度规则算，
       模型只回答方向与强度——否则一次生成就能把画像拉到底。
    """
    template = SYSTEM_PROMPTS["viewpoint_analysis"]
    return with_human_base(template.format(
        user_profile=profile_digest or "（还没有任何画像，这是第一次记录）",
        viewpoint_text=viewpoint_text,
        dimension_catalog=dimension_catalog,
    ))


def build_practice_summary_prompt(
    practice_title: str,
    side_self: str,
    side_partner: str,
) -> str:
    """构建关系练习 AI 整理的 prompt"""
    template = SYSTEM_PROMPTS["practice_summary"]
    return with_human_base(template.format(
        practice_title=practice_title,
        side_self=side_self or "（未作答）",
        side_partner=side_partner or "（未作答）",
    ))


def build_dual_summary_prompt(
    event_title: str,
    side_self: str,
    side_partner: str,
) -> str:
    """构建双视角对照总结的 prompt"""
    template = SYSTEM_PROMPTS["dual_summary"]
    return with_human_base(template.format(
        event_title=event_title,
        side_self=side_self or "（未填写）",
        side_partner=side_partner or "（未填写）",
    ))


def build_mediation_rewrite_prompt(
    inviter_profile: str,
    partner_profile: str,
    conflict_pattern: str,
    inviter_input: str,
    partner_input: str,
) -> str:
    """构建调解「双方改写」的 prompt（整改 §8.5：从 mediation_service 迁入）。

    提示词层是唯一的 prompt 出口（代码风格约束：不得硬编码在业务函数里）。
    两侧输入由调用方**按作者分组**后传入，本函数只负责拼装与兜底文案。
    """
    template = SYSTEM_PROMPTS["mediation_rewrite"]
    return with_human_base(template.format(
        inviter_profile=inviter_profile,
        partner_profile=partner_profile,
        conflict_pattern=conflict_pattern,
        inviter_input=inviter_input or "（未填写）",
        partner_input=partner_input or "（未填写）",
    ))


def build_mediation_summary_prompt(
    user_profile: str,
    partner_profile: str,
    conflict_pattern: str,
    all_text: str,
) -> str:
    """构建调解总结的 prompt（整改 §8.5：从 mediation_service 迁入）。

    只应在**双方确认后**调用——双方独立输入的公开时机由状态机决定
    （§8.5-3 / §8.5-5），本函数不做可见性判定。
    """
    template = SYSTEM_PROMPTS["mediation_summary"]
    return with_human_base(template.format(
        user_profile=user_profile,
        partner_profile=partner_profile,
        conflict_pattern=conflict_pattern,
        all_text=all_text,
    ))
