package xyz.fz.weibo.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class GroupDailyBriefPageTest {

    private static HttpServer server;
    private static Playwright playwright;
    private static Browser browser;
    private static String baseUrl;
    private static final AtomicBoolean generated = new AtomicBoolean();
    private static final long MESSAGE_TIME = LocalDate.of(2026, 8, 15)
            .atTime(17, 43).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    private static final String BRIEF = """
            {"gid":101,"date":"2026-08-15","summary":"讨论了周末安排。",
             "items":[{"mid":42,"summary":"确认集合时间。","senderName":"甲",
                       "text":"周六十点集合","createdAt":%d}],
             "messageCount":1,"analyzedCount":1,"createdAt":1}
            """.formatted(MESSAGE_TIME);

    @BeforeAll
    static void start_browser_and_server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/groups", exchange -> sendJson(exchange,
                "[{\"gid\":101,\"name\":\"周末群\"}]"));
        server.createContext("/chat/messages/cursor", exchange -> sendJson(exchange,
                "{\"items\":[],\"hasMore\":false}"));
        server.createContext("/chat/messages", exchange -> sendJson(exchange,
                "{\"items\":[{\"createdAt\":" + MESSAGE_TIME + "}]}"));
        server.createContext("/chat/daily-brief", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) generated.set(true);
            if (!generated.get()) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            sendJson(exchange, BRIEF);
        });
        server.createContext("/chat/brief/", GroupDailyBriefPageTest::sendStaticResource);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        if (Boolean.getBoolean("playwright.systemChrome")) options.setChannel("chrome");
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void stop_browser_and_server() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.stop(0);
    }

    @Test
    void mobile_can_generate_and_open_original_message() {
        generated.set(false);
        try (Page page = browser.newPage(new Browser.NewPageOptions()
                .setViewportSize(390, 844))) {
            page.navigate(baseUrl + "/chat/brief/index.html?gid=101");
            assertThat(page.locator("#date")).hasValue("2026-08-15");
            assertThat(page.locator("#status")).containsText("还没有简报");
            page.locator("#generate").click();
            assertThat(page.locator("#summary")).hasText("讨论了周末安排。");
            page.getByText("查看原消息与上下文").click();
            assertThat(page.locator("#source .target p")).hasText("周六十点集合");
            assertThat(page).hasURL(java.util.regex.Pattern.compile(".*mid=42.*"));
            page.reload();
            assertThat(page.locator("#source .target p")).hasText("周六十点集合");
        }
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void sendStaticResource(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try (InputStream stream = GroupDailyBriefPageTest.class.getResourceAsStream("/static" + path)) {
            if (stream == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", path.endsWith(".css")
                    ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }
}
