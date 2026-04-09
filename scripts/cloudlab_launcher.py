#!/usr/bin/env python3
"""
CloudLab Launcher — deploys and runs rolling-upgrade fuzzing campaigns
on CloudLab machines.

Usage examples:
    # Full deploy + launch on all 12 machines (first 6: mode 5, second 6: mode 6)
    python3 scripts/cloudlab_launcher.py deploy --timeout-sec 43200

    # Launch only (skip setup/build, machines already prepared)
    python3 scripts/cloudlab_launcher.py launch --timeout-sec 43200

    # Monitor running campaigns
    python3 scripts/cloudlab_launcher.py monitor

    # Stop all campaigns
    python3 scripts/cloudlab_launcher.py stop

    # Download results
    python3 scripts/cloudlab_launcher.py download --dest /mnt/ssd/rupfuzz/cloudlab-results/apr10

    # Custom: only first 6 machines, mode 5
    python3 scripts/cloudlab_launcher.py deploy --machines 1-6 --mode 5
"""

import argparse
import json
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT_DIR = SCRIPT_DIR.parent
MACHINE_LIST = SCRIPT_DIR / "cloudlab-runner" / "machine_list.txt"

SSH_OPTS = "-o ConnectTimeout=15 -o StrictHostKeyChecking=no -o ServerAliveInterval=30"
REMOTE_WORKSPACE = "/users/swang516/xlab/rupfuzz"
REMOTE_REPO = f"{REMOTE_WORKSPACE}/upfuzz"
REMOTE_SSG = f"{REMOTE_WORKSPACE}/ssg-runtime"
UPFUZZ_REPO_URL = "https://github.com/shuaiwang516/upfuzz.git"
SSG_REPO_URL = "https://github.com/shuaiwang516/ssg-runtime.git"
BRANCH = "rupfuzz"

# Job table: same 6 version pairs for both mode 5 and mode 6
JOBS = [
    {"id": 1, "system": "cassandra", "original": "apache-cassandra-3.11.19",
     "upgraded": "apache-cassandra-4.1.10"},
    {"id": 2, "system": "cassandra", "original": "apache-cassandra-4.1.10",
     "upgraded": "apache-cassandra-5.0.6"},
    {"id": 3, "system": "hbase", "original": "hbase-2.5.13",
     "upgraded": "hbase-2.6.4"},
    {"id": 4, "system": "hbase", "original": "hbase-2.6.4",
     "upgraded": "hbase-4.0.0-alpha-1-SNAPSHOT"},
    {"id": 5, "system": "hdfs", "original": "hadoop-2.10.2",
     "upgraded": "hadoop-3.3.6"},
    {"id": 6, "system": "hdfs", "original": "hadoop-3.3.6",
     "upgraded": "hadoop-3.4.2"},
]


@dataclass
class MachineAssignment:
    host: str           # e.g. "pc22.cloudlab.umass.edu"
    user: str           # e.g. "swang516"
    job: dict           # entry from JOBS
    mode: int           # 5 or 6
    run_name: str       # e.g. "mode5-apache-cassandra-3.11.19-to-..."

    @property
    def ssh(self):
        return f"{self.user}@{self.host}"

    @property
    def label(self):
        return f"{self.host}:{self.job['system']}:mode{self.mode}"


def parse_machine_list(path: Path) -> List[tuple]:
    """Parse machine_list.txt, return list of (user, host) tuples."""
    machines = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        # Format: "ssh user@host" or just "user@host" or "host"
        parts = line.split()
        target = parts[-1]  # last token is user@host
        if "@" in target:
            user, host = target.split("@", 1)
        else:
            user, host = "swang516", target
        machines.append((user, host))
    return machines


