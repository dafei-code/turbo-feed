package com.turbofeed.gateway.repository;

import com.turbofeed.gateway.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户持久层（JDBC，经 ShardingSphere DataSource 路由到 user 分片）。
 *
 * <p><b>分片路由约束</b>：user 表分片键为 {@code id}。INSERT 显式带 id（应用层雪花生成），
 * ShardingSphere 按 id 精准命中单分片。而注册去重 / 登录定位的查询条件是 {@code phone}，
 * 天然不带分片键——改造前直接 {@code WHERE phone = ?}，ShardingSphere 会<b>广播到全部 4 张
 * 物理表再合并</b>：4 分片尚可，分片数扩容（规划 128）后一次登录就是 128 次跨库查询。</p>
 *
 * <p><b>本次改造（changelog 0036）</b>：引入 {@link UserPhoneRouterRepository} 路由表
 * （phone → uid，单表），把按手机号的两条查询都改写成「先查路由拿 uid → 再按 id 精准命中单分片」，
 * 彻底消除广播。迁移期的安全设计见 {@link #resolveUserIdByPhone}。</p>
 *
 * <p><b>无 Lombok</b>：显式构造器（本机构建环境对修改过的文件 Lombok 注解处理不生效，见项目记忆）。</p>
 */
@Repository
public class UserJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(UserJdbcRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final UserPhoneRouterRepository phoneRouter;

    public UserJdbcRepository(JdbcTemplate jdbcTemplate, UserPhoneRouterRepository phoneRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.phoneRouter = phoneRouter;
    }

    /**
     * 注册去重：手机号是否已被占用。
     *
     * <p>语义等价改造前的 {@code SELECT COUNT(*) FROM user WHERE phone = ?}（0 / 1 之外无中间态，
     * 手机号全局唯一），但查询路径从「广播 4 片合并 COUNT」变成「单表主键点查」。</p>
     */
    public long countByPhone(String phone) {
        return resolveUserIdByPhone(phone).isPresent() ? 1L : 0L;
    }

    /**
     * <b>按手机号解析 uid（路由优先 + 广播兜底 + 命中回填）</b>——本次改造的核心。
     *
     * <p>三级策略是为了让「加索引」这件事可以<b>零停机、不依赖数据迁移完成</b>：</p>
     * <ol>
     *   <li><b>路由命中</b>（常态）：单表 PK 点查，O(1)，与分片数无关；</li>
     *   <li><b>路由未命中</b>（存量数据未回填 / 绑定补偿未完成）：<b>正确性优先</b>，回退广播查询；</li>
     *   <li><b>广播命中</b>：说明这是历史用户，<b>顺手回填</b>路由表——回填是惰性、幂等的，
     *       下一次同一手机号的查询就走路由了（双读 + 写回，经典的在线索引迁移手法）。</li>
     * </ol>
     *
     * <p>因此「路由表没有全量数据」<b>不会</b>导致误判为未注册——最坏只是退回旧的广播成本，
     * 这正是选择「路由表」而不是「直接改分片键」的原因（后者必须一次性完成全量数据重分布）。</p>
     */
    public Optional<Long> resolveUserIdByPhone(String phone) {
        Optional<Long> hit = phoneRouter.findUserId(phone);
        if (hit.isPresent()) {
            return hit;
        }
        Optional<Long> legacy = findByPhoneBroadcast(phone).map(UserIdHash::id);
        // 惰性回填：幂等（INSERT IGNORE），回填失败不影响本次结果，下次再试。
        legacy.ifPresent(uid -> phoneRouter.bindIfAbsent(phone, uid));
        return legacy;
    }

    /** 落库一条用户记录。id 为分片键，ShardingSphere 按 id 精准路由。 */
    public void insert(User user) {
        // role 为 null（如注册路径未显式指定）时按最小权限 USER 落库，避免空角色。
        String role = (user.getRole() == null || user.getRole().isBlank()) ? "USER" : user.getRole();
        jdbcTemplate.update(
                "INSERT INTO user (id, phone, password_hash, nickname, status, role) VALUES (?, ?, ?, ?, ?, ?)",
                user.getId(), user.getPhone(), user.getPasswordHash(), user.getNickname(), user.getStatus(), role);
    }

    /**
     * 登录定位：按手机号取 id + 密码哈希 + 状态 + 角色。
     *
     * <p>路径：路由查 uid → {@link #findById}（带 id 分片键，单分片命中）。
     * 路由未命中走广播兜底并回填（见 {@link #resolveUserIdByPhone}）。</p>
     */
    public Optional<UserIdHash> findByPhone(String phone) {
        Optional<Long> uid = resolveUserIdByPhone(phone);
        if (uid.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserIdHash> account = findById(uid.get());
        if (account.isPresent()) {
            return account;
        }
        // 路由命中、但按 uid 定位不到 user 行——两种可能，处置方式完全不同：
        //   ① 数据错位：user 行被直接写进了「非路由目标」的物理表（手工 INSERT / 种子脚本算错分片）。
        //      改造前这类数据是<b>能登录的</b>，因为按 phone 广播会把所有物理表扫一遍。
        //   ② 孤儿映射：注册时「绑定成功、落库失败」且补偿没跑到（进程崩溃）。
        // 因此先用广播再确认一次（只在异常路径发生），绝不能一上来就删映射——
        // 那会把「数据错位」直接升级成「账号永久不可用」（删了下次还会重建再删）。
        Optional<UserIdHash> actual = findByPhoneBroadcast(phone);
        if (actual.isPresent()) {
            log.warn("手机号路由命中但按 id 未定位到 user 行，已按广播兜底命中"
                            + "（user 行可能落在非路由分片，请核对分片规则）: phone={}, routedUid={}",
                    phone, uid.get());
            return actual;
        }
        // 广播也没有 → 确属孤儿映射：不清理该手机号将永远显示「已注册」却又登不进去，且无法重新注册。
        phoneRouter.unbind(phone, uid.get());
        return Optional.empty();
    }

    /** 按 id 查询（带分片键，单分片命中）。 */
    public Optional<UserIdHash> findById(long id) {
        List<UserIdHash> list = jdbcTemplate.query(
                "SELECT id, password_hash, status, role FROM user WHERE id = ?",
                (rs, rn) -> new UserIdHash(rs.getLong("id"), rs.getString("password_hash"), rs.getInt("status"), rs.getString("role")),
                id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 仅在路由表未命中时使用的兜底：<b>无分片键，会广播到全部分片</b>。
     *
     * <p>⚠️ 新代码不要直接调它——请走 {@link #resolveUserIdByPhone} /
     * {@link #findByPhone}，否则扩容后广播成本会被悄悄带回热路径。</p>
     */
    private Optional<UserIdHash> findByPhoneBroadcast(String phone) {
        List<UserIdHash> list = jdbcTemplate.query(
                "SELECT id, password_hash, status, role FROM user WHERE phone = ?",
                (rs, rn) -> new UserIdHash(rs.getLong("id"), rs.getString("password_hash"), rs.getInt("status"), rs.getString("role")),
                phone);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 登录用轻量投影：仅取登录必需的字段，避免把整行用户对象暴露给 Service。 */
    public record UserIdHash(Long id, String passwordHash, int status, String role) {
    }
}
