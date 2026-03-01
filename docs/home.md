### 🏠 Home: Udea Engine Overview
Udea is a high-level 2D game engine built on **LibGDX** and the **Fleks ECS** framework. It is designed for networked, data-driven games, allowing developers to define gameplay logic using a powerful Kotlin-based DSL.

#### Key Features:
*   **Server-Authoritative Networking:** Built-in entity synchronization using KryoNet.
*   **Data-Driven Assets:** Define blueprints, abilities, and animations in `.udea.kts` scripts.
*   **Gameplay Ability System (GAS):** A modular system for attributes, status effects (Gameplay Effects), and abilities.
*   **Integrated Physics:** Seamless Box2D integration within the ECS.
*   **Modular UI:** Screen-based UI management using Scene2D.

#### Documentation Index:
- [🚀 Getting Started](getting_started.md)
- [🧩 ECS Framework (Fleks)](ecs.md)
- [⚔️ Gameplay Ability System (GAS)](gas.md)
- [📦 Asset Management & DSL](assets.md)
- [🌐 Networking & Synchronization](networking.md)

#### Core Modules:
- **`common`**: Core engine code, ECS systems, and network synchronization.
- **`level-editor`**: In-game editor for creating levels.
- **`idea-plugin`**: IntelliJ IDEA support for `.udea.kts` files.
- **`gradle-plugin`**: Automated code generation for DSL and networking.
