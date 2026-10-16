package com.yit.rag.repository.chat;

import com.yit.rag.domain.chat.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 消息仓储。
 *
 * <p>按会话查历史消息时用 {@code order by id asc}：id 自增等价于时间序，
 * 而且比 created_at 排序更稳定（同一秒内的多条消息不会乱序）。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByTenantIdAndSessionIdOrderByIdAsc(Long tenantId, Long sessionId);

    long countByTenantIdAndSessionId(Long tenantId, Long sessionId);
}
