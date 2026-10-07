"""
短期记忆模块（新增，独立于 server.py）

功能：
- 基于 LangChain 的 ConversationSummaryMemory 做「对话摘要式」短期记忆
- 用 Redis 持久化摘要，跨请求/重启保留

说明：
- langchain 1.x 已把 ConversationSummaryMemory 移到 langchain_classic 包。
- 摘要式记忆：每轮对话后用 LLM 把历史压缩成一段摘要，避免上下文无限膨胀。
"""
from langchain_classic.memory import ConversationSummaryMemory

SUMMARY_TTL_SECONDS = 2 * 60 * 60  # 会话摘要保留 2 小时


class ChatMemoryStore:
    def __init__(self, redis_client, llm):
        self.redis = redis_client
        self.llm = llm

    @staticmethod
    def _summary_key(session_id: str) -> str:
        return f"chat:session:{session_id}:summary"

    def load_memory(self, session_id: str) -> ConversationSummaryMemory:
        """加载（或新建）某个会话的摘要记忆。"""
        memory = ConversationSummaryMemory(llm=self.llm, memory_key="chat_history")
        saved = self.redis.get(self._summary_key(session_id))
        if saved:
            try:
                memory.buffer = saved
            except Exception:
                pass
        return memory

    def get_history_text(self, memory) -> str:
        """取出当前摘要，作为 prompt 里的历史上下文。"""
        variables = memory.load_memory_variables({})
        return variables.get("chat_history", "")

    def save_turn(self, session_id: str, memory, user_msg: str, ai_msg: str):
        """保存本轮问答，更新摘要并落 Redis。"""
        memory.save_context({"input": user_msg}, {"output": ai_msg})
        summary = getattr(memory, "buffer", "")
        if summary:
            self.redis.set(self._summary_key(session_id), summary, ex=SUMMARY_TTL_SECONDS)
