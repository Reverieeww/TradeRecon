# ADR 06 — Optional, bounded, read-only investigation
Problem: fluent text can be wrong, injected, slow or costly.
Options: AI controls matching; optional evidence-only summaries over deterministic rules/templates.
Decision: OFF default, clearly labelled MOCK, explicitly configured LIVE; fixed OpenAI Chat Completions endpoint, structured output plus local strict validation. No tools, database capability, HTML rendering or close-case action in the provider.
Queue: persistent rows, global advisory-lock admission, 20 waiting / 2 running; 20-second call deadline, one transient retry; abandoned calls fail without automatic rebilling. Evidence changes hide stale output. Prompt/model/provider/hash scoped cache.
Cost: optional network dependency and human semantic review; valid IDs do not establish true inference. Raw provider failures may lack usage metadata. Conservative reservations are not invoices and are not refunded after uncertain calls. Separate app/evaluation ledgers require separately allocated budgets.
Verification: local HTTP adapter tests, queue/cache/staleness/permissions tests and frozen MOCK contract evaluation. LIVE semantic quality and human time benefit remain unmeasured until approved evaluation.
Official contract checked against https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat and https://platform.openai.com/docs/api-reference/chat/create on 2026-10-18. No model ID or current price is guessed.

