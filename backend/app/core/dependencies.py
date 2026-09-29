from sqlalchemy.orm import Session
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.database import get_db
from app.security.jwt import decode_token
from app.repositories import user_repo, couple_repo

security_scheme = HTTPBearer(auto_error=False)


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security_scheme),
    db: Session = Depends(get_db),
):
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 20005, "message": "未登录", "data": None},
        )
    token = credentials.credentials
    payload = decode_token(token)
    if payload is None or payload.get("type") != "access":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 20005, "message": "Token 无效", "data": None},
        )
    user_id = int(payload["sub"])
    user = user_repo.get_user_by_id(db, user_id)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 20005, "message": "Token 无效", "data": None},
        )
    return user


def get_current_relation(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    relation = couple_repo.get_relation_by_user_including_unbinding(db, current_user.id)
    return relation


def require_couple_mode(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """确保用户当前为情侣模式"""
    if not current_user.has_couple:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 30002, "message": "请先绑定情侣", "data": None},
        )
    relation = couple_repo.get_relation_by_user_including_unbinding(db, current_user.id)
    if not relation or relation.status not in ("active", "unbinding"):
        user_repo.update_has_couple(db, current_user.id, False)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 30002, "message": "请先绑定情侣", "data": None},
        )
    return current_user, relation
