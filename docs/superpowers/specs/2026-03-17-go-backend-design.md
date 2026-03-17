# Go Backend Design

**Date:** 2026-03-17
**Status:** Approved

## Overview

Add a Go code generation backend to the Temper compiler. The backend translates Temper source modules to idiomatic Go 1.24+ source files. It follows the same plugin-based architecture as the existing Rust, Python, Java, and JavaScript backends.

## Constraints and Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Error handling | Multi-return `(T, error)` | Idiomatic Go; maps cleanly to Temper's bubble mechanism |
| Go version | 1.24 (latest stable) | Enables generics for clean parameterized type translation |
| Async/coroutines | Translate to sync | Simpler generated code; matches Rust/Python backends; goroutines add complexity for library code |
| Module path | From library metadata | `Symbol("goModulePath")` constant in `GoBackend`; read via `TString.unpackOrNull(configExports[goModulePathSymbol]) ?: error("go-module-path config is required")`; does not fall back to `backendLibraryName` since Go module paths are URLs, not dashed identifiers |
| Code generation strategy | Lightweight in-memory Go AST → render (Option B) | Matches dominant pattern in codebase (Rust, Java); clean separation between translation and rendering; fallback to `go/ast` + `gofmt` subprocess (Option C) if Option B proves insufficient |

## Architecture

The `be-go/` subproject is a Kotlin Multiplatform (`mpp`) project. It is registered in `settings.gradle` and added as a dependency in `bundled-backends/`.

### Compilation pipeline

1. **`GoBackend.tentativeTmpL()`** — delegates to shared `TmpLTranslator` with `GoSupportNetwork`
2. **`GoBackend.translate()`** — drives `GoTranslator` per module; produces `.go` `TranslatedFileSpecification`s and a `go.mod` `MetadataFileSpecification`
3. **`GoTranslator`** — walks the `TmpL` tree, builds an in-memory Go AST (`Go.kt`, `object Go { ... }`), then renders it to tokens via `GoRenderer`
4. **`GoBackend.postWrite()`** — runs `gofmt` as a formatting pass; `go build` verification runs from `GoSpecifics.runBestEffort` inside the test runner (not `postWrite`), matching the Rust/Lua pattern

### Key files

```
be-go/
├── build.gradle
└── src/
    ├── commonMain/
    │   ├── kotlin/lang/temper/be/go/
    │   │   ├── GoBackend.kt          # Backend + Factory, lifecycle methods
    │   │   ├── GoTranslator.kt       # TmpL → Go AST per module
    │   │   ├── Go.kt                 # object Go { ... } — lightweight in-memory Go AST nodes
    │   │   ├── GoRenderer.kt         # Go AST → TokenSink
    │   │   ├── GoSupportNetwork.kt   # Strategies + builtin support code
    │   │   ├── GoNames.kt            # Name/identifier handling
    │   │   ├── GoSpecifics.kt        # go build / go test invocation
    │   │   └── GoExt.kt              # Misc utilities
    │   └── resources/lang/temper/be/go/
    │       └── temper-core/          # Bundled Go runtime support package
    │           ├── result.go
    │           ├── string.go
    │           ├── list.go
    │           └── math.go
    └── commonTest/
        └── kotlin/lang/temper/be/go/
            ├── GoFunctionalTest.kt   # Extends FunctionalTestRunner<GoBackend>
            └── GoBackendTest.kt      # Unit tests for translation logic
```

## Type Mapping

| Temper | Go |
|---|---|
| `Int` | `int` |
| `Float64` | `float64` |
| `Boolean` | `bool` |
| `String` | `string` |
| `Void` | omitted — `representationOfVoid` returns `DoNotReifyVoid` for both genres |
| `T?` (nullable) | `*T` for value types; nil-able interface for interface types |
| `List<T>` | `[]T` |
| `Map<K,V>` | `map[K]V` |
| `Result<T,E>` | `(T, error)` multi-return |
| `Date` | `time.Time` (stdlib) |
| `StringBuilder` | `strings.Builder` (stdlib) |

### Error bubbling

`GoSupportNetwork` uses `bubbleStrategy = BubbleBranchStrategy.IfHandlerScopeVar` (same as Rust), which causes `TmpLTranslator` to emit `if`-branch TmpL nodes for error propagation. Temper's `!` (bubble) operator maps to:
```go
result, err := someFunc()
if err != nil {
    return zero, err
}
```

`TemperError` in `temper-core/result.go` is a concrete type implementing `error`, used as the error value for all Temper-originated failures.

### SupportNetwork strategy values

| Property | Value |
|---|---|
| `bubbleStrategy` | `BubbleBranchStrategy.IfHandlerScopeVar` |
| `coroutineStrategy` | `CoroutineStrategy.TranslateToRegularFunction` |
| `functionTypeStrategy` | `FunctionTypeStrategy.ToFunctionType` |
| `representationOfVoid` | `RepresentationOfVoid.DoNotReifyVoid` (both genres) |

