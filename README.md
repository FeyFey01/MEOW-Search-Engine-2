# Web Crawler and Search System

This project implements a single-machine web crawler and search system.
The crawler explores web pages using a depth-limited breadth-first search (BFS), builds an inverted index, and provides a web interface to monitor crawling and perform keyword-based search.

<img width="1280" height="676" alt="overview" src="https://github.com/user-attachments/assets/2d6e4c8f-227d-4501-a728-b1fcd4e481f9" />

---

## Overview

The system consists of three main components:

* **Crawler Engine (Java)**
  Handles crawling, URL normalization, deduplication, HTML parsing, and indexing.

* **API Layer (Python)**
  Manages crawler processes, exposes HTTP endpoints, and performs search over the index.

* **User Interface (HTML/JS)**
  Allows users to start crawlers, monitor progress, and search indexed data.

---

## Requirements

* Java 17+ (`java -version`)
* Maven 3.8+ (`mvn -version`)
* Python 3.9+ (`python3 --version`)

---

## Setup (Clone from GitHub)

### 1. Clone the repository

```bash
git clone https://github.com/FeyFey01/MEOW-Search-Engine.git
cd MEOW-Search-Engine
```

If the crawler is inside a subfolder:

```bash
cd crawler
```

---

### 2. Build (optional)

```bash
mvn -q -DskipTests package
```

This generates:

```
target/crawler-1.0.0-jar-with-dependencies.jar
```

---

## Running the System

Start the local API server:

```bash
python3 local_api.py --host 127.0.0.1 --port 3600
```

Then open in your browser:

* Create crawler:
  [http://127.0.0.1:3600/ui/crawler.html](http://127.0.0.1:3600/ui/crawler.html)

* Status page:
  [http://127.0.0.1:3600/ui/status.html](http://127.0.0.1:3600/ui/status.html)

* Search page:
  [http://127.0.0.1:3600/ui/search.html](http://127.0.0.1:3600/ui/search.html)

> Note: Running `python3 local_api.py` without specifying `--host` and `--port` may not correctly serve the UI in some environments.

---

## Usage

### 1. Create a crawler

* Enter a seed URL
* Set depth and parameters
* Start crawling

<img width="1280" height="380" alt="crawler" src="https://github.com/user-attachments/assets/e2b57be4-ce7e-46d4-a2ff-5075eab5249a" />

---

### 2. Monitor crawler status

* View visited URLs and queue
* Inspect logs and runtime state

<img width="1067" height="666" alt="status" src="https://github.com/user-attachments/assets/786858dd-4b12-44ac-ba9e-1a9bbbb13274" />

---

### 3. Search indexed content

* Enter a keyword
* View ranked results

<img width="565" height="574" alt="search" src="https://github.com/user-attachments/assets/956cee50-cba1-4b97-a836-4bc5d5bf645e" />

---

## Commands

```bash
python3 main.py index <url> <k>
python3 main.py index <url> <k> <workers> <queue_capacity>
python3 main.py status <job_id>
python3 main.py resume <job_id>
```

Examples:

```bash
python3 main.py index https://example.com 2
python3 main.py status <job_id>
python3 main.py resume <job_id>
```

---

## Data Storage

Each crawl job creates:

```
data/<job_id>/
```

Contents:

* `pages.jsonl` — crawled pages
* `edges.jsonl` — discovered links
* `p.data` — inverted index
* `state.json` — crawl state
* `runtime.log` — logs

---

## Search Model

* Exact word matching is used
* Each index entry format:

```
word url origin depth frequency
```

Example:

```
latina https://www.wikipedia.org/ https://www.wikipedia.org/ 0 2
```

* Ranking formula:

```
score = frequency * 10 + 1000 - depth * 5
```
