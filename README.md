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
| Embedded engine    | H2 2.3.232 (scratch databases + tests)      |

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
├── Main.java                       application entry point, theme bootstrap
├── core.db
│   ├── ConnectionConfig            endpoint + credentials, builds the JDBC URL
│   ├── QueryResult                 detached outcome of one statement
│   ├── DatabaseService             Hikari pool, async execution
│   ├── SchemaIntrospectionService  DatabaseMetaData reader
│   ├── ResultSetMapper             drains a cursor into a QueryResult
│   └── DatabaseNode/TableNode/ColumnNode
└── ui.components
    ├── SqlEditorPane               RichTextFX editor, line numbers
    ├── SqlSyntaxHighlighter        pure regex tokeniser (no JavaFX types)
    └── DynamicResultTable          grid built from result metadata
```

`core.db` has no JavaFX imports at all and is covered by its own tests, so it can
be exercised headlessly.

**Concurrency rule:** no database call ever runs on the JavaFX Application Thread.
Every query goes through a `javafx.concurrent.Task` or `CompletableFuture`, and UI
mutation happens only in JavaFX callbacks or via `Platform.runLater()`.

**Error policy:** a statement that the server rejects resolves to a `QueryResult`
with `errorMessage` set, not a failed future — a rejected query is data, not a
control-flow exception. Connection and introspection failures do fail their future,
because those are lifecycle problems.

## Testing

```bash
./gradlew test
```

The `core.db` suite runs against a throwaway in-memory H2 database, so it needs no
running server. The SQL tokeniser is tested through `SqlSyntaxHighlighter.tokenize`,
which returns plain offsets and therefore needs no JavaFX toolkit either.

## Roadmap

- [x] **Phase 1** — Gradle build, JavaFX + AtlantaFX bootstrap window
- [x] **Phase 2** — Headless engine: `DatabaseService`, `SchemaIntrospectionService`
- [x] **Phase 3** — `SqlEditorPane` (syntax highlighting), `DynamicResultTable`
- [ ] **Phase 4** — Main layout: schema tree, split panes, toolbar
- [ ] **Phase 5** — Concurrency bridge wiring UI actions to background services
