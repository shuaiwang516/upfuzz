# Observability CSV Reference

This document describes the CSV artifacts emitted by the rolling-upgrade
observability logic in `org.zlab.upfuzz.fuzzingengine.server.observability`.
The files are written under `<failureDir>/observability/` by
`ObservabilityMetrics`.

The schema is append-only in practice: newer runs may have extra trailing
columns compared with older CloudLab artifacts. For example, the Apr18
artifact path may lack some newer `trace_window_summary.csv`,
`seed_lifecycle_summary.csv`, `scheduler_metrics_summary.csv`, or optional
sidecar columns/files that current `upfuzz-shuai` can emit.

## Shared Terms

- `old-old` / `oo`: old-version baseline lane.
- `old-new` / `ro` / rolling: real rolling-upgrade lane.
- `new-new` / `nn`: new-version baseline lane.
- `round`, `round_id`, `execution_index`: completed differential execution
  index on the fuzzing server.
- `test_packet_id`: UpFuzz test-plan identifier for the execution.
- `lineage_root`: admitted seed ancestor used to credit later descendant
  payoff. `-1` means no known parent/root.
- Boolean columns are serialized as `true` or `false`.

## Enum Values

`admission_reason`:

- `BRANCH_ONLY`: admitted because branch coverage found new probes.
- `BRANCH_AND_TRACE`: admitted because branch novelty and effective trace
  evidence both fired.
- `TRACE_ONLY_WINDOW_SIM`: admitted from trace similarity without branch
  novelty.
- `TRACE_ONLY_TRIDIFF_EXCLUSIVE`: admitted from rolling-exclusive message
  tri-diff without branch novelty.
- `TRACE_ONLY_TRIDIFF_MISSING`: historical compatibility value. Current policy
  records missing-message co-firing but does not use it as a direct admission
  reason.
- `UNKNOWN`: not admitted or reason unavailable.

`trace_evidence_strength`:

- `NONE`: no interesting trace evidence, or trace scoring was disabled.
- `UNSUPPORTED`: trace windows fired, but no all-three family/flow support.
- `UNSUPPORTED_BUT_REPEATABLE`: unsupported but contains repeated
  rolling-only upgrade-critical traffic.
- `WEAK`: supported trace evidence exists, but it failed the strong bar.
- `STRONG`: support-backed, mixed-version-relevant trace evidence reached the
  strong scorer threshold.

`structured_candidate_strength`:

- `NONE`: no structured Checker-D divergence.
- `WEAK`: structured divergence exists but at least one lane had unstable
  outcome such as `UNKNOWN` or `DAEMON_ERROR`.
- `STRONG`: all lanes were stable and rolling diverged from both baselines.

`weak_candidate_kind`:

- `NONE`: not a weak candidate.
- `ROLLING_ONLY_EVENT_FAILURE`: rolling lane failed an event while baselines
  stayed stable.
- `ROLLING_ONLY_ERROR_LOG`: rolling lane emitted candidate ERROR logs while
  baselines stayed clean.
- `UNSTABLE_STRUCTURED_DIVERGENCE`: Checker D diverged but did not reach
  `STRONG`.
- `OTHER`: reserved fallback.

`queue_priority_class`:

- `BRANCH_ONLY`: branch-only admission.
- `BRANCH_AND_STRONG_TRACE`: branch admission with strong trace evidence.
- `BRANCH_AND_WEAK_TRACE`: branch admission with lower-confidence trace
  evidence.
- `TRACE_ONLY_STRONG`: trace-only admission with strong trace evidence.
- `TRACE_ONLY_WEAK`: trace-only admission with weak or unsupported trace
  evidence.
- `UNKNOWN`: unlabeled/default.

`scheduler_class`:

- `REPRO_CONFIRM`: strict-priority lane for strong structured candidate
  parents.
- `MAIN_EXPLOIT`: weighted lane for branch plus strong trace admissions.
- `BRANCH_SCOUT`: weighted lane for branch-only exploration.
- `SHADOW_EVAL`: low-budget lane for lower-confidence trace work.

`branch_novelty_class`:

- `ROLLING_POST_UPGRADE`: rolling upgraded-side coverage found probes missing
  from the new-new baseline.
