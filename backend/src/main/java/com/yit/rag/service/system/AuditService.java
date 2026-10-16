package com.yit.rag.service.system;

import com.yit.rag.common.tenant.TenantContext;
import com.yit.rag.domain.system.SysAuditLog;
import com.yit.rag.repository.system.SysAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计日志服务。
 *
 * <p>这个类解决什么问题：企业级系统必须能回答「谁在什么时候改了什么」。
 * 这里提供统一的写入入口，让各个业务点只写一行 {@code auditService.log(...)}，
 * 不用关心租户从哪来、异常怎么处理。
 *
 * <p>两个刻意的设计：
 * <ol>
 *   <li><b>独立事务 + 吞异常</b>：审计写入失败绝不能让业务回滚。
 *       想象一下「删文档成功但审计表写不进去 → 整个事务回滚」，
 *       用户会觉得系统莫名其妙不好用。审计是旁路，不是主链路。</li>
 *   <li><b>从 TenantContext 自动取租户</b>：调用方不用传 tenantId，
 *       避免漏传导致审计记录挂错租户（那样审计就失去意义了）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final SysAuditLogRepository auditLogRepository;

    /** 常用动作码，避免各处硬编码字符串拼错 */
    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_LOGOUT = "LOGOUT";
    public static final String ACTION_KB_CREATE = "KB_CREATE";
    public static final String ACTION_KB_DELETE = "KB_DELETE";
    public static final String ACTION_DOC_UPLOAD = "DOC_UPLOAD";
    public static final String ACTION_DOC_DELETE = "DOC_DELETE";
    public static final String ACTION_DOC_REINDEX = "DOC_REINDEX";
    public static final String ACTION_CHAT_STREAM = "CHAT_STREAM";
    public static final String ACTION_CS_HANDOFF = "CS_HANDOFF";
    public static final String ACTION_MODEL_EDIT = "MODEL_EDIT";
    public static final String ACTION_PROMPT_EDIT = "PROMPT_EDIT";

    /**
     * 记录一条审计日志（成功）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String resource, String detail) {
        log(action, resource, detail, true);
    }

    /**
     * 记录一条审计日志。
     *
     * @param action   动作码，如 DOC_UPLOAD
     * @param resource 资源标识，如 doc:12
     * @param detail   详细说明
     * @param success  是否成功
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String resource, String detail, boolean success) {
        try {
            SysAuditLog entity = new SysAuditLog();
            Long tenantId = TenantContext.getTenantIdOrNull();
            entity.setTenantId(tenantId == null ? 0L : tenantId);
            entity.setUserId(TenantContext.getUserIdOrNull());
            entity.setUsername(TenantContext.getUsernameOrNull());
            entity.setAction(action);
            entity.setResource(resource);
            entity.setDetail(detail == null ? null
                    : (detail.length() > 1000 ? detail.substring(0, 1000) : detail));
            entity.setSuccess(success ? 1 : 0);
            auditLogRepository.save(entity);
            log.debug("审计日志已记录: action={} resource={} success={}", action, resource, success);
        } catch (Exception e) {
            // 审计失败不影响业务，仅告警
            log.warn("写入审计日志失败（不影响业务）action={} resource={}: {}", action, resource, e.getMessage());
        }
    }

    /**
     * 带 HTTP 请求信息的审计（记录来源 IP）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String resource, String detail, boolean success, HttpServletRequest request) {
        try {
            SysAuditLog entity = new SysAuditLog();
            Long tenantId = TenantContext.getTenantIdOrNull();
            entity.setTenantId(tenantId == null ? 0L : tenantId);
            entity.setUserId(TenantContext.getUserIdOrNull());
            entity.setUsername(TenantContext.getUsernameOrNull());
            entity.setAction(action);
            entity.setResource(resource);
            entity.setDetail(detail == null ? null
                    : (detail.length() > 1000 ? detail.substring(0, 1000) : detail));
            entity.setIp(resolveIp(request));
            entity.setSuccess(success ? 1 : 0);
            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("写入审计日志失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 登录场景专用：此时 TenantContext 还没建立（JWT 还没签发），
     * 所以租户与用户名必须显式传进来。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logLogin(Long tenantId, Long userId, String username, String detail,
                         boolean success, HttpServletRequest request) {
        try {
            SysAuditLog entity = new SysAuditLog();
            entity.setTenantId(tenantId == null ? 0L : tenantId);
            entity.setUserId(userId);
            entity.setUsername(username);
            entity.setAction(ACTION_LOGIN);
            entity.setResource("auth");
            entity.setDetail(detail);
            entity.setIp(request == null ? null : resolveIp(request));
            entity.setSuccess(success ? 1 : 0);
            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("写入登录审计失败: {}", e.getMessage());
        }
    }

    /**
     * 管理端分页查询（强制按当前租户过滤）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> page(int page, int size) {
        Long tenantId = TenantContext.requireTenantId();
        int safeSize = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Page<SysAuditLog> result = auditLogRepository.findByTenantIdOrderByIdDesc(tenantId, pageable);

        Map<String, Object> data = new LinkedHashMap<>();
        // 契约：{total, records:[...]}
        data.put("total", result.getTotalElements());
        data.put("page", page);
        data.put("size", safeSize);
        data.put("records", result.getContent());
        return data;
    }

    /** 取真实客户端 IP：经过 Nginx 时要看 X-Forwarded-For */
    private static String resolveIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                // X-Forwarded-For 可能是 "client, proxy1, proxy2"，取第一个
                int comma = v.indexOf(',');
                return (comma > 0 ? v.substring(0, comma) : v).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
