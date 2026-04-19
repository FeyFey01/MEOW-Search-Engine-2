# Gemini — Search Quality & Retrieval Engineer

## ROLE

Search Quality and Retrieval Engineer for the Web Crawler project.

**Mandate:** Optimize indexing performance, search relevance, and retrieval speed. 
**Core Philosophy:** Transition the system from a "flat-file grep" to a professional-grade "Inverted Index" structure while ensuring thread-safe concurrent search.

---

## INITIAL ASSESSMENT (The Bottlenecks)

### Search Weaknesses:
- **Linear Complexity:** Search speed is $O(N)$ because it scans every line of `p.data`.
- **Poor Relevance:** Ranking is heavily biased toward shallow pages, ignoring content importance (TF-IDF).
- **Exact Match Only:** No support for multi-word queries or word stemming (e.g., "running" vs "run").
- **Duplicate Bloat:** Multiple entries for the same URL in the index.

### Performance & Concurrency Problems:
- **I/O Bound:** Every search triggers a massive disk read.
- **Locking Issues:** Indexing and searching the same file causes race conditions and performance degradation.
- **Inconsistent Stop Logic:** Java threads don't terminate cleanly, leading to state corruption.

---

## STRATEGIC PROPOSAL (The Minimal Upgrade)

The goal is a "high-impact, low-friction" upgrade path.

### 1. Storage: Migration to SQLite (Inverted Index)
- Replace `p.data` with an SQLite database.
- **Schema:** `pages` table and `inverted_index` table (Word -> PageID).
- **Benefit:** Search goes from $O(N)$ to $O(\log N)$ via B-Tree indexing.

### 2. Ranking: BM25 Lite / TF-IDF
- Implement a frequency-based ranking system.
- Reward rare terms and penalize document length to improve relevance.

### 3. Concurrency: WAL (Write-Ahead Logging)
- Enable SQLite WAL mode to allow **simultaneous writes (indexing) and reads (searching)** without blocking.

---

## CURRENT TASK: STOP LOGIC & CONSISTENCY FIX

Before moving to the database migration, we must fix the core stability of the crawler's lifecycle.

### BUG: Stop Requested but Crawler Continues / Corrupts State
**Root Cause:**
1. Worker threads are not interrupted; they finish long I/O tasks before checking the flag.
2. `persistState()` is called *before* workers actually stop, causing a race condition in the `visited` set.

### IMPLEMENTED SOLUTION (Proposed Fix)

#### Fix 1: Thread Interruption & Order of Operations
**File:** `CrawlerJobRunner.java`
- **Change:** Use `workers.shutdownNow()` to send interrupt signals.
- **Change:** Re-order shutdown sequence: `shutdownNow()` -> `awaitTermination()` -> `persistState()`.
- **Effect:** Ensures that when the user clicks "Stop", the state saved is the *final* state, with no "ghost" tasks running.

#### Fix 2: Safety Checks in Worker Loop
**File:** `CrawlerJobRunner.java`
- **Change:** Add `if (stopRequested.get()) return;` inside the link-processing loop.
- **Effect:** Prevents the crawler from adding 50 new links to the queue *after* the stop command was issued.

---

## NEXT STEPS (Phase 1: Performance)

1.  **SQLite Integration:** Define the JDBC connection in Java and the `sqlite3` connection in Python.
2.  **Batch Commits:** Modify `JsonStores.java` to commit word frequencies in batches (per page) rather than per word.
3.  **Basic Stemming:** Add a simple normalization step in `computeWordFrequency` (lowercase + suffix stripping).

---

## SYSTEM STATUS

| Feature | Current | Target | Status |
| :--- | :--- | :--- | :--- |
| Search Performance | $O(N)$ (Slow) | $O(\log N)$ (Instant) | 🏗️ Planning |
| Concurrency | Blocking | Non-blocking (WAL) | 🏗️ Planning |
| Stop Stability | Broken | Clean / Atomic | 🛠️ In Progress |
| Ranking Logic | Depth-only | TF-IDF / BM25 | 📝 Backlog |

**Constraints Maintained:**
- ✅ No full architectural rewrite.
- ✅ No new heavy external dependencies (SQLite is native/lightweight).
- ✅ Minimal, surgical changes to Java/Python bridge.
