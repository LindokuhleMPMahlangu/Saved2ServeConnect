package com.saved2serve.repository;

import com.saved2serve.domain.ChurchDirectoryRecord;

import java.util.List;

public interface ChurchDirectoryRepository {
    List<ChurchDirectoryRecord> findApprovedByAreaCode(String areaCode);
}
