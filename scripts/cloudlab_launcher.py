#!/usr/bin/env python3
"""
CloudLab Launcher — deploys and runs rolling-upgrade fuzzing campaigns
on CloudLab machines using tmux sessions for all long-running operations.

Usage:
    python3 scripts/cloudlab_launcher.py deploy --timeout-sec 43200
    python3 scripts/cloudlab_launcher.py launch --timeout-sec 43200
    python3 scripts/cloudlab_launcher.py monitor
    python3 scripts/cloudlab_launcher.py monitor --continuous --interval 3600
    python3 scripts/cloudlab_launcher.py stop
    python3 scripts/cloudlab_launcher.py download --dest /mnt/ssd/rupfuzz/cloudlab-results/apr10
"""

import argparse
import os
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
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
DOCKERHUB_PREFIX = "shuaiwang516"

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
    host: str
    user: str
    job: dict
    mode: int
    run_name: str

    @property
    def ssh(self):
        return f"{self.user}@{self.host}"

    @property
    def short_host(self):
        return self.host.split(".")[0]

    @property
    def label(self):
        return f"{self.short_host}:{self.job['system']}:m{self.mode}"


def parse_machine_list(path: Path) -> List[tuple]:
    machines = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        target = parts[-1]
        if "@" in target:
            user, host = target.split("@", 1)
        else:
            user, host = "swang516", target
        machines.append((user, host))
    return machines


def build_assignments(machines, modes, machine_range, tag, timeout_sec):
    if machine_range:
        start, end = map(int, machine_range.split("-"))
        machines = machines[start - 1:end]
    assignments = []
    for i, (user, host) in enumerate(machines):
        job = JOBS[i % len(JOBS)]
        if modes == "5":
            mode = 5
        elif modes == "6":
            mode = 6
        else:
            mode = 5 if i < 6 else 6
        run_name = f"{tag}-mode{mode}-{job['original']}-to-{job['upgraded']}"
        assignments.append(MachineAssignment(host, user, job, mode, run_name))
    return assignments


# ─── SSH helpers ─────────────────────────────────────────────