def build_assignments(machines: List[tuple], modes: Optional[str],
                      machine_range: Optional[str],
                      tag: str, timeout_sec: int) -> List[MachineAssignment]:
    """Build machine-to-job assignments."""
    # Filter by range if specified
    if machine_range:
        start, end = map(int, machine_range.split("-"))
        machines = machines[start - 1:end]

    assignments = []

    if modes == "5":
        # All machines get mode 5
        for i, (user, host) in enumerate(machines):
            job = JOBS[i % len(JOBS)]
            run_name = f"{tag}-mode5-{job['original']}-to-{job['upgraded']}"
            assignments.append(MachineAssignment(host, user, job, 5, run_name))
    elif modes == "6":
        # All machines get mode 6
        for i, (user, host) in enumerate(machines):
            job = JOBS[i % len(JOBS)]
            run_name = f"{tag}-mode6-{job['original']}-to-{job['upgraded']}"
            assignments.append(MachineAssignment(host, user, job, 6, run_name))
    else:
        # Default: first 6 = mode 5, second 6 = mode 6
        for i, (user, host) in enumerate(machines):
            job = JOBS[i % len(JOBS)]
            mode = 5 if i < 6 else 6
            run_name = f"{tag}-mode{mode}-{job['original']}-to-{job['upgraded']}"
            assignments.append(MachineAssignment(host, user, job, mode, run_name))

    return assignments


def ssh_run(assignment: MachineAssignment, cmd: str,
            timeout: int = 600) -> tuple:
    """Run a command on a remote machine via SSH. Returns (assignment, stdout, ok)."""
    full_cmd = f"ssh {SSH_OPTS} {assignment.ssh} {repr(cmd)}"
    try:
        result = subprocess.run(
            full_cmd, shell=True, capture_output=True, text=True,
            timeout=timeout)
        ok = result.returncode == 0
        output = result.stdout + result.stderr
        return (assignment, output.strip(), ok)
    except subprocess.TimeoutExpired:
        return (assignment, "SSH command timed out", False)
    except Exception as e:
        return (assignment, str(e), False)


def parallel_ssh(assignments: List[MachineAssignment], cmd_fn,
                 timeout: int = 600, desc: str = ""):
    """Run commands on all machines in parallel."""
    print(f"\n{'='*60}")
    print(f"  {desc} ({len(assignments)} machines)")
    print(f"{'='*60}")

    results = []
    with ThreadPoolExecutor(max_workers=len(assignments)) as pool:
        futures = {}
        for a in assignments:
            cmd = cmd_fn(a)
            futures[pool.submit(ssh_run, a, cmd, timeout)] = a

        for future in as_completed(futures):
            a, output, ok = future.result()
            status = "OK" if ok else "FAIL"
            # Print last meaningful line
            last_line = output.strip().split("\n")[-1] if output.strip() else ""
            print(f"  [{status}] {a.label}: {last_line[:120]}")
            results.append((a, output, ok))

    failed = [r for r in results if not r[2]]
    if failed:
        print(f"\n  WARNING: {len(failed)} machine(s) failed:")
        for a, output, _ in failed:
            print(f"    {a.label}: {output[-200:]}")
    return results


# ─── Commands ────────────────────────────────────────────────

