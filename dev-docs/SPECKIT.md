# Spec-Driven Development with Spec Kit

This project uses [Spec Kit](https://github.com/github/spec-kit), GitHub's open-source specification-driven development toolkit, to bring structure and traceability to feature development when working with AI coding assistants like Claude Code.

## What is Spec Kit?

Spec Kit is a framework that guides AI-assisted development through a structured workflow: **specify, plan, task, implement**. Instead of jumping straight into code, each feature goes through progressive refinement — from a business-level specification to a technical plan to actionable tasks — before any implementation begins.

This approach reduces rework, improves requirement coverage, and produces auditable design artifacts alongside the code.

## How It Was Installed

Spec Kit was bootstrapped from the official [github/spec-kit](https://github.com/github/spec-kit) repository. The installation added two sets of files:

1. **`.specify/`** — Scaffolding directory containing templates, scripts, and project memory (checked into the repo).
2. **`.claude/commands/speckit.*.md`** — Slash command definitions that integrate Spec Kit into Claude Code sessions.

## Directory Structure

```
.specify/
├── memory/
│   └── constitution.md           # Project principles and governance rules
├── scripts/
│   └── bash/
│       ├── common.sh             # Shared utility functions
│       ├── check-prerequisites.sh # Validates feature branch setup
│       ├── create-new-feature.sh  # Initializes a new feature branch and spec
│       ├── setup-plan.sh          # Prepares context for planning phase
│       └── update-agent-context.sh # Propagates tech decisions to CLAUDE.md
└── templates/
    ├── spec-template.md          # Feature specification template
    ├── plan-template.md          # Implementation plan template
    ├── tasks-template.md         # Task breakdown template
    ├── checklist-template.md     # Requirements quality checklist template
    ├── constitution-template.md  # Constitution template (for new projects)
    └── agent-file-template.md    # Agent context file template
```

## Workflow

The Spec Kit workflow is sequential. Each phase builds on the previous one:

### 1. Constitution (`/speckit.constitution`)

One-time setup. Defines project-wide principles, technology choices, and governance rules in `.specify/memory/constitution.md`. All downstream artifacts are validated against the constitution.

### 2. Specify (`/speckit.specify <feature description>`)

Creates a feature specification from a natural language description. Produces a `spec.md` in a new feature branch under `specs/<number>-<short-name>/`. The spec focuses on **what** and **why** — no implementation details.

Example:
```
/speckit.specify Add semantic search with vector embeddings for hybrid search
```

### 3. Clarify (`/speckit.clarify`)

Optional. Reviews the spec for ambiguities and asks up to 5 targeted clarification questions. Answers are encoded directly into the spec. Run this before planning if the spec has open questions.

### 4. Plan (`/speckit.plan`)

Generates a technical implementation plan (`plan.md`) from the spec. Includes architecture decisions, data models, API contracts, and a constitution compliance check.

### 5. Tasks (`/speckit.tasks`)

Breaks the plan into dependency-ordered, actionable tasks (`tasks.md`). Tasks are organized by user story with parallel execution markers and exact file paths.

### 6. Analyze (`/speckit.analyze`)

Read-only consistency check across `spec.md`, `plan.md`, and `tasks.md`. Identifies coverage gaps, duplications, ambiguities, and constitution violations before implementation begins.

### 7. Checklist (`/speckit.checklist <domain>`)

Generates requirements-quality checklists (e.g., `security.md`, `ux.md`) that validate whether the **spec itself** is complete and unambiguous — not whether the implementation works.

### 8. Implement (`/speckit.implement`)

Executes the task plan phase by phase, tracking progress in `tasks.md` by marking completed items.

### 9. Tasks to Issues (`/speckit.taskstoissues`)

Converts the task list into GitHub issues for team coordination.

## Feature Artifacts

Each feature produces artifacts in `specs/<number>-<short-name>/`:

```
specs/1-semantic-search/
├── spec.md                    # What to build and why
├── plan.md                    # How to build it
├── tasks.md                   # Step-by-step execution plan
├── research.md                # Technical decisions and alternatives
├── data-model.md              # Entity definitions and relationships
├── contracts/                 # API/interface contracts
├── checklists/                # Requirements quality checklists
│   ├── security.md
│   └── api.md
└── quickstart.md              # Integration test scenarios
```

## When to Use Spec Kit

**Good fit:**
- New features with multiple components or user stories
- Cross-cutting changes (e.g., auth, observability, new transport mode)
- Features requiring design review or team coordination

**Skip it for:**
- Bug fixes
- Simple dependency updates
- Single-file changes
- Formatting or documentation-only changes

## References

- [Spec Kit repository](https://github.com/github/spec-kit)
- [Spec-driven development with AI (GitHub Blog)](https://github.blog/ai-and-ml/generative-ai/spec-driven-development-with-ai-get-started-with-a-new-open-source-toolkit/)