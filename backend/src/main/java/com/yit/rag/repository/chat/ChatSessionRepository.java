package com.yit.rag.repository.chat;

import com.yit.rag.domain.chat.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。
 *
 * <p>对外统一用 {@code sessionUuid} 定位会话，所以查单个会话的入口都是
 * {@code findBySessionUuidAndTenantId}；同时校验租户，防止有人猜 uuid 越权。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId);

    List<ChatSession> findByTenantIdAndUserIdAndSceneOrderByIdDesc(Long tenantId, Long userId, String scene);

    Optional<ChatSession> findBySessionUuidAndTenantId(String sessionUuid, Long tenantId);

    long countByTenantId(Long tenantId);
}
