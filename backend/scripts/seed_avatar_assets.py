import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal
from app.models.avatar import AiAvatarAsset


ASSETS_DATA = [
    {"asset_type": "face", "asset_url": "/assets/avatar/face/round.svg", "unlock_condition": None},
    {"asset_type": "face", "asset_url": "/assets/avatar/face/oval.svg", "unlock_condition": None},
    {"asset_type": "face", "asset_url": "/assets/avatar/face/square.svg", "unlock_condition": "完成3次练习"},
    {"asset_type": "eye", "asset_url": "/assets/avatar/eye/sparkle.svg", "unlock_condition": None},
    {"asset_type": "eye", "asset_url": "/assets/avatar/eye/heart.svg", "unlock_condition": "收到10封信件"},
    {"asset_type": "eye", "asset_url": "/assets/avatar/eye/sleepy.svg", "unlock_condition": None},
    {"asset_type": "mouth", "asset_url": "/assets/avatar/mouth/smile.svg", "unlock_condition": None},
    {"asset_type": "mouth", "asset_url": "/assets/avatar/mouth/open.svg", "unlock_condition": None},
    {"asset_type": "mouth", "asset_url": "/assets/avatar/mouth/kiss.svg", "unlock_condition": "纪念日当天"},
    {"asset_type": "blush", "asset_url": "/assets/avatar/blush/pink.svg", "unlock_condition": None},
    {"asset_type": "blush", "asset_url": "/assets/avatar/blush/peach.svg", "unlock_condition": None},
    {"asset_type": "hat", "asset_url": "/assets/avatar/hat/none.svg", "unlock_condition": None},
    {"asset_type": "hat", "asset_url": "/assets/avatar/hat/bow.svg", "unlock_condition": None},
    {"asset_type": "hat", "asset_url": "/assets/avatar/hat/crown.svg", "unlock_condition": "完成10次练习"},
    {"asset_type": "wing", "asset_url": "/assets/avatar/wing/none.svg", "unlock_condition": None},
    {"asset_type": "wing", "asset_url": "/assets/avatar/wing/angel.svg", "unlock_condition": "关系持续100天"},
    {"asset_type": "tail", "asset_url": "/assets/avatar/tail/none.svg", "unlock_condition": None},
    {"asset_type": "tail", "asset_url": "/assets/avatar/tail/cat.svg", "unlock_condition": None},
    {"asset_type": "tail", "asset_url": "/assets/avatar/tail/bunny.svg", "unlock_condition": "发送20封信件"},
    {"asset_type": "clothing", "asset_url": "/assets/avatar/clothing/default.svg", "unlock_condition": None},
    {"asset_type": "clothing", "asset_url": "/assets/avatar/clothing/formal.svg", "unlock_condition": None},
    {"asset_type": "clothing", "asset_url": "/assets/avatar/clothing/pajama.svg", "unlock_condition": None},
    {"asset_type": "handheld", "asset_url": "/assets/avatar/handheld/none.svg", "unlock_condition": None},
    {"asset_type": "handheld", "asset_url": "/assets/avatar/handheld/letter.svg", "unlock_condition": "收到第一封信"},
    {"asset_type": "handheld", "asset_url": "/assets/avatar/handheld/flower.svg", "unlock_condition": None},
    {"asset_type": "background", "asset_url": "/assets/avatar/bg/garden.svg", "unlock_condition": None},
    {"asset_type": "background", "asset_url": "/assets/avatar/bg/starry.svg", "unlock_condition": None},
    {"asset_type": "background", "asset_url": "/assets/avatar/bg/sunset.svg", "unlock_condition": "完成5次练习"},
]


def seed_avatar_assets():
    db = SessionLocal()
    try:
        existing = db.query(AiAvatarAsset).first()
        if existing:
            print("素材数据已存在，跳过")
            return

        for data in ASSETS_DATA:
            asset = AiAvatarAsset(**data)
            db.add(asset)

        db.commit()
        print(f"已创建 {len(ASSETS_DATA)} 个形象素材")
    except Exception as e:
        db.rollback()
        print(f"错误: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    seed_avatar_assets()