def cmd_deploy(assignments: List[MachineAssignment], args):
    """Full deployment: setup env → clone repos → build → Docker → launch."""

    # Step 1: Setup environment (install Docker, Java, etc.)
    print("\n[Step 1/5] Setting up environment on all machines...")
    parallel_ssh(assignments, lambda a: (
        f"if command -v docker >/dev/null 2>&1; then echo 'Docker already installed'; "
        f"else mkdir -p {REMOTE_WORKSPACE} && cd {REMOTE_WORKSPACE} && "
        f"git clone --depth 1 --branch {BRANCH} {UPFUZZ_REPO_URL} upfuzz && "
        f"cd upfuzz && sudo bash scripts/setup-cloudlab/setup_env.sh "
        f"--mode full --use-existing-repos "
        f"--skip-prebuild-check --skip-image-check --skip-build; fi"
    ), timeout=600, desc="Environment setup")

    # Step 2: Clone/update repos
    print("\n[Step 2/5] Cloning/updating repos...")
    parallel_ssh(assignments, lambda a: (
        f"mkdir -p {REMOTE_WORKSPACE} && "
        f"( if [ ! -d {REMOTE_REPO}/.git ]; then "
        f"cd {REMOTE_WORKSPACE} && git clone --depth 1 --branch {BRANCH} {UPFUZZ_REPO_URL} upfuzz; "
        f"else cd {REMOTE_REPO} && git fetch origin {BRANCH} --depth 1 && "
        f"git checkout {BRANCH} && git reset --hard origin/{BRANCH}; fi ) && "
        f"( if [ ! -d {REMOTE_SSG}/.git ]; then "
        f"cd {REMOTE_WORKSPACE} && git clone --depth 1 --branch {BRANCH} {SSG_REPO_URL} ssg-runtime; "
        f"else cd {REMOTE_SSG} && git fetch origin {BRANCH} --depth 1 && "
        f"git checkout {BRANCH} && git reset --hard origin/{BRANCH}; fi ) && "
        f"echo 'repos ready'"
    ), timeout=300, desc="Clone/update repos")

    # Step 3: Build ssg-runtime + upfuzz
    print("\n[Step 3/5] Building ssg-runtime + upfuzz...")
    parallel_ssh(assignments, lambda a: (
        f"cd {REMOTE_SSG} && "
        f"JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew --no-daemon fatJar 2>&1 | tail -1 && "
        f"mkdir -p {REMOTE_REPO}/lib && "
        f"cp build/libs/ssgFatJar.jar {REMOTE_REPO}/lib/ && "
        f"cd {REMOTE_REPO} && "
        f"git fetch origin main --depth 1 2>/dev/null; "
        f"JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 "
        f"./gradlew --no-daemon clean classes -x spotlessCheck -x spotlessApply -x test 2>&1 | tail -1 && "
        f"JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 "
        f"./gradlew --no-daemon copyDependencies 2>&1 | tail -1 && "
        f"echo 'build OK'"
    ), timeout=300, desc="Build ssg-runtime + upfuzz")

    # Step 4: Build Docker images (downloads prebuilds from mir automatically)
    print("\n[Step 4/5] Building Docker images...")
    parallel_ssh(assignments, lambda a: (
        f"cd {REMOTE_REPO} && "
        f"bash scripts/docker/build_rolling_image_pair.sh "
        f"{a.job['system']} {a.job['original']} {a.job['upgraded']} 2>&1 | tail -3 && "
        f"echo 'docker build OK'"
    ), timeout=1800, desc="Docker image build")

    # Step 5: Launch fuzzing
    cmd_launch(assignments, args)


def cmd_launch(assignments: List[MachineAssignment], args):
    """Launch fuzzing campaigns on all machines."""
    timeout_sec = args.timeout_sec
    tag = args.tag

    print(f"\n[Launch] Starting campaigns (timeout={timeout_sec}s, "
          f"tag={tag})...")

    def launch_cmd(a: MachineAssignment) -> str:
        trace_flags = ""
        if a.mode == 5:
            trace_flags = "--use-trace true --print-trace true --require-trace-signal"
        else:
            trace_flags = "--use-trace false --print-trace false"

        runner_args = (
            f"--system {a.job['system']} "
            f"--original {a.job['original']} "
            f"--upgraded {a.job['upgraded']} "
            f"--rounds 999999 "
            f"--timeout-sec {timeout_sec} "
            f"--testing-mode {a.mode} "
            f"{trace_flags} "
            f"--run-name {a.run_name}"
        )

        return (
            f"cd {REMOTE_REPO} && "
            f"tmux kill-session -t fuzz 2>/dev/null; "
            f"rm -rf failure/* logs/* corpus/* 2>/dev/null; "
            f"tmux new-session -d -s fuzz "
            f"\"cd {REMOTE_REPO} && "
            f"bash scripts/runner/run_rolling_fuzzing.sh {runner_args} "
            f"2>&1 | tee ~/fuzz_campaign.log\" && "
            f"echo 'launched mode{a.mode}'"
        )

    parallel_ssh(assignments, launch_cmd, timeout=60,
                 desc=f"Launch campaigns (tag={tag})")

    print(f"\n  Campaign start: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Expected end:   ~{timeout_sec // 3600}h from now")
    print(f"\n  Monitor with: python3 {__file__} monitor")