- `ROLLING_PRE_UPGRADE_ONLY`: rolling old-side coverage found probes missing
  from the old-old baseline, but upgraded-side rolling novelty was absent.
- `SHARED`: new probes were shared by baseline and rolling lanes.
- `BASELINE_ONLY`: only baselines found the new probes.
- `NONE`: no new branch probes.

`support_class` in `trace_window_summary.csv`:

- `UNSUPPORTED`: no all-three overlap at family or flow level.
- `BACKGROUND_ONLY`: all-three overlap exists only for background traffic.
- `FAMILY_BACKED`: all-three non-background protocol-family overlap exists.
- `FLOW_BACKED`: all-three flow overlap exists, including deterministic
  fallback flows.
- `FULL`: all-three explicit-ID flow overlap exists.

## `trace_admission_summary.csv`

One row per completed differential execution. It records whether the round was
admitted to the corpus, which branch/trace rules fired, candidate labels, and
cumulative admission counters after the round.

| Column | Meaning |
| --- | --- |
| `execution_index` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `admitted` | Whether the round was accepted into the corpus/short-term queue. |
| `admission_reason` | Primary admission reason. `UNKNOWN` for non-admitted rows. |
| `new_branch_coverage` | Whether old-side or upgraded-side branch coverage found any new probe. |
| `trace_interesting` | Raw trace-interesting signal before final strength/dedup admission gates. |
| `tri_diff_exclusive_fired` | At least one window had rolling-exclusive message identities above the configured count/fraction thresholds. |
| `tri_diff_missing_fired` | At least one window had rolling-missing message identities above thresholds. This is diagnostic under current policy. |
| `window_sim_fired` | At least one aligned window fired the canonical trace similarity rule. |
| `aggregate_sim_fired` | Whole-round aggregate trace similarity fired after no per-window hit admitted the trace. |
| `trace_signature_suppressed` | Trace-only admission was suppressed because recent trace signatures were saturated. |
| `structured_candidate` | Checker-D structured cross-cluster divergence fired. |
| `weak_candidate` | Backward-compatible weak flag for rolling-only event/error-log candidates. Weak structured divergence is represented by `weak_candidate_kind`. |
| `windows_evaluated` | Number of aligned trace windows evaluated this round. |
| `overall_verdict` | Final verdict classification, for example `NONE`, `ROLLING_UPGRADE_BUG_CANDIDATE`, `SAME_VERSION_BUG`, `INFRA_NOISE`, or `ORACLE_NOISE`. |
| `cumulative_branch_only` | Cumulative admitted rounds with `BRANCH_ONLY`. |
| `cumulative_branch_and_trace` | Cumulative admitted rounds with `BRANCH_AND_TRACE`. |
| `cumulative_trace_only_window_sim` | Cumulative admitted rounds with `TRACE_ONLY_WINDOW_SIM`. |
| `cumulative_trace_only_tridiff_exclusive` | Cumulative admitted rounds with `TRACE_ONLY_TRIDIFF_EXCLUSIVE`. |
| `cumulative_trace_only_tridiff_missing` | Historical cumulative counter for missing-only tri-diff admissions. Expected to stay zero under current policy. |
| `cumulative_trace_signature_suppressions` | Total trace-only admissions suppressed by trace-signature dedup so far. |
| `structured_candidate_strength` | Confidence label for Checker-D structured divergence. |
| `weak_candidate_kind` | Fine-grained weak candidate subtype. |
| `trace_evidence_strength` | Round-level trace evidence strength. |
| `unsupported_trace_window_count` | Evaluated windows with enough events but no support gate. |
| `support_backed_trace_window_count` | Evaluated windows with enough events and support gate passed. |
| `queue_priority_class` | Admission-facing priority class stamped onto the queued plan. |

## `trace_admission_totals.csv`

Small aggregate snapshot of admission counts by reason. It is refreshed on
each observability flush.

| Column | Meaning |
| --- | --- |
| `reason` | Admission reason enum value. |
| `count` | Number of admitted seeds with that reason so far. |

## `trace_window_summary.csv`

One row per aligned comparable trace window that the canonical trace scoring
path evaluated. Older artifacts may stop after `support_gate_passed`; current
code appends boundary-resolution, flow, and composite-scorer columns.

