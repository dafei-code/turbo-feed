package com.turbofeed.gateway.service;

import com.turbofeed.gateway.domain.User;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.UserJdbcRepository;
import com.turbofeed.gateway.repository.UserPhoneRouterRepository;
import com.turbofeed.gateway.service.moderation.ContentScene;
import com.turbofeed.gateway.service.moderation.ContentSecurityService;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户注册服务：手机号格式校验 + 全局唯一去重 + 昵称内容安全 + BCrypt 哈希落库。
 *
 * <p>注册成功后返回脱敏的用户信息（不含 passwordHash）。登录流程在 {@link AuthService}，
 * 本服务不签发 JWT——保持「注册 / 登录」两个动作职责清晰，前端注册后跳登录页换取令牌。</p>
 *
 * <p><b>昵称为什么要在这里拦</b>：昵称是<b>全站公开展示位</b>——评论、帖子、列表页到处都会渲染它，
 * 且会被大量复制传播，违规昵称的影响面远大于一条评论。而改造前这里是<b>完全没过滤</b>的
 * （只校验手机号格式与密码长度），属于审查实测出来的缺口。放在注册路径上拦，
 * 也顺带避免了「注册后无法改名」场景下的补救难题。</p>
 *
 * <p><b>已知边界</b>：昵称目前只在注册时检测一次。若日后开放「修改昵称」入口，
 * 该入口必须同样走 {@link ContentSecurityService}——这条约束写在这里是为了让
 * 后来者加接口时能看到（历史缺口正是「文案入口没人记得加检测」造成的）。</p>
 */
@Service
public class UserService {

    private final UserJdbcRepository userJdbcRepository;
    private final UserPhoneRouterRepository phoneRouter;
    /** 雪花 ID 生成器由 {@code SnowflakeConfig} 装配（workerId/datacenterId 必须逐实例区分）。 */
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ContentSecurityService contentSecurityService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 显式构造器（不用 {@code @RequiredArgsConstructor}：本机构建环境对被修改文件的 Lombok 注解处理不生效）。 */
    public UserService(UserJdbcRepository userJdbcRepository,
                       UserPhoneRouterRepository phoneRouter,
                       SnowflakeIdGenerator snowflakeIdGenerator,
                       ContentSecurityService contentSecurityService) {
        this.userJdbcRepository = userJdbcRepository;
        this.phoneRouter = phoneRouter;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.contentSecurityService = contentSecurityService;
    }

    public Map<String, Object> register(String phone, String password, String nickname) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号格式不正确");
        }
        if (password == null || password.length() < 6) {
            throw new BizException(ErrorCode.PARAM_ERROR, "密码至少 6 位");
        }
        if (userJdbcRepository.countByPhone(phone) > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号已注册");
        }
        long id = snowflakeIdGenerator.nextId();
        String hash = passwordEncoder.encode(password);
        String name = (nickname == null || nickname.isBlank())
                ? "用户" + phone.substring(phone.length() - 4) : nickname;
        // 昵称内容安全：长度上限 + 敏感词（场景 NICKNAME，尺度最严）。
        // 只检测用户显式传入的昵称——服务端生成的默认昵称（"用户1234"）必然合规，
        // 而「用户+4 位数字」这种形态反而可能撞上词库里的纯数字词条，白挨一次误拦。
        // ⚠️ 刻意排在「绑定路由表」之前：绑定是一道并发闸门，先做必然失败的校验，
        //    可避免「昵称违规 → 绑了又解绑」的无谓写放大与崩溃残留窗口。
        if (nickname != null && !nickname.isBlank()) {
            // userId 传本次新生成的 id：注册路径上用户级白名单必然为空命中
            // （该 id 刚刚生成，不可能已有豁免条目），传 0 亦可，此处传真实 id 以便日志可追溯
            contentSecurityService.requireClean(ContentScene.NICKNAME, name, id);
        }
        // 并发闸门：路由表主键 phone 是「唯一一处真正 enforce 手机号全局唯一」的约束
        // （user 表的 uk_phone 只在本分片内唯一，跨分片重复拦不住）。
        // 两个请求同时注册同一手机号时，INSERT IGNORE 只有一个拿到 rows=1，另一个在此被拒。
        if (!phoneRouter.bindIfAbsent(phone, id)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号已注册");
        }
        try {
            userJdbcRepository.insert(new User(id, phone, hash, name, 1));
        } catch (RuntimeException e) {
            // 补偿：绑定成功但用户行没落库 → 必须解绑。否则该手机号会永远显示「已注册」
            // 却又登不进去（路由指向空行），且无法重新注册。
            // 崩溃（进程挂掉）落在两步之间时补偿执行不到——由登录侧 findByPhone 的自愈解绑兜底。
            phoneRouter.unbind(phone, id);
            throw e;
        }
        return Map.of(
                "id", id,
                "phone", phone,
                "nickname", name,
                "status", 1);
    }
}
