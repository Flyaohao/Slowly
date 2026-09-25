"""
关系画像问卷 v3 - 基于权威量表
核心量表：
  - ECR-R (Experiences in Close Relationships-Revised): Fraley, Waller & Brennan (2000)
    36题, 18焦虑 + 18回避, 7级Likert
  - CPQ (Communication Patterns Questionnaire): Christensen & Heavey (1990)
    冲突模式：需求-退缩、相互回避、相互建设
补充量表：
  - Love Languages Quiz: Gary Chapman (1992), 5种爱的语言
  - Gottman四骑士行为自评: 批评/蔑视/防御/石墙

维度说明（与profile_service.py对齐）：
  - attachment_anxiety: 依恋焦虑 (ECR-R焦虑分量表)
  - attachment_avoidance: 依恋回避 (ECR-R回避分量表)
  - conflict_pursue: 冲突追问倾向 (ECR-R焦虑 + CPQ需求端)
  - conflict_withdraw: 冲突退缩倾向 (ECR-R回避 + CPQ退缩端)
  - defensive_response: 防御反驳倾向 (Gottman防御)
  - emotional_validation_need: 情绪确认需求 (补充)
  - factual_explanation_need: 事实解释需求 (补充)
  - personal_space_need: 独处冷静需求 (ECR-R回避相关)
  - reassurance_need: 安全感确认需求 (ECR-R焦虑相关)
  - directness_preference: 直接表达偏好 (补充)
  - softness_preference: 柔和表达偏好 (补充)

注意：
  - 标注(R)的为反向计分题，实际存储时已转换为正向分数
  - ECR-R原题使用7级Likert: 1=非常不同意 ~ 7=非常同意
  - 情景题和排序题为补充题，非原量表内容
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal, engine
from app.models.questionnaire import Questionnaire, QuestionnaireQuestion, QuestionnaireOption


# 7级Likert标准选项
LIKERT_7 = [
    {"text": "非常不同意", "score": 0},
    {"text": "不同意", "score": 17},
    {"text": "有点不同意", "score": 33},
    {"text": "中立", "score": 50},
    {"text": "有点同意", "score": 67},
    {"text": "同意", "score": 83},
    {"text": "非常同意", "score": 100},
]


QUESTIONS_DATA = [
    # ================================================================
    # Part A: ECR-R 依恋焦虑分量表 (18题)
    # 来源: Fraley, Waller & Brennan (2000)
    # 维度: attachment_anxiety
    # ================================================================
    {
        "dimension": "attachment_anxiety",
        "text": "我害怕伴侣会不再爱我",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #1",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我经常担心伴侣不想和我在一起",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #2",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我经常担心伴侣并不是真的爱我",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #3",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我担心伴侣不像我在乎 TA 那样在乎我",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #4",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我经常希望伴侣对我的感情能像我对 TA 的一样深",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #5",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我非常担心我的亲密关系",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #6",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "当伴侣不在身边时，我担心 TA 可能会对别人产生兴趣",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #7",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "当我向伴侣表达感情时，我害怕 TA 不会同样对待我",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #8",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我很少担心伴侣会离开我",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向：不同意=高焦虑
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #9 (R)",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "伴侣让我对自己产生怀疑",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #10",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我不太担心被抛弃",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #11 (R)",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我发现伴侣不想如我期望的那样亲近",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #12",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "有时伴侣会无缘无故地改变对我的态度",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #13",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我渴望非常亲近的欲望有时会吓到对方",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #14",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我担心自己不如别人好",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #15",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "伴侣似乎只有在我生气时才会注意到我",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #16",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "我担心会孤独一人",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #17",
    },
    {
        "dimension": "attachment_anxiety",
        "text": "当伴侣与我意见不一致时，我担心这意味着 TA 不爱我了",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #18",
    },

    # ================================================================
    # Part B: ECR-R 依恋回避分量表 (18题)
    # 来源: Fraley, Waller & Brennan (2000)
    # 维度: attachment_avoidance
    # ================================================================
    {
        "dimension": "attachment_avoidance",
        "text": "我不太愿意向伴侣展示我内心深处的感受",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #19",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我感到很自在地与伴侣分享我的私人想法和感受",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #20 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我觉得很难让自己依赖伴侣",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #21",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "与伴侣非常亲近让我感到很自在",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #22 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我对向伴侣敞开心扉感到不自在",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #23",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我不希望与伴侣太过亲近",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #24",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "当伴侣想要非常亲近时，我会感到不舒服",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #25",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我觉得与伴侣亲近是比较容易的事",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #26 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "对我来说，与伴侣亲近并不难",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #27 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我发现自己很难对伴侣完全放心",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #28",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我倾向于不向伴侣倾诉太多",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #29",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "当伴侣与我太亲近时我会感到紧张",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #30",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我感到可以自在地依赖伴侣",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #31 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "向伴侣表达情感对我来说很容易",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #32 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "伴侣并不真正了解我",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #33",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "当伴侣想要更亲近我时我会退缩",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #34",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "我经常与伴侣分享我的感受和想法",
        "type": "likert",
        "options": [
            {"text": "非常不同意", "score": 100},  # 反向
            {"text": "不同意", "score": 83},
            {"text": "有点不同意", "score": 67},
            {"text": "中立", "score": 50},
            {"text": "有点同意", "score": 33},
            {"text": "同意", "score": 17},
            {"text": "非常同意", "score": 0},
        ],
        "source": "ECR-R #35 (R)",
    },
    {
        "dimension": "attachment_avoidance",
        "text": "当伴侣不在身边时我会感到安心",
        "type": "likert",
        "options": LIKERT_7,
        "source": "ECR-R #36",
    },

    # ================================================================
    # Part C: 补充题 (14题)
    # 每个维度1道核心Likert + 情景/排序/多选各1道
    # ================================================================
    # 冲突与防御
    {
        "dimension": "conflict_pursue",
        "text": "发生矛盾时，我会忍不住追问对方的态度和想法",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "conflict_withdraw",
        "text": "当争吵升级时，我更想离开现场而不是继续争论",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "defensive_response",
        "text": "当伴侣指出我的问题时，我的第一反应是解释或反驳",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    # 沟通需求
    {
        "dimension": "emotional_validation_need",
        "text": "比起解决方案，我更希望伴侣先倾听我的感受",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "factual_explanation_need",
        "text": "比起安慰，我更希望伴侣能清楚解释事情的原因",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "personal_space_need",
        "text": "压力大时，我更想一个人待着而不是找伴侣倾诉",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "reassurance_need",
        "text": "我需要伴侣经常用行动或语言确认对我的爱",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "directness_preference",
        "text": "我希望伴侣有想法直接说，不要拐弯抹角",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "softness_preference",
        "text": "即使是正确的话，如果语气太强硬我也很难接受",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    {
        "dimension": "emotional_validation_need",
        "text": "当我难过时，我最需要的是伴侣的理解和陪伴",
        "type": "likert",
        "options": LIKERT_7,
        "source": "adapted",
    },
    # 情景题 (2道，覆盖最核心的冲突场景)
    {
        "dimension": "conflict_pursue",
        "text": "伴侣答应陪你吃饭，但临时说要加班。你的第一反应是？",
        "type": "single_choice",
        "options": [
            {"text": "虽然失望，但表示理解", "score": 0},
            {"text": "有点不开心，但不会说什么", "score": 33},
            {"text": "会追问为什么总是这样", "score": 67},
            {"text": "很生气，直接表达不满", "score": 100},
        ],
        "source": "scenario",
    },
    {
        "dimension": "conflict_withdraw",
        "text": "你和伴侣吵了起来，气氛越来越紧张。你会？",
        "type": "single_choice",
        "options": [
            {"text": "继续沟通，直到解决", "score": 0},
            {"text": "先暂停，但很快回来聊", "score": 33},
            {"text": "说「我不想吵了」，然后沉默", "score": 67},
            {"text": "直接走开，很久才能平复", "score": 100},
        ],
        "source": "scenario",
    },
    # 爱的语言（排序题）
    {
        "dimension": "reassurance_need",
        "text": "以下哪些方式最能让你感受到被爱？请按重要性排序，排在第一位的最重要",
        "type": "sort",
        "options": [
            {"text": "肯定的话语（夸奖、鼓励）", "score": 0},
            {"text": "高质量的陪伴（专注在一起）", "score": 0},
            {"text": "收到礼物或惊喜", "score": 0},
            {"text": "服务的行动（帮忙做事）", "score": 0},
            {"text": "身体接触（拥抱、牵手）", "score": 0},
        ],
        "source": "Chapman adapted",
    },
    # 多选题：表达雷区
    {
        "dimension": "defensive_response",
        "text": "以下哪些话会让你感到受伤？（可多选）",
        "type": "multi_choice",
        "options": [
            {"text": "[你想太多了]", "score": 100},
            {"text": "[你每次都这样]", "score": 100},
            {"text": "[随便你吧]", "score": 100},
            {"text": "[你能不能讲道理]", "score": 100},
            {"text": "[我都是为你好]", "score": 100},
            {"text": "[算了，不说了]", "score": 100},
            {"text": "以上都不会特别在意", "score": 0},
        ],
        "source": "adapted",
    },
]


def seed_questionnaire():
    db = SessionLocal()
    try:
        existing = db.query(Questionnaire).filter(Questionnaire.status == "active").first()
        if existing:
            print(f"问卷已存在: id={existing.id}, title={existing.title}")
            print("如需重新创建，请先将现有问卷状态改为 inactive")
            return

        q = Questionnaire(
            title="关系画像问卷",
            description="本问卷核心部分采用 ECR-R 依恋量表（Fraley, Waller & Brennan, 2000），"
                        "并补充了冲突模式、沟通偏好和爱的语言等维度。\n\n"
                        "共50题，约需12-15分钟完成。\n\n"
                        "重要说明：本问卷不是心理诊断工具，结果仅供 AI 军师参考，"
                        "用于为你提供更个性化的沟通建议。如有心理困扰，请寻求专业心理咨询师的帮助。",
            version=3,
            status="active",
        )
        db.add(q)
        db.flush()
        print(f"创建问卷: id={q.id}")

        for idx, qdata in enumerate(QUESTIONS_DATA):
            question = QuestionnaireQuestion(
                questionnaire_id=q.id,
                question_text=qdata["text"],
                question_type=qdata["type"],
                dimension_key=qdata["dimension"],
                is_required=True,
                weight=1.0,
                sort_order=idx + 1,
            )
            db.add(question)
            db.flush()

            for opt_idx, opt_data in enumerate(qdata["options"]):
                option = QuestionnaireOption(
                    question_id=question.id,
                    option_text=opt_data["text"],
                    score_value=opt_data["score"],
                    sort_order=opt_idx + 1,
                )
                db.add(option)

        db.commit()

        # 统计
        dims = {}
        types = {}
        sources = {}
        for qd in QUESTIONS_DATA:
            dims[qd["dimension"]] = dims.get(qd["dimension"], 0) + 1
            types[qd["type"]] = types.get(qd["type"], 0) + 1
            src = qd.get("source", "unknown")
            if "ECR-R" in src:
                sources["ECR-R"] = sources.get("ECR-R", 0) + 1
            elif "adapted" in src:
                sources["adapted"] = sources.get("adapted", 0) + 1
            elif "scenario" in src:
                sources["scenario"] = sources.get("scenario", 0) + 1
            elif "Chapman" in src:
                sources["Chapman"] = sources.get("Chapman", 0) + 1
            else:
                sources[src] = sources.get(src, 0) + 1

        print(f"已创建 {len(QUESTIONS_DATA)} 道题目")
        print(f"  题目来源: {sources}")
        print(f"  维度分布: {dims}")
        print(f"  题型分布: {types}")
    except Exception as e:
        db.rollback()
        print(f"错误: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    seed_questionnaire()