| Column | Meaning |
| --- | --- |
| `round` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `window_ordinal` | Zero-based window ordinal in the rolling lane. |
| `comparison_stage` | Normalized stage identifier used to align windows, such as `PRE_UPGRADE`, `POST_STAGE_1`, or `POST_FINAL_STAGE`. |
| `total_messages` | Total message events considered across the aligned window. |
| `total_all_three_count` | Number of message identity buckets present in all three lanes. |
| `rolling_exclusive` | Count of message identity buckets present in rolling and absent from both baselines. |
| `rolling_missing` | Count of message identity buckets present in both baselines and absent from rolling. |
| `rolling_exclusive_fraction` | `rolling_exclusive / total_messages`, formatted to 4 decimals. |
| `rolling_missing_fraction` | `rolling_missing / total_messages`, formatted to 4 decimals. |
| `sim_oo_ro` | Similarity between old-old and rolling for this window. |
| `sim_ro_nn` | Similarity between rolling and new-new for this window. |
| `sim_baseline` | Baseline similarity between old-old and new-new. |
| `rolling_min_similarity` | `min(sim_oo_ro, sim_ro_nn)`. |
| `rolling_divergence_margin` | How much farther rolling is from each baseline than the baselines are from each other. |
| `window_has_enough_events` | Whether the window met the configured minimum event gate. |
| `window_sim_fired` | Canonical per-window similarity rule fired. |
| `tri_diff_exclusive_fired` | Rolling-exclusive tri-diff rule fired for this window. |
| `tri_diff_missing_fired` | Rolling-missing tri-diff diagnostic rule fired for this window. |
| `baseline_shared_count` | Number of message buckets shared by old-old and new-new baselines. |
| `changed_message_count` | Rolling-lane messages whose payload/message class changed across versions. Mode 5/6 typically passes `0` for this corroborator. |
| `upgraded_boundary_event_count` | Per-event count of rolling messages crossing upgraded/non-upgraded node boundaries with resolved endpoints. |
| `trace_evidence_strength` | Per-window trace strength from the composite scorer. |
| `support_gate_passed` | Whether the window had any support stronger than `UNSUPPORTED`. |
| `boundary_event_count_total` | Total rolling-lane boundary-candidate events inspected. |
| `boundary_event_count_index_resolved` | Endpoint resolutions that matched nodes by numeric/index identity. |
| `boundary_event_count_role_resolved` | Endpoint resolutions that matched a unique node by role. |
| `boundary_event_count_role_ambiguous` | Endpoint resolutions where role existed but mapped to multiple possible nodes. |
| `boundary_event_count_unresolved` | Endpoint resolutions that could not be mapped to a node. |
| `flow_total_old_old` | Number of extracted logical flows in old-old. |
| `flow_total_rolling` | Number of extracted logical flows in rolling. |
| `flow_total_new_new` | Number of extracted logical flows in new-new. |
| `flow_explicit_id_rolling` | Rolling flows grouped by explicit logical/delivery ID. |
| `flow_fallback_rolling` | Rolling flows grouped by deterministic fallback key. |
| `flow_grouping_failed_rolling` | Rolling events/flows that could not be grouped. |
| `flow_boundary_involved_rolling` | Rolling flows that traversed an upgraded boundary. |
| `flow_role_ambiguous_boundary_rolling` | Boundary-involved rolling flows with role-ambiguous endpoint resolution. |
| `flow_unresolved_boundary_rolling` | Boundary-involved rolling flows with unresolved endpoint resolution. |
| `flow_top_divergent_families` | Ranked divergent protocol families, formatted like `FAMILY=gap@ro=a/oo=b/nn=c` joined by `;`. |
| `flow_top_divergent_details_rolling` | Per-family rolling detail labels, formatted like `FAMILY:label=count|label=count`. |
| `support_class` | Support tier used by the composite scorer. |
| `family_support_count` | Count of protocol families present in all three lanes. |
| `flow_support_count` | Count of flows present in all three lanes. |
| `baseline_flow_support_count` | Count of flows shared by old-old and new-new baselines. |
| `background_family_support_count` | Count of all-three support families classified as background. |
| `upgrade_critical_support_count` | Count of all-three support families classified as upgrade-critical. |
| `family_jaccard_oo_ro` | Family-set Jaccard similarity between old-old and rolling. |
| `family_jaccard_ro_nn` | Family-set Jaccard similarity between rolling and new-new. |
| `family_jaccard_oo_nn` | Family-set Jaccard similarity between old-old and new-new. |
| `family_weighted_sim_oo_ro` | Family similarity between old-old and rolling after protocol-family-class weighting. |
| `family_weighted_sim_ro_nn` | Weighted family similarity between rolling and new-new. |
| `family_weighted_sim_oo_nn` | Weighted family similarity between old-old and new-new. |
| `flow_jaccard_oo_ro` | Flow Jaccard similarity between old-old and rolling. |
| `flow_jaccard_ro_nn` | Flow Jaccard similarity between rolling and new-new. |
| `flow_jaccard_oo_nn` | Flow Jaccard similarity between old-old and new-new. |
| `explicit_flow_jaccard_oo_ro` | Explicit-ID flow Jaccard similarity between old-old and rolling. |
| `explicit_flow_jaccard_ro_nn` | Explicit-ID flow Jaccard similarity between rolling and new-new. |
| `explicit_flow_jaccard_oo_nn` | Explicit-ID flow Jaccard similarity between old-old and new-new. |
| `order_similarity_oo_ro` | Per-role-pair compressed upgrade-critical order similarity between old-old and rolling. |
| `order_similarity_ro_nn` | Order similarity between rolling and new-new. |
| `order_similarity_oo_nn` | Order similarity between old-old and new-new. |
| `baseline_agreement_score` | Baseline agreement component, currently clamped weighted `oo_nn` family similarity. |
| `rolling_divergence_score` | Rolling divergence component, `1 - min(weighted oo_ro, weighted ro_nn)`. |
| `order_divergence_score` | Order divergence component, `1 - min(order oo_ro, order ro_nn)`. |
| `background_share_rolling` | Share of rolling traffic classified as background family traffic. |
| `boundary_bonus_applied` | Composite-score bonus applied for boundary/flow/changed-message corroboration. |
| `order_bonus_applied` | Composite-score bonus applied for supported order divergence. |
| `background_cap_applied` | Amount clipped from the raw composite score due to background/unsupported cap. |
| `composite_score` | Final non-negative trace guidance score after bonuses and caps. |
| `rolling_exclusive_upgrade_critical_events` | Count of rolling-exclusive events in upgrade-critical families. |
| `dominant_supported_family` | Dominant all-three supported protocol family, if any. |
| `dominant_divergent_family` | Dominant rolling-divergent protocol family, if any. |
| `dominant_order_anomalous_family` | Dominant upgrade-critical family contributing order anomaly, if any. |
| `family_profile_label` | Compact family mix label: `BACKGROUND_ONLY`, `MIXED`, `UPGRADE_CRITICAL`, or `OTHER`. |
| `rolling_only_upgrade_critical_present` | Whether rolling had upgrade-critical traffic absent from both baselines. |
| `firing_reasons` | `|`-joined scorer reason labels such as `composite_reached_strong` or `boundary_corroborated`. |

