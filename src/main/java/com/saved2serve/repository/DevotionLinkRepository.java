package com.saved2serve.repository;

import com.saved2serve.domain.DevotionLink;

import java.util.List;

public interface DevotionLinkRepository {
    void saveApproved(DevotionLink devotionLink);

    List<DevotionLink> findPublished();
}
