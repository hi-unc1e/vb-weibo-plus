package xyz.fz.weibo.domain;

public record AnalysisRangePreview(String rangeMode, String rangeStart, String rangeEnd,
                                   String lastAnalyzedAt, long messageCount, int analyzedCount,
                                   boolean hasPrevious) {
}
