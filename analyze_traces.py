#!/usr/bin/env python3
"""Analyze network trace data from differential execution experiment."""
import re
import sys
from collections import Counter, OrderedDict

LOG_FILE = "logs/upfuzz_server.log"

def parse_entries(log_file, cluster_name):
    """Parse trace entries for a given cluster."""
    entries = []
    pattern = re.compile(
        r'\[' + re.escape(cluster_name) + r'\] entry\[(\d+)\] TraceEntry\{id=(\d+), '
        r"methodName='([^']+)', hashcode=(-?\d+), changedMessage=(\w+), "
        r"timestamp=(\d+), recentExecPathHash=(-?\d+), "
        r"recentExecPath=\[([^\]]*)\]"
    )
    with open(log_file) as f:
        for line in f:
            m = pattern.search(line)
            if m:
                entries.append({
                    'idx': int(m.group(1)),
                    'id': int(m.group(2)),  # 1=SEND, 2=RECV
                    'methodName': m.group(3),
                    'hashcode': m.group(4),
                    'changedMessage': m.group(5) == 'true',
                    'timestamp': int(m.group(6)),
                    'recentExecPathHash': m.group(7),
                    'recentExecPath': m.group(8),
                })
    return entries

def generate_ngrams(hashcodes, n=2):
    """Generate n-grams from hashcode list."""
    ngrams = []
    for i in range(len(hashcodes) - n + 1):
        ngrams.append("-".join(hashcodes[i:i+n]))
    return ngrams

def get_method_summary(entries):
    """Get method name frequency."""
    methods = Counter()
    for e in entries:
        direction = "SEND" if e['id'] == 1 else "RECV"
        methods[e['methodName']] += 1
    return methods

def check_def_use(entries):
    """Check if DEF (recentExecPath) has non-zero values."""
    non_zero_count = 0
    for e in entries:
        path = e['recentExecPath']
        if path and any(int(x.strip()) != 0 for x in path.split(',') if x.strip()):
            non_zero_count += 1
    return non_zero_count

