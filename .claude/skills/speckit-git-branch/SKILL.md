---
name: "speckit-git-branch"
description: "Create and switch to the SpecKit feature branch before writing a spec. Invoked as the before_specify hook by /speckit-specify."
argument-hint: "The feature description (and optional --short-name / GIT_BRANCH_NAME passthrough)"
compatibility: "Requires spec-kit project structure with .specify/scripts/bash/create-new-feature.sh"
---

## Purpose

This skill is the `before_specify` git hook registered in `.specify/extensions.yml`.
`/speckit-specify` runs it **before** writing the spec so the feature branch is created
and checked out first. It wraps the bundled `create-new-feature.sh` script, which
creates+switches the branch, creates the `specs/<NNN>-<short-name>/` directory, copies
the spec template, and prints `{BRANCH_NAME, SPEC_FILE, FEATURE_NUM}` as JSON.

## Inputs

- The **feature description** (the text passed to `/speckit-specify`) arrives as `$ARGUMENTS`.
- Optional overrides:
  - `GIT_BRANCH_NAME=<name>` — if the caller provided an exact branch name, pass it through
    as `--short-name <name>` so the script uses it verbatim (numbering is still prepended).
  - `--short-name <name>` — a custom 2-4 word slug.
  - `--number NNN` — force the feature number (default: next sequential under `specs/`).

## Steps

1. Resolve the feature description from `$ARGUMENTS`. If empty, ERROR: "No feature description provided".

2. Run the branch script from the repo root (JSON mode):

   ```bash
   .specify/scripts/bash/create-new-feature.sh --json "<feature description>"
   ```

   - If the caller supplied a short name or `GIT_BRANCH_NAME`, add `--short-name "<slug>"`.
   - The script derives `NNN` sequentially from existing `specs/` dirs + git branches,
     creates+switches branch `NNN-<slug>`, and scaffolds `specs/NNN-<slug>/spec.md`.

3. Capture the JSON on stdout. It contains:
   - `BRANCH_NAME` — the created/switched branch (e.g. `007-avoidance-substance-aggregate`)
   - `SPEC_FILE` — path to the scaffolded spec (e.g. `specs/007-.../spec.md`)
   - `FEATURE_NUM` — the numeric prefix (e.g. `007`)

4. Persist the resolved directory so downstream commands agree with the branch:

   Write `.specify/feature.json` with `{"feature_directory": "<dir of SPEC_FILE>"}`.
   (This makes `/speckit-specify` reuse the hook-created directory as
   `SPECIFY_FEATURE_DIRECTORY` instead of generating a divergent one.)

5. Report the JSON back verbatim (`BRANCH_NAME`, `SPEC_FILE`, `FEATURE_NUM`) so the
   calling `/speckit-specify` flow uses `SPEC_FILE`'s directory as its feature directory
   and only **populates** the already-scaffolded spec (do not re-number or re-create it).

## Notes

- **Idempotency**: if the branch already exists, the script switches to it (with
  `--allow-existing-branch`) or errors asking for a rerun; surface that error, don't loop.
- This skill only handles the branch + scaffold. Filling in the spec content, the quality
  checklist, and validation remain the responsibility of `/speckit-specify`.
