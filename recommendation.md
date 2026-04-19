## Production Roadmap

To deploy this crawler in a high-scale production environment, the system should evolve from a single-machine architecture into a **distributed, horizontally scalable system**. The frontier queue and visited set should be externalized into distributed data stores such as **Apache Kafka** (for task streaming) and **Redis or Cassandra** (for deduplication and state tracking), enabling multiple crawler nodes to operate concurrently without overlap. The crawling workers should be containerized (e.g., using Docker) and orchestrated via **Kubernetes**, allowing dynamic scaling based on workload. The persistence layer should transition from local JSONL files to durable, queryable storage such as **cloud object storage (e.g., S3)** or distributed file systems, while metadata and checkpoints can be stored in a structured database. Additionally, implementing **rate limiting, robots.txt compliance, and domain-based politeness policies** will be necessary to ensure responsible and sustainable crawling at scale.

From an operational perspective, the system should incorporate **robust observability, fault tolerance, and recovery mechanisms**. Centralized logging (e.g., ELK stack), metrics collection (e.g., Prometheus), and monitoring dashboards (e.g., Grafana) should be added to track crawl throughput, error rates, queue pressure, and system health. Checkpointing should be optimized using incremental or snapshot-based approaches to reduce overhead, and job coordination should be managed via a distributed scheduler or workflow engine. To improve reliability, retry strategies, circuit breakers, and backoff mechanisms should be introduced for network operations. Finally, the architecture should be modularized to cleanly separate crawling, indexing, and future search components, ensuring that subsequent phases (indexing and ranking) can integrate seamlessly without requiring major redesign.

---

## Current Stability Updates (Implemented)

The following near-term correctness fixes were implemented to stabilize local stop/resume behavior before larger production migration:

- **Atomic state persistence in API layer**: `state.json` writes now use temp-file + `os.replace` to reduce corruption/race risk.
- **Fresh state re-read before overwrite**: max-URL auto-finish and manual stop paths re-load latest state before setting terminal fields.
- **Safe resume queue admission**: resume flow uses bounded `offer` semantics and logs dropped tasks instead of crashing on queue overflow.
- **PID-based process control**: local API tracks `runner.pid` and `jvm.pid` for resilient stop/pause/cancel even when in-memory process metadata is missing.
- **Stronger worker stop semantics**: crawler shutdown path now prioritizes interrupt-aware worker termination before final state persist to reduce post-stop counter drift.

These updates keep the current single-machine architecture more reliable while preserving compatibility with the long-term distributed roadmap above.
