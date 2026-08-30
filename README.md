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
│   ├── DataSourceDriver            the interface the UI talks to
│   ├── DriverCapabilities          what a driver supports
│   ├── DriverRegistry              driver id -> factory
│   ├── JdbcSqlDriver               JDBC/HikariCP implementation (+ session txn / cancel)
│   ├── SchemaIntrospectionService  DatabaseMetaData reader
│   ├── ResultSetMapper             drains a cursor into a QueryResult
│   ├── ConnectionConfig            endpoint + credentials, builds the JDBC URL
│   ├── QueryResult                 detached outcome of one statement
│   └── SchemaNode                  one entry in the structure tree
├── core.explain
│   ├── ExplainSql                  dialect-aware EXPLAIN wrapping
│   ├── ExplainPlanParser           tabular / text plan → tree
│   └── ExplainPlanNode             one node in a plan tree
└── ui.components
    ├── SqlEditorPane               RichTextFX editor, line numbers
    ├── SqlSyntaxHighlighter        pure regex tokeniser (no JavaFX types)
    ├── DynamicResultTable          grid built from result metadata
    └── ExplainPlanTreeView         readable EXPLAIN tree
```

`core.db` has no JavaFX imports at all and is covered by its own tests, so it can
be exercised headlessly.

**Driver abstraction:** the UI depends only on `DataSourceDriver`, obtained from
`DriverRegistry`. `JdbcSqlDriver` (registered as `jdbc-mysql`) is the only
implementation today, but nothing above the interface names it. Structure is
returned as generic `SchemaNode` values whose type-specific attributes live in a
metadata map, so a non-relational backend could populate the same tree.

**Lazy schema loading:** `getSchemaTree()` returns only the top level with children
unloaded; `getChildren(node)` fetches one level at a time. Introspecting an entire
server upfront would be unusable on a large database. An empty `children()` list
therefore does not mean "leaf" — use `SchemaNode.isLeaf()`, which is decided by
node type.

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

### MySQL integration test

`MySqlIntegrationTest` drives a real MySQL server through `DataSourceDriver`. It
**skips itself** when nothing answers, so the normal build stays self-contained.
Point it at any server:

```bash
./gradlew test -Dsqlide.mysql.host=127.0.0.1 -Dsqlide.mysql.port=3306 \
               -Dsqlide.mysql.user=root -Dsqlide.mysql.password=secret
```

Defaults are `127.0.0.1:3307`, user `root`, empty password. To spin up a disposable
server on Windows without touching an installed instance:

```powershell
$base = "C:\Program Files\MySQL\MySQL Server 8.0"
$data = "$env:TEMP\sqlide-mysql-data"
& "$base\bin\mysqld.exe" --no-defaults --initialize-insecure --basedir="$base" --datadir="$data"
& "$base\bin\mysqld.exe" --no-defaults --basedir="$base" --datadir="$data" --port=3307 --mysqlx=0 --console
```

Keep the data directory outside `build/`, or `gradlew clean` will fail trying to
delete files the running server holds open.

## Roadmap

- [x] **Phase 1** — Gradle build, JavaFX + AtlantaFX bootstrap window
- [x] **Phase 2** — Headless engine: driver + `SchemaIntrospectionService`
- [x] **Phase 3** — `SqlEditorPane` (syntax highlighting), `DynamicResultTable`
- [x] **Phase 3.5** — `DataSourceDriver` interface, `SchemaNode`, `DriverRegistry`
- [x] **Phase 4** — Main layout: schema tree, split panes, toolbar
- [x] **Phase 5** — Concurrency bridge wiring UI actions to background services
- [x] **Phase 6** — Schema-aware autocomplete, object viewer, active database
- [x] **Phase 7** — Transactions & execution control: auto-commit / begin / commit / rollback,
  cancellable queries (`Statement.cancel`), EXPLAIN / EXPLAIN ANALYZE plan tree
