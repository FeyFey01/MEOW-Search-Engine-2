# Multi-Agent Workflow: Project 1 to Project 2

## 1. Project Context

This project is the evolution of the original crawler/search system from Project 1 into a **multi-agent AI workflow** for Project 2.

Project 1 focused on building a working crawler and search prototype with standard AI assistance.  
Project 2 kept the same core product requirements, but added a new constraint: the system had to be developed through a **clear multi-agent workflow**, where different AI agents were responsible for different parts of the design, implementation, review, and decision-making process.

The final runtime product is still a local crawler + search engine.  
The multi-agent requirement was satisfied through the **development process**, not by implementing a multi-agent runtime inside the product itself.

---

## 2. Why a Multi-Agent Workflow Was Used

A single-agent approach was not ideal for this project because the system had multiple separate concerns:

- crawler architecture and stop/resume behavior
- search and retrieval quality
- implementation safety
- concurrency and correctness review
- final decision-making across conflicting recommendations

Using distinct AI roles reduced bias, improved review separation, and made the development process easier to validate.  
This was especially useful because crawler correctness problems, search quality problems, and implementation safety problems are not the same kind of problem and should not be handled by the same role.

---

## 3. The Baseline (Project 1)

The starting point was a single-machine crawler/search system with:

- a bounded crawl frontier
- multi-threaded crawling
- visited URL tracking
- file-based persistence
- a local API/control layer
- search over crawl output
- resume and stop/pause behavior

At a high level:

- **Crawl** = concurrent URL exploration with depth limits
- **Persistence** = file-based snapshots and incremental logs
- **Search** = query layer over crawl output
- **Control layer** = Python API managing JVM crawler lifecycle

The architecture was functional, but there were important risks:

- stop/pause behavior was not fully consistent
- shared mutable state was too broad
- resume behavior could fail under edge cases
- search was too close to flat-file scanning
- concurrency-sensitive behavior needed deeper validation

---

## 4. Agents Used in This Project

The project was developed with four distinct AI roles.

### 4.1 GPT — System Architect and Agent Orchestrator

GPT handled the system-level reasoning and orchestration layer.

#### Initial prompt used
> **"You are acting as a Senior System Architect reviewing a web crawler + search engine project."**

#### Responsibilities
- review the overall architecture
- identify structural and concurrency risks
- define the safest minimal-change strategy
- decide which agent should handle each part of the work
- interpret and compare outputs from the other agents
- generate prompts for the next agent in the workflow
- make the final engineering decision when agents disagreed

#### Output focus
- system architecture review
- bounded crawl design
- stop/resume consistency
- concurrency risks
- search separation
- decision-making and orchestration

GPT acted as the coordination layer for the whole process.

---

### 4.2 Gemini — Search Quality and Retrieval Engineer

Gemini focused on the search side of the system.

#### Initial prompt used
> **"You are acting as a Search Quality and Retrieval Engineer for a web crawler + search engine project."**

#### Responsibilities
- review search and indexing quality
- identify retrieval bottlenecks
- propose ranking and indexing improvements
- evaluate search performance and relevance
- recommend better search-side structure and query handling

#### Output focus
- relevance scoring
- ranking strategy
- index structure improvements
- search performance bottlenecks
- separation of crawl vs search concerns

Gemini was used specifically to analyze the search side as a retrieval problem, not as a crawler problem.

---

### 4.3 Copilot / Claude Haiku 4.5 — Backend Implementation Agent

Copilot handled implementation work under strict constraints.

#### Initial prompt used
> **"You are acting as the Backend Implementation Agent for an existing web crawler + search engine project."**

#### Responsibilities
- apply only approved minimal fixes
- avoid architectural rewrites
- keep changes localized and rollback-safe
- implement Python and Java patches carefully
- preserve existing behavior unless a bug required correction

#### Output focus
- atomic state writes
- safe resume handling
- shutdown correctness
- small correctness patches in Python and Java
- rollback-safe implementation

Copilot was used as the implementation agent, not as a design authority.

---

### 4.4 Cursor — Reliability and Correctness Reviewer

Cursor acted as the final code-level reviewer.

#### Initial prompt used
> **"You are acting as the Reliability and Review Agent."**

#### Responsibilities
- inspect the actual code after implementation
- detect hidden race conditions and edge cases
- verify that patches were safe in the real codebase
- evaluate whether suggested changes were truly minimal
- approve or reject implementation details before pushing

#### Output focus
- crash risks
- state corruption risks
- race conditions
- resume inconsistencies
- incorrect crawler behavior

Cursor was the final correctness gate before changes were accepted.

---

## 5. How the Agents Communicated

The workflow was deliberate and sequential.

### Communication model
1. GPT reviewed the architecture.
2. GPT generated a focused prompt for Gemini.
3. Gemini reviewed search/retrieval quality and suggested improvements.
4. GPT converted those findings into a constrained implementation prompt for Copilot.
5. Copilot implemented the approved minimal patches.
6. Cursor reviewed the actual code and found any remaining correctness issues.
7. GPT interpreted Cursor’s findings and decided whether additional small patches were needed.
8. The final accepted changes were pushed only after the review loop was complete.

This created a real review-and-decision chain rather than a single-agent coding process.

---

## 6. Actual Prompting Strategy

The agents were not abstract labels; they were instantiated as **LLM roles via structured prompts**.

