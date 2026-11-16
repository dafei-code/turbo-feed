package com.turbofeed.gateway.service.comment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PR-A 守护：评论落库的严格幂等语义。
 *
 * <ul>
 *   <li>不再使用 {@code INSERT IGNORE}（它会把超长 content / 非空约束错误降级为 warning）。</li>
 *   <li>已存在则跳过；并发写入撞主键（DuplicateKeyException）视为幂等命中。</li>
 *   <li>其它 SQL 异常必须上抛，交给 MQ 重试 / DLQ 或本地 ERROR 日志。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommentJdbcRepositoryIdempotencyTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    private CommentJdbcRepository repo() {
        return new CommentJdbcRepository(jdbcTemplate);
    }

    private void stubExists(boolean exists) {
        doReturn(exists ? List.of(1) : List.of())
                .when(jdbcTemplate).query(anyString(), ArgumentMatchers.<RowMapper<Integer>>any(),
                        any(Object[].class));
    }

    @Test
    void insertIfAbsent_usesPlainInsert_neverInsertIgnore() {
        stubExists(false);
        doReturn(1).when(jdbcTemplate).update(anyString(), any(Object[].class));

        assertTrue(repo().insertIfAbsent(sample()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertFalse(sql.getValue().toUpperCase().contains("IGNORE"),
                "INSERT IGNORE 会吞掉非主键 SQL 错误，评论正文会静默丢失");
        assertTrue(sql.getValue().toUpperCase().startsWith("INSERT INTO COMMENT"));
    }

    @Test
    void insertIfAbsent_skipsWhenAlreadyExists() {
        stubExists(true);
        assertFalse(repo().insertIfAbsent(sample()));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void insertIfAbsent_treatsDuplicateKeyAsIdempotentHit() {
        stubExists(false);
        doThrow(new DuplicateKeyException("pk conflict"))
                .when(jdbcTemplate).update(anyString(), any(Object[].class));
        assertFalse(repo().insertIfAbsent(sample()),
                "并发重投撞主键应视为已落库，不能把异常抛给 MQ 造成无意义重试");
    }

    @Test
    void insertIfAbsent_rethrowsNonPrimaryKeySqlErrors() {
        stubExists(false);
        doThrow(new DataIntegrityViolationException("content too long"))
                .when(jdbcTemplate).update(anyString(), any(Object[].class));
        assertThrows(DataIntegrityViolationException.class, () -> repo().insertIfAbsent(sample()),
                "非主键错误必须可见，否则又回到「计数 +1 但正文静默消失」");
    }

    private Comment sample() {
        return new Comment(123L, "m1", 9L, 0L, 0L, "hello", CommentStatus.APPROVED, 0, Instant.now());
    }
}
