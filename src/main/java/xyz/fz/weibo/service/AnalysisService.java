package xyz.fz.weibo.service;

import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xyz.fz.weibo.client.AiClient;
import xyz.fz.weibo.domain.AnalysisPageResult;
import xyz.fz.weibo.domain.AnalysisRangePreview;
import xyz.fz.weibo.domain.AnalysisSummary;
import xyz.fz.weibo.domain.AnalysisView;
import xyz.fz.weibo.entity.AnalysisEntity;
import xyz.fz.weibo.entity.MessageEntity;
import xyz.fz.weibo.repository.AnalysisRepository;
import xyz.fz.weibo.repository.MessageRepository;
import xyz.fz.weibo.service.exception.InvalidRequestException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 群聊分析：取本地消息拼接后调 AI 分析，结果存库。
 */
@Service
public class AnalysisService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RANGE_MESSAGES = 1000;

    private final MessageRepository messageRepository;
    private final AnalysisRepository analysisRepository;
    private final AiClient aiClient;
    private final JdbcTemplate jdbcTemplate;

    public AnalysisService(MessageRepository messageRepository, AnalysisRepository analysisRepository,
                           AiClient aiClient, JdbcTemplate jdbcTemplate) {
        this.messageRepository = messageRepository;
        this.analysisRepository = analysisRepository;
        this.aiClient = aiClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public AnalysisView analyze(long gid, LocalDate date, String prompt) {
        return analyze(gid, date, "day", prompt);
    }

    public AnalysisView analyze(long gid, LocalDate date, String rangeMode, String prompt) {
        PreparedAnalysis prepared = prepare(gid, date, rangeMode, prompt);
        String result = aiClient.chat(prepared.promptText());
        return save(gid, prepared, result);
    }

    /**
     * 流式分析：AI 生成的增量文本通过 deltaConsumer 逐段回调，完成后存库。
     */
    public AnalysisView analyzeStreaming(long gid, LocalDate date, String prompt, Consumer<String> deltaConsumer) {
        return analyzeStreaming(gid, date, "day", prompt, deltaConsumer);
    }

    public AnalysisView analyzeStreaming(long gid, LocalDate date, String rangeMode,
                                         String prompt, Consumer<String> deltaConsumer) {
        PreparedAnalysis prepared = prepare(gid, date, rangeMode, prompt);
        String result = aiClient.chatStream(prepared.promptText(), deltaConsumer);
        return save(gid, prepared, result);
    }

    private record Range(long start, long end, String mode, String lastAnalyzedAt, boolean hasPrevious) {
    }

    private record RangeMetadata(String mode, long start, long end, long totalCount) {
    }

    private record PreparedAnalysis(long dateMillis, String rawPrompt, String promptText,
                                    int messageCount, RangeMetadata metadata) {
    }

    public AnalysisRangePreview preview(long gid, LocalDate date, String rangeMode) {
        Range range = resolveRange(gid, date, rangeMode);
        if (!range.hasPrevious() && "since_last".equals(range.mode())) {
            return new AnalysisRangePreview(range.mode(), null, null, null, 0, 0, false);
        }
        long count = messageRepository.countByGidAndCreatedAtBetween(gid, range.start(), range.end());
        return new AnalysisRangePreview(range.mode(), formatDateTime(range.start()),
                formatDateTime(range.end()), range.lastAnalyzedAt(), count,
                (int) Math.min(count, "day".equals(range.mode()) ? Integer.MAX_VALUE : MAX_RANGE_MESSAGES),
                range.hasPrevious());
    }

    private PreparedAnalysis prepare(long gid, LocalDate date, String rangeMode, String prompt) {
        validateGid(gid);
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidRequestException("prompt 不能为空。");
        }
        Range range = resolveRange(gid, date, rangeMode);
        if (!range.hasPrevious() && "since_last".equals(range.mode())) {
            throw new InvalidRequestException("暂无上次分析记录，请先选择其他范围完成一次分析。");
        }
        int limit = "day".equals(range.mode()) ? Integer.MAX_VALUE : MAX_RANGE_MESSAGES;
        Page<MessageEntity> page = messageRepository.findPage(gid, range.start(), range.end(),
                null, null, MessageRepository.pageRequest(1, limit));
        if (!"day".equals(range.mode()) && page.getTotalElements() > MAX_RANGE_MESSAGES) {
            throw new InvalidRequestException("范围内消息超过 " + MAX_RANGE_MESSAGES + " 条，请选择较短范围。");
        }
        List<MessageEntity> messages = page.getContent();

        if (messages.isEmpty()) {
            throw new InvalidRequestException("该日期无本地消息，请先同步。");
        }

        String scope = "day".equals(range.mode()) ? "" : "分析范围：" + formatDateTime(range.start())
                + " 至 " + formatDateTime(range.end()) + "。\n\n";
        String promptText = prompt + "\n\n" + scope + "以下是群聊消息：\n\n"
                + buildMessageText(messages, !"day".equals(range.mode()));
        long dateMillis = "day".equals(range.mode()) ? range.start() : range.end();
        return new PreparedAnalysis(dateMillis, prompt, promptText, messages.size(),
                new RangeMetadata(range.mode(), range.start(), range.end(), page.getTotalElements()));
    }

    private AnalysisView save(long gid, PreparedAnalysis prepared, String result) {
        AnalysisEntity entity = new AnalysisEntity(
                gid, prepared.dateMillis(), prepared.rawPrompt(), result,
                prepared.messageCount(), System.currentTimeMillis());
        AnalysisEntity saved = analysisRepository.save(entity);
        RangeMetadata metadata = prepared.metadata();
        jdbcTemplate.update("insert into analysis_ranges (analysis_id, range_mode, range_start, range_end, total_count) values (?, ?, ?, ?, ?)",
                saved.getId(), metadata.mode(), metadata.start(), metadata.end(), metadata.totalCount());
        return toView(saved);
    }

    private Range resolveRange(long gid, LocalDate date, String rangeMode) {
        validateGid(gid);
        String mode = rangeMode == null || rangeMode.isBlank() ? "day" : rangeMode;
        long now = System.currentTimeMillis();
        Optional<AnalysisEntity> previous = analysisRepository.findTopByGidOrderByCreatedAtDescIdDesc(gid);
        String lastAt = previous.map(value -> formatDateTime(value.getCreatedAt())).orElse(null);
        return switch (mode) {
            case "day" -> {
                if (date == null) throw new InvalidRequestException("date 不能为空。");
                long start = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
                long end = Math.min(date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1, now);
                yield new Range(start, end, mode, lastAt, previous.isPresent());
            }
            case "last3", "last7" -> {
                int days = "last3".equals(mode) ? 3 : 7;
                yield new Range(now - days * 86_400_000L + 1, now, mode, lastAt, previous.isPresent());
            }
            case "since_last" -> {
                if (previous.isEmpty()) yield new Range(0, now, mode, null, false);
                AnalysisEntity last = previous.get();
                long coveredUntil = readMetadata(last.getId())
                        .map(RangeMetadata::end).orElse(last.getCreatedAt());
                yield new Range(coveredUntil + 1, now, mode, lastAt, true);
            }
            default -> throw new InvalidRequestException("分析范围无效。");
        };
    }

    private Optional<RangeMetadata> readMetadata(long id) {
        return jdbcTemplate.query("select range_mode, range_start, range_end, total_count from analysis_ranges where analysis_id = ?",
                (rs, rowNum) -> new RangeMetadata(rs.getString(1), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4)), id).stream().findFirst();
    }

    private String formatDateTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZONE).format(DATETIME_FMT);
    }

    public AnalysisPageResult list(long gid, int page, int size) {
        validateGid(gid);
        validatePage(page, size);

        Page<AnalysisEntity> result = analysisRepository.findPage(gid, AnalysisRepository.pageRequest(page, size));
        List<AnalysisSummary> items = result.getContent().stream()
                .map(this::toSummary)
                .toList();
        return new AnalysisPageResult(items, page, size, result.getTotalElements());
    }

    public AnalysisView get(long id) {
        AnalysisEntity entity = analysisRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("分析记录不存在。"));
        return toView(entity);
    }

    private String buildMessageText(List<MessageEntity> messages, boolean includeDate) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity msg : messages) {
            String time = Instant.ofEpochMilli(msg.getCreatedAt())
                    .atZone(ZONE)
                    .format(includeDate ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            : DateTimeFormatter.ofPattern("HH:mm"));
            String sender = msg.getSenderName() != null ? msg.getSenderName() : "未知";
            String text = msg.getText();
            if (text == null || text.isBlank()) {
                String typeName = msg.getMsgTypeName();
                text = typeName != null && !typeName.isBlank() ? "[" + typeName + "]" : "[非文本消息]";
            }
            sb.append("[").append(time).append("] ").append(sender).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private AnalysisView toView(AnalysisEntity entity) {
        String dateStr = Instant.ofEpochMilli(entity.getDate()).atZone(ZONE).toLocalDate().format(DATE_FMT);
        String createdAtStr = Instant.ofEpochMilli(entity.getCreatedAt()).atZone(ZONE).format(DATETIME_FMT);
        Optional<RangeMetadata> metadata = readMetadata(entity.getId());
        return new AnalysisView(
                entity.getId(),
                entity.getGid(),
                dateStr,
                entity.getPrompt(),
                entity.getResult(),
                entity.getMessageCount(),
                createdAtStr,
                metadata.map(RangeMetadata::mode).orElse(null),
                metadata.map(value -> formatDateTime(value.start())).orElse(null),
                metadata.map(value -> formatDateTime(value.end())).orElse(null),
                metadata.map(RangeMetadata::totalCount).orElse((long) entity.getMessageCount())
        );
    }

    private AnalysisSummary toSummary(AnalysisEntity entity) {
        String dateStr = Instant.ofEpochMilli(entity.getDate()).atZone(ZONE).toLocalDate().format(DATE_FMT);
        String createdAtStr = Instant.ofEpochMilli(entity.getCreatedAt()).atZone(ZONE).format(DATETIME_FMT);
        Optional<RangeMetadata> metadata = readMetadata(entity.getId());
        return new AnalysisSummary(entity.getId(), dateStr, entity.getPrompt(), entity.getMessageCount(),
                createdAtStr, metadata.map(RangeMetadata::mode).orElse(null),
                metadata.map(value -> formatDateTime(value.start())).orElse(null),
                metadata.map(value -> formatDateTime(value.end())).orElse(null),
                metadata.map(RangeMetadata::totalCount).orElse((long) entity.getMessageCount()));
    }

    private void validateGid(long gid) {
        if (gid <= 0) {
            throw new InvalidRequestException("gid 必须大于 0。");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 1) {
            throw new InvalidRequestException("page 必须大于等于 1。");
        }
        if (size < 1 || size > 100) {
            throw new InvalidRequestException("size 必须介于 1 和 100 之间。");
        }
    }
}