### GPT prompt style
GPT was used with an architecture-review prompt, for example:

- review the current web crawler/search project
- identify structural risks
- propose minimal safe improvements
- do not rewrite the whole system
- focus on system-level architecture and coordination

### Gemini prompt style
Gemini was used with a search-quality prompt, for example:

- focus only on retrieval quality and search design
- identify ranking and indexing problems
- do not redesign the crawler architecture
- propose improvements for search relevance and performance

### Copilot prompt style
Copilot was used with a constrained implementation prompt, for example:

- implement only approved fixes
- do not refactor architecture
- do not add new features
- keep changes minimal and rollback-safe
- touch only the specific files required

### Cursor prompt style
Cursor was used with a correctness-review prompt, for example:

- review actual code changes
- find hidden concurrency risks
- evaluate crash/corruption behavior
- report only real bugs or safety issues
- do not suggest new features or major rewrites

This structure made each agent’s responsibility concrete and measurable.

---

## 7. Major Decision Phases

### Phase 1 — Architecture Review (GPT)

GPT reviewed the crawler/search architecture and identified the main weaknesses:

- stop and depth-limit enforcement was not centralized
- shared mutable state was too broad
- persistence was coupled too closely to worker execution
- search was too tightly coupled to crawl internals
- concurrency-sensitive behavior needed stronger review coverage

This phase established the system-level risks.

---

### Phase 2 — Search and Retrieval Review (Gemini)

Gemini reviewed the search side of the project.

Key observations:

- the search path behaved too much like a flat-file grep
- relevance ranking was weak
- the retrieval layer needed clearer separation from crawling
- search performance would not scale well without a better index structure

The larger search redesign was noted, but deferred because the immediate priority was correctness and lifecycle safety.

---

### Phase 3 — Minimal Correctness Fixes (Copilot)

Copilot implemented constrained fixes only after they were approved.

#### Python (`local_api.py`)
- atomic `state.json` writes using a temporary file and `os.replace`
- re-reading persisted state before merging shutdown flags
- fixing stale-state fallback with an explicit `is not None` check
- safer manual stop/pause state updates

#### Java (`CrawlerJobRunner.java`)
- safe resume handling when the saved frontier exceeded queue capacity
- replacing unsafe `addAll(pending)` logic with bounded `offer()` behavior
- dropping excess resume items gracefully instead of crashing

These changes were intentionally narrow and rollback-safe.

---

### Phase 4 — Reliability Review (Cursor)

Cursor reviewed the actual implementation and confirmed that:

- no new worker-thread races were introduced
- atomic write safety was preserved
- resume overflow handling was safe
- the patches did not break crawler logic
- the remaining risk was a coordination issue between JVM and Python state ownership, not a crash or corruption bug

Cursor also identified small edge cases:

- an empty dict should not be treated as a valid fresh-state fallback
- manual stop/pause needed a state update path as well

Those were then corrected with small additional patches.

---

### Phase 5 — Final Orchestration and Acceptance (GPT)

GPT evaluated the outputs from the other agents and made the final decision on what to keep.

At this stage, GPT ensured that:

- the agent outputs were consistent with each other
- no unnecessary architectural expansion was introduced
- the final code remained minimal and stable
- the fixes matched the original scope of the assignment

---

## 8. Final Outcome

The multi-agent process produced a safer and more stable system.

### Improvements achieved
- atomic persistence for `state.json`
- reduced risk of corrupted state during shutdown
- safer resume behavior for bounded frontier overflow
- better consistency between runtime state and persisted state
- stronger stop/pause lifecycle behavior
- clearer separation between architecture review, search review, implementation, and correctness validation

---

## 9. Remaining Risk

The main remaining limitation is a **last-writer-wins coordination risk** between:

- the JVM crawler checkpoint writes
- the Python control-layer shutdown writes

This does not create file corruption anymore, but it can still cause temporary state inconsistency in edge cases if both sides write around the same time.

That tradeoff was accepted because the project required a minimal, local, single-machine solution rather than a full distributed state-coordination system.

---

## 10. Explicit Non-Goals

The following were intentionally not implemented during the stabilization pass:

- full database migration to SQLite
- major search ranking overhaul
- distributed crawling
- large-scale architecture redesign
- heavyweight locking or coordination systems
- deep changes to visited/fetched semantics

These were deferred to preserve scope and avoid unnecessary regressions.

---

## 11. Why This Satisfies the Multi-Agent Requirement

This project satisfies the multi-agent requirement because:

- the agents had distinct responsibilities
- they did not all solve the same problem
- their outputs were reviewed and compared
- GPT coordinated the communication between them
- implementation decisions were made after evaluation, not automatically
- the final system reflects a documented multi-agent development process

The system itself does not need to run as a multi-agent runtime.  
What matters is that the development process clearly demonstrates multi-agent collaboration, and this project does.

---

## 12. Summary

This project evolved from a basic crawler/search prototype into a more stable local system through a structured multi-agent workflow.

The process was:

- **GPT** for architecture and orchestration
- **Gemini** for search/retrieval analysis
- **Copilot / Claude Haiku** for constrained implementation
- **Cursor** for correctness and reliability review
- **GPT** again for final coordination and decision-making

That workflow was used to improve the system safely, keep the changes minimal, and document the engineering process clearly.