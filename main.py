#!/usr/bin/env python3
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
JAR = ROOT / "target" / "crawler-1.0.0-jar-with-dependencies.jar"


def ensure_build():
    if JAR.exists():
        return
    if shutil.which("mvn") is None:
        raise RuntimeError(
            "Maven (mvn) is required to build the Java crawler. "
            "Install Maven (e.g. brew install maven) or pre-build the jar."
        )
    subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=ROOT, check=True)


def run_java(java_args):
    ensure_build()
    # Replace this process with java so parents (e.g. local_api) signal the JVM, not a stub python.
    if os.name == "nt":
        cmd = ["java", "-jar", str(JAR)] + java_args
        completed = subprocess.run(cmd, cwd=ROOT)
        sys.exit(completed.returncode)
    java = shutil.which("java")
    if not java:
        print("java not found in PATH", file=sys.stderr)
        sys.exit(127)
    argv = [java, "-jar", str(JAR)] + java_args
    try:
        os.execv(java, argv)
    except OSError as e:
        print(f"failed to exec java: {e}", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Crawler HW launcher")
    sub = parser.add_subparsers(dest="command", required=True)

    p_index = sub.add_parser("index", help="start a new crawl job")
    p_index.add_argument("url")
    p_index.add_argument("k", type=int)
    p_index.add_argument("workers", nargs="?", type=int, default=8)
    p_index.add_argument("queue_capacity", nargs="?", type=int, default=1000)

    p_resume = sub.add_parser("resume", help="resume a saved crawl job")
    p_resume.add_argument("job_id")

    p_status = sub.add_parser("status", help="read current crawl job state")
    p_status.add_argument("job_id")

    p_verify = sub.add_parser("verify", help="validate crawl output consistency")
    p_verify.add_argument("job_id")

    args = parser.parse_args()
    if args.command == "index":
        run_java(
            [
                "index",
                args.url,
                str(args.k),
                str(args.workers),
                str(args.queue_capacity),
            ]
        )
    elif args.command == "resume":
        run_java(["resume", args.job_id])
    elif args.command == "status":
        run_java(["status", args.job_id])
    elif args.command == "verify":
        run_java(["verify", args.job_id])


if __name__ == "__main__":
    main()
