# CloudLab Raw Data Downloader

Script:
- `scripts/cloudlab-downloader/download_cloudlab_raw_data.sh`

## Purpose
Download per-host CloudLab artifacts into a local `raw_data` tree with the same structure used by repro tooling (same style as `cloudlab-results/mar1/raw_data`).

For each host, the downloader creates:

```text
<target-root>/<host>/
  runner_result/
  cloudlab_result/
  logs/
  failure/
  download_meta.env
```

## Default Inputs
- Machine list: `scripts/cloudlab-runner/machine_list.txt`
- Remote repo: `/users/swang516/xlab/rupfuzz/upfuzz`
- Run mapping: latest `dispatch.tsv` under `scripts/cloudlab-runner/results/`

## Requirements
- `ssh` and `rsync` available locally.
- SSH access to all machines in the machine list.
- Remote paths exist under the configured repo.

## Quick Start

Download current run artifacts into `mar3/raw_data`:

```bash
scripts/cloudlab-downloader/download_cloudlab_raw_data.sh \
  --target-root /home/shuai/xlab/rupfuzz/cloudlab-results/mar3/raw_data
```

Default host-level parallelism is `6`.

## Useful Examples

Dry-run only (no copy):

```bash
scripts/cloudlab-downloader/download_cloudlab_raw_data.sh \
  --target-root /home/shuai/xlab/rupfuzz/cloudlab-results/mar3/raw_data \
  --dry-run
```

Custom machine list and parallelism:

```bash
scripts/cloudlab-downloader/download_cloudlab_raw_data.sh \
  --machine-list /home/shuai/xlab/rupfuzz/upfuzz-shuai/scripts/cloudlab-runner/machine_list.txt \
  --target-root /home/shuai/xlab/rupfuzz/cloudlab-results/mar3/raw_data \
  --parallel 3
```

Pin one run name for all hosts:

```bash
scripts/cloudlab-downloader/download_cloudlab_raw_data.sh \
  --target-root /home/shuai/xlab/rupfuzz/cloudlab-results/mar3/raw_data \
  --run-name cloudlab_cont_20260302_2324_job1_c220g5-110404.wisc.cloudlab.us
```

Disable delete behavior (keep stale local files):

```bash
scripts/cloudlab-downloader/download_cloudlab_raw_data.sh \
  --target-root /home/shuai/xlab/rupfuzz/cloudlab-results/mar3/raw_data \
  --no-delete
```

## Options

- `--target-root <path>`: required local raw-data root.
- `--machine-list <path>`: machine list file.
- `--results-root <path>`: local cloudlab-runner results root for auto-dispatch lookup.
- `--dispatch-tsv <path>`: explicit `dispatch.tsv`.
- `--run-name <name>`: force run name for all hosts.
- `--remote-repo <path>`: remote repo root on CloudLab hosts.
- `--parallel <N>`: host-level concurrent downloads.
- `--dry-run`: show planned sync only.
- `--no-delete`: do not pass `--delete` to `rsync`.

## Run Name Resolution
Per host, run name is resolved in this order:
1. `--run-name` (if provided)
2. host mapping from `dispatch.tsv`
3. latest remote directory under `${REMOTE_REPO}/scripts/runner/results/`

## Notes
- The script uses `rsync -az` and `--delete` by default.
- It logs per-host progress and prints success/failure summary at the end.
