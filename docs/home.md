### 🏠 Home: Udea Engine Overview
Udea is a high-level 2D game engine built on **LibGDX** and the **Fleks ECS** framework. It is designed for networked, data-driven games, allowing developers to define gameplay logic using a powerful Kotlin-based DSL.

#### Key Features:
*   **Server-Authoritative Networking:** Built-in entity synchronization using KryoNet.
*   **Data-Driven Assets:** Define blueprints, abilities, and animations in `.udea.kts` scripts.
*   **Gameplay Ability System (GAS):** A modular system for attributes, status effects (Gameplay Effects), and abilities.
*   **Integrated Physics:** Seamless Box2D integration within the ECS.
*   **Modular UI:** Screen-based UI management using Scene2D.

> **Two trees live in this repository right now.** Everything below this box describes the
> **old** engine, which is being replaced module by module and deleted at the Phase 6 exit.
> The rewrite (`udea-*` and `moba`) is documented separately:
>
> - [The AI-native rewrite design spec](superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md) — what is being built and why.
> - [Engineering standards](engineering-standards.md) — binding on every `udea-*` module. Section 8 is what a reviewer rejects.
> - [Module graph](module-graph.md) — the modules, the arrows between them, and the `UDEA-MG-00N` rules the build enforces.
> - [Replication contract](contracts/replicator.md) — **frozen**. The index-alignment invariant every generated `Replicator` and every `FieldStore` obeys.
> - [Measured budgets](budgets.md) — the numbers the Phase 0 exit is stated in, and where CI gates each one.
> - [The K2 compiler plugin](compiler-plugin.md) — the FIR checkers, the KDoc harvester, and why the plugin must stay optional.

#### Documentation Index:
- [🚀 Getting Started](getting_started.md)
- [🧩 ECS Framework (Fleks)](ecs.md)
- [⚔️ Gameplay Ability System (GAS)](gas.md)
- [📦 Asset Management & DSL](assets.md)
- [🌐 Networking & Synchronization](networking.md)

> **These pages describe the pre-rewrite engine and are being replaced.** `AGENTS.md` at the
> repository root is the current brief: module table, dependency rules, the tick model and the
> frozen contracts. The rewrite of this tree is scheduled for Phase 6.

#### Core Modules:
- **`udea-*`**: The engine being built. See `AGENTS.md`.
- **`moba`**: The 5v5 MOBA the engine is built against.
- **`common`**: Old engine core, ECS systems and network synchronization. Deleted in Phase 6.
- **`gradle-plugin`**: Old code generation for DSL and networking. Deleted in Phase 6.
- **`example`**: Old example game. Replaced by `moba`, deleted in Phase 6.

The level editor, the IDEA plugin and `compose-ui` were deleted in Phase 0: the MCP tool
surface is the editor, so nothing replaces them. `docs/migration/ledger.md` has the order.
