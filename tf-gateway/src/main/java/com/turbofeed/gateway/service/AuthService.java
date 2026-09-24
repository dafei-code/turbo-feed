package com.turbofeed.gateway.service;

import com.turbofeed.gateway.config.JwtProperties;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.UserJdbcRepository;
import com.turbofeed.gateway.security.JwtUtil;
import com.turbofeed.gateway.security.RefreshTokenStore;
import com.turbofeed.gateway.security.Role;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 登录服务：查 user 表 + BCrypt 校验，通过后签发「访问令牌 + 刷新令牌」对。
 *
 * <p>登录账号即手机号（凭证层，仅用于登录入口定位 UID）；校验通过后签发 JWT，
 * 令牌 sub 携带系统内部 {@code uid}（身份层 / 分片键），下游 {@code UserContextHolder}
 * 透传 uid，业务层按 uid 精准命中分片。密码落库为 BCrypt 哈希，此处用
 * {@link BCryptPasswordEncoder#matches} 恒定时间比对。</p>
 *
 * <p><b>双令牌模型</b>：
 * <ul>
 *   <li>访问令牌（access）：短期（默认 30min），无状态，携 role，过滤器据此鉴权；</li>
 *   <li>刷新令牌（refresh）：长期（默认 7d），<b>服务端有状态</b>（{@link RefreshTokenStore}），
 *       不含 role；访问令牌过期后凭它换发新对，且可主动吊销 / 轮换。</li>
 * </ul>
 * 访问令牌泄露窗口被压到分钟级；刷新令牌即便泄露，服务端可一键吊销（见
 * {@link #revokeRefresh} / {@link #revokeAllForUser}）。</p>
 *
 * <p><b>已修复的业务逻辑缺陷（代码审计 + 统一整改）</b>：
 * <ul>
 *   <li>① 刷新绕过封禁：{@link #refresh} 现每次从库实时取 {@code status}，封禁态立即失效；
 *       原实现只校验 RT 是否在库，被禁用账号仍能无限刷新。</li>
 *   <li>④ 访问令牌非瞬时失效：登录 / 刷新都登记 AT 的 jti 进「用户活跃集合」，
 *       使角色变更 / 封禁 / 全端登出能即时把 AT 也拉黑（不止 RT）。</li>
 *   <li>⑤ 账号枚举：{@link #login} 已统一为「手机号或密码错误」，不再暴露是否注册。</li>
 *   <li>⑥ 登出仅废当前对：{@link #logout} 升级为<b>全端登出</b>（吊销该用户全部 AT+RT）。</li>
 *   <li>⑦ 时钟来源：RT 的 Redis TTL 直接用 {@link JwtProperties#getRefreshExpireSeconds()}（签发即满 TTL），
 *       不再用 {@code System.currentTimeMillis()} 现算，避免时区 / 注入不可控。</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserJdbcRepository userJdbcRepository;
    private final JwtUtil jwtUtil;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 登录并签发「访问令牌 + 刷新令牌」对。
     *
     * @return 令牌对，前端持久化 refresh（HttpOnly Cookie 更安全），访问令牌存内存并按需刷新
     * @throws BizException 手机号或密码错误 / 账号禁用
     */
    public LoginResult login(String phone, String password) {
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号与密码不能为空");
        }
        Optional<UserJdbcRepository.UserIdHash> account = userJdbcRepository.findByPhone(phone);
        // 修复⑤：账号不存在与密码错误合并为同一提示，消除「手机号是否注册」的枚举侧信道。
        if (account.isEmpty() || !passwordEncoder.matches(password, account.get().passwordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }
        if (account.get().status() == 2) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已禁用");
        }
        // 令牌内携带 UID 而非手机号（对标抖音账号体系）。
        // 角色来自 user 表 role 列（真 RBAC），登录直接读库派生，不在配置里维护白名单；
        // DB 缺省为 USER（最小权限），空角色也兜底为 USER，杜绝越权签发。
        String uid = String.valueOf(account.get().id());
        String role = account.get().role();
        if (role == null || role.isBlank()) {
            role = "USER";
        }
        String access = jwtUtil.generateToken(uid, role);
        String refresh = jwtUtil.generateRefreshToken(uid);
        // 修复④：登录即登记 RT（有状态）与 AT 的 jti（进用户集合），
        // 后续「全端登出 / 角色变更 / 封禁」才能把仍在有效期内的访问令牌也即时拉黑。
        // 修复⑦：RT 的 Redis TTL 取签发时的满额 refreshExpireSeconds（刚签发 ≈ 剩余有效期），
        // 不再用 System.currentTimeMillis 现算，避免时区/注入不可控。
        refreshTokenStore.storeRefresh(jwtUtil.parseForRevoke(refresh).jti(), uid, jwtProperties.getRefreshExpireSeconds());
        refreshTokenStore.registerAccessToken(jwtUtil.parseForRevoke(access).jti(), uid);
        return new LoginResult(access, refresh);
    }

    /**
     * 用刷新令牌换发新「访问 + 刷新」对（刷新令牌轮换：旧 RT 吊销，新 RT 落库）。
     *
     * <p>轮换让「一枚刷新令牌只能用一次」——即便 RT 被窃取，原持有者下次刷新会使其失效，
     * 窃取方使用时也会被服务端识别为已吊销。role 每次从库实时取，使角色变更即时生效。</p>
     *
     * <p><b>修复①</b>：刷新前先按 uid 回查 user 表取最新 {@code status}，被禁用（status=2）
     * 直接失败并吊销该 RT，杜绝「账号被封但刷新令牌仍可用」的越权窗口。</p>
     *
     * @throws BizException 刷新令牌为空 / 非法 / 已过期 / 已吊销 / 账号已禁用
     */
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "刷新令牌不能为空");
        }
        // 验签 + type=refresh + 有效期 + jti；任一不符抛 UNAUTHORIZED。
        JwtUtil.JwtClaims claims = jwtUtil.parseRefresh(refreshToken);
        String uid = claims.userId();
        if (!refreshTokenStore.isValid(claims.jti(), uid)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已吊销");
        }
        // 修复①：实时校验账号状态，封禁态立即失效（原实现绕过封禁）。
        UserJdbcRepository.UserIdHash acct = userJdbcRepository.findById(Long.parseLong(uid))
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        if (acct.status() == 2) {
            // 命中封禁：先吊销这枚 RT，避免被持续复用，再报错。
            refreshTokenStore.revoke(claims.jti(), uid);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已禁用");
        }
        // 轮换：先吊销旧 RT，再签发新对。
        refreshTokenStore.revoke(claims.jti(), uid);
        String role = (acct.role() == null || acct.role().isBlank()) ? "USER" : acct.role();
        String access = jwtUtil.generateToken(uid, role);
        String newRefresh = jwtUtil.generateRefreshToken(uid);
        // 修复⑦ + ④：新 RT 落库（满额 TTL）、新 AT jti 登记进用户集合（可被即时吊销）。
        refreshTokenStore.storeRefresh(jwtUtil.parseForRevoke(newRefresh).jti(), uid, jwtProperties.getRefreshExpireSeconds());
        refreshTokenStore.registerAccessToken(jwtUtil.parseForRevoke(access).jti(), uid);
        return new LoginResult(access, newRefresh);
    }

    /**
     * 登出：<b>全端失效</b>（吊销该用户全部访问令牌 + 刷新令牌）。
     *
     * <p><b>修复⑥</b>：原登出仅吊销「调用方手里的这一对」令牌，其余端仍在线。
     * 现从访问令牌解析出 uid，对该用户<b>全部令牌</b>走 {@link RefreshTokenStore#revokeAllForUser}：
     * 集合内每个 jti 进黑名单（覆盖仍在有效期内的访问令牌，过滤器即时拒绝），
     * 同时删除对应 RT key——实现「一处登出，全端即时失效」。</p>
     *
     * <p>访问令牌缺失 / 解析失败时退化为只吊销刷新令牌（单端），保证基本登出仍可工作。
     * 全链路 fail-open：Redis 异常仅记日志，不阻断登出。</p>
     *
     * @param accessToken 当前访问令牌（Authorization: Bearer）
     * @param refreshToken 当前刷新令牌（用于退化路径）
     */
    public void logout(String accessToken, String refreshToken) {
        String uid = resolveUidFromAccessToken(accessToken);
        if (uid != null) {
            refreshTokenStore.revokeAllForUser(uid);
            return;
        }
        // 退化路径：无可用访问令牌，仅吊销刷新令牌（单端登出）。
        if (refreshToken != null && !refreshToken.isBlank()) {
            revokeRefresh(refreshToken);
        }
    }

    /** 仅验签 + 有效期解析访问令牌的 uid；失败（空 / 过期 / 非法）返回 null。 */
    private String resolveUidFromAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            return jwtUtil.parseClaims(accessToken).userId();
        } catch (BizException e) {
            return null;
        }
    }

    /** 吊销单枚刷新令牌（登出退化路径 / 单端登出调用；非法 / 过期令牌静默忽略）。 */
    public void revokeRefresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            JwtUtil.JwtClaims claims = jwtUtil.parseRefresh(refreshToken);
            refreshTokenStore.revoke(claims.jti(), claims.userId());
        } catch (BizException e) {
            // 非法 / 已过期刷新令牌无需吊销
        }
    }

    /**
     * 吊销某用户全部刷新令牌（全端登出 / 角色变更联动吊销）。
     *
     * @return 实际吊销的令牌数
     */
    public long revokeAllForUser(String uid) {
        return refreshTokenStore.revokeAllForUser(uid);
    }

    /**
     * 调整用户角色，并吊销其全部令牌（角色变更即时生效）。
     *
     * <p>吊销全部令牌（RT key 删除 + AT 进黑名单）后，该用户各端无法再刷新；访问令牌在黑名单作用下
     * 立即失效，重新登录即携带<b>新角色</b>——实现「升权 / 降权 / 封禁」秒级收敛。</p>
     *
     * @param uid 目标用户 uid（user 表分片键）
     * @param newRole 目标角色编码：USER / REVIEWER / ADMIN
     * @return 吊销的令牌数
     * @throws BizException 角色编码非法 / 用户不存在
     */
    public long changeRole(String uid, String newRole) {
        if (newRole == null || newRole.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色不能为空");
        }
        boolean known = java.util.Arrays.stream(Role.values()).anyMatch(r -> r.code().equals(newRole));
        if (!known) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知角色编码: " + newRole);
        }
        int updated = userJdbcRepository.updateRole(Long.parseLong(uid), newRole);
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户不存在: " + uid);
        }
        // 吊销全部令牌：各端被迫重新登录，新令牌携带更新后的角色。
        return refreshTokenStore.revokeAllForUser(uid);
    }

    /** 登录 / 刷新返回的令牌对。 */
    public record LoginResult(String accessToken, String refreshToken) {}
}
