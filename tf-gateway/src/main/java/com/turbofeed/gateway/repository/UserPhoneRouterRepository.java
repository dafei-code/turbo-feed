package com.turbofeed.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 手机号路由表仓储（{@code user_phone_router}，<b>单表</b>，落 ds_0）。
 *
 * <p><b>它解决什么问题</b>：{@code user} 表分片键是 {@code id}，而注册去重 / 登录定位的查询条件是
 * {@code phone}——不带分片键，ShardingSphere 只能<b>广播到全部 4 张物理表再合并结果</b>。
 * 4 分片时尚可接受，但分片数一扩（规划中的 128），一次登录就变成 128 次跨库查询，
 * 「注册去重」也会退化成 128 次 COUNT 合并。本表把「phone → uid」的映射独立成一张
 * <b>按 phone 直接定位</b>的表，先查它拿到 uid，再按 {@code id} 精准命中 user 的单个分片——
 * 两次查询都从「广播 N 片」降为「命中 1 片」，且分片数扩容不再放大查询量。</p>
 *
 * <p><b>为什么是单表而不是按 phone 分片</b>（权衡已记录，便于日后反悔）：
 * <ul>
 *   <li>注册 / 登录的 QPS 比上传、Feed 读取低几个数量级，单表 PK 点查远未到瓶颈；</li>
 *   <li>唯一性由<b>一处</b> {@code PRIMARY KEY(phone)} 保证，而不是「每片各自唯一」——
 *       后者依赖「同一 phone 必落同一片」这一前提，一旦分片算法或 sharding-count 变更就可能失效；</li>
 *   <li>存量回填 / 人工订正只需一条同实例跨库 {@code INSERT ... SELECT}，运维成本最低。</li>
 * </ul>
 * 真到瓶颈时的演进路径（均已实证可行）：把本表按 {@code phone} 挂 autoTables + HASH_MOD
 * ——{@code HashModShardingAlgorithm} 的字节码为 {@code Math.abs(value.hashCode()) % shardingCount}，
 * 走 {@code Object.hashCode()}，<b>字符串分片键可用</b>；或直接迁到 Redis / 分布式 KV。</p>
 *
 * <p><b>并发闸门语义</b>：{@link #bindIfAbsent} 用 {@code INSERT IGNORE}，靠主键冲突把
 * 「两个请求同时注册同一手机号」收敛为一个成功、一个失败（返回 false）。这是本表除了加速之外的
 * 第二个价值：它是<b>唯一一处真正 enforce 手机号全局唯一</b>的约束（user 表的 {@code uk_phone}
 * 只在本片内唯一，跨片重复它拦不住）。</p>
 *
 * <p><b>无 Lombok</b>：显式构造器（本机构建环境对新建文件的 Lombok 注解处理不生效，见项目记忆）。</p>
 */
@Repository
public class UserPhoneRouterRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserPhoneRouterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按手机号查 UID（单表主键点查，单分片命中）。
     *
     * @return 命中返回 uid；未命中返回 {@code Optional.empty()}
     *         （可能是真未注册，也可能是存量数据尚未回填——调用方须自行兜底，见
     *         {@code UserJdbcRepository#resolveUserIdByPhone}）
     */
    public Optional<Long> findUserId(String phone) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT user_id FROM user_phone_router WHERE phone = ?",
                (rs, rn) -> rs.getLong("user_id"), phone);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 绑定 phone → uid（幂等，靠主键冲突判定「已被占用」）。
     *
     * @return true = 本次绑定成功（此前无人占用）；false = 该手机号已被占用（并发注册 / 已注册）
     */
    public boolean bindIfAbsent(String phone, long userId) {
        int rows = jdbcTemplate.update(
                "INSERT IGNORE INTO user_phone_router (phone, user_id) VALUES (?, ?)", phone, userId);
        return rows == 1;
    }

    /**
     * 解绑（仅用于「绑定成功但后续落库失败」的补偿，以及登录时发现路由指向空行的自愈）。
     *
     * <p><b>必须带 user_id 条件</b>：只按 phone 删会把「该手机号已被另一个 uid 合法占用」的行误删，
     * 把别人的账号凭空注销掉。</p>
     *
     * @return 是否真的删掉了一行
     */
    public boolean unbind(String phone, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM user_phone_router WHERE phone = ? AND user_id = ?", phone, userId) == 1;
    }
}