## `branch_novelty_summary.csv`

One row per completed differential execution. It attributes new branch probes
by version axis and source lane before the coverage state is merged.

| Column | Meaning |
| --- | --- |
| `round_id` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `old_version_baseline_only_probes` | Old-version probes found by old-old baseline but not by rolling old-side coverage this round. |
| `old_version_rolling_only_probes` | Old-version probes found by rolling old-side coverage but not by old-old baseline. |
| `old_version_shared_probes` | Old-version probes newly found by both old-old and rolling old-side coverage. |
| `new_version_baseline_only_probes` | New-version probes found by new-new baseline but not by rolling upgraded-side coverage. |
| `new_version_rolling_only_probes` | New-version probes found by rolling upgraded-side coverage but not by new-new baseline. |
| `new_version_shared_probes` | New-version probes newly found by both new-new and rolling upgraded-side coverage. |
| `total_new_probes` | Sum of the six probe-count columns above. |
| `old_version_novelty_source` | Per-version source label: `NONE`, `ROLLING_ONLY`, `BASELINE_ONLY`, `MIXED`, or `SHARED`. |
| `new_version_novelty_source` | Same source label for the new-version axis. |
| `branch_novelty_class` | Round-level scheduler classification of branch novelty. |
| `rolling_only_old_probe_count` | Alias for `old_version_rolling_only_probes`. |
| `rolling_only_new_probe_count` | Alias for `new_version_rolling_only_probes`. |
| `baseline_only_probe_count` | Old plus new baseline-only probes. |
| `shared_probe_count` | Old plus new shared probes. |

