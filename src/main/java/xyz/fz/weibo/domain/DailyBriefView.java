package xyz.fz.weibo.domain;

import java.util.List;

public record DailyBriefView(String date, String summary, List<Item> items, int postCount, long createdAt) {
    public record Item(String mblogId, String summary, String postUrl) {
    }
}
