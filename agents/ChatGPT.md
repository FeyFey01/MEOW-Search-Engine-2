# GPT.md - Multi-Agent Work Log

## Role

This file documents the reasoning, decisions, and execution flow performed by GPT as part of a multi-agent system working on this repository.

My roles in this project:

- **System Architect**
- **Agent Orchestrator**

---

## Purpose

This document is maintained to:

- Track system-level architectural reasoning
- Record multi-agent collaboration flow (GPT, Gemini, Copilot, Cursor)
- Preserve prompt → response → implementation traceability
- Document correctness fixes and validation steps
- Provide a clear history of why decisions were made

---

## System Context

The project is a **single-machine crawler + search engine system** with:

- bounded crawl frontier
- multi-threaded worker execution
- visited URL tracking
- file-based persistence (`state.json`, `pages.jsonl`, `edges.jsonl`)
- resume capability after interruption
- local search API over crawled data

At a high level:

- Crawl = concurrent URL exploration with depth limits
- Persistence = file-based snapshot + incremental logs
- Search = index-backed query layer over crawl output
- Control layer = Python API managing JVM crawler lifecycle

---

## Agent Orchestration Model

This project was executed using a multi-agent workflow with clearly separated responsibilities.

### 1. GPT — System Architect & Orchestrator

Responsibilities:

- Global system architecture review
- Identifying structural and concurrency risks
- Defining safe minimal-change strategy
- Coordinating other agents via structured prompts
- Evaluating consistency across agent outputs
- Ensuring correctness over feature expansion

---

### 2. Gemini — Search & Retrieval Specialist

Responsibilities:

- Review of search/index design
- Retrieval quality evaluation
- Suggestions for ranking and indexing improvements
- Identification of search-side architectural improvements

Output focus:

- relevance
- ranking strategy
- index structure improvements
- separation of crawl vs search concerns

---

### 3. Copilot — Implementation Agent

Responsibilities:

- Implement minimal, constrained patches
- Follow strict “surgical fix” instructions
- Avoid architectural rewrites
- Preserve backward compatibility

Typical changes:

- atomic state writes (`tempfile + os.replace`)
- bounded frontier safety fixes
- resume stability improvements
- small correctness patches in Python/Java

---

### 4. Cursor — Reliability & Correctness Reviewer

Responsibilities:

- Deep code-level correctness review
- Concurrency/race-condition detection
- Validation of Copilot patches in real code context
- Identifying hidden edge cases
- Fixing or refining issues when necessary
- Final safety evaluation before push to main

Cursor acted as the **final correctness gate** before changes were accepted.

---

## Execution Flow (Actual Timeline)

### Step 1 — System Architecture Review (GPT)

- Evaluated crawler + search architecture
- Identified:
  - weak stop semantics
  - shared mutable state risks
  - persistence coupling issues
  - lack of centralized crawl control
  - inconsistent resume behavior under concurrency

---

### Step 2 — Search & Retrieval Review (Gemini)

- Evaluated search/indexing design
- Provided improvements for:
  - retrieval quality
  - ranking strategy
  - separation between crawl and search layers
- Larger redesigns were deferred to keep scope minimal

---

### Step 3 — Implementation Phase (Copilot)

Copilot applied constrained fixes:

#### Python (`local_api.py`)
- Atomic `state.json` writes using temp file + `os.replace`
- Re-read of state before merging shutdown flags
- Fix for stale state overwrite risk (`fresh is not None` check)
- Manual stop/pause state synchronization improvements

#### Java (`CrawlerJobRunner.java`)
- Safe resume handling for oversized frontier
- Replaced unsafe `addAll(pending)` with bounded `offer()` loop
- Dropped overflow tasks safely with logging
- Preserved queue capacity constraints

---

### Step 4 — Reliability Review (Cursor)

Cursor performed deep validation of applied patches:

Confirmed:

- No new worker-thread race conditions introduced
- Atomic write model is correct and improves safety
- Resume logic is stable under overflow conditions
- enqueued/frontier semantics remain consistent
- No crawler logic corruption introduced

Also identified:

- subtle edge case in `fresh if fresh else state`
- need for strict `is not None` check instead of truthiness fallback
- additional safety improvement for stop/pause state synchronization

These were corrected with minimal additional patches.

---

### Step 5 — Orchestration Refinement (GPT)

GPT ensured:

- each agent’s output was correctly interpreted
- Copilot implementation matched intended constraints
- Cursor review results were integrated safely
- no unsafe architectural expansion occurred
- fixes remained minimal, reversible, and bounded

GPT acted as the **coordination layer between agents**, generating structured prompts and validating consistency of outputs.

---

## Final System State After Fixes

### Improvements achieved:

- Safe atomic state persistence (Python side)
- Stable resume behavior under bounded queue constraints (Java side)
- Reduced risk of corrupted `state.json`
- Improved stop/pause lifecycle consistency
- Better separation of runtime state vs persisted snapshots
- More reliable concurrency behavior in crawl execution

---

## Known Remaining Risk

### Cross-process state coordination

There is still a **last-writer-wins risk** between:

- JVM crawler checkpoint writes
- Python control-layer shutdown updates

This does not cause corruption, but can cause:

- temporary state inconsistency
- slightly stale snapshot overwrites
- race between shutdown and final JVM checkpoint

This is a **coordination-level issue**, not a structural crash risk.

---

## Explicit Non-Goals (Preserved Scope)

The following were intentionally NOT implemented:

- full search engine redesign (BM25 / TF-IDF migration)
- database migration (SQLite / persistent index store)
- distributed crawling
- large-scale refactor of crawler architecture
- deep restructuring of worker model
- introduction of locking-heavy coordination systems

---

## Change Log

- v1: Initial multi-agent orchestration log
- v2: Aligned to actual executed agent workflow (GPT → Gemini → Copilot → Cursor → GPT coordination)
- v3: Cleaned and structured for repository-level documentation