"""Agent 侧数据结构:模式/计划/产物/Critic/状态(移植自 dto/AgentState.java 等)。"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_PLAN_TASKS = 5
MAX_TASK_CHARS = 500


class TaskStage(str, Enum):
    """28 个阶段(移植自 dto/TaskStage.java,顺序即状态机)。"""

    QUEUED = "QUEUED"
    CONSUMING = "CONSUMING"
    VIDEO_CONTEXT = "VIDEO_CONTEXT"
    CONTEXT_COMPLETED = "CONTEXT_COMPLETED"
    CHUNKS_COMPLETED = "CHUNKS_COMPLETED"
    RETRIEVAL = "RETRIEVAL"
    AGENT_LOOP = "AGENT_LOOP"
    PLAN_COMPLETED = "PLAN_COMPLETED"
    EXECUTOR_STARTED = "EXECUTOR_STARTED"
    EXECUTOR_COMPLETED = "EXECUTOR_COMPLETED"
    CRITIC_STARTED = "CRITIC_STARTED"
    CRITIC_PASSED = "CRITIC_PASSED"
    CRITIC_RETRY_REQUIRED = "CRITIC_RETRY_REQUIRED"
    EVIDENCE_REFRESHED = "EVIDENCE_REFRESHED"
    ANALYSIS_COMPLETED = "ANALYSIS_COMPLETED"
    ANALYSIS_COMPLETED_WITH_WARNINGS = "ANALYSIS_COMPLETED_WITH_WARNINGS"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    RETRYING = "RETRYING"
    COMPLETED = "COMPLETED"
    COMPLETED_REUSED = "COMPLETED_REUSED"
    FAILED = "FAILED"
    DEAD_LETTERED = "DEAD_LETTERED"
    MANUAL_REPLAY = "MANUAL_REPLAY"
    REVISION_PENDING = "REVISION_PENDING"
    REVISION_APPLIED = "REVISION_APPLIED"
    TRANSCRIPTION = "TRANSCRIPTION"
    ASR = "ASR"
    DISPATCH_FAILED = "DISPATCH_FAILED"


class AnalysisMode(str, Enum):
    GENERAL = "GENERAL"
    LEARNING = "LEARNING"
    REVIEW = "REVIEW"
    CREATION = "CREATION"

    @classmethod
    def from_nullable(cls, value: str | None) -> "AnalysisMode":
        """消息/内部使用:非法值一律回退 GENERAL(永不抛错)。"""
        if value is None:
            return cls.GENERAL
        try:
            return cls(str(value).upper())
        except ValueError:
            return cls.GENERAL

    @classmethod
    def from_request(cls, value: str | None) -> "AnalysisMode":
        """HTTP 入口:显式非法值直接抛错,防拼错静默降级。"""
        if value is None or value == "":
            return cls.GENERAL
        try:
            return cls(str(value).upper())
        except ValueError as exc:
            raise ValueError(f"未知分析模式: {value}") from exc


class Evidence(BaseModel):
    """一条时间戳证据;claim 必须与其支撑的结论逐字一致(原版 prompt 硬性要求)。"""

    model_config = ConfigDict(populate_by_name=True)

    timestamp_ms: int = Field(alias="timestampMs")
    source: str = "ASR"  # ASR | OCR | ASR+OCR
    content: str = ""
    claim: str = ""


class ResultSection(BaseModel):
    """模式专属结构化产物(如 LEARNING 的 outline/quiz)。key 参与程序校验。"""

    key: str
    title: str = ""
    items: list[str] = Field(default_factory=list)


class AnalysisResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    title: str = ""
    conclusions: list[str] = Field(default_factory=list)
    evidence: list[Evidence] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    sections: list[ResultSection] = Field(default_factory=list)

    def section_keys(self) -> set[str]:
        return {s.key for s in self.sections}


class AgentPlan(BaseModel):
    understood_goal: str = Field(default="", alias="understoodGoal")
    tasks: list[str] = Field(default_factory=list)

    model_config = ConfigDict(populate_by_name=True)


class CriticResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    passed: bool = False
    feedback: list[str] = Field(default_factory=list)
    missing_requirements: list[str] = Field(default_factory=list, alias="missingRequirements")
    unsupported_claims: list[str] = Field(default_factory=list, alias="unsupportedClaims")
    required_timestamps: list[int] = Field(default_factory=list, alias="requiredTimestamps")


class AgentState(BaseModel):
    """单轮 Agent 状态(checkpoint 的 criticState 载荷)。critique=None 表示草稿待校。"""

    round: int = 0
    plan: AgentPlan | None = None
    result: AnalysisResult | None = None
    critique: CriticResult | None = None


class ModeProfile(BaseModel):
    """模式档案:三段指令分别追加到 Planner/Executor/Critic prompt 末尾。"""

    mode: AnalysisMode
    display_name: str
    plan_instruction: str = ""
    execute_instruction: str = ""
    critic_instruction: str = ""
    required_section_keys: list[str] = Field(default_factory=list)


GENERAL = ModeProfile(mode=AnalysisMode.GENERAL, display_name="通用")
LEARNING = ModeProfile(
    mode=AnalysisMode.LEARNING,
    display_name="学习",
    plan_instruction="按知识主题而非时间顺序拆解任务,确保知识点成体系、无跳步。",
    execute_instruction=(
        "除基础结构外,必须输出 sections 数组,包含以下 key: "
        "outline(知识大纲)、keypoints(重点难点)、quiz(自测题,每题附答案)、pitfalls(易错点)。"
    ),
    critic_instruction="检查知识点是否成体系、有无跳步、自测题是否覆盖核心概念。",
    required_section_keys=["outline", "keypoints", "quiz", "pitfalls"],
)
REVIEW = ModeProfile(
    mode=AnalysisMode.REVIEW,
    display_name="审查",
    plan_instruction="把目标拆成对每个主要论点的可验证审查项。",
    execute_instruction=(
        "除基础结构外,必须输出 sections 数组,包含以下 key: "
        "fallacies(逻辑漏洞)、exaggerations(夸大表述)、omissions(遗漏点)、doubtful(存疑结论,附理由)。"
    ),
    critic_instruction="采用更严格门槛:论据是否充分、有无偷换概念,证据不足必须判不通过。",
    required_section_keys=["fallacies", "exaggerations", "omissions", "doubtful"],
)
CREATION = ModeProfile(
    mode=AnalysisMode.CREATION,
    display_name="创作",
    plan_instruction="围绕可发布资产拆解任务:定位爆点、可切片段落与传播钩子。",
    execute_instruction=(
        "除基础结构外,必须输出 sections 数组,包含以下 key: "
        "highlights(爆点片段,每条含起止时间戳)、titles(备选标题)、intro(简介文案)、script(口播脚本要点)。"
    ),
    critic_instruction="每个爆点必须有真实时间戳支撑,不得虚构。",
    required_section_keys=["highlights", "titles", "intro", "script"],
)

MODE_PROFILES: dict[AnalysisMode, ModeProfile] = {
    p.mode: p for p in (GENERAL, LEARNING, REVIEW, CREATION)
}


def get_profile(mode: AnalysisMode) -> ModeProfile:
    """启动自检等价:未注册的模式直接 KeyError 拒绝(原版启动失败防读写端不对称)。"""
    return MODE_PROFILES[mode]


def ensure_registry_complete() -> None:
    missing = [m for m in AnalysisMode if m not in MODE_PROFILES]
    if missing:
        raise RuntimeError(f"ModeProfile 未注册: {missing}")


ensure_registry_complete()


def validate_plan(plan: AgentPlan) -> list[str]:
    """返回违规列表;空列表 = 合法(1–5 个任务、每条非空且 ≤500 字)。"""
    issues: list[str] = []
    tasks = [t.strip() for t in plan.tasks if t and t.strip()]
    if not (1 <= len(tasks) <= MAX_PLAN_TASKS):
        issues.append(f"任务数量必须在 1–{MAX_PLAN_TASKS} 之间,当前 {len(tasks)}")
    for t in tasks:
        if len(t) > MAX_TASK_CHARS:
            issues.append(f"任务超长(>{MAX_TASK_CHARS} 字): {t[:40]}…")
    if not plan.understood_goal.strip():
        issues.append("缺少目标理解(understoodGoal)")
    return issues
