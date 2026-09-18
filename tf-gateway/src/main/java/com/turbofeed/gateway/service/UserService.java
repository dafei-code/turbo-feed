package com.turbofeed.gateway.service;

import com.turbofeed.gateway.domain.User;
import com.turbofeed.gateway.exception.BizException;
import com.turbofeed.gateway.repository.UserJdbcRepository;
import com.turbofeed.gateway.service.moderation.ContentScene;
import com.turbofeed.gateway.service.moderation.ContentSecurityService;
import com.turbofeed.gateway.util.SnowflakeIdGenerator;
import com.turbofeed.shared.result.ErrorCode;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class UserService {

    private final UserJdbcRepository userJdbcRepository;
    /** 雪花 ID 生成器由 {@code SnowflakeConfig} 装配（workerId/datacenterId 必须逐实例区分）。 */
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ContentSecurityService contentSecurityService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
        if (nickname != null && !nickname.isBlank()) {
            // userId 传本次新生成的 id：注册路径上用户级白名单必然为空命中
            // （该 id 刚刚生成，不可能已有豁免条目），传 0 亦可，此处传真实 id 以便日志可追溯
            contentSecurityService.requireClean(ContentScene.NICKNAME, name, id);
        }
        userJdbcRepository.insert(new User(id, phone, hash, name, 1));
        return Map.of(
                "id", id,
                "phone", phone,
                "nickname", name,
                "status", 1);
    }
}
