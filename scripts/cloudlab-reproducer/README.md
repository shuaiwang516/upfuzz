# Cloudlab Failure Reproducer

Script:
- `run_reproduce_from_raw_data.sh`

## Purpose
Replay one failure from downloaded cloudlab raw data by:
1. parsing `fullSequence_*.report` and `validation commands`;
2. rewriting `examplecase/testplan*.txt` and `examplecase/validcommands*.txt`;
3. launching `scripts/runner/run_rolling_fuzzing.sh` in `testingMode=3`.

## Usage
### Mode A: host + failure id from raw_data root
```bash
scripts/cloudlab-reproducer/run_reproduce_from_raw_data.sh \
  --raw-data-root /path/to/raw_data \
  --host c220g5-111230.wisc.cloudlab.us \
  --failure-id failure_10
```

### Mode B: direct failure dir + explicit versions
```bash
scripts/cloudlab-reproducer/run_reproduce_from_raw_data.sh \
  --failure-dir /path/to/failure_10 \
  --system hdfs \
  --original hadoop-2.10.2 \
  --upgraded hadoop-3.3.6
```

## Important options
- `--rounds <N>` default `1`
- `--timeout-sec <N>` default `3600`
- `--run-name <name>`
- `--use-trace <true|false>` default `true`
- `--print-trace <true|false>` default `true`
- `--skip-pre-clean <true|false>` default `true`

## Output
- Runner result directory: `scripts/runner/results/<run-name>`
- Server/client logs: same result dir + `logs/upfuzz_*.log`

