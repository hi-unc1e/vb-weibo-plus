package xyz.fz.weibo.domain;

public record AnalysisSummary(
        Long id,
        String date,
        String promptPreview,
        int messageCount,
        String createdAt,
        String rangeMode,
        String rangeStart,
        String rangeEnd,
        long totalCount
) {
    public AnalysisSummary(Long id, String date, String promptPreview,
                           int messageCount, String createdAt) {
        this(id, date, promptPreview, messageCount, createdAt, null, null, null, messageCount);
    }
}
