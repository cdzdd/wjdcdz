package com.hengsheng.fund;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicNewsController {
    private final PublicNewsFeed feed;

    public PublicNewsController(PublicNewsFeed feed) {
        this.feed = feed;
    }

    @GetMapping("/api/public/news")
    public PublicNewsFeed.Snapshot news() {
        return feed.snapshot();
    }
}
