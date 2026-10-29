package com.turbofeed.feedengine.interest;

import com.turbofeed.shared.model.FeedBehaviorEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收藏信号进入用户画像的回归护栏：权重 = 1.5（强意图，同 SHARE/COMMENT），且属正向（不被负反馈通道吞掉）。
 */
@ExtendWith(MockitoExtension.class)
class InterestServiceFavoriteTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock TagIndexService tagIndexService;

    private InterestService service() {
        return new InterestService(redisTemplate, tagIndexService);
    }

    @Test
    void favorite_hasWeight15() {
        double w = service().eventWeight(new FeedBehaviorEvent("k", "FAVORITE", null, null, "1", null, null));
        assertEquals(1.5, w, 1e-9, "收藏应计为强意图权重 1.5");
    }

    @Test
    void unfavorite_hasZeroWeight_andNotNegative() {
        double w = service().eventWeight(new FeedBehaviorEvent("k", "UNFAVORITE", null, null, "1", null, null));
        assertEquals(0.0, w, 1e-9, "取消收藏是回退信号，不再计正向权重");
    }

    @Test
    void favorite_isPositiveSignal_notNegativeFeedback() {
        double w = service().eventWeight(new FeedBehaviorEvent("k", "FAVORITE", null, null, "1", null, null));
        assertTrue(w > 0, "收藏应走正向权重通道；负反馈类型仅 NOT_INTERESTED");
    }
}
