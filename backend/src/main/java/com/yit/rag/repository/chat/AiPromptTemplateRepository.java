package com.yit.rag.repository.chat;

import com.yit.rag.domain.chat.AiPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Prompt 模板仓储。
 *
 * <p>模板有「平台级（tenant_id=0）+ 租户级」两层，取用时租户级优先覆盖平台级，
 * 这样租户可以自定义提示词，同时保留平台默认值。
 */
public interface AiPromptTemplateRepository extends JpaRepository<AiPromptTemplate, Long> {

    List<AiPromptTemplate> findByTenantIdInOrderByIdAsc(List<Long> tenantIds);

    Optional<AiPromptTemplate> findByTenantIdAndTemplateCode(Long tenantId, String templateCode);

    /** 管理端列表：平台级 + 当前租户 */
    @Query("select t from AiPromptTemplate t where t.tenantId = 0 or t.tenantId = :tenantId order by t.id")
    List<AiPromptTemplate> findVisible(@Param("tenantId") Long tenantId);
}
