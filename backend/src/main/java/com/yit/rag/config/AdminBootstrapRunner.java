package com.yit.rag.config;

import com.yit.rag.domain.system.SysRole;
import com.yit.rag.domain.system.SysUser;
import com.yit.rag.domain.system.SysUserRole;
import com.yit.rag.domain.system.Tenant;
import com.yit.rag.repository.system.SysRoleRepository;
import com.yit.rag.repository.system.SysUserRepository;
import com.yit.rag.repository.system.SysUserRoleRepository;
import com.yit.rag.repository.system.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员引导 —— 应用启动时确保有一个能登录的账号。
 *
 * <p>这个类解决什么问题：{@code db/seed.sql} 里<b>故意没有</b>插入用户。
 * 原因是「把 BCrypt 哈希写死在 SQL 里」有两个问题：
 * <ol>
 *   <li>哈希是一串不可读的字符串，评审的人无法确认它对应的密码是什么</li>
 *   <li>不同环境想用不同初始密码时，得手工改 SQL 或重新生成哈希</li>
 * </ol>
 * 所以改成启动时用 {@link PasswordEncoder} 现场加密创建，密码来自配置
 * （{@code app.bootstrap.password}，生产用环境变量覆盖）。
 *
 * <p>幂等性保证：只有「租户 demo 下不存在 admin」时才创建。
 * 因此重启多少次都不会重复插入，也不会覆盖用户后来改过的密码 ——
 * 这一点很重要，否则重启一次密码就被重置回初始值，是个安全隐患。
 *
 * <p>顺带做的容错：库里 19 张表是外部准备好的，如果 MySQL 连不上或表不存在，
 * 这里会抛异常并给出明确提示，而不是让人对着一堆 Hibernate 报错猜。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AdminBootstrapRunner implements CommandLineRunner {

    private final AppProperties props;
    private final TenantRepository tenantRepository;
    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        AppProperties.Bootstrap cfg = props.getBootstrap();
        if (!cfg.isEnabled()) {
            log.info("管理员引导已关闭（app.bootstrap.enabled=false），跳过");
            return;
        }
        try {
            doBootstrap(cfg);
        } catch (Exception e) {
            // 引导失败不阻断启动：可能是首次部署时表还没建、或者连接了只读从库。
            // 但必须把原因打清楚，让人知道现在登不上去。
            log.error("管理员引导失败（应用继续启动，但可能无法登录）：{}", e.getMessage(), e);
        }
    }

    private void doBootstrap(AppProperties.Bootstrap cfg) {
        Tenant tenant = tenantRepository.findByTenantCode(cfg.getTenantCode()).orElse(null);
        if (tenant == null) {
            log.warn("租户 [{}] 不存在，跳过管理员引导。" +
                    "请先执行 db/seed.sql 初始化种子数据（sys_tenant / sys_role / sys_permission）",
                    cfg.getTenantCode());
            return;
        }

        // 幂等：已存在就什么都不做，绝不覆盖已有密码
        if (userRepository.existsByTenantIdAndUsername(tenant.getId(), cfg.getUsername())) {
            log.info("管理员账号已存在，跳过创建：tenant={} username={}",
                    cfg.getTenantCode(), cfg.getUsername());
            return;
        }

        SysUser admin = new SysUser();
        admin.setTenantId(tenant.getId());
        admin.setUsername(cfg.getUsername());
        // 现场 BCrypt 加密：库里只存哈希，绝不存明文
        admin.setPasswordHash(passwordEncoder.encode(cfg.getPassword()));
        admin.setNickname(cfg.getNickname());
        admin.setEmail("admin@" + cfg.getTenantCode() + ".local");
        admin.setStatus(1);
        admin = userRepository.save(admin);

        // 绑定系统内置 ADMIN 角色（sys_role 里 tenant_id=0、role_code='ADMIN' 的那条）
        SysRole adminRole = roleRepository.findByTenantIdAndRoleCode(0L, "ADMIN").orElse(null);
        if (adminRole == null) {
            log.warn("系统内置角色 ADMIN（tenant_id=0）不存在，管理员已创建但未绑定角色。" +
                    "请检查 db/seed.sql 是否执行完整");
            return;
        }
        if (!userRoleRepository.existsByLeftIdAndRightId(admin.getId(), adminRole.getId())) {
            userRoleRepository.save(new SysUserRole(admin.getId(), adminRole.getId()));
        }

        log.info("========================================================");
        log.info("  管理员账号引导完成");
        log.info("  租户编码 : {}", tenant.getTenantCode());
        log.info("  用户名   : {}", admin.getUsername());
        log.info("  密码     : {}   （仅本次打印，请登录后及时修改）", cfg.getPassword());
        log.info("  绑定角色 : {} (id={})", adminRole.getRoleCode(), adminRole.getId());
        log.info("========================================================");
    }
}
