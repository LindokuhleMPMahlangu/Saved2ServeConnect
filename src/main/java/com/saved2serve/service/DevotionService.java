package com.saved2serve.service;

import com.saved2serve.domain.DevotionLink;
import com.saved2serve.domain.DevotionLinkEntry;
import com.saved2serve.domain.DevotionLinkStatus;
import com.saved2serve.repository.DevotionLinkRepository;
import com.saved2serve.validation.Saved2ServeValidator;

import java.util.List;
import java.util.Objects;

public final class DevotionService {
    private final DevotionLinkRepository devotionLinkRepository;

    public DevotionService(DevotionLinkRepository devotionLinkRepository) {
        this.devotionLinkRepository = Objects.requireNonNull(
                devotionLinkRepository, "devotionLinkRepository must not be null"
        );
    }

    public List<DevotionLinkEntry> findPublished() {
        return devotionLinkRepository.findPublished().stream()
                .filter(DevotionService::isPublishable)
                .map(DevotionService::toPublicEntry)
                .toList();
    }

    private static boolean isPublishable(DevotionLink link) {
        return link.status() == DevotionLinkStatus.APPROVED
                && Saved2ServeValidator.validateDevotionLink(link.url()).valid();
    }

    private static DevotionLinkEntry toPublicEntry(DevotionLink link) {
        return new DevotionLinkEntry(
                link.devotionId(), link.title(), link.url(), link.platform()
        );
    }
}