def ssh_quick(a: MachineAssignment, cmd: str, timeout: int = 30) -> str:
    """Run a short SSH command, return stdout. For quick checks only."""
    try:
        r = subprocess.run(
            ["ssh"] + SSH_OPTS.split() + [a.ssh, cmd],
            capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except Exception:
        return ""


def scp_to(a: MachineAssignment, local_path: str, remote_path: str):
    """Copy a file to a remote machine."""
    subprocess.run(
        ["scp"] + SSH_OPTS.split()[:4] + [local_path, f"{a.ssh}:{remote_path}"],
        capture_output=True, timeout=30)


def tmux_run(a: MachineAssignment, session: str, script_content: str):
    """Write a bash script locally, SCP it, run it in a tmux session on the
    remote machine. The script writes a marker file on completion."""
    marker = f"/tmp/_launcher_{session}_done"
    wrapped = (
        f"#!/bin/bash\n"
        f"rm -f {marker}\n"
        f"{script_content}\n"
        f"echo $? > {marker}\n"
    )
    with tempfile.NamedTemporaryFile(
            mode="w", suffix=".sh", delete=False, prefix="launcher_") as f:
        f.write(wrapped)
        local_path = f.name
    try:
        scp_to(a, local_path, f"/tmp/_launcher_{session}.sh")
        # Kill old session, start new one
        ssh_quick(a, f"tmux kill-session -t {session} 2>/dev/null; "
                  f"tmux new-session -d -s {session} "
                  f"'bash /tmp/_launcher_{session}.sh'")
    finally:
        os.unlink(local_path)


def wait_for_marker(assignments: List[MachineAssignment], session: str,
                    timeout: int, poll_interval: int = 15,
                    desc: str = ""):
    """Poll all machines until their marker file appears or timeout."""
    marker = f"/tmp/_launcher_{session}_done"
    start = time.time()
    pending = set(a.label for a in assignments)
    results = {}

    print(f"\n  Waiting for {desc} (timeout={timeout}s, poll={poll_interval}s)...")

    while pending and (time.time() - start) < timeout:
        time.sleep(poll_interval)
        elapsed = int(time.time() - start)

        with ThreadPoolExecutor(max_workers=len(assignments)) as pool:
            futs = {pool.submit(ssh_quick, a, f"cat {marker} 2>/dev/null"): a
                    for a in assignments if a.label in pending}
            for fut in as_completed(futs):
                a = futs[fut]
                val = fut.result().strip()
                if val != "":
                    rc = val
                    status = "OK" if rc == "0" else f"FAIL(rc={rc})"
                    print(f"  [{elapsed:>4d}s] [{status}] {a.label}")
                    results[a.label] = (a, rc == "0")
                    pending.discard(a.label)

        if pending:
            print(f"  [{elapsed:>4d}s] waiting: {len(pending)} remaining...")

    for label in pending:
        a = next(x for x in assignments if x.label == label)
        print(f"  [TIMEOUT] {a.label}")
        results[a.label] = (a, False)

    ok_count = sum(1 for _, ok in results.values() if ok)
    print(f"\n  {desc}: {ok_count}/{len(assignments)} succeeded")
    return results


def require_success(results, stage: str):
    failed = [a.label for a, ok in results.values() if not ok]
    if failed:
        print(f"\n[ERROR] {stage} did not complete successfully on {len(failed)} machines:")
        for label in failed:
            print(f"  - {label}")
        sys.exit(1)


def parallel_quick(assignments, cmd_fn, desc=""):
    """Run quick SSH commands on all machines in parallel."""
    print(f"\n{'='*60}")
    print(f"  {desc} ({len(assignments)} machines)")
    print(f"{'='*60}")
    results = []
    with ThreadPoolExecutor(max_workers=len(assignments)) as pool:
        futs = {pool.submit(ssh_quick, a, cmd_fn(a), 30): a
                for a in assignments}
        for fut in as_completed(futs):
            a = futs[fut]
            out = fut.result()
            last = out.split("\n")[-1] if out else "(no output)"
            print(f"  {a.label}: {last[:120]}")
            results.append((a, out))
    return results


# ─── Commands ────────────────────────────────────────────────

def cmd_deploy(assignments: List[MachineAssignment], args):
    """Full deployment: setup → clone → build → pull+setup → launch."""
    prebuild_timeout = max(3600, min(args.timeout_sec, 7200))

    # Step 1: Setup environment
    print("\n[Step 1/5] Setting up environment...")
    for a in assignments:
        tmux_run(a, "setup", (
            f"if command -v docker >/dev/null 2>&1; then\n"
            f"  echo 'Docker already installed'\n"
            f"else\n"
            f"  mkdir -p {REMOTE_WORKSPACE}\n"
            f"  cd {REMOTE_WORKSPACE}\n"
            f"  git clone --depth 1 --branch {BRANCH} {UPFUZZ_REPO_URL} upfuzz\n"
            f"  cd upfuzz\n"
            f"  sudo bash scripts/setup-cloudlab/setup_env.sh "
            f"--mode full "
            f"--skip-prebuild-check --skip-image-check --skip-build\n"
            f"fi\n"
        ))
    results = wait_for_marker(assignments, "setup", timeout=600,
                              desc="Environment setup")
    require_success(results, "Environment setup")

    # Step 2: Clone/update repos
    print("\n[Step 2/5] Cloning/updating repos...")
    for a in assignments:
        tmux_run(a, "clone", (
            f"mkdir -p {REMOTE_WORKSPACE}\n"
            f"if [ ! -d {REMOTE_REPO}/.git ]; then\n"
            f"  cd {REMOTE_WORKSPACE} && git clone --depth 1 --branch {BRANCH} {UPFUZZ_REPO_URL} upfuzz\n"
            f"else\n"
            f"  cd {REMOTE_REPO} && git fetch origin {BRANCH} --depth 1 && "
            f"git checkout {BRANCH} && git reset --hard origin/{BRANCH}\n"
            f"fi\n"
            f"if [ ! -d {REMOTE_SSG}/.git ]; then\n"
            f"  cd {REMOTE_WORKSPACE} && git clone --depth 1 --branch {BRANCH} {SSG_REPO_URL} ssg-runtime\n"
            f"else\n"
            f"  cd {REMOTE_SSG} && git fetch origin {BRANCH} --depth 1 && "
            f"git checkout {BRANCH} && git reset --hard origin/{BRANCH}\n"
            f"fi\n"
        ))
    results = wait_for_marker(assignments, "clone", timeout=300,
                              desc="Clone repos")
    require_success(results, "Clone repos")

    # Step 3: Build ssg-runtime + upfuzz
    print("\n[Step 3/5] Building ssg-runtime + upfuzz...")
    for a in assignments:
        tmux_run(a, "build", (
            f"cd {REMOTE_SSG}\n"
            f"JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew --no-daemon fatJar\n"
            f"mkdir -p {REMOTE_REPO}/lib\n"
            f"cp build/libs/ssgFatJar.jar {REMOTE_REPO}/lib/\n"
            f"cd {REMOTE_REPO}\n"
            f"git fetch origin main --depth 1 2>/dev/null || true\n"
            f"JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 "
            f"./gradlew --no-daemon clean classes "
            f"-x spotlessCheck -x spotlessApply -x test\n"
            f"JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 "
            f"./gradlew --no-daemon copyDependencies\n"
        ))
    results = wait_for_marker(assignments, "build", timeout=300, desc="Build")
    require_success(results, "Build")

    # Step 4: Pull Docker images from Docker Hub + setup prebuilds from mir
    # Images are pre-built locally and pushed to Docker Hub — no docker build on CloudLab.
    # SKIP_DOCKER_BUILD=1 makes the build script only download/extract/patch prebuilds.
    print("\n[Step 4/5] Pulling Docker images + setting up prebuilds...")
    for a in assignments:
        tag = f"upfuzz_{a.job['system']}:{a.job['original']}_{a.job['upgraded']}"
        remote_tag = f"{DOCKERHUB_PREFIX}/{tag}"

        dep_pull = ""
        if a.job['system'] == 'hbase':
            dep_pull = (
                f"docker pull {DOCKERHUB_PREFIX}/upfuzz_hdfs:hadoop-2.10.2\n"
                f"docker tag {DOCKERHUB_PREFIX}/upfuzz_hdfs:hadoop-2.10.2 upfuzz_hdfs:hadoop-2.10.2\n"
            )

        tmux_run(a, "docker", (
            f"docker pull {remote_tag}\n"
            f"docker tag {remote_tag} {tag}\n"
            f"{dep_pull}"
            f"cd {REMOTE_REPO}\n"
            f"SKIP_DOCKER_BUILD=1 bash scripts/docker/build_rolling_image_pair.sh "
            f"{a.job['system']} {a.job['original']} {a.job['upgraded']}\n"
        ))
    results = wait_for_marker(assignments, "docker", timeout=prebuild_timeout,
                              poll_interval=20,
                              desc="Pull images + setup prebuilds")
    require_success(results, "Pull images + setup prebuilds")

    # Step 5: Launch
    cmd_launch(assignments, args)


def cmd_launch(assignments: List[MachineAssignment], args):
    """Launch fuzzing campaigns."""
    timeout_sec = args.timeout_sec
    print(f"\n[Launch] Starting campaigns (timeout={timeout_sec}s, tag={args.tag})...")

    for a in assignments:
        trace_flags = ("--use-trace true --print-trace true --require-trace-signal"
                       if a.mode == 5 else "--use-trace false --print-trace false")
        tmux_run(a, "fuzz", (
            f"cd {REMOTE_REPO}\n"
            f"rm -rf failure/* logs/* corpus/* 2>/dev/null\n"
            f"bash scripts/runner/run_rolling_fuzzing.sh \\\n"
            f"  --system {a.job['system']} \\\n"
            f"  --original {a.job['original']} \\\n"
            f"  --upgraded {a.job['upgraded']} \\\n"
            f"  --rounds 999999 \\\n"
            f"  --timeout-sec {timeout_sec} \\\n"
            f"  --testing-mode {a.mode} \\\n"
            f"  {trace_flags} \\\n"
            f"  --run-name {a.run_name}\n"
        ))

    # Quick verify tmux sessions started
    time.sleep(5)
    parallel_quick(assignments,
                   lambda a: "tmux has-session -t fuzz 2>/dev/null && echo 'alive' || echo 'dead'",
                   desc="Verify launch")

    print(f"\n  Campaign start: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Expected end:   ~{timeout_sec // 3600}h from now")


def cmd_monitor(assignments: List[MachineAssignment], args):
    """Monitor running campaigns."""
    def check(a):
        return (
            f"stdout=$(ls -dt {REMOTE_REPO}/scripts/runner/results/{a.run_name}*/server_stdout.log 2>/dev/null | head -1); "
            f"alive=$(tmux has-session -t fuzz 2>/dev/null && echo YES || echo NO); "
            f"rounds=$(grep -o 'total exec : [0-9]*' \"$stdout\" 2>/dev/null | awk '{{print $4}}' | sort -n | tail -1); "
            f"log={REMOTE_REPO}/logs/upfuzz_server.log; "
            f"int=$(grep -c 'traceInteresting=true' \"$log\" 2>/dev/null || echo 0); "
            f"corp=$(grep -c 'Added test plan to corpus' \"$log\" 2>/dev/null || echo 0); "
            f"strong=$(ls -d {REMOTE_REPO}/failure/candidate/strong/failure_* 2>/dev/null | wc -l); "
            f"weak=$(ls -d {REMOTE_REPO}/failure/candidate/weak/failure_* 2>/dev/null | wc -l); "
            f"same=$(ls -d {REMOTE_REPO}/failure/same_version/failure_* 2>/dev/null | wc -l); "
            f"noise=$(ls -d {REMOTE_REPO}/failure/noise/failure_* 2>/dev/null | wc -l); "
            f"obs=0; od={REMOTE_REPO}/failure/observability; "
            f"for c in trace_admission_summary.csv trace_window_summary.csv "
            f"seed_lifecycle_summary.csv queue_activity_summary.csv "
            f"scheduler_metrics_summary.csv branch_novelty_summary.csv "
            f"stage_novelty_summary.csv; do "
            f"[ -f $od/$c ] && obs=$((obs+1)); done; "
            f"echo \"alive=$alive rounds=${{rounds:-0}} int=$int corpus=$corp "
            f"strong=$strong weak=$weak same=$same noise=$noise obs=$obs\""
        )

    if args.continuous:
        print(f"Continuous monitoring every {args.interval}s. Ctrl+C to stop.")
        try:
            while True:
                results = parallel_quick(assignments, check,
                                         desc=f"Status {time.strftime('%H:%M')}")
                if all("alive=NO" in out for _, out in results):
                    print("\n  All campaigns finished.")
                    break
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print("\nStopped.")
    else:
        parallel_quick(assignments, check,
                       desc=f"Status {time.strftime('%Y-%m-%d %H:%M')}")


def cmd_stop(assignments: List[MachineAssignment], args):
    """Stop all campaigns."""
    for a in assignments:
        tmux_run(a, "cleanup", (
            f"tmux kill-session -t fuzz 2>/dev/null || true\n"
            f"cd {REMOTE_REPO} && bin/clean.sh --force 2>/dev/null || true\n"
            f"pkill -f 'run_rolling_fuzzing|FuzzingServer|FuzzingClient' 2>/dev/null || true\n"
            f"docker ps -q | xargs -r docker stop 2>/dev/null || true\n"
            f"docker ps -aq | xargs -r docker rm 2>/dev/null || true\n"
        ))
    wait_for_marker(assignments, "cleanup", timeout=120, desc="Stop campaigns")


def cmd_download(assignments: List[MachineAssignment], args):
    """Download results from all machines."""
    dest = Path(args.dest) / "raw_data"
    dest.mkdir(parents=True, exist_ok=True)

    def download_one(a):
        d = dest / a.host
        for sub in ["cloudlab_result", "logs", "runner_result", "failure"]:
            (d / sub).mkdir(parents=True, exist_ok=True)

        run_dir = ssh_quick(a, f"ls -dt {REMOTE_REPO}/scripts/runner/results/{a.run_name}* 2>/dev/null | head -1")

        if run_dir:
            subprocess.run(
                f'rsync -az -e "ssh {SSH_OPTS}" {a.ssh}:{run_dir}/ {d}/runner_result/',
                shell=True, capture_output=True, timeout=120)

        for f in ["logs/upfuzz_server.log", "logs/upfuzz_client_1.log"]:
            subprocess.run(
                f"scp {SSH_OPTS} {a.ssh}:{REMOTE_REPO}/{f} {d}/logs/ 2>/dev/null",
                shell=True, capture_output=True, timeout=120)

        for sub in ["candidate", "noise", "same-version-bug"]:
            subprocess.run(
                f"scp -r {SSH_OPTS} {a.ssh}:{REMOTE_REPO}/failure/{sub} {d}/failure/ 2>/dev/null",
                shell=True, capture_output=True, timeout=120)

        subprocess.run(
            f"scp {SSH_OPTS} {a.ssh}:~/fuzz_campaign.log {d}/cloudlab_result/tmux_session.log 2>/dev/null",
            shell=True, capture_output=True, timeout=30)

        (d / "download_meta.env").write_text(
            f"HOST={a.host}\nRUN_NAME={os.path.basename(run_dir) if run_dir else a.run_name}\n"
            f"TESTING_MODE={a.mode}\nSYSTEM={a.job['system']}\n"
            f"ORIGINAL={a.job['original']}\nUPGRADED={a.job['upgraded']}\n"
            f"REMOTE_REPO={REMOTE_REPO}\nDOWNLOADED_AT={time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        return a

    print(f"\nDownloading to {dest}...")
    with ThreadPoolExecutor(max_workers=len(assignments)) as pool:
        for fut in as_completed({pool.submit(download_one, a): a for a in assignments}):
            a = fut.result()
            lp = dest / a.host / "logs" / "upfuzz_server.log"
            sz = f"{lp.stat().st_size/1048576:.0f}M" if lp.exists() else "?"
            print(f"  {a.label}: log={sz}")
    print(f"Done. Results at: {dest}")


# ─── Main ────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description="CloudLab Launcher")
    p.add_argument("command", choices=["deploy", "launch", "monitor", "stop", "download"])
    p.add_argument("--machine-list", type=Path, default=MACHINE_LIST)
    p.add_argument("--machines", type=str, default=None, help="Range e.g. '1-6'")
    p.add_argument("--mode", type=str, default=None, choices=["5", "6"])
    p.add_argument("--timeout-sec", type=int, default=43200, help="Campaign timeout (default: 12h)")
    p.add_argument("--tag", type=str, default=time.strftime("campaign-%Y%m%d"))
    p.add_argument("--dest", type=str, default=None)
    p.add_argument("--continuous", action="store_true")
    p.add_argument("--interval", type=int, default=3600)
    args = p.parse_args()

    machines = parse_machine_list(args.machine_list)
    if not machines:
        sys.exit("No machines found")

    assignments = build_assignments(machines, args.mode, args.machines,
                                    args.tag, args.timeout_sec)

    print(f"\n{'='*60}")
    print(f"  CloudLab Launcher — {args.command}")
    print(f"{'='*60}")
    print(f"  Machines: {len(assignments)}  Tag: {args.tag}")
    if args.command in ("deploy", "launch"):
        print(f"  Timeout: {args.timeout_sec}s ({args.timeout_sec // 3600}h)")
    print()
    for a in assignments:
        print(f"  {a.short_host:8s} → {a.job['system']:10s} "
              f"{a.job['original']} → {a.job['upgraded']}  [mode {a.mode}]")
    print()

    {"deploy": cmd_deploy, "launch": cmd_launch, "monitor": cmd_monitor,
     "stop": cmd_stop, "download": cmd_download}[args.command](assignments, args)


if __name__ == "__main__":
    main()
