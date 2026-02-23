#!/usr/bin/env python3
"""Analyze Jaccard similarity computation from server log."""

import re
from collections import defaultdict

with open('logs/upfuzz_server.log', 'r') as f:
    lines = f.readlines()

# Extract trace entries with recentExecPathHash
def extract_entries(cluster_tag, lines):
    entries = []
    pattern = re.compile(
        r'\[' + re.escape(cluster_tag) + r'\] entry\[(\d+)\] TraceEntry\{id=(\d+), '
        r"methodName='([^']+)', hashcode=(-?\d+), changedMessage=(true|false), "
        r"timestamp=(\d+), recentExecPathHash=(-?\d+)"
    )
    for line in lines:
        m = pattern.search(line)
        if m:
            entries.append({
                'idx': int(m.group(1)),
                'id': int(m.group(2)),
                'verb': m.group(3),
                'hashcode': int(m.group(4)),
                'changed': m.group(5) == 'true',
                'timestamp': int(m.group(6)),
                'execPathHash': int(m.group(7)),
            })
    return entries

old_entries = extract_entries('Only Old', lines)
rolling_entries = extract_entries('Rolling', lines)
new_entries = extract_entries('Only New', lines)

print(f'Trace entries: Old={len(old_entries)}, Rolling={len(rolling_entries)}, New={len(new_entries)}')

# Build combined hashcode sequences: hashcode_execPathHash
def build_combined_seq(entries):
    return [str(e['hashcode']) + '_' + str(e['execPathHash']) for e in entries]

old_combined = build_combined_seq(old_entries)
rolling_combined = build_combined_seq(rolling_entries)
new_combined = build_combined_seq(new_entries)

# Generate 2-grams
def gen_2grams(seq):
    return [seq[i] + '-' + seq[i+1] for i in range(len(seq) - 1)]

old_2grams_combined = set(gen_2grams(old_combined))
rolling_2grams_combined = set(gen_2grams(rolling_combined))
new_2grams_combined = set(gen_2grams(new_combined))

def jaccard(s1, s2):
    inter = s1 & s2
    union = s1 | s2
    return len(inter) / len(union) if union else 0.0

sim_combined_old_rolling = jaccard(old_2grams_combined, rolling_2grams_combined)
sim_combined_rolling_new = jaccard(rolling_2grams_combined, new_2grams_combined)

# Also compute with just hashcode (verb only)
old_verb_seq = [str(e['hashcode']) for e in old_entries]
rolling_verb_seq = [str(e['hashcode']) for e in rolling_entries]
new_verb_seq = [str(e['hashcode']) for e in new_entries]

old_2grams_verb = set(gen_2grams(old_verb_seq))
rolling_2grams_verb = set(gen_2grams(rolling_verb_seq))
new_2grams_verb = set(gen_2grams(new_verb_seq))

sim_verb_old_rolling = jaccard(old_2grams_verb, rolling_2grams_verb)
sim_verb_rolling_new = jaccard(rolling_2grams_verb, new_2grams_verb)

print()
print('=== Similarity Comparison ===')
print(f'Verb-only 2-grams: Sim[0]={sim_verb_old_rolling:.6f}, Sim[1]={sim_verb_rolling_new:.6f}')
print(f'Combined (verb+execPath) 2-grams: Sim[0]={sim_combined_old_rolling:.6f}, Sim[1]={sim_combined_rolling_new:.6f}')
print(f'Reported (DataSketches): Sim[0]=0.475410, Sim[1]=0.185039')

print()
print(f'Unique verb-only 2-grams: Old={len(old_2grams_verb)}, Rolling={len(rolling_2grams_verb)}, New={len(new_2grams_verb)}')
print(f'Unique combined 2-grams: Old={len(old_2grams_combined)}, Rolling={len(rolling_2grams_combined)}, New={len(new_2grams_combined)}')

# Count how many unique exec path hashes per verb
def count_exec_paths_per_verb(entries):
    verb_paths = defaultdict(set)
    for e in entries:
        verb_paths[e['verb']].add(e['execPathHash'])
    return verb_paths

old_paths = count_exec_paths_per_verb(old_entries)
rolling_paths = count_exec_paths_per_verb(rolling_entries)
new_paths = count_exec_paths_per_verb(new_entries)

print()
print('=== Unique ExecPath hashes per verb ===')
all_verbs = sorted(set(list(old_paths.keys()) + list(rolling_paths.keys()) + list(new_paths.keys())))
for verb in all_verbs:
    o = len(old_paths.get(verb, set()))
    r = len(rolling_paths.get(verb, set()))
    n = len(new_paths.get(verb, set()))
    print(f'  {verb:35s}  Old={o:2d}  Rolling={r:2d}  New={n:2d}')

# Show example: same verb, different exec path hashes across clusters
print()
print('=== Example: SEND_GOSSIP_DIGEST_SYN exec path hashes ===')
print(f'  Old: {sorted(old_paths.get("SEND_GOSSIP_DIGEST_SYN", set()))}')
print(f'  Rolling: {sorted(rolling_paths.get("SEND_GOSSIP_DIGEST_SYN", set()))}')
print(f'  New: {sorted(new_paths.get("SEND_GOSSIP_DIGEST_SYN", set()))}')

print()
print('=== Example: SEND_GOSSIP_DIGEST_ACK2 exec path hashes ===')
print(f'  Old: {sorted(old_paths.get("SEND_GOSSIP_DIGEST_ACK2", set()))}')
print(f'  Rolling: {sorted(rolling_paths.get("SEND_GOSSIP_DIGEST_ACK2", set()))}')
print(f'  New: {sorted(new_paths.get("SEND_GOSSIP_DIGEST_ACK2", set()))}')

# How many entries have non-zero exec paths (non-default hash)
default_hash = -8925661592318841563  # hash of all-zeros ring buffer
for tag, entries in [('Old', old_entries), ('Rolling', rolling_entries), ('New', new_entries)]:
    non_default = sum(1 for e in entries if e['execPathHash'] != default_hash)
    print(f'\n{tag}: {non_default}/{len(entries)} entries have non-default execPathHash ({100*non_default/len(entries) if entries else 0:.1f}%)')
