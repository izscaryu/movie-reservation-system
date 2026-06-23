package org.example.moviereservationsystem.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable, contract-friendly pagination envelope. We deliberately do NOT serialize
 * Spring's {@code Page}/{@code PageImpl} directly: its JSON shape is not part of
 * the API contract (it has changed across Spring versions and leaks internal
 * fields like {@code pageable} and {@code sort}), and exposing it would break the
 * DTOs-only rule. Every paginated endpoint returns this instead.
 *
 * @param content       the page's items (response DTOs, never entities)
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages    total number of pages
 * @param first         whether this is the first page
 * @param last          whether this is the last page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    /** Maximum page size accepted by any paginated endpoint. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Build from a Spring {@link Page} whose content is already the response type. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /**
     * Build from already-mapped content plus a {@link Page} used only for its
     * metadata. Needed for the two-step movie query, where the page is of IDs and
     * the content is mapped separately.
     */
    public static <T> PageResponse<T> of(List<T> content, Page<?> meta) {
        return new PageResponse<>(
                content,
                meta.getNumber(),
                meta.getSize(),
                meta.getTotalElements(),
                meta.getTotalPages(),
                meta.isFirst(),
                meta.isLast());
    }
}
