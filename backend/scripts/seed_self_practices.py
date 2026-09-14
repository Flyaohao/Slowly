"""自我练习种子数据（单身模式专属）

与 `seed_practices.py` 的区别：那边灌的是情侣双向练习 `RelationshipPractice`，
本脚本灌的是单身单人练习 `SelfPractice`。两者是不同表、不同语义，不要混用。

内容设计原则：单身模式下没有伴侣可配合，因此全部设计成**一个人就能完成**的练习，
重点是自我觉察与自我照顾，而不是沟通演练。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal
from app.models.self_practice import SelfPractice


SELF_PRACTICES_DATA = [
    {
        "title": "情绪命名练习",
        "practice_type": "emotion_naming",
        "description": "把今天最强烈的一股情绪写下来，并给它一个尽量精确的名字。很多人说得出「不开心」，却说不出到底是委屈、失望、被忽视，还是只是累了。命名越精确，情绪就越容易被安放。",
        "guidance": "1. 回想今天情绪起伏最大的一刻\n2. 先写下最直觉的那个词\n3. 再往下追问：还有没有更贴切的词？\n4. 写下当时身体的感觉（胸口发紧？肩膀发酸？）\n5. 最后写一句：如果这个情绪会说话，它想告诉我什么",
    },
    {
        "title": "自我安抚练习",
        "practice_type": "self_soothing",
        "description": "练习在情绪上来时先照顾自己，而不是立刻找人倾诉或转移注意力。这是把「被安抚」的能力慢慢长在自己身上的过程。",
        "guidance": "1. 想一件最近让你难受的事\n2. 想象这件事发生在你最好的朋友身上，你会对他说什么？把这句话原样写下来\n3. 现在把这句话里的「你」换成「我」，再读一遍\n4. 写下读完之后的感受\n5. 选一件此刻能让自己稍微舒服一点的小事，马上去做",
    },
    {
        "title": "依恋模式觉察",
        "practice_type": "attachment_awareness",
        "description": "回顾一段让你印象深刻的亲密关系（不限于恋爱，也包括家人和挚友），看看自己在关系紧张时的第一反应是什么：追上去问清楚、还是先躲开冷一冷？这不是诊断，只是一次观察。",
        "guidance": "1. 想起一个人、一段关系\n2. 写下关系出现紧张时，你的第一反应\n3. 再写下你当时真正害怕的是什么\n4. 那次你最终做了什么？结果如何？\n5. 如果重来一次，你希望自己怎么做",
    },
    {
        "title": "边界感练习",
        "practice_type": "boundary",
        "description": "找出一个你长期在勉强自己的场景，练习把「我不愿意」说出口。边界不是把人推开，而是让关系能长久。",
        "guidance": "1. 写下一个你常答应、但心里其实不愿意的事\n2. 写下你一直答应它的原因（怕对方失望？怕被说不合群？）\n3. 写一句既不伤人、又能表达自己真实想法的话\n4. 如果对方因此不高兴，你能接受吗？写下你的答案\n5. 挑一个低风险的场合，把这句话说出来",
    },
    {
        "title": "需求表达预演",
        "practice_type": "need_expression",
        "description": "用「我感到……因为我需要……」的句式，把自己想要的东西说清楚。这套句式的好处是：它描述的是你自己，而不是指责对方。",
        "guidance": "1. 写下一件你最近希望别人为你做的事\n2. 用「你总是……」开头写一遍（这是指责版）\n3. 再用「我感到……因为我需要……」重写一遍（这是表达版）\n4. 对比两句话，写下读起来的差别\n5. 把表达版读出声，感受一下说出口的难度",
    },
    {
        "title": "独处充电练习",
        "practice_type": "self_recharge",
        "description": "练习有意识地独处，而不是在孤独感袭来时被动地刷手机。分清楚「一个人待着」和「被孤立」是两件不同的事。",
        "guidance": "1. 关掉所有通知，留出 20 分钟\n2. 写下一件你一直想做、但总说没时间的事\n3. 现在就去做，或者为它定一个具体的时间\n4. 结束后写下：这 20 分钟里，什么时候最放松？\n5. 写下你打算多久这样陪自己一次",
    },
]


def seed_self_practices():
    db = SessionLocal()
    try:
        existing = db.query(SelfPractice).first()
        if existing:
            print("自我练习数据已存在，跳过")
            return

        for data in SELF_PRACTICES_DATA:
            db.add(SelfPractice(**data))

        db.commit()
        print(f"已创建 {len(SELF_PRACTICES_DATA)} 种自我练习")
    except Exception as e:
        db.rollback()
        print(f"错误: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    seed_self_practices()