def cmd_monitor(assignments: List[MachineAssignment], args):
    """Monitor running campaigns — one-shot or continuous."""

    def check_cmd(a: MachineAssignment) -> str:
        return (
            f"stdout=$(ls -dt {REMOTE_REPO}/scripts/runner/results/{a.run_name}*/server_stdout.log 2>/dev/null | head -1); "
            f"alive=$(tmux has-session -t fuzz 2>/dev/null && echo YES || echo NO); "
            f"rounds=$(grep -o 'total exec : [0-9]*' \"$stdout\" 2>/dev/null | awk '{{print $4}}' | sort -n | tail -1); "
            f"log={REMOTE_REPO}/logs/upfuzz_server.log; "
            f"interesting=$(grep -c 'traceInteresting=true' \"$log\" 2>/dev/null || echo 0); "
            f"corpus=$(grep -c 'Added test plan to corpus' \"$log\" 2>/dev/null || echo 0); "
            f"cand=$(ls -d {REMOTE_REPO}/failure/candidate/failure_* 2>/dev/null | wc -l); "
            f"noise=$(ls -d {REMOTE_REPO}/failure/noise/failure_* 2>/dev/null | wc -l); "
            f"partial=$(grep -c 'Partial alignment' \"$log\" 2>/dev/null || echo 0); "
            f"echo \"alive=$alive rounds=${{rounds:-0}} int=$interesting corpus=$corpus cand=$cand noise=$noise partial=$partial\""
        )

    if args.continuous:
        interval = args.interval
        print(f"Continuous monitoring every {interval}s. Ctrl+C to stop.")
        try:
            while True:
                results = parallel_ssh(assignments, check_cmd, timeout=30,
                                       desc=f"Monitor {time.strftime('%H:%M')}")
                # Check if all dead
                all_dead = all(
                    "alive=NO" in output for _, output, _ in results)
                if all_dead:
                    print("\n  All campaigns finished.")
                    break
                time.sleep(interval)
        except KeyboardInterrupt:
            print("\nMonitoring stopped.")
    else:
        parallel_ssh(assignments, check_cmd, timeout=30,
                     desc=f"Status at {time.strftime('%Y-%m-%d %H:%M')}")


def cmd_stop(assignments: List[MachineAssignment], args):
    """Stop all campaigns and clean up."""
    parallel_ssh(assignments, lambda a: (
        f"tmux kill-session -t fuzz 2>/dev/null; "
        f"cd {REMOTE_REPO} && bin/clean.sh --force 2>/dev/null; "
        f"pkill -f 'run_rolling_fuzzing|FuzzingServer|FuzzingClient' 2>/dev/null; "
        f"docker ps -q | xargs -r docker stop 2>/dev/null; "
        f"docker ps -aq | xargs -r docker rm 2>/dev/null; "
        f"echo 'stopped'"
    ), timeout=60, desc="Stop all campaigns")


def cmd_download(assignments: List[MachineAssignment], args):
    """Download results from all machines."""
    dest = Path(args.dest) / "raw_data"
    dest.mkdir(parents=True, exist_ok=True)

    def download_one(a: MachineAssignment):
        machine_dir = dest / f"{a.host}"
        for sub in ["cloudlab_result", "logs", "runner_result", "failure"]:
            (machine_dir / sub).mkdir(parents=True, exist_ok=True)

        ssh = f"ssh {SSH_OPTS} {a.ssh}"
        scp = f"scp -o ConnectTimeout=15"

        # Find run dir
        run_dir = subprocess.run(
            f'{ssh} "ls -dt {REMOTE_REPO}/scripts/runner/results/{a.run_name}* 2>/dev/null | head -1"',
            shell=True, capture_output=True, text=True
        ).stdout.strip()

        if run_dir:
            subprocess.run(
                f'rsync -az -e "ssh {SSH_OPTS}" {a.ssh}:{run_dir}/ {machine_dir}/runner_result/',
                shell=True, capture_output=True)

        # Logs
        subprocess.run(
            f'{scp} {a.ssh}:{REMOTE_REPO}/logs/upfuzz_server.log {machine_dir}/logs/ 2>/dev/null',
            shell=True, capture_output=True)
        subprocess.run(
            f'{scp} {a.ssh}:{REMOTE_REPO}/logs/upfuzz_client_1.log {machine_dir}/logs/ 2>/dev/null',
            shell=True, capture_output=True)

        # Failure dirs
        for subdir in ["candidate", "noise", "same-version-bug"]:
            subprocess.run(
                f'{scp} -r {a.ssh}:{REMOTE_REPO}/failure/{subdir} {machine_dir}/failure/ 2>/dev/null',
                shell=True, capture_output=True)

        # Campaign log
        subprocess.run(
            f'{scp} {a.ssh}:~/fuzz_campaign.log {machine_dir}/cloudlab_result/tmux_session.log 2>/dev/null',
            shell=True, capture_output=True)

        # Meta
        run_name = os.path.basename(run_dir) if run_dir else a.run_name
        (machine_dir / "download_meta.env").write_text(
            f"HOST={a.host}\n"
            f"RUN_NAME={run_name}\n"
            f"TESTING_MODE={a.mode}\n"
            f"SYSTEM={a.job['system']}\n"
            f"ORIGINAL={a.job['original']}\n"
            f"UPGRADED={a.job['upgraded']}\n"
            f"REMOTE_REPO={REMOTE_REPO}\n"
            f"DOWNLOADED_AT={time.strftime('%Y-%m-%d %H:%M:%S')}\n"
        )
        return a

    print(f"\nDownloading results to {dest}...")
    with ThreadPoolExecutor(max_workers=len(assignments)) as pool:
        futures = {pool.submit(download_one, a): a for a in assignments}
        for f in as_completed(futures):
            a = f.result()
            machine_dir = dest / f"{a.host}"
            log_size = "?"
            log_path = machine_dir / "logs" / "upfuzz_server.log"
            if log_path.exists():
                sz = log_path.stat().st_size
                log_size = f"{sz / 1048576:.0f}M" if sz > 1048576 else f"{sz / 1024:.0f}K"
            print(f"  {a.label}: log={log_size}")

    print(f"\nDone. Results at: {dest}")