## `queue_activity_summary.csv`

One row per short-term queue enqueue or dequeue. This file describes movement
through the scheduler, not final campaign verdicts.

| Column | Meaning |
| --- | --- |
| `round_id` | Server round at which the queue event was recorded. |
| `test_packet_id` | Test plan packet ID associated with the queue entry. |
| `lineage_root` | Root admitted seed credited for descendants. `-1` means none. |
| `enqueue_or_dequeue` | `ENQUEUE` or `DEQUEUE`. |
| `admission_reason` | Admission reason stamped onto the queued plan. |
| `trace_evidence_strength` | Trace evidence label stamped onto the queued plan. |
| `structured_candidate_strength` | Structured candidate strength stamped onto the queued plan. |
| `queue_priority_class` | Admission-facing priority class. |
| `scheduler_class` | Internal scheduler lane containing the entry. |
| `planned_mutation_budget` | Mutation budget for this queue entry. Older comments used `-1` for enqueues; current enqueue rows pass the planned budget when available. |

## `scheduler_metrics_summary.csv`

One row per completed differential execution. The first two columns identify
the round. All remaining columns repeat the same metric names for each
`scheduler_class` in this order: `repro_confirm`, `main_exploit`,
`branch_scout`, `shadow_eval`.

Counters are cumulative since campaign start. To get per-round deltas, diff
consecutive rows. Occupancy is instantaneous queue size at snapshot time.

| Column Pattern | Meaning |
| --- | --- |
| `round_id` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `<lane>_occupancy` | Number of queued plans in the lane at snapshot time. |
| `<lane>_enqueues` | Cumulative enqueue events attributed to the lane. |
| `<lane>_dequeues` | Cumulative dequeue events attributed to the lane. |
| `<lane>_mutation_budget_spent` | Cumulative mutation budget consumed by dequeued plans from the lane. |
| `<lane>_branch_payoff` | Cumulative descendant branch-payoff credits attributed to the lane. |
| `<lane>_strong_payoff` | Cumulative strong structured-candidate payoff credits attributed to the lane. |
| `<lane>_weak_payoff` | Cumulative weak candidate payoff credits attributed to the lane. |
| `<lane>_dedup_collisions` | Cumulative dedup collisions for entries in the lane. |
| `<lane>_decay_demotions` | Cumulative times entries decayed/demoted after repeated no-payoff dequeues. |
| `<lane>_branch_backbone_reweights` | Cumulative branch-backbone score reweight events attributed to the lane. |
| `<lane>_quarantine_events` | Cumulative weak-candidate quarantine events attributed to the lane. |
| `<lane>_quarantine_rejections` | Cumulative admissions rejected because the lineage was quarantined, attributed to the lane. |

Older artifacts may stop after `<lane>_decay_demotions`.

## `seed_lifecycle_summary.csv`

One row per corpus-admitted seed. Counters are updated as the seed is selected
as a mutation parent and as descendants produce branch, trace, or candidate
payoff.

| Column | Meaning |
| --- | --- |
| `seed_test_id` | Test ID of the admitted seed. |
| `creation_round` | Round when the seed was admitted. |
| `creation_timestamp_ms` | Wall-clock creation timestamp in milliseconds since Unix epoch. |
| `creation_reason` | Admission reason that created the seed. |
| `parent_seed_test_id` | Parent/root seed ID at creation, or `-1` when none. |
| `times_selected_as_parent` | Number of times this seed was selected for mutation. |
| `descendant_new_branch_hits` | Descendant rounds that produced new branch coverage. |
| `descendant_structured_candidate_hits` | Strong structured Checker-D descendant candidate hits. This is the strong slice in current code. |
| `descendant_weak_candidate_hits` | Backward-compatible weak umbrella: rolling-only event plus rolling-only error-log descendant hits. |
| `descendant_weak_structured_candidate_hits` | Descendant weak structured divergences. |
| `descendant_weak_event_candidate_hits` | Descendant rolling-only event-failure candidates. |
| `descendant_weak_error_log_candidate_hits` | Descendant rolling-only error-log candidates. |
| `descendant_strong_trace_hits` | Descendant rounds that produced `STRONG` trace evidence. |
| `descendant_trace_assisted_candidate_hits` | Descendant rounds where strong trace evidence co-fired with a strong structured candidate. |
| `descendant_strong_trace_only_hits` | Descendant rounds where strong trace fired without a structured candidate. |
| `descendant_shadow_low_value_hits` | Descendant admissions routed to `SHADOW_EVAL` without candidate payoff. |
| `descendant_branch_backbone_reweight_events` | Times this lineage received branch-backbone reweight bonus. |
| `descendant_branch_backbone_quarantine_events` | Times this lineage entered weak-candidate quarantine cooldown. |
| `branch_novelty_class` | Branch novelty class at seed creation time. |

