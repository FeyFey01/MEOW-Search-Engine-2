# Product Requirements Document (PRD)

## MEOW Search Engine - Web Crawler (Phase 1)

---

## 1. Objective

Design and implement a fault-tolerant, single-machine, multithreaded web crawler that starts from a seed URL, explores pages up to a configurable depth `k`, and persists state so interrupted jobs can resume safely.

This phase establishes reliable crawl data generation for later indexing and search phases.

---

## 2. Scope

### In Scope

- Depth-limited crawling with Breadth-First Search (BFS)
- Configurable multithreaded workers on a single machine
- URL normalization and deduplication per crawl job
- Thread-safe frontier and visited-state management
- Periodic persistence of crawl state (`state.json`)
- Append-only crawl outputs (`pages.jsonl`, `edges.jsonl`)
- Graceful shutdown and resume from persisted state
- Basic HTTP fetch + HTML parse for text and links

### Out of Scope

- Distributed crawling or horizontal scaling
- Query APIs, ranking, or user-facing search endpoints
- Advanced NLP/semantic processing
- Full crawl politeness framework (robots.txt, adaptive rate strategies)
- JavaScript rendering (headless browser execution)

---

## 3. Users and Use Cases

### Primary Users

- Engineers building indexing/search pipeline components
- Developers validating crawl correctness, reliability, and throughput

### Core Use Cases

- Start a new crawl from a seed URL with depth/worker settings
- Monitor crawl progress and output generation
- Stop a crawl safely and resume later without duplicate visits
- Export page/link datasets for downstream indexing

---

## 4. System Overview

The crawler runs as a single process with a bounded work queue and multiple worker threads.

Core components:

- **Job Controller**: Initializes jobs, tracks lifecycle, coordinates shutdown
- **Frontier Queue**: Bounded, depth-aware queue for pending URLs
- **Visited Registry**: Deduplicates normalized URLs per job
- **Fetcher**: Retrieves page content with timeouts/retry policy
- **Parser**: Extracts clean text and outgoing links from HTML
- **Persistence Layer**: Periodically snapshots state and appends output records
- **Resume Loader**: Restores frontier + visited set from `state.json`

---

## 5. Functional Requirements

### 5.1 Crawl Initialization

- Must accept:
  - `origin_url` (required)
  - `max_depth` (required)
  - `worker_count` (optional; default provided)
  - `queue_capacity` (optional; default provided)
- Must create a unique `crawl_job_id`
- Must initialize output directory and files for the job

### 5.2 URL Traversal

- Must perform BFS traversal semantics by depth
- Every URL must carry a depth value
- URLs with `depth > max_depth` must not be fetched

### 5.3 URL Normalization and Deduplication

- URLs must be normalized before deduplication and enqueue checks
- Each normalized URL can be visited at most once per job
- Duplicate discoveries must not trigger duplicate fetches

### 5.4 Concurrency and Thread Safety

- Multiple workers must fetch/process concurrently
- Shared structures must be thread-safe
- Workers must not corrupt frontier/visited/output state

### 5.5 Queue Management and Backpressure

- Frontier must be bounded via configurable capacity
- System must define overflow behavior (drop/reject policy)
- Crawler must avoid deadlock/livelock under pressure

### 5.6 Fetching and Parsing

- Must fetch via HTTP(S) with timeout controls
- For valid HTML responses, parser must extract:
  - Visible text content
  - Outgoing links (absolute/normalized where possible)
- Non-reachable or invalid pages must be recorded as failures, not fatal errors

### 5.7 Persistence

- Must persist periodic checkpoint to `state.json` including:
  - Job metadata/status
  - Frontier snapshot
  - Visited snapshot
  - Counters and timestamps
- Must append crawled page records to `pages.jsonl`
- Must append discovered edge records to `edges.jsonl`

### 5.8 Resume

- Must restore crawl from `state.json`
- Must preserve deduplication guarantees after resume
- Must continue from saved frontier without replaying completed work

### 5.9 Shutdown Handling

- Must support graceful interrupt handling
- On shutdown signal, in-flight progress should be checkpointed
- Job status must transition to a terminal or resumable state

---

## 6. Non-Functional Requirements

### 6.1 Performance

- Throughput should improve with worker count up to machine/network limits
- Checkpointing should not significantly stall crawl throughput

### 6.2 Reliability

- Crawler should survive transient network failures and malformed pages
- Individual page failures must not crash the job

### 6.3 Correctness and Consistency

- No duplicate URL visits within a job
- BFS depth constraints must be enforced
- Persisted state must be internally consistent and resumable

### 6.4 Resource Control

- Memory growth must be bounded by queue + visited management strategy
- Disk writes should be append-oriented and recoverable

---

## 7. Data Model

### 7.1 Page Record (`pages.jsonl`)

Each line should include:

- `crawl_job_id`
- `origin_url`
- `url`
- `depth`
- `fetched_at` (timestamp)
- `content_text`
- `outgoing_links` (array)
- `http_status` (if available)
- `error` (nullable)

### 7.2 Edge Record (`edges.jsonl`)

Each line should include:

- `crawl_job_id`
- `origin_url`
- `source_url`
- `target_url`
- `source_depth`
- `discovered_at` (timestamp)

### 7.3 State Snapshot (`state.json`)

State should include:

- `crawl_job_id`
- `origin_url`, `max_depth`, runtime settings
- `status` (`running`, `paused`, `completed`, `failed`)
- `frontier` (pending URL entries with depth)
- `visited` (normalized URLs)
- counters (`fetched_count`, `error_count`, `dropped_count`)
- checkpoint metadata (`last_checkpoint_at`, version)

---

## 8. Success Metrics and Acceptance Criteria

### Must-Have Acceptance Criteria

- Each URL is fetched at most once per job after normalization
- Depth constraint `k` is always enforced
- Crawler remains operational under high queue pressure (no deadlock)
- Resume reproduces expected continuation without duplicates
- `pages.jsonl` and `edges.jsonl` are valid append-only JSONL outputs

### Operational Metrics

- Pages fetched per minute
- Parse success/failure ratio
- Queue utilization and drop rate
- Resume recovery time from checkpoint

---

## 9. Risks and Mitigations

- **Queue saturation** -> Use bounded queue policy + dropped URL metrics
- **Large visited state** -> Consider compact serialization and periodic flush tuning
- **Checkpoint overhead** -> Tune checkpoint interval and use efficient IO writes
- **Network variability** -> Timeout/retry strategy with per-page failure isolation
- **Parsing inconsistencies** -> Harden parser and normalize extraction behavior
