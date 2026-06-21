from pydantic import BaseModel, Field, field_validator
from typing import Optional, Any, Dict, List, Union
from datetime import datetime


class OptionOut(BaseModel):
    id: int
    option_text: str
    score_value: float
    sort_order: int

    model_config = {"from_attributes": True}


class QuestionOut(BaseModel):
    id: int
    question_text: str
    question_type: str
    dimension_key: str
    is_required: bool
    weight: float
    sort_order: int
    options: List[OptionOut] = []

    model_config = {"from_attributes": True}


class QuestionnaireOut(BaseModel):
    id: int
    title: str
    description: Optional[str] = None
    version: int
    status: str
    created_at: datetime

    model_config = {"from_attributes": True}


class QuestionnaireDetailOut(QuestionnaireOut):
    questions: List[QuestionOut] = []


class AnswerItem(BaseModel):
    question_id: int
    answer_value: Any

    @field_validator("answer_value")
    @classmethod
    def validate_answer_value(cls, v: Any) -> Any:
        """Validate answer value is one of the expected types."""
        if isinstance(v, dict):
            # single_choice: {"selected_option_id": int}
            # multi_choice: {"selected_option_ids": [int, ...]}
            # sort: {"ordered_option_ids": [int, ...]}
            allowed_keys = {"selected_option_id", "selected_option_ids", "ordered_option_ids"}
            if not any(k in v for k in allowed_keys):
                raise ValueError(f"无效的答案格式，允许的键: {allowed_keys}")
            return v
        if isinstance(v, (int, float)):
            # Likert raw value
            if v < 0 or v > 100:
                raise ValueError("数值答案必须在 0-100 之间")
            return v
        if isinstance(v, str):
            # Open text
            if len(v) > 5000:
                raise ValueError("文本答案不能超过 5000 字")
            return v
        if isinstance(v, list):
            # Legacy list format
            if len(v) > 50:
                raise ValueError("列表答案不能超过 50 项")
            return v
        raise ValueError(f"不支持的答案类型: {type(v).__name__}")


class SaveAnswersRequest(BaseModel):
    answers: List[AnswerItem]

    @field_validator("answers")
    @classmethod
    def validate_answers_count(cls, v: List) -> List:
        if len(v) > 100:
            raise ValueError("单次保存不能超过 100 条答案")
        return v


class AnswerResponse(BaseModel):
    id: int
    question_id: int
    answer_value: Any


class ProgressOut(BaseModel):
    questionnaire_id: int
    total_questions: int
    answered_count: int
    progress_percent: float
    current_question_index: int = 0
    is_submitted: bool
    answers: List[AnswerResponse] = []


class SaveProgressIndexRequest(BaseModel):
    current_index: int = Field(ge=0, description="当前题目索引，不能为负数")


class AnalysisResponse(BaseModel):
    analysis: str
    profile_type: str
    profile_label: str
    confidence: float
    dimension_scores: Dict[str, float]


class SubmissionOut(BaseModel):
    id: int
    questionnaire_id: int
    questionnaire_title: str
    total_questions: int
    answered_count: int
    profile_type: Optional[str] = None
    profile_summary: Optional[str] = None
    dimension_scores: Optional[Dict[str, float]] = None
    analysis_text: Optional[str] = None
    couple_profile_ready: bool = False
    created_at: datetime

    model_config = {"from_attributes": True}


class SubmitResponse(BaseModel):
    questionnaire_id: int
    profile_generated: bool = True
    couple_profile_ready: bool = False
