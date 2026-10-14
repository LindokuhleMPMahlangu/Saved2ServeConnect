package com.saved2serve.service;

import com.saved2serve.domain.ChurchDirectoryEntry;
import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.repository.ChurchDirectoryRepository;

import java.util.List;
import java.util.Objects;

public final class ChurchDirectoryService {
    private final ChurchDirectoryRepository churchDirectoryRepository;

    public ChurchDirectoryService(ChurchDirectoryRepository churchDirectoryRepository) {
        this.churchDirectoryRepository = Objects.requireNonNull(
                churchDirectoryRepository, "churchDirectoryRepository must not be null"
        );
    }

    public List<ChurchDirectoryEntry> findByAreaCode(String areaCode) {
        String normalizedAreaCode = requireAreaCode(areaCode);
        return churchDirectoryRepository.findApprovedByAreaCode(normalizedAreaCode).stream()
                .filter(record -> record.status() == ChurchDirectoryStatus.APPROVED)
                .filter(record -> record.areaCode().equals(normalizedAreaCode))
                .map(ChurchDirectoryService::toPublicEntry)
                .toList();
    }

    private static ChurchDirectoryEntry toPublicEntry(ChurchDirectoryRecord record) {
        return new ChurchDirectoryEntry(record.churchId(), record.displayName(), record.publicWebsite());
    }

    private static String requireAreaCode(String areaCode) {
        if (areaCode == null || areaCode.isBlank()) {
            throw new IllegalArgumentException("areaCode must not be null or blank.");
        }
        return areaCode.trim();
    }
}
