@echo off
REM ==========================================
REM 启动 OpenTHU Agent Core 服务端
REM ==========================================

echo [1/3] 设置环境 API KEY...
set OPENAI_API_KEY=sk-SKyAMSwrA7wm9weB2xSQJw31eCl05nR1OsHzP4dwiwvVprKc

echo [3/3] 启动 Agent 本地 Server (将在端口 28789 运行)...
python -m agent.langgraph.agent_core_server --host 0.0.0.0 --port 28789 --llm-base-url "https://api.moonshot.cn/v1" --llm-model "moonshot-v1-8k" --store-file openthu_agent_core_store.json --memory-file openthu_agent_memory.json
