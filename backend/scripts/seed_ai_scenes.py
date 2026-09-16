"""写入 AI 场景与提示模板种子数据。

提示模板内容**唯一来源是 `app/services/prompt_builder.SYSTEM_PROMPTS`**，
本脚本只负责落库，不再复制一份文本。

为什么改成引用：
    原先脚本里内联了一份与 prompt_builder 逐字重复的模板文本，于是同一段 prompt
    在两个文件里各自维护。2026-09-14 修复字段漂移时发现，脚本里那一份同样带着
    「private_advisor 声明 suggested_actions」「expression_rewrite 声明
    do_not_say / next_step」「letter_understand 七个字段错五个」等错误——
    只改 prompt_builder 的话，坑还留着。改成引用后，这类漂移在结构上不可能再发生。

入库时把单花括号转义成双花括号（`{user_profile}` → `{{user_profile}}`），
保持本表原有的存储约定不变。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal
from app.models.ai import AiScene, AiPromptTemplate
from app.services.prompt_builder import SYSTEM_PROMPTS


#: scene_key → (展示名, 描述)。模板内容从 SYSTEM_PROMPTS 取。
SCENE_META = [
    ("private_advisor", "私人军师", "私密模式，AI 先安抚情绪，再结合画像给出建议。默认不向伴侣开放。"),
    ("partner_translate", "对方翻译", "输入伴侣的一句话，AI 帮你理解背后含义。"),
    ("expression_rewrite", "表达改写", "输入你想说的话，AI 帮你改写成更柔和的版本。"),
    ("cold_war", "冷战调解", "冷战状态下的沟通指导，帮助打破僵局。"),
    ("mediation", "矛盾调解", "中立的矛盾调解和沟通建议。"),
    ("letter_understand", "信件解读", "深入理解一段文字的含义，逐句分析。"),
    ("letter_rewrite", "信件改写", "根据风格要求改写信件，保留核心诉求。"),
    (
        "relationship_review",
        "关系复盘",
        "复盘一次争吵、冷战或和好：找出触发点、双方真实需求、误解发生处，给出下次可用的表达。",
    ),
]


def _db_template(scene_key: str) -> str:
    """取该场景的 prompt 并转义花括号，以匹配本表原有的存储约定。"""
    raw = SYSTEM_PROMPTS[scene_key]
    return raw.replace("{", "{{").replace("}", "}}")


def seed_ai_scenes():
    db = SessionLocal()
    try:
        for scene_key, name, description in SCENE_META:
            if scene_key not in SYSTEM_PROMPTS:
                print(f"跳过 {scene_key}：prompt_builder 中无此场景")
                continue

            existing = db.query(AiScene).filter(AiScene.scene_key == scene_key).first()
            if existing:
                print(f"场景已存在: {scene_key}")
                continue

            scene = AiScene(scene_key=scene_key, name=name, description=description)
            db.add(scene)
            db.flush()

            db.add(AiPromptTemplate(
                scene_key=scene_key,
                template_content=_db_template(scene_key),
                version=1,
                status="active",
            ))
            print(f"创建场景: {scene_key} ({name})")

        db.commit()
        print(f"已创建 {len(SCENE_META)} 个 AI 场景")
    except Exception as e:
        db.rollback()
        print(f"错误: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    seed_ai_scenes()
