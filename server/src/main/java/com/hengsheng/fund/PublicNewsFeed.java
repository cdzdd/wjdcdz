package com.hengsheng.fund;

import jakarta.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

@Component
public class PublicNewsFeed {
    private static final Logger log = LoggerFactory.getLogger(PublicNewsFeed.class);
    private static final URI FEED_URL = URI.create("https://www.stats.gov.cn/sj/zxfb/rss.xml");
    private static final Pattern ARTICLE_PATH = Pattern.compile("/sj/zxfb/\\d{6}/t\\d{8}_\\d+\\.html");
    private static final Set<String> FIELDS = Set.of("title", "link", "pubDate", "source", "author");
    private static final Set<String> OFFICIAL_SOURCES = Set.of("", "国家统计局", "国家统计局网站", "中国统计信息网");
    private static final DateTimeFormatter PUBLICATION_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ITEMS = 20;
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;

    public record Item(String title, String source, String publishedAt, String url) {}
    public record Snapshot(List<Item> items, String updatedAt, boolean stale) {}

    private final boolean refreshEnabled;
    private final ScheduledExecutorService refreshWorker = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("official-news-refresh").factory());
    private volatile Snapshot snapshot = new Snapshot(List.of(), null, true);

    public PublicNewsFeed(@Value("${fund.news.refresh-enabled:true}") boolean refreshEnabled) {
        this.refreshEnabled = refreshEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (refreshEnabled) {
            refreshWorker.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.HOURS);
        }
    }

    @PreDestroy
    void stop() {
        refreshWorker.shutdownNow();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private void refresh() {
        try {
            List<Item> items = fetch();
            if (items.isEmpty()) {
                throw new IOException("No eligible official statistics items");
            }
            snapshot = new Snapshot(items, Instant.now().toString(), false);
            log.info("Official statistics feed refreshed with {} items", items.size());
        } catch (IOException | XMLStreamException ex) {
            Snapshot previous = snapshot;
            snapshot = new Snapshot(previous.items(), previous.updatedAt(), true);
            log.warn("Official statistics feed refresh failed: {}", ex.getMessage());
        }
    }

    private static List<Item> fetch() throws IOException, XMLStreamException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        HttpURLConnection connection = (HttpURLConnection) FEED_URL.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Upstream HTTP status " + status);
            }
            try (InputStream input = new BoundedFeed(connection.getInputStream(), deadline)) {
                return parse(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    static List<Item> parse(InputStream input) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(input);
        try {
            expectTag(reader, "rss");
            expectTag(reader, "channel");
            List<Item> items = new ArrayList<>();
            Set<String> urls = new HashSet<>();
            while (items.size() < MAX_ITEMS && reader.nextTag() == START_ELEMENT) {
                if (!"item".equals(reader.getLocalName())) {
                    skipElement(reader);
                    continue;
                }
                Item item = readItem(reader);
                if (item != null && urls.add(item.url())) {
                    items.add(item);
                }
            }
            return List.copyOf(items);
        } finally {
            reader.close();
        }
    }

    private static void expectTag(XMLStreamReader reader, String tag) throws XMLStreamException {
        if (reader.nextTag() != START_ELEMENT || !tag.equals(reader.getLocalName())) {
            throw new XMLStreamException("Unexpected statistics feed structure");
        }
    }

    private static Item readItem(XMLStreamReader reader) throws XMLStreamException {
        Map<String, String> fields = new HashMap<>();
        while (reader.nextTag() == START_ELEMENT) {
            String name = reader.getLocalName();
            if (FIELDS.contains(name)) {
                fields.putIfAbsent(name, reader.getElementText().strip());
            } else {
                skipElement(reader);
            }
        }
        String title = fields.getOrDefault("title", "");
        String link = fields.getOrDefault("link", "");
        if (title.isBlank() || title.length() > 200 || title.contains("转载") || title.contains("转自")
                || !OFFICIAL_SOURCES.contains(fields.getOrDefault("source", ""))
                || !fields.getOrDefault("author", "").isBlank() || !isOfficialArticle(link)) {
            return null;
        }
        try {
            String publishedAt = LocalDateTime.parse(fields.getOrDefault("pubDate", ""), PUBLICATION_TIME)
                .atZone(CHINA).toOffsetDateTime().toString();
            return new Item(title, "国家统计局", publishedAt, link);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static boolean isOfficialArticle(String link) {
        try {
            URI uri = URI.create(link);
            return "https".equalsIgnoreCase(uri.getScheme()) && "www.stats.gov.cn".equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && ARTICLE_PATH.matcher(uri.getRawPath()).matches();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = reader.next();
            if (event == START_ELEMENT) {
                depth++;
            } else if (event == END_ELEMENT) {
                depth--;
            }
        }
    }

    private static final class BoundedFeed extends FilterInputStream {
        private final long deadline;
        private int bytesRead;

        BoundedFeed(InputStream input, long deadline) {
            super(input);
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            checkLimits(0);
            int value = super.read();
            checkLimits(value == -1 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkLimits(0);
            int count = super.read(bytes, offset, Math.min(length, MAX_FEED_BYTES - bytesRead + 1));
            checkLimits(Math.max(count, 0));
            return count;
        }

        private void checkLimits(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > MAX_FEED_BYTES) {
                throw new IOException("Statistics feed exceeded the read size limit");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IOException("Statistics feed exceeded the refresh deadline");
            }
        }
    }
}
