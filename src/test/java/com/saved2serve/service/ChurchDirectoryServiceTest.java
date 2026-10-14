package com.saved2serve.service;

import com.saved2serve.domain.ChurchDirectoryEntry;
import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.repository.ChurchDirectoryRepository;
import com.saved2serve.seed.ChurchDirectorySeed;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChurchDirectoryServiceTest {
    @Test
    void pilotChurchMatchesOnlyItsConfirmedPostcode() {
        ChurchDirectoryRepository repository = mock(ChurchDirectoryRepository.class);
        when(repository.findApprovedByAreaCode("1800"))
                .thenReturn(List.of(ChurchDirectorySeed.HOUSE_OF_TREASURES));
        when(repository.findApprovedByAreaCode("2091")).thenReturn(List.of());
        ChurchDirectoryService service = new ChurchDirectoryService(repository);

        assertEquals(List.of(new ChurchDirectoryEntry(
                "house-of-treasures-ministries",
                "House of Treasures Ministries",
                "https://houseoftreasuresministries.com",
                "14 Impala Road, Rispark, Johannesburg South, 2091, South Africa"
        )), service.findByAreaCode("1800"));
        assertEquals(List.of(), service.findByAreaCode("2091"));
    }

    @Test
    void returnsOnlyApprovedExactAreaMatchesAndPublicFields() {
        ChurchDirectoryRepository repository = mock(ChurchDirectoryRepository.class);
        when(repository.findApprovedByAreaCode("00123")).thenReturn(List.of(
                record("approved", "00123", ChurchDirectoryStatus.APPROVED),
                record("pending", "00123", ChurchDirectoryStatus.PENDING_REVIEW),
                record("rejected", "00123", ChurchDirectoryStatus.REJECTED),
                record("inactive", "00123", ChurchDirectoryStatus.INACTIVE),
                record("different-area", "123", ChurchDirectoryStatus.APPROVED)
        ));
        ChurchDirectoryService service = new ChurchDirectoryService(repository);

        List<ChurchDirectoryEntry> results = service.findByAreaCode(" 00123 ");

        assertEquals(List.of(new ChurchDirectoryEntry(
                        "approved", "Church approved", "https://example.org", ""
                )),
                results);
        verify(repository).findApprovedByAreaCode("00123");
    }

    @Test
    void returnsEmptyListWhenNoApprovedChurchesMatch() {
        ChurchDirectoryRepository repository = mock(ChurchDirectoryRepository.class);
        when(repository.findApprovedByAreaCode("99999")).thenReturn(List.of());
        ChurchDirectoryService service = new ChurchDirectoryService(repository);

        assertEquals(List.of(), service.findByAreaCode("99999"));
    }

    @Test
    void rejectsBlankAreaCodeWithoutRepositoryAccess() {
        ChurchDirectoryRepository repository = mock(ChurchDirectoryRepository.class);
        ChurchDirectoryService service = new ChurchDirectoryService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.findByAreaCode("  "));
    }

    @Test
    void approvedChurchRequiresVerificationMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new ChurchDirectoryRecord(
                "church-1",
                "Church One",
                "https://example.org",
                "00123",
                ChurchDirectoryStatus.APPROVED,
                null,
                " "
        ));
    }

    private static ChurchDirectoryRecord record(String id, String areaCode, ChurchDirectoryStatus status) {
        boolean approved = status == ChurchDirectoryStatus.APPROVED;
        return new ChurchDirectoryRecord(
                id,
                "Church " + id,
                "https://example.org",
                areaCode,
                status,
                approved ? Instant.parse("2026-10-25T10:00:00Z") : null,
                approved ? "verified-source:" + id : ""
        );
    }
}
