# Pagination — the house pattern

Load this before adding pagination to any endpoint. The scaffold already generates the reusable
primitives in `shared/pagination/` (`CursorPage`, `PagedResponse`, `PageCursor`); this guide is how
you wire them to a real endpoint and which style to pick.

Three rules are non-negotiable regardless of style:

1. **Cap the page size.** An uncapped `?size=1000000` is a denial-of-service. Enforced globally via
   `spring.data.web.pageable.max-page-size` (offset) and a hard `Limit` cap in the service (keyset).
2. **Never serialize `Page`/`PageImpl`/`Window` directly.** Their JSON shape is unstable and Spring
   warns against it. Always map to `CursorPage<T>` or `PagedResponse<T>`.
3. **Entities never leave the service layer.** Map to a response record *before* wrapping — the
   `content` list is always DTOs, never `@Entity` types.

---

## Which style?

**Keyset / cursor — the default.** `WHERE (created_at, id) < (:ts, :id) ORDER BY created_at DESC,
id DESC LIMIT :n`. Uses the index, so it's O(log n) at any depth and stable when rows are inserted.
Use it for anything user-facing, infinite scroll, feeds, or any table that will grow. Trade-off: no
jump-to-arbitrary-page, no cheap total count, sort must be on stable indexed columns.

**Offset — the fallback.** `Pageable` → `Page`. Only when the UI genuinely needs page numbers and a
total count (admin tables, dashboards) *and* the table stays small. `Page` runs a `COUNT(*)` every
request and `OFFSET n` scans+discards n rows — it degrades past a few thousand rows and returns
inconsistent results if data shifts between requests. Prefer `Slice` over `Page` when you don't need
the total (skips the `COUNT`).

Rule of thumb: **start with keyset; drop to offset only when a real requirement forces page numbers.**

---

## Repository

Paginated repositories must extend `JpaRepository` — `ListCrudRepository` has **no paging methods**.

```java
public interface JobRepository extends JpaRepository<Job, Long> {

    // Keyset scroll. Sort defines the keyset columns; Limit caps the page.
    Window<Job> findByStatus(JobStatus status, ScrollPosition position, Limit limit, Sort sort);

    // Offset fallback (only if you truly need it).
    Page<Job> findByStatus(JobStatus status, Pageable pageable);
}
```

**Index requirement:** the keyset sort columns must be backed by a composite index in the same
order and direction, and must end in a unique column (append the PK to break ties):

```sql
CREATE INDEX idx_job_created_at_id ON job (created_at DESC, id DESC);
```

Without that index keyset pagination silently falls back to a full sort — the whole point is lost.

---

## Keyset — service + controller

```java
// service — entity stays here; mapped to a DTO before the Window leaves
private static final int MAX_SIZE = 100;

public CursorPage<JobResponse> list(JobStatus status, String cursor, int size) {
    int capped = Math.min(Math.max(size, 1), MAX_SIZE);
    ScrollPosition position = PageCursor.decode(cursor);      // blank cursor -> first page
    Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    Window<Job> window = jobRepository.findByStatus(status, position, Limit.of(capped), sort);
    return CursorPage.of(window, JobResponse::from);
}
```

```java
// controller — HTTP only; cursor is an opaque token the client echoes back
@GetMapping
public ResponseEntity<CursorPage<JobResponse>> list(
        @RequestParam(defaultValue = "OPEN") JobStatus status,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(jobService.list(status, cursor, size));
}
```

Response shape — `nextCursor` is null on the last page:

```json
{
  "content": [ { "id": 42, "title": "..." } ],
  "nextCursor": "eyJjcmVhdGVkQXQiOjE3Mjb9",
  "hasNext": true
}
```

The client fetches the next page with `?cursor=<nextCursor>`. A malformed cursor is a client error —
`PageCursor.decode` throws `InvalidCursorException`, which carries `ErrorKind.INVALID_INPUT`, so the
single `BaseException` handler in `GlobalExceptionHandler` turns it into a `400` `ProblemDetail`. No
handler change is needed for it, or for any other exception you add to the hierarchy.

**Cursor type fidelity (the one gotcha):** the cursor encodes the keyset column values as JSON. Long
ids and numbers round-trip cleanly. Temporal columns (`Instant`, `LocalDateTime`) are the sharp edge —
Jackson 3 serializes them out of the box (java.time support is built into databind 3, no module to
register), but confirm your keyset columns deserialize back to the type the query binds. When in
doubt, key on `(epochMillis, id)` or `(id)` alone — both round-trip without coercion surprises.

---

## Offset — service + controller (fallback)

```java
public PagedResponse<JobResponse> list(JobStatus status, Pageable pageable) {
    return PagedResponse.of(jobRepository.findByStatus(status, pageable).map(JobResponse::from));
}
```

```java
@GetMapping
public ResponseEntity<PagedResponse<JobResponse>> list(
        @RequestParam(defaultValue = "OPEN") JobStatus status,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
        Pageable pageable) {
    return ResponseEntity.ok(jobService.list(status, pageable));
}
```

`max-page-size` in `application.yml` caps `?size=` before it reaches the service — no manual clamp needed.

---

## Testing (integration, against a real Postgres)

Keyset correctness only shows up with more rows than one page, so seed enough to force a second page.

```java
@Test
void should_return_stable_pages_when_scrolling_by_cursor() {
    seedJobs(25);   // > default page size

    var first = jobService.list(JobStatus.OPEN, null, 10);
    assertThat(first.content()).hasSize(10);
    assertThat(first.hasNext()).isTrue();

    var second = jobService.list(JobStatus.OPEN, first.nextCursor(), 10);
    assertThat(second.content()).hasSize(10);
    // no overlap between pages — the whole point of keyset
    assertThat(idsOf(second)).doesNotContainAnyElementsOf(idsOf(first));
}

@Test
void should_cap_page_size_when_client_requests_too_many() {
    seedJobs(200);
    var page = jobService.list(JobStatus.OPEN, null, 10_000);
    assertThat(page.content()).hasSizeLessThanOrEqualTo(100);   // MAX_SIZE
}
```
