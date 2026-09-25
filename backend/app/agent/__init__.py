"""Agent 模块

对外暴露：
    ALL_TOOLS         — 只读工具名白名单（封闭集合，不含用户数据）
    build_agent_tools — 按请求身份构建工具集合（v3.2 §7.1 服务端注入）
    run_agent         — 执行一次带工具调用的对话
"""

from app.agent.executor import build_model, run_agent
from app.agent.tools import ALL_TOOLS, build_agent_tools

__all__ = ["ALL_TOOLS", "build_agent_tools", "run_agent", "build_model"]
