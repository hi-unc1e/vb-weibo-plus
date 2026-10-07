package xyz.fz.weibo.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import xyz.fz.weibo.entity.AnalysisEntity;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.test.database.replace=none",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=always"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisRepositoryTest {

    private static final Path DATABASE = createDatabase();

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE.toAbsolutePath());
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    }

    @AfterAll
    void deleteDatabase() throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
    }

    @Test
    void page_request_is_zero_based_with_desc_sort() {
        var pageable = AnalysisRepository.pageRequest(2, 10);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void save_assigns_id() {
        AnalysisEntity entity = analysis(100L, 1_000L, "提示", "结果", 1);

        AnalysisEntity saved = analysisRepository.save(entity);

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void find_page_filters_by_gid_and_sorts_newest_first() {
        analysisRepository.save(analysis(100L, 1_000L, "早的", "结果一", 1, 100L));
        analysisRepository.save(analysis(100L, 2_000L, "晚的", "结果二", 2, 200L));
        analysisRepository.save(analysis(200L, 3_000L, "别的群", "结果三", 3, 300L));

        Page<AnalysisEntity> page = analysisRepository.findPage(100L, AnalysisRepository.pageRequest(1, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AnalysisEntity::getPrompt)
                .containsExactly("晚的", "早的");
    }

    @Test
    void latest_analysis_is_scoped_to_group() {
        analysisRepository.save(analysis(100L, 0L, "早的", "结果", 1, 100L));
        analysisRepository.save(analysis(100L, 0L, "晚的", "结果", 1, 200L));
        analysisRepository.save(analysis(200L, 0L, "别的群", "结果", 1, 300L));

        assertThat(analysisRepository.findTopByGidOrderByCreatedAtDescIdDesc(100L))
                .get().extracting(AnalysisEntity::getPrompt).isEqualTo("晚的");
    }

    @Test
    void analysis_range_metadata_is_saved_in_existing_database_schema() {
        AnalysisEntity saved = analysisRepository.save(analysis(100L, 0L, "提示", "结果", 2));

        jdbcTemplate.update("insert into analysis_ranges (analysis_id, range_mode, range_start, range_end, total_count) values (?, ?, ?, ?, ?)",
                saved.getId(), "last3", 1_000L, 2_000L, 3L);

        assertThat(jdbcTemplate.queryForObject("select range_mode from analysis_ranges where analysis_id = ?",
                String.class, saved.getId())).isEqualTo("last3");
    }

    private AnalysisEntity analysis(long gid, long date, String prompt, String result, int messageCount) {
        return analysis(gid, date, prompt, result, messageCount, System.currentTimeMillis());
    }

    private AnalysisEntity analysis(long gid, long date, String prompt, String result, int messageCount, long createdAt) {
        return new AnalysisEntity(gid, date, prompt, result, messageCount, createdAt);
    }

    private static Path createDatabase() {
        try {
            return Files.createTempFile("analysis-repository-", ".db");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite test database", e);
        }
    }
}
