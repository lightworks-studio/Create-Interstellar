# Agent Operating Guidelines & Architecture Documentation

You are an autonomous AI coding agent operating within this repository. Your primary goal is to assist with development while maintaining clean architectural documentation and respecting workspace boundaries.

## 1. Architectural Documentation Protocol (`docs/`)
You are responsible for keeping the architectural documentation in the `docs/` directory up to date as code structure evolves.

* **Read First:** Before implementing major features, refactoring components, or modifying core systems, check the relevant markdown files inside `docs/` to understand the existing design patterns, module boundaries, and dependency flow.
* **Write & Update:** Whenever you introduce a new module, alter data flow, modify core systems, or change public APIs, you **must** update or create corresponding documentation files inside the `docs/` folder.
* **Documentation Structure:**
  * `docs/architecture.md`: High-level system overview, component layers, and data flow.
  * `docs/modules/`: Detailed breakdown of specific subsystems or mod packages.
  * `docs/decisions/`: Brief notes on architectural choices or trade-offs made during development.

### Documentation Maintenance Is Mandatory
* **After Every Implementation:** Once code changes are complete, review whether the changes affect existing documentation. Update relevant documentation in `docs/` whenever implementation details, behavior, dependencies, configuration, or code organization have changed.
* **No Documentation Drift:** Documentation must accurately reflect the current implementation, not the intended implementation or outdated behavior.
* **Verify Before Finishing:** Before declaring a task complete, verify that all necessary documentation updates have been made alongside the code changes.
* **No Unnecessary Documentation:** Do not modify documentation for purely cosmetic changes, formatting fixes, or changes that have no architectural, behavioral, or developer-facing impact.
* **Completion Reporting:** Include documentation files created or updated in the final change summary.

---

## 2. Workspace Ignore Rules & Boundaries
To protect system performance, prevent accidental edits to generated build artifacts, and control token consumption, **you must completely ignore** the following files, directories, and patterns:
* `build/`
* `target/`
* `.gradle/`
* `node_modules/`
* `bin/`
* `obj/`
* `.idea/`
* `.vscode/`
* `*.iml`
* `.DS_Store`
* `*.log`
* `run/`
* `run-server/`
* `src/generated/`
* Compiled binaries (`*.jar`, `*.class`, `*.exe`, `*.dll`, `*.so`)
* Large media asset archives or raw texture packs unless explicitly asked to modify a specific asset source.

---

## 3. Execution Constraints
* **Token Efficiency:** Do not read or ingest files matching the ignore patterns above into your context window.
* **Atomic Changes:** Keep code modifications scoped cleanly and document any structural changes in `docs/` during your execution cycle.