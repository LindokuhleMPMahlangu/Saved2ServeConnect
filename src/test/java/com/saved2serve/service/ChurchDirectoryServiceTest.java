package com.saved2serve.service;

import com.saved2serve.domain.ChurchDirectoryEntry;
import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.repository.ChurchDirectoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChurchDirectoryServiceTest {
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

        assertEquals(List.of(new ChurchDirectoryEntry("approved", "Church approved", "https://example.org")),
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

    private static ChurchDirectoryRecord record(String id, String areaCode, ChurchDirectoryStatus status) {
        return new ChurchDirectoryRecord(id, "Church " + id, "https://example.org", areaCode, status);
    }
}
