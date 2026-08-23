# Phase log

One appended entry per phase boundary, recording the decision and its date.

Spec section 7's top risk is scope: eight phases plus a 5v5 MOBA, one person, and the most
likely failure is running out of will around Phase 5. Its mitigation ends with **"say out loud
at each phase boundary whether to continue"**. This file is where that sentence lands.

The rules that keep it useful:

- **Append only.** An entry is never edited to look better in hindsight. A later entry can say
  the earlier decision was wrong; that is the record working.
- **One word for the decision** — continue, stop, or re-plan. A paragraph that avoids saying
  which is a paragraph that decided nothing.
- **Write it even when it is obvious.** The value is not the answer, it is that the question was
  asked at a moment when stopping was still cheap.

The checkpoint issue template is `.github/ISSUE_TEMPLATE/phase-checkpoint.md`. There is one
checkpoint per phase, 0 through 7, and each blocks the next phase's epics, so a phase cannot
quietly start before its predecessor's checkpoint is answered.

## The eight checkpoints

Open one issue per row, titled `Phase N checkpoint: decide whether to continue`, from the
template. Its exit-criteria list is copied verbatim from spec section 6 — copied, not
paraphrased, because a paraphrased criterion is one somebody can argue their way past.

| Checkpoint | Blocks | The question it forces |
|---|---|---|
| Phase 0 | epics #10, #11 | Do the kernel, the generator and the compiler plugin actually hold? Also open: the committed third-party art (`docs/art-assets.md`) |
| Phase 1 | epic #12 | Can an agent see, drive and rewind unattended, through the **unmodified** bridge? |
| Phase 2 | epics #13, #14, #15, #16 | Do assets compile at build time, inside the latency budgets? |
| Phase 3 | Phase 4 work on epic #14 | Two clients, one JVM, clean `desync_report` — is the netcode real? |
| Phase 4 | Phase 5 work on epic #16 | Prediction, fog and real UDP over two processes |
| Phase 5 | Phase 6 work on epic #16 | **The likeliest place to run out of will (spec section 7).** A bot lane running unattended for ten minutes |
| Phase 6 | epic #18 | Full 5v5, and `settings.gradle.kts` down to the new modules only |
| Phase 7 | — | Bit-exact replay on two OS/JVM combinations |

> **Not yet opened.** The eight issues are outstanding: the credentials this repository's
> automation ran under can read issues but not create them (`403 Resource not accessible by
> personal access token`). The template, the blocking order and the exit criteria are all here;
> opening them is a manual step.

---

## Entry format

```
## Phase N — <continue | stop | re-plan>

**Date:** YYYY-MM-DD  ·  **Issue:** #NNN

Exit criteria: <met | met except X | not met>
Ships if we stop here: <one sentence>
Why: <two or three sentences, no more>
What changes: <what the next phase does differently, or "nothing">
```

---

## Entries

_No entry yet. The Phase 0 entry goes here when the Phase 0 checkpoint issue closes — which is
the last thing Phase 0 does, after its exit criteria are green, not before._
