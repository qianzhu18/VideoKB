# Project working rules

## Product implementation direction

- This checkout is the sole active VideoKB product engineering project. Its backend is Java 21 / Spring Boot and its web client is Vue.
- The canonical GitHub repository and sole publishing destination is `https://github.com/qianzhu18/VideoKB.git`, with `main` as the default branch. Follow the repository ownership and publishing safeguards in `agent.md` before any Git publication.
- Do not push, sync, or open PRs for this project against `qianzhu18/DOVideo-AI`, `Xiaoc7r/DOVideo-AI`, or another upstream/fork repository. Verify `origin`, the current branch, and working tree before publishing; stop if the remote is not the canonical VideoKB repository.
- The prior contents and history of the VideoKB repository were replaced at the owner's explicit request. Do not merge or restore the unrelated former project, its branches, tags, or commit history into this product.
- Do not rewrite remote `main` history or force-push unless the owner explicitly requests it in the current task. Routine publication should fast-forward from the current canonical `origin/main`.
- Do not move product implementation to Python or Node.js because the user has used those languages in other projects. Python files may remain as evaluation/data assets; the product backend remains Java.
- Python files may remain as evaluation/data assets; do not extend them as product implementation.
- When a README, TODO, or report claims a capability is complete, verify it in source code and distinguish code presence from tests, real-data evaluation, and end-to-end acceptance.
- Treat Qdrant as the current transitional vector store. The user selected Milvus on 2026-10-09 and reconfirmed it as the final dense-store target on 2026-10-17. The migration decision and evidence log lives at `docs/专题资料/向量数据库迁移决策与过程记录.md`. Cutover still requires dual-write, backfill, shadow-query, authorization, recovery, and benchmark gates; do not describe the confirmed direction as an implemented migration.

## Knowledge-base acceptance

- A backend endpoint is not a completed user-facing feature until the Vue workflow calls it and displays the answer, source, timestamp, evidence, and refusal state as applicable.
- Retrieval quality claims must name the corpus, metric, and evaluation artifact. The existing 30-case set and Python runner are evaluation assets, not proof of real-corpus readiness.
- Do not claim local-folder ingestion, MCP, or real-video processing is accepted based only on unit tests or documentation; require the corresponding end-to-end evidence.

## Documentation contract

- Read `agent.md` for product requirements, `docs/当前交付状态.md` for delivery status, `docs/待办事项.md` for remaining work, and `docs/总索引.md` for navigation before changing knowledge-base behavior.
- For a scoped code task, locate the affected component through `docs/文档协作规范.md`'s code-path table and read its `docs/专题资料/` card first. Use that card to select source files and text acceptance cases; verify its claims against current code and Git history before editing.
- `agent.md` owns business requirements and acceptance criteria; it is different from this `AGENTS.md`, which owns agent working rules. Do not use interview notes, resume targets, or proposed architecture as proof of implementation.
- Keep active engineering and product documents self-contained inside this repository. Workspace notes outside the checkout are reference material, not mandatory specifications for repository readers.
- A behavior change is not complete until its corresponding BR status, current status, active work item, API/user instructions, migration/compatibility notes, and evidence links are updated as applicable. Follow `docs/文档协作规范.md`.
- Describe the current content/placement, durable ingest job/outbox, generation publication, and query authorization boundaries accurately. Do not reintroduce report-dependent ingest or delete-first index rebuild descriptions as current behavior.
- State the default Qdrant dense + MySQL LIKE + RRF and the explicitly enabled Milvus native BM25 lexical path separately. BM25 has local code/tests/real-corpus evidence; reranking, full dense-store replaceability, multi-turn conversations and community member authorization remain unimplemented. Keep Qdrant data until the full migration gates pass.
- MCP clients currently share one configured upstream account. Directory discovery and multiple client bearer tokens do not constitute independent community-user authorization. Document default search/ask scope differences until resolved.
- Separate source code, unit tests, deterministic AI on real infrastructure, real media/model evaluation, production deployment, and user research. Synthetic student scenarios cannot be reported as real interviews or observed community demand.
- Preserve historical records with their dates and replacement links under `docs/历史归档/`; keep current queues to at most ten actionable items. Do not discard user edits during documentation cleanup.
- Before finishing, check Markdown links/anchors and inspect current entry points for stale statements. Documentation-only changes require link and consistency checks, not an unrelated model evaluation run.
- Before any commit, branch push, or PR merge, complete the documentation sync required by agent.md's delivery gate: BR status, CURRENT, MASTER_TODO, applicable SOPs and evidence links, and the affected page under `docs/专题资料/` (flowchart, modification history, test cases) for component-level behavior changes. An undocumented change is undelivered and must not be committed, pushed, or merged; documentation and code land in the same commit or PR. Append one line to the component card's modification history when a PR merges.
- Workspace reference material outside the checkout — `../知识库部分/` (interview-oriented technical notes and video-lecture digests), `../docs/` (workspace material map), and `../模拟面试/` — is background for understanding and interview preparation, never a repository specification or proof of implementation. Record facts with repository documents.

- All filenames inside `docs/` must use Chinese subject names, including indexes and attachments. Use `docs/总索引.md` as the single navigation entry; `docs/专题资料/组件维护导航.md` remains the required code-path-to-component-card map. Keep only `专题资料/`, `历史归档/`, and `图片附件/` one level below docs. Group topics in the total index instead of creating nested folders or parallel topic indexes. New/moved/renamed/archived documents must be listed there, have their inbound repository links repaired, and pass the repo link checker including archive/reference documents.
