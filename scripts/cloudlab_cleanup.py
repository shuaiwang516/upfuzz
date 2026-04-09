#!/usr/bin/env python3
"""
CloudLab Cleanup — wipe all RupFuzz state from CloudLab machines,
returning them to a fresh state.

Usage:
    python3 scripts/cloudlab_cleanup.py
    python3 scripts/cloudlab_cleanup.py --machines 1-6
    python3 scripts/cloudlab_cleanup.py --dry-run
"""

import argparse
import os
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MACHINE_LIST = SCRIPT_DIR / "cloudlab-runner" / "machine_list.txt"
SSH_OPTS = ["-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=no"]

CLEANUP_SCRIPT = """#!/bin/bash
# Kill all user processes related to RupFuzz
tmux kill-server 2>/dev/null || true
pkill -f 'FuzzingServer|FuzzingClient|run_rolling_fuzzing|start_server|start_clients|gradlew' 2>/dev/null || true
sleep 1

# Stop and remove ALL Docker containers
docker ps -aq | xargs -r docker stop 2>/dev/null || true
docker ps -aq | xargs -r docker rm -f 2>/dev/null || true

# Remove ALL Docker images
imgs=$(docker images -aq 2>/dev/null)
if [ -n "$imgs" ]; then
    echo "$imgs" | xargs docker rmi -f 2>/dev/null || true
fi

# Prune everything (networks, volumes, build cache)
docker system prune -af --volumes 2>/dev/null || true

# Remove all RupFuzz repos and data
sudo rm -rf /users/swang516/xlab 2>/dev/null || true

# Remove launcher temp files and logs
rm -f ~/fuzz_campaign*.log ~/run_fuzz*.sh ~/docker_build*.log 2>/dev/null || true
rm -f /tmp/_launcher_*.sh /tmp/_launcher_*_done /tmp/mon.sh 2>/dev/null || true

# Verify clean state
img_count=$(docker images -q 2>/dev/null | wc -l)
ctr_count=$(docker ps -aq 2>/dev/null | wc -l)
if [ -d /users/swang516/xlab ]; then
    repo_status="EXISTS"
else
    repo_status="gone"
fi
echo "CLEAN: images=$img_count containers=$ctr_count repo=$repo_status"
"""


def parse_machine_list(path: Path) -> list:
    machines = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        target = line.split()[-1]
        if "@" in target:
            user, host = target.split("@", 1)
        else:
            user, host = "swang516", target
        machines.append((user, host))
    return machines


def clean_one(user, host):
    """SCP the cleanup script to the machine, then execute it."""
    short = host.split(".")[0]
    with tempfile.NamedTemporaryFile(
            mode="w", suffix=".sh", delete=False, prefix="cleanup_") as f:
        f.write(CLEANUP_SCRIPT)
        local_script = f.name
    try:
        # Copy script to remote
        subprocess.run(
            ["scp"] + SSH_OPTS[:4] + [local_script, f"{user}@{host}:/tmp/_cleanup.sh"],
            capture_output=True, timeout=30)
        # Execute remotely
        r = subprocess.run(
            ["ssh"] + SSH_OPTS + [f"{user}@{host}", "bash /tmp/_cleanup.sh"],
            capture_output=True, text=True, timeout=120)
        output = (r.stdout + r.stderr).strip()
        last_line = output.split("\n")[-1] if output else "(no output)"
        ok = "CLEAN:" in last_line
        return short, last_line, ok
    except subprocess.TimeoutExpired:
        return short, "timeout", False
    except Exception as e:
        return short, str(e), False
    finally:
        os.unlink(local_script)


def main():
    p = argparse.ArgumentParser(description="Wipe all RupFuzz state from CloudLab machines")
    p.add_argument("--machine-list", type=Path, default=MACHINE_LIST)
    p.add_argument("--machines", type=str, default=None, help="Range e.g. '1-6'")
    p.add_argument("--dry-run", action="store_true", help="Show plan without executing")
    args = p.parse_args()

    machines = parse_machine_list(args.machine_list)
    if not machines:
        sys.exit("No machines found")

    if args.machines:
        start, end = map(int, args.machines.split("-"))
        machines = machines[start - 1:end]

    print(f"\n{'='*50}")
    print(f"  CloudLab Cleanup — {len(machines)} machines")
    print(f"{'='*50}")
    for i, (user, host) in enumerate(machines, 1):
        print(f"  {i:2d}. {user}@{host}")
    print()

    if args.dry_run:
        print("  [DRY RUN] Would execute on each machine:")
        print("    - Kill tmux, FuzzingServer/Client, gradlew")
        print("    - Stop + remove all Docker containers")
        print("    - Remove all Docker images + prune")
        print("    - sudo rm -rf /users/swang516/xlab")
        print("    - Remove temp files and logs")
        return

    print("  Cleaning all machines in parallel...")
    start_time = time.time()

    with ThreadPoolExecutor(max_workers=len(machines)) as pool:
        futures = {
            pool.submit(clean_one, user, host): (user, host)
            for user, host in machines
        }
        for fut in as_completed(futures):
            short, msg, ok = fut.result()
            status = "OK" if ok else "FAIL"
            print(f"  [{status}] {short:8s}: {msg}")

    elapsed = int(time.time() - start_time)
    print(f"\n  Done in {elapsed}s.")


if __name__ == "__main__":
    main()
