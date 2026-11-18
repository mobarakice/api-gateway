# 0001 Record architecture decisions

- Status: Accepted (2026-11-18)

## Context
The gateway is built over many sessions. Choices need to survive between sessions, and reviewers need the reasoning behind them.

## Options
1. Decisions in commit messages only.
2. Short ADRs in `docs/adr/NNNN-title.md`.

## Decision
Option 2. Every ADR covers: context, options, decision, performance impact, memory impact, Native Image impact, and operational impact. ADRs are immutable once accepted. A new ADR supersedes an old one, and the old one is marked `Superseded by NNNN`.

## Impact
- Performance, memory, Native Image: none.
- Operational: reviewers check ADR coverage for significant changes.
