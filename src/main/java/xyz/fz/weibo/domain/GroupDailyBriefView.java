package xyz.fz.weibo.domain;

import java.util.List;

public record GroupDailyBriefView(long gid, String date, String summary, List<Item> items,
                                  long messageCount, int analyzedCount, long createdAt) {
    public record Item(long mid, String summary, String senderName, String text, long createdAt) {
    }
}
