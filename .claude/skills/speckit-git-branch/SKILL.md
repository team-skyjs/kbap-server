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
  - `JIRA_KEY=<KEY>` — **meogo default**. A Jira issue key (e.g. `KB-28`). When present, take the
    **Jira-keyed path in Step 2** instead of the numbered script: the branch/dir become
    `kb-<nn>-<slug>` (e.g. `kb-28-food-spiciness`) with **no sequential number**, because the Jira
    key is globally unique and concurrent developers never collide. See
    `docs/guides/git-branch-strategy.md §1.1`.
  - `GIT_BRANCH_NAME=<name>` — if the caller provided an exact branch name, pass it through
    as `--short-name <name>` so the script uses it verbatim (numbering is still prepended).
  - `--short-name <name>` — a custom 2-4 word slug.
  - `--number NNN` — force the feature number (default: next sequential under `specs/`).

## Steps

1. Resolve the feature description from `$ARGUMENTS`. If empty, ERROR: "No feature description provided".

2. Create the branch + scaffold. Two paths:

   **2a. Jira-keyed path (meogo default — when `JIRA_KEY` is provided).**
   Do **not** call the numbered script. Create the branch and scaffold directly from the repo root,
   so no sequential number is prepended (the shared `create-new-feature.sh` stays untouched):

   ```bash
   SLUG="<2-4 word slug from --short-name or the description>"     # e.g. food-spiciness
   PREFIX="$(printf '%s' "$JIRA_KEY" | tr '[:upper:]' '[:lower:]')" # KB-28 -> kb-28
   BRANCH_NAME="${PREFIX}-${SLUG}"                                  # kb-28-food-spiciness
   FEATURE_DIR="specs/${BRANCH_NAME}"
   SPEC_FILE="${FEATURE_DIR}/spec.md"

   # create+switch (idempotent: switch if it already exists)
   git checkout -b "$BRANCH_NAME" 2>/dev/null || git checkout "$BRANCH_NAME"

   mkdir -p "$FEATURE_DIR"
   [ -f "$SPEC_FILE" ] || cp .specify/templates/spec-template.md "$SPEC_FILE"
   ```

   Set `FEATURE_NUM="$PREFIX"` (there is no numeric prefix). Proceed to Step 3 with these values.

   **2b. Default numbered path (no `JIRA_KEY`).** Run the branch script from the repo root (JSON mode):

   ```bash
   .specify/scripts/bash/create-new-feature.sh --json "<feature description>"
   ```

   - If the caller supplied a short name or `GIT_BRANCH_NAME`, add `--short-name "<slug>"`.
   - The script derives `NNN` sequentially from existing `specs/` dirs + git branches,
     creates+switches branch `NNN-<slug>`, and scaffolds `specs/NNN-<slug>/spec.md`.

3. Resolve `BRANCH_NAME`, `SPEC_FILE`, `FEATURE_NUM`:
   - Path 2a (Jira): use the values you set directly (e.g. `kb-28-food-spiciness`,
     `specs/kb-28-food-spiciness/spec.md`, `kb-28`).
   - Path 2b (numbered): capture them from the script's JSON on stdout (e.g.
     `007-avoidance-substance-aggregate`, `specs/007-.../spec.md`, `007`).

4. Persist the resolved directory so downstream commands agree with the branch:

   Write `.specify/feature.json` with `{"feature_directory": "<dir of SPEC_FILE>"}`.
   (This makes `/speckit-specify` reuse the hook-created directory as
   `SPECIFY_FEATURE_DIRECTORY` instead of generating a divergent one.)

5. Report the JSON back verbatim (`BRANCH_NAME`, `SPEC_FILE`, `FEATURE_NUM`) so the
   calling `/speckit-specify` flow uses `SPEC_FILE`'s directory as its feature directory
   and only **populates** the already-scaffolded spec (do not re-number or re-create it).

## Notes

- **Idempotency**: if the branch already exists, path 2a switches to it (`git checkout` fallback)
  and reuses the existing `spec.md`; path 2b's script switches (with `--allow-existing-branch`)
  or errors asking for a rerun — surface that error, don't loop.
- This skill only handles the branch + scaffold. Filling in the spec content, the quality
  checklist, and validation remain the responsibility of `/speckit-specify`.
