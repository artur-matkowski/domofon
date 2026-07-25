# Conventions

*[Wiki home](README.md) › conventions*

## Layering (single Gradle module, enforced by package + review)

```
domain/     pure Kotlin. No android.* imports, no upward references. Unit-test target.
data/       talks to the world (broker, prefs, camera, location). May use domain.
ui/         renders. Consumes data via ui/shared/GateViewModel; never owns a socket.
receivers/  thin framework entry points; delegate to the container immediately.
root        entry points that cannot move + AppContainer.
```

Rules of thumb: only `data/mqtt` speaks MQTT; only `data/config` touches persistent
settings; policy/wording lives in `domain/GatePolicy` and nowhere else; anything a JVM
test should reach lives in `domain/` or behind constructor-injected flows.

## Ownership idioms (RAII in Kotlin)

- A resource is a class implementing `AutoCloseable`, closed by exactly one owner, in the
  lifecycle callback paired with its creation — or `acquire(tag).use { }` for one-shot
  work. Closing twice is always safe.
- Shared-resource claims are **lease objects**, never counters
  ([modules/gate.md](modules/gate.md) invariant 2 records why).
- Coroutines live in an owned scope: a surface's `lifecycleScope`, or the container's
  `appScope` (receiver work — bounded with `withTimeoutOrNull` inside the goAsync budget).
  Never `CoroutineScope(...)` inline without an owner that cancels it.
- Late/duplicate async callbacks are invalidated by **object identity** (the transport
  handle), not by counters.

## Dependency injection

Constructor injection everywhere; `AppContainer` is the only place that news up shared
objects; `Context.container` is only for framework-constructed entry points. New interface
only when a second implementation (usually a fake) actually exists.

## Logging and redaction

Tag `"Domofon"`. Never log: broker host, credentials, camera URLs, home coordinates,
network exception *messages* (they embed URLs). Class names and error codes are the safe,
diagnosable parts. Release strips `Log.v/d/i` (`-assumenosideeffects`) — anything a field
failure needs must be `Log.w`/`Log.e`.

## Comments

Comments state constraints the code cannot show — usually *why*, often with the measured
failure that created the rule. This codebase's comment density is deliberate; keep it for
load-bearing invariants, don't narrate mechanics.

## Commits

**Conventional Commits** — `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`,
`build:` (optional scope); `feat!:` or a `BREAKING CHANGE:` footer marks a breaking
change. **The type is load-bearing, not cosmetic**: the release script derives the next
semver from commit types since the last `v*` tag. End Claude-authored commits with the
`Co-Authored-By: Claude` trailer.

## Versioning

Derived from git, never hand-edited: `versionCode` = commit count, `versionName` =
`git describe` (debug) or the computed semver (release). See
[build-and-release.md](build-and-release.md).

## Documentation

- Solved a problem? Append Symptom → Cause → Fix to
  [troubleshooting.md](troubleshooting.md).
- Made a decision worth defending later, or killed a design? Add it to
  [architecture/decisions.md](architecture/decisions.md).
- Changed a module's API or invariants? Update its page under `modules/`.

### The module page template

```markdown
# Module: <name> (<packages>)
*[Wiki home](../README.md) › modules › <name>*
## Responsibility        one paragraph + a class table
## Public API            signatures callers actually touch
## Invariants            numbered; each states its WHY (usually the bug it prevents)
## Gotchas               the non-obvious rest
## Related pages         relative links
```

Relative links only (`[gate](modules/gate.md)`), so the wiki works on GitHub, in editors,
and offline. Every page opens with a breadcrumb to the wiki home.