# ─── Main ────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="CloudLab Launcher for rolling-upgrade fuzzing campaigns")
    parser.add_argument(
        "command", choices=["deploy", "launch", "monitor", "stop", "download"],
        help="Action to perform")
    parser.add_argument(
        "--machine-list", type=Path, default=MACHINE_LIST,
        help="Path to machine_list.txt")
    parser.add_argument(
        "--machines", type=str, default=None,
        help="Machine range, e.g. '1-6' or '7-12' (1-indexed)")
    parser.add_argument(
        "--mode", type=str, default=None, choices=["5", "6"],
        help="Force testing mode for all machines (default: first 6=mode5, second 6=mode6)")
    parser.add_argument(
        "--timeout-sec", type=int, default=43200,
        help="Campaign timeout in seconds (default: 43200 = 12h)")
    parser.add_argument(
        "--tag", type=str,
        default=time.strftime("campaign-%Y%m%d"),
        help="Run name tag (default: campaign-YYYYMMDD)")
    parser.add_argument(
        "--dest", type=str, default=None,
        help="Download destination directory (for 'download' command)")
    parser.add_argument(
        "--continuous", action="store_true",
        help="For 'monitor': run continuously instead of one-shot")
    parser.add_argument(
        "--interval", type=int, default=3600,
        help="For 'monitor --continuous': check interval in seconds (default: 3600)")

    args = parser.parse_args()

    # Parse machines
    machines = parse_machine_list(args.machine_list)
    if not machines:
        print(f"ERROR: No machines found in {args.machine_list}", file=sys.stderr)
        sys.exit(1)

    # Build assignments
    assignments = build_assignments(machines, args.mode, args.machines,
                                    args.tag, args.timeout_sec)

    # Print plan
    print(f"\n{'='*60}")
    print(f"  CloudLab Launcher — {args.command}")
    print(f"{'='*60}")
    print(f"  Machines: {len(assignments)}")
    print(f"  Tag: {args.tag}")
    if args.command in ("deploy", "launch"):
        print(f"  Timeout: {args.timeout_sec}s ({args.timeout_sec // 3600}h)")
    print()
    for a in assignments:
        print(f"  {a.host:40s} → job {a.job['id']} "
              f"{a.job['system']:10s} {a.job['original']} → {a.job['upgraded']} "
              f"[mode {a.mode}]")
    print()

    # Dispatch
    if args.command == "deploy":
        cmd_deploy(assignments, args)
    elif args.command == "launch":
        cmd_launch(assignments, args)
    elif args.command == "monitor":
        cmd_monitor(assignments, args)
    elif args.command == "stop":
        cmd_stop(assignments, args)
    elif args.command == "download":
        if not args.dest:
            print("ERROR: --dest required for download", file=sys.stderr)
            sys.exit(1)
        cmd_download(assignments, args)


if __name__ == "__main__":
    main()
