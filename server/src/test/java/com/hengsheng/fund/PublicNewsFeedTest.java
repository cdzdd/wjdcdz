package com.hengsheng.fund;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicNewsFeedTest {
    private static final String OFFICIAL_URL = "https://www.stats.gov.cn/sj/zxfb/202611/t20261109_1965795.html";

    @Test
    void retainsOnlyOfficialDataReleasesWithoutDuplicates() throws Exception {
        var items = PublicNewsFeed.parse(feed(
            item(OFFICIAL_URL, "", "") +
            item(OFFICIAL_URL, "", "") +
            item("https://www.stats.gov.cn.example/sj/zxfb/202611/t20261109_2.html", "", "") +
            item("http://www.stats.gov.cn/sj/zxfb/202611/t20261109_3.html", "", "") +
            item("https://www.stats.gov.cn/sj/sjjd/202611/t20261109_4.html", "", "") +
            item("https://www.stats.gov.cn/sj/zxfb/202611/t20261109_5.html", "转载来源", "") +
            item("https://www.stats.gov.cn/sj/zxfb/202611/t20261109_6.html", "国家统计局", "署名作者")
        ));
        assertThat(items).containsExactly(new PublicNewsFeed.Item(
            "居民消费价格发布", "国家统计局", "2026-11-09T09:30:02+08:00", OFFICIAL_URL));
    }

    @Test
    void rejectsDoctypeAndCapsTheNumberOfResults() throws Exception {
        assertThatThrownBy(() -> PublicNewsFeed.parse(xml("""
            <!DOCTYPE rss [<!ENTITY external SYSTEM "file:///example.txt">]>
            <rss><channel><item><title>&external;</title></item></channel></rss>
            """))).isInstanceOf(XMLStreamException.class);

        var entries = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            entries.append(item("https://www.stats.gov.cn/sj/zxfb/202611/t20261109_" + i + ".html", "", ""));
        }
        assertThat(PublicNewsFeed.parse(feed(entries.toString()))).hasSize(20);
    }

    private static String item(String url, String source, String author) {
        return """
            <item>
              <title>居民消费价格发布</title>
              <link>%s</link>
              <pubDate>2026-11-09 09:30:02</pubDate>
              <source>%s</source>
              <author>%s</author>
              <description><![CDATA[<p>正文</p>]]></description>
              <content><![CDATA[完整正文]]></content>
            </item>
            """.formatted(url, source, author);
    }

    private static ByteArrayInputStream feed(String entries) {
        return xml("<rss version=\"2.0\"><channel><title>数据发布</title>" + entries + "</channel></rss>");
    }

    private static ByteArrayInputStream xml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
