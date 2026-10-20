package com.turbofeed.gateway.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 搜索索引写入器（网关侧：用户昵称索引 + 联想词典）。
 *
 * <h3>为什么昵称要「随索引一起存」</h3>
 * <p>{@code UserJdbcRepository#findById} 只返回 {@code (id, passwordHash, status, role)}，
 * <b>没有 nickname</b>；若搜索结果只存 userId，还得再回表一次才能拿到昵称。而
 * {@code user} 表按 {@code id} 分片、单次回表成本不高，但 N 条结果就是 N 次往返。
 * 因此把原始昵称塞进 ZSET member：{@code "{userId}\u0000{nickname}"}——一次 ZRANGE
 * 就拿到「id + 展示名」，零额外回表。</p>
 *
 * <h3>联想词典为什么用 score 全 0 的 ZSET</h3>
 * <p>Redis 的 {@code ZRANGEBYLEX} 只在<b>所有成员 score 相同</b>时才按字典序工作。
 * 词典键 {@code tf:search:lex} 恒写 score=0，于是可以「按前缀取一段」——
 * 这正是搜索联想需要的形态。成员带类型前缀（{@code tag:} / {@code user:}）以支持
 * 话题与用户混排返回。</p>
 *
 * <p><b>fail-open</b>：索引是搜索的加速副本，写失败只告警——<b>绝不能让注册失败</b>。
 * 缺失的条目可由 S3 冷补表回填。</p>
 *
 * <p>⚠️ 本机构建环境 Lombok 对新文件不生效 → 显式构造器 + 显式 Logger。</p>
 */
@Component
public class SearchIndexWriter {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexWriter.class);

    /** 昵称精确索引：{@code tf:user:idx:{nicknameNorm} => ZSET(member="{userId}\0{nickname}", score=注册时间ms)}。 */
    static final String USER_INDEX_PREFIX = "tf:user:idx:";
    /** 联想词典：{@code tf:search:lex => ZSET(member="user:{nick}"|"tag:{tag}", score=0)}。 */
    static final String LEX_KEY = "tf:search:lex";
    /** 词典类型前缀。 */
    public static final String LEX_USER = "user:";
    public static final String LEX_TAG = "tag:";

    private final StringRedisTemplate redis;

    public SearchIndexWriter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 建立（或刷新）一个用户的昵称索引与联想词。
     *
     * @param userId   用户 ID
     * @param nickname 原始昵称（保留大小写用于展示）
     */
    public void indexUser(long userId, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return;
        }
        String norm = normalize(nickname);
        if (norm.isEmpty()) {
            return;
        }
        try {
            String member = userId + "\u0000" + nickname;
            redis.opsForZSet().add(USER_INDEX_PREFIX + norm, member, System.currentTimeMillis());
            redis.opsForZSet().add(LEX_KEY, LEX_USER + norm, 0d);
        } catch (Exception e) {
            // fail-open：索引写失败不能把注册打回
            log.warn("用户搜索索引写入失败（不影响注册）: userId={}, {}", userId, e.getMessage());
        }
    }

    /** 昵称归一化：去首尾空白 + 小写（与话题归一化口径一致，保证「大小写不同」能互相搜到）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
