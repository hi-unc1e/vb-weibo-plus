package xyz.fz.weibo.domain;

public record AnalysisView(
        Long id,
        long gid,
        String date,
        String prompt,
        String result,
        int messageCount,
        String createdAt,
        String rangeMode,
        String rangeStart,
        String rangeEnd,
        long totalCount
) {
    public AnalysisView(Long id, long gid, String date, String prompt, String result,
                        int messageCount, String createdAt) {
        this(id, gid, date, prompt, result, messageCount, createdAt, null, null, null, messageCount);
    }
}