### Naming conventions

- Exported Temper declarations → `PascalCase` (Go exported)
- Private/internal declarations → `camelCase` (Go unexported)
- Temper module `my-library/utils` → Go package `utils`, import path `<module-path>/utils`
- Each Temper module becomes one `.go` file in its package directory

## Go AST Nodes

`Go.kt` contains `object Go { ... }` defining only the constructs Temper can express. Node references in `GoTranslator` and `GoRenderer` are `Go.File(...)`, `Go.FuncDecl(...)`, etc. — matching the `Rust.*` and `Py.*` conventions. Additional nodes are added as needed.

**Declarations:**
- `Go.File(packageName, imports, decls)`
- `Go.FuncDecl(name, receiver?, params, results, body)`
- `Go.TypeDecl(name, type)`
- `Go.StructType(fields)`
- `Go.InterfaceType(methods)`

**Statements:**
- `Go.BlockStmt(stmts)`
- `Go.ReturnStmt(results)`
- `Go.IfStmt(init?, cond, body, else?)`
- `Go.AssignStmt(lhs, rhs, op)` — `=` or `:=`
- `Go.ExprStmt(expr)`

**Expressions:**
- `Go.CallExpr(fun, args)`
- `Go.SelectorExpr(x, sel)`
- `Go.IndexExpr(x, index)` — slice/map subscript
- `Go.IndexTypeExpr(x, typeArgs)` — generic type instantiation, e.g. `SomeType[T, U]`
- `Go.BinaryExpr(x, op, y)`
- `Go.UnaryExpr(op, x)`
- `Go.CompositeLit(type?, elts)`
- `Go.Ident(name)`
- `Go.BasicLit(kind, value)`
- `Go.StarExpr(x)`
- `Go.SliceExpr(x, low?, high?)`
- `Go.TypeAssertExpr(x, type)`

## Runtime Support Library

Bundled as compiler JAR resources at `be-go/src/commonMain/resources/lang/temper/be/go/temper-core/`. Package name: `tempercore`.

| File | Contents |
|---|---|
| `go.mod` | Module declaration for `temper-core` package (required for `go mod tidy` with local replace directive) |
| `result.go` | `TemperError` type implementing `error` |
| `string.go` | String utilities (indexOf, slice, codepoint ops) |
| `list.go` | Slice helpers (immutable-style append, bounds-checked get) |
| `math.go` | Integer overflow checking, float utilities |

Generated library `go.mod` includes a `go 1.24` directive (pinned in `GoSpecifics` as a minimum-version constant, analogous to `RustcCommand.minVersion`) and references `temper-core` via a local `replace` directive pointing at the extracted resource directory. `temper-core/go.mod` declares its own module path (e.g. `temper.systems/core/go`).

## Testing

**`GoFunctionalTest.kt`** extends `FunctionalTestRunner<GoBackend>` and implements `runGeneratedCode()`:
1. Copy generated `.go` files and `temper-core/` into a temp directory
2. Run `go mod tidy` then `go build ./...`
3. For output-asserting tests: build and run the executable, assert stdout matches expected

All existing functional test suite cases (hello world, control flow, type operations, algorithms, etc.) are exercised automatically.

**`GoBackendTest.kt`** covers unit-level concerns: individual TmpL node → Go AST translations, name mangling, type mapping edge cases.

**CI:** Go is pre-installed on `ubuntu-latest` GitHub-hosted runners; verify the pre-installed version meets Go 1.24 before adding an explicit `actions/setup-go` step.

**`ControlFlowAsync`:** Since `coroutineStrategy = TranslateToRegularFunction`, async Temper code is lowered to synchronous Go. The Rust backend uses the same strategy and passes `ControlFlowAsync`. The Go column in the functional test matrix should start with `ControlFlowAsync` marked as expected-pass; open a tracking issue if it fails.

## Integration Steps

The following mechanical wiring is required beyond writing `be-go/` itself:

1. `settings.gradle` — add `include ':be-go'`
2. `bundled-backends/build.gradle` — add `implementation project(':be-go')` dependency
3. `.github/workflows/build-and-run-tests.yml` — add `go` install step
4. `functional-test-matrix.md` — add `Go` column (if this file exists and is maintained)

**`BackendSupportLevel` annotation** on `GoBackend.Factory`:
```kotlin
@BackendSupportLevel(isSupported = false, isDefaultSupported = false, isTested = true)
```
This gates the backend from becoming a default compilation target until it graduates from CI-only status.

## Out of Scope

- Goroutine/channel translation (future work; sync translation ships first)
- CGo interop
- Build tag support
- `go generate` integration
