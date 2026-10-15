package com.saved2serve.domain;

public record DevotionLinkEntry(
        String devotionId,
        String title,
        String url,
        String platform
) {
}