Older artifacts may include only the columns through
`descendant_strong_trace_hits` plus `branch_novelty_class`.

## `trace_metadata_coverage.csv`

One row per round per lane. This diagnostic file records whether trace entries
carry enough message IDs, delivery IDs, roles, and peer IDs for flow and
boundary reconstruction.

| Column | Meaning |
| --- | --- |
| `round` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `lane_name` | Human lane name, usually old-old, rolling, or new-new. |
| `total_entries` | Number of merged trace entries in the lane. |
| `entries_with_logical_message_id` | Entries with usable `logicalMessageId`. |
| `entries_with_delivery_id` | Entries with usable `deliveryId`. |
| `entries_with_node_role` | Entries with resolved local node role. |
| `entries_with_peer_role` | Entries with resolved peer role. |
| `entries_with_missing_peer_id` | Entries whose peer ID is null, empty, or literal `null`. |
| `entries_with_role_ambiguous_peer_id` | Entries whose peer ID resolved only to an ambiguous shared role. |
| `entries_with_unresolved_peer_id` | Entries whose peer ID was present but could not be resolved. |
| `topology_node_count` | Number of nodes in the topology snapshot used for resolution. |
| `topology_id_mapping_count` | Number of ID-to-node mappings in the topology snapshot. |

## `stage_novelty_summary.csv` (optional)

Emitted only when `enableStageCoverageSnapshots=true` and rolling feedback
contains stage coverage snapshots. It attributes new-version branch novelty to
rolling-upgrade stage boundaries.

| Column | Meaning |
| --- | --- |
| `round_id` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `total_new_version_probes` | Total new-version probes discovered by rolling upgraded-side coverage by round end. |
| `probes_at_first_upgrade` | New-version probes already visible at the first upgrade snapshot, or `-1` if missing. |
| `probes_at_last_upgrade` | New-version probes visible at the last upgrade snapshot, or `-1` if missing. |
| `probes_at_finalize` | New-version probes visible after finalize, or `-1` if missing. |
| `upgrade_snapshot_count` | Number of `AFTER_UPGRADE_*` snapshots observed. |

## `trace_window_classifier_inputs.csv` (optional)

Emitted only when `enableTraceFlowTupleDump=true`. File absence means the dump
was disabled. A header-only file means the dump was enabled but no classifier
input tuples were recorded.

Each row is one aggregated classifier tuple for a lane/window. The tuple is
`(protocol, rpc_service, rpc_method, message_type, message_kind, payload_type)`
with a count and the live classifier's family assignment.

| Column | Meaning |
| --- | --- |
| `round` | Completed differential execution index. |
| `test_packet_id` | Test plan packet ID. |
| `window_ordinal` | Trace window ordinal. |
| `comparison_stage` | Normalized stage identifier for the window. |
| `lane` | Short lane label: `oo`, `ro`, or `nn`. |
| `protocol` | Protocol metadata from the trace entry. |
| `rpc_service` | RPC service metadata from the trace entry. |
| `rpc_method` | RPC method metadata from the trace entry. |
| `message_type` | Message type metadata from the trace entry. |
| `message_kind` | Message kind metadata from the trace entry. |
| `payload_type` | Payload/log metadata used as classifier payload type. |
| `current_family` | Protocol family assigned by the live classifier. |
| `count` | Number of trace entries in this lane/window with the same tuple. |
