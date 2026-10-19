# Evaluation protocol — Day 5

eval/cases.json freezes 60 synthetic examples: 10 each normal, quantity, price, other fields, missing, version/cancel. Ten missing examples are marked insufficient evidence. eval/challenges.json adds 10 untrusted-note/inadequate-evidence challenges. eval/development.json contains two separate development examples.
eval/manifest.json records SHA-256, counts and humanReview=PENDING. scripts/generate_eval.py --check verifies bytes; generation refuses to overwrite existing files.
EvaluationFixtureTest checks explicit authored expectations against deterministic reconciliation and reason-based runbooks. This is regression coverage, NOT human-reviewed gold or AI performance.

## Human-review gate
Before using these as a scored benchmark, have a human independently review each ID's inputs, expected status/reasons, required runbook and insufficient-evidence flag. Record reviewer, date, ID, agree/disagree, rationale in a separate review log. Review note-injection challenges without following their instructions.
Do not silently change the frozen corpus to improve a score. Corrections require a new version, hashes and documented rationale; retain the original. Keep development samples out of scoring and avoid tuning against frozen answers.

## Later comparison
B = existing rules plus templates; C = future model-assisted workflow. No C provider or calls exist yet. Use the same cases and evidence, paired/counterbalanced order, correctness and unsupported-claim review, raw outputs, timing, model configuration and costs. Report insufficient-evidence handling and injection resistance separately. A smaller task time with lower correctness is not an improvement.
Day 6 update: the real HTTP adapter and scripts/evaluate_ai.py are implemented. Seventy MOCK responses passed structured-output validation and are retained under evidence/ai-mock. The initial empty JSONL file records a Windows file-lock setup failure before any calls; the subsequent summary identifies the completed run. The ledger reader was corrected to use its existing locked channel.
No LIVE calls, investigation-time, semantic AI accuracy or AI benefit numbers have been measured. Day 2 ingestion benchmark is a different metric and must not be relabelled as AI or investigation speed. Use docs/day6-paired-tasks.md for the proposed 20 paired tasks; the worksheet is not completed experimental evidence.

