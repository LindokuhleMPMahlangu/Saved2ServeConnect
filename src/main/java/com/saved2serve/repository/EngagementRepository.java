package com.saved2serve.repository;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.SubmissionStatus;

import java.util.List;

public interface EngagementRepository {
    void save(Engagement engagement);

    List<Engagement> findByStatus(SubmissionStatus status);

    Page<Engagement> findByStatus(SubmissionStatus status, int pageSize, String cursor);

    List<Engagement> findByAreaCodeAndStatus(String areaCode, SubmissionStatus status);
}
