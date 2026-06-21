import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal
from app.models.practice import RelationshipPractice


PRACTICES_DATA = [
    {
        "title": "倾听练习",
        "practice_type": "listening",
        "description": "一方表达自己的感受和想法，另一方复述自己听到的内容，确认理解是否准确。练习目的是培养真正的倾听能力，而不是急于回应或反驳。",
    },
    {
        "title": "感谢练习",
        "practice_type": "thanks",
        "description": "写下对伴侣的感谢，可以是日常小事，也可以是重要的支持。练习目的是培养感恩的习惯，让对方感受到被看见和被珍惜。",
    },
    {
        "title": "道歉练习",
        "practice_type": "apology",
        "description": "学习真诚道歉的结构：承认具体行为、理解对方感受、表达悔意、说明改变计划。练习目的是让道歉更有力量，而不是敷衍了事。",
    },
    {
        "title": "表达需求练习",
        "practice_type": "need",
        "description": "用「我感到……因为我需要……」的句式表达自己的需求，而不是指责对方。练习目的是学会非暴力沟通，减少防御性反应。",
    },
    {
        "title": "和好复盘",
        "practice_type": "reconcile",
        "description": "回顾一次冲突的完整过程：发生了什么、各自的感受、触发了什么、下次可以怎么做。练习目的是从冲突中学习，而不是留下伤疤。",
    },
    {
        "title": "安慰练习",
        "practice_type": "comfort",
        "description": "学习如何安慰伴侣：先认可情绪、再提供支持，而不是急于解决问题。练习目的是让对方感到被理解，而不是被说教。",
    },
    {
        "title": "误解澄清练习",
        "practice_type": "clarify",
        "description": "识别和澄清误解：说出自己的理解、请对方确认、澄清差异。练习目的是减少「你以为我以为」的信息差，建立更准确的认知。",
    },
]


def seed_practices():
    db = SessionLocal()
    try:
        existing = db.query(RelationshipPractice).first()
        if existing:
            print("练习数据已存在，跳过")
            return

        for data in PRACTICES_DATA:
            practice = RelationshipPractice(**data)
            db.add(practice)

        db.commit()
        print(f"已创建 {len(PRACTICES_DATA)} 种关系练习")
    except Exception as e:
        db.rollback()
        print(f"错误: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    seed_practices()
