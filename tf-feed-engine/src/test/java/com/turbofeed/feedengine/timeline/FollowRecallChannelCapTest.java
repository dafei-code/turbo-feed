package com.turbofeed.feedengine.timeline;

import com.turbofeed.feedengine.interest.SessionSequenceService;
import com.turbofeed.feedengine.ranking.UserRealtimeProfile;
import com.turbofeed.shared.model.FeedItemView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0 动态配额单测：大 V 每位作者默认取 1 条，普通作者仍 3 条；
 * bigVAuthors 为空（开关关闭 / 读失败）时所有作者都按 3 条，零回归。
 */
@ExtendWith(MockitoExtension.class)
class FollowRecallChannelCapTest {

    @Mock RecallSupport recallSupport;

    private FollowRecallChannel channel(int bigvCap) {
        FollowRecallChannel c = new FollowRecallChannel(recallSupport);
        ReflectionTestUtils.setField(c, "bigvPerAuthorCap", bigvCap);
        return c;
    }

    private RecallContext ctx(Set<String> bigVAuthors) {
        RecallContext c = new RecallContext("u1", 0, 20, null, Map.of(), Map.of(),
                List.<SessionSequenceService.SessionItem>of(), Set.of(),
                (UserRealtimeProfile) null, Set.of("big", "normal"), Map.of(), recallSupport);
        c.bigVAuthors = bigVAuthors;
        return c;
    }

    private FeedItemView item(String tk) {
        FeedItemView it = mock(FeedItemView.class);
        when(it.timelineKey()).thenReturn(tk);
        when(it.createdAt()).thenReturn(Instant.now());
        return it;
    }

    @Test
    void bigVUsesSmallerCap() {
        FeedItemView bigItem = item("big-post");
        FeedItemView normalItem = item("normal-post");
        when(recallSupport.authorRecentItems("big", "u1", 1, null)).thenReturn(List.of(bigItem));
        when(recallSupport.authorRecentItems("normal", "u1", 3, null)).thenReturn(List.of(normalItem));

        List<ScoredItem> out = channel(1).recall(ctx(Set.of("big")), 10);

        assertEquals(2, out.size());
        verify(recallSupport).authorRecentItems("big", "u1", 1, null);
        verify(recallSupport).authorRecentItems("normal", "u1", 3, null);
        verify(recallSupport, never()).authorRecentItems("big", "u1", 3, null);
    }

    @Test
    void emptyBigVSet_fallsBackToOriginalCap() {
        when(recallSupport.authorRecentItems("big", "u1", 3, null)).thenReturn(List.of());
        when(recallSupport.authorRecentItems("normal", "u1", 3, null)).thenReturn(List.of());

        channel(1).recall(ctx(Set.of()), 10);

        verify(recallSupport).authorRecentItems("big", "u1", 3, null);
        verify(recallSupport).authorRecentItems("normal", "u1", 3, null);
    }
}
