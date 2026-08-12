package com.mindsafe.ai.memory;

import org.springframework.ai.chat.messages.Message;

/**
 * 会话记忆原子追加（doing/92 R-015）
 * <p>
 * SAFE-202 召回语义：追加更正消息而非整表替换——find+saveAll 为 read-modify-write，
 * 并发召回会互相覆盖；本接口要求实现为单条原子写（RedisChatMemoryRepository 以
 * rightPush 追加，序列化契约与既有消息一致）。
 */
public interface ChatMemoryAppender {

    /** 追加一条消息到会话记忆末尾（原子，不做 find+saveAll） */
    void append(String conversationId, Message message);

    /** 会话记忆是否已存在（原子判空，供召回守卫：记忆为空时跳过追加避免悬空更正） */
    boolean hasMessages(String conversationId);
}
