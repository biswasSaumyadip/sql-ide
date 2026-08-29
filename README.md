# SQL IDE

A cross-platform SQL IDE (in the spirit of DataGrip / DBeaver) built with JavaFX.

## Tech stack

| Concern            | Choice                                      |
| ------------------ | ------------------------------------------- |
| Language           | Java 21 (builds on any JDK 21+)             |
| Build              | Gradle 9.7.1, Kotlin DSL, version catalog   |
| UI                 | JavaFX 21.0.12 via `org.openjfx.javafxplugin` |
| Theme              | AtlantaFX 2.0.1 (`CupertinoDark`)           |
| Editor             | RichTextFX 0.11.5                           |
| Connection pooling | HikariCP 6.3.0                              |
| Default driver     | `mysql-connector-j` 9.3.0                   |

Dependency versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Requirements

- JDK 21 or newer on `PATH` (or `JAVA_HOME`). No local Gradle install needed — use the wrapper.

## Running

```bash
./gradlew run          # Linux / macOS
.\gradlew.bat run      # Windows
```

## Building

```bash
./gradlew build        # compile + test
./gradlew installDist  # runnable distribution under build/install/sql-ide
```

## Architecture

The codebase is split so that nothing touching JDBC knows about JavaFX:

```
com.lazaro.sqlide
├── Main.java          application entry point, theme bootstrap
├── core.db            headless database + schema introspection services
└── ui.components      SQL editor, dynamic result grid, main layout
```

**Concurrency rule:** no database call ever runs on the JavaFX Application Thread.
Every query goes through a `javafx.concurrent.Task` or `CompletableFuture`, and UI
mutation happens only in JavaFX callbacks or via `Platform.runLater()`.

## Roadmap

- [x] **Phase 1** — Gradle build, JavaFX + AtlantaFX bootstrap window
- [ ] **Phase 2** — Headless engine: `DatabaseService`, `SchemaIntrospectionService`
- [ ] **Phase 3** — `SqlEditorPane` (syntax highlighting), `DynamicResultTable`
- [ ] **Phase 4** — Main layout: schema tree, split panes, toolbar
- [ ] **Phase 5** — Concurrency bridge wiring UI actions to background services