def main():
    clusters = ["Only Old", "Rolling", "Only New"]
    all_entries = {}
    all_hashcodes = {}
    all_ngrams = {}

    print("=" * 100)
    print("DIFFERENTIAL EXECUTION NETWORK TRACE ANALYSIS REPORT")
    print("=" * 100)

    # Parse all entries
    for cluster in clusters:
        entries = parse_entries(LOG_FILE, cluster)
        all_entries[cluster] = entries
        all_hashcodes[cluster] = [e['hashcode'] for e in entries]
        all_ngrams[cluster] = generate_ngrams(all_hashcodes[cluster])

    # =====================================================
    # SECTION 1: Cluster Verification
    # =====================================================
    print("\n" + "=" * 100)
    print("SECTION 1: CLUSTER VERIFICATION")
    print("=" * 100)
    for cluster in clusters:
        entries = all_entries[cluster]
        print(f"\n--- {cluster} ---")
        print(f"  Total trace entries (merged across nodes): {len(entries)}")
        if entries:
            ts_start = entries[0]['timestamp']
            ts_end = entries[-1]['timestamp']
            print(f"  Time span: {ts_start} to {ts_end} ({(ts_end - ts_start)/1000:.1f}s)")
        methods = get_method_summary(entries)
        print(f"  Unique message types: {len(methods)}")
        for method, count in methods.most_common():
            print(f"    {method}: {count}")

    # =====================================================
    # SECTION 2: DEF/USE (recentExecPath) Analysis
    # =====================================================
    print("\n" + "=" * 100)
    print("SECTION 2: DEF/USE (recentExecPath) ANALYSIS")
    print("=" * 100)
    for cluster in clusters:
        entries = all_entries[cluster]
        non_zero = check_def_use(entries)
        print(f"\n--- {cluster} ---")
        print(f"  Entries with non-zero recentExecPath (DEF): {non_zero}/{len(entries)}")
        if non_zero == 0:
            print(f"  WARNING: All recentExecPath values are zero!")
            print(f"  This means Runtime.hit() is never called — DEF instrumentation is NOT active.")
        # Check recentExecPathHash
        hashes = set(e['recentExecPathHash'] for e in entries)
        print(f"  Unique recentExecPathHash values: {len(hashes)}")
        for h in hashes:
            print(f"    hash={h}")

    print(f"\n  USE (post-receive execution path): NOT YET IMPLEMENTED in current instrumentation.")
    print(f"  The current hashcode used for Jaccard similarity is methodName.hashCode() only.")

    # =====================================================
    # SECTION 3: First 30 Entries Per Cluster (Ordered)
    # =====================================================
    print("\n" + "=" * 100)
    print("SECTION 3: FIRST 30 TRACE ENTRIES PER CLUSTER (time-ordered)")
    print("=" * 100)
    for cluster in clusters:
        entries = all_entries[cluster][:30]
        print(f"\n--- {cluster} (first 30 of {len(all_entries[cluster])}) ---")
        print(f"  {'Idx':>4} {'Dir':>4} {'Method':<35} {'Hashcode':>12} {'Changed':>8}")
        print(f"  {'----':>4} {'----':>4} {'-----':<35} {'--------':>12} {'-------':>8}")
        for e in entries:
            direction = "SEND" if e['id'] == 1 else "RECV"
            print(f"  {e['idx']:4d} {direction:>4} {e['methodName']:<35} {e['hashcode']:>12} {str(e['changedMessage']):>8}")

    # =====================================================
    # SECTION 4: Jaccard Similarity Root Cause Analysis
    # =====================================================
    print("\n" + "=" * 100)
    print("SECTION 4: JACCARD SIMILARITY ROOT CAUSE ANALYSIS")
    print("=" * 100)

    pairs = [("Only Old", "Rolling"), ("Rolling", "Only New")]
    for cluster_a, cluster_b in pairs:
        ngrams_a = set(all_ngrams[cluster_a])
        ngrams_b = set(all_ngrams[cluster_b])

        intersection = ngrams_a & ngrams_b
        union = ngrams_a | ngrams_b
        jaccard = len(intersection) / len(union) if union else 0

        only_a = ngrams_a - ngrams_b
        only_b = ngrams_b - ngrams_a

        print(f"\n--- Comparison: {cluster_a} vs {cluster_b} ---")
        print(f"  Total unique 2-grams in {cluster_a}: {len(ngrams_a)}")
        print(f"  Total unique 2-grams in {cluster_b}: {len(ngrams_b)}")
        print(f"  Shared 2-grams: {len(intersection)}")
        print(f"  Only in {cluster_a}: {len(only_a)}")
        print(f"  Only in {cluster_b}: {len(only_b)}")
        print(f"  Jaccard (set-based) = |intersection|/|union| = {len(intersection)}/{len(union)} = {jaccard:.4f}")
        print(f"  NOTE: DataSketches approximate Jaccard may differ slightly from exact set-based.")

        # Decode 2-grams back to method names
        hashcode_to_method = {}
        for cluster in clusters:
            for e in all_entries[cluster]:
                hashcode_to_method[e['hashcode']] = e['methodName']

        def decode_ngram(ngram):
            parts = ngram.split("-")
            methods = [hashcode_to_method.get(p, f"?({p})") for p in parts]
            return " -> ".join(methods)

        print(f"\n  Top 2-grams ONLY in {cluster_a} (absent from {cluster_b}):")
        # Count frequency of each 2-gram
        ngram_counts_a = Counter(all_ngrams[cluster_a])
        only_a_sorted = sorted(only_a, key=lambda x: -ngram_counts_a[x])
        for ng in only_a_sorted[:20]:
            print(f"    {decode_ngram(ng):70s} (count={ngram_counts_a[ng]})")

        ngram_counts_b = Counter(all_ngrams[cluster_b])
        print(f"\n  Top 2-grams ONLY in {cluster_b} (absent from {cluster_a}):")
        only_b_sorted = sorted(only_b, key=lambda x: -ngram_counts_b[x])
        for ng in only_b_sorted[:20]:
            print(f"    {decode_ngram(ng):70s} (count={ngram_counts_b[ng]})")

        print(f"\n  Top shared 2-grams (common to both):")
        shared_sorted = sorted(intersection, key=lambda x: -(ngram_counts_a[x]+ngram_counts_b[x]))
        for ng in shared_sorted[:15]:
            print(f"    {decode_ngram(ng):70s} (A={ngram_counts_a[ng]}, B={ngram_counts_b[ng]})")

    # =====================================================
    # SECTION 5: Message Type Differences Between Versions
    # =====================================================
    print("\n" + "=" * 100)
    print("SECTION 5: MESSAGE TYPE DIFFERENCES BETWEEN VERSIONS")
    print("=" * 100)

    methods_old = set(e['methodName'] for e in all_entries["Only Old"])
    methods_rolling = set(e['methodName'] for e in all_entries["Rolling"])
    methods_new = set(e['methodName'] for e in all_entries["Only New"])

    print(f"\n  Message types in Old-Old only: {methods_old - methods_new}")
    print(f"  Message types in New-New only: {methods_new - methods_old}")
    print(f"  Message types in Rolling only: {methods_rolling - methods_old - methods_new}")
    print(f"  Common to all: {methods_old & methods_rolling & methods_new}")

    # =====================================================
    # SECTION 6: changedMessage Analysis
    # =====================================================
    print("\n" + "=" * 100)
    print("SECTION 6: changedMessage ANALYSIS")
    print("=" * 100)
    for cluster in clusters:
        entries = all_entries[cluster]
        changed = sum(1 for e in entries if e['changedMessage'])
        print(f"  {cluster}: {changed}/{len(entries)} entries have changedMessage=true")
    if all(sum(1 for e in all_entries[c] if e['changedMessage']) == 0 for c in clusters):
        print(f"\n  WARNING: changedMessage is always false across all clusters.")
        print(f"  This means modifiedFields.json is not loaded in Docker containers.")

    print("\n" + "=" * 100)
    print("END OF REPORT")
    print("=" * 100)

if __name__ == "__main__":
    main()
