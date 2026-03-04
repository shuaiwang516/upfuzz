# CloudLab Cleanup Scripts

This directory provides cleanup scripts for the machines listed in:

- `scripts/cloudlab-runner/machine_list.txt`

Default remote repo path is:

- `/users/swang516/xlab/rupfuzz/upfuzz`

## Scripts

1. `kill_cloudlab_running.sh`
- Stops currently running fuzzing-related state on each machine:
  - `upfuzz_*` tmux sessions
  - fuzzing server/client Java processes
  - qemu leftovers
  - Docker containers/networks from running clusters

2. `deep_cleanup_cloudlab_hosts.sh`
- Calls `kill_cloudlab_running.sh` first, then performs deep cleanup:
  - removes/recreates:
    - `scripts/runner/results`
    - `scripts/cloudlab-runner/results`
    - `logs`
    - `failure`
    - `fuzzing_storage`
    - `corpus`
    - `graph`
  - removes root generated artifacts:
    - `config.json`
    - `zlab-jacoco.exec`
    - `snapshot.*`
  - removes `/tmp/upfuzz`
  - removes `upfuzz_*` Docker images by default (use `--keep-images` to skip)

## Usage

Run kill-only cleanup:

```bash
scripts/cloudlab-cleanup/kill_cloudlab_running.sh
```

Run deep cleanup on all hosts:

```bash
scripts/cloudlab-cleanup/deep_cleanup_cloudlab_hosts.sh
```

Common options:

```bash
--machine-list <path>   # default: scripts/cloudlab-runner/machine_list.txt
--remote-repo <path>    # default: /users/swang516/xlab/rupfuzz/upfuzz
--parallel <N>          # host-level parallelism (default: 6)
--dry-run               # print actions without executing
```

Deep-clean specific option:

```bash
--keep-images           # keep upfuzz_* Docker images
```

## Notes

- These scripts are intended for CloudLab job reset operations.
- Deep cleanup is destructive for fuzzing outputs on remote hosts.
- Source code and prebuild artifacts are preserved.
