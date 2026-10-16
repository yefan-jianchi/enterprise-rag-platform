package com.yit.rag.repository.cs;

import com.yit.rag.domain.cs.CsOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 订单仓储 —— 智能客服工具 {@code queryOrder} 的底层查询。
 *
 * <p>这个类解决什么问题：工具调用的第一要务是「查得准、查不串租户」。
 * queryOrder 支持两种入参（订单号 / 手机号），所以给两条精确查询，
 * 再加一条模糊查询给管理端列表用。
 */
public interface CsOrderRepository extends JpaRepository<CsOrder, Long> {

    Optional<CsOrder> findByTenantIdAndOrderNo(Long tenantId, String orderNo);

    List<CsOrder> findByTenantIdAndPhoneOrderByIdDesc(Long tenantId, String phone);

    /** 管理端列表：关键词匹配订单号 / 客户名 / 手机号 / 商品名 */
    @Query("""
            select o from CsOrder o
            where o.tenantId = :tenantId
              and (:keyword is null or :keyword = ''
                   or o.orderNo like concat('%', :keyword, '%')
                   or o.customerName like concat('%', :keyword, '%')
                   or o.phone like concat('%', :keyword, '%')
                   or o.productName like concat('%', :keyword, '%'))
            order by o.id desc
            """)
    List<CsOrder> search(@Param("tenantId") Long tenantId, @Param("keyword") String keyword);

    long countByTenantId(Long tenantId);
}
