package com.turbofeed.feedengine.timeline;

import java.util.Set;

/**
 * 死信重放请求。
 *
 * @param mediaIds 仅重放这些 mediaId 对应的死信（与 {@code all} 二选一）
 * @param all      为 true 时重放全部死信（忽略 mediaIds）
 */
public record DlqReplayRequest(Set<String> mediaIds, boolean all) {
}
