---
id: software-development
title: Software Development
kind: domain
summary: The craft day to day — clean code, testing, debugging, Kotlin and Android, tools, delivery and growing as a developer.
---

## Clean code essentials
tags: clean code, naming, functions, readability, comments
ask: How long should a function be? | When are comments worth writing?
Code is read far more than it's written, so optimize for the reader.
- Names reveal intent: overdueWorkOrders beats list2; avoid abbreviations and noise words (data, info, manager). Booleans read as questions (isOverdue, hasPermission).
- Functions do one thing at one level of abstraction. Short is a side effect of focus, not a goal; a 30-line function that reads top to bottom is fine.
- Few parameters; group related ones into an object. Avoid boolean flag arguments that switch behavior — split into two functions.
- Prefer early returns over deep nesting.
- Comments explain why, not what: business rules, surprising constraints, links to tickets or specs. If a comment explains what the code does, try renaming instead.
- Consistency beats personal preference: follow the codebase's conventions and let a formatter (ktlint, Prettier, Black) settle style debates.
Readable code is also the cheapest defense against bugs.

## Refactoring
tags: refactoring, code smells, technical debt, safe changes
ask: What are the most common code smells? | How do you refactor without breaking things?
Refactoring changes the structure of code without changing its behavior, in small, verifiable steps. Martin Fowler's catalog names the moves: Extract Function, Inline, Rename, Move, Extract Class, Replace Conditional with Polymorphism, Introduce Parameter Object, and more.

Code smells that suggest refactoring: long functions, large classes, duplicated logic, long parameter lists, feature envy (a method more interested in another class's data), primitive obsession (strings for everything), shotgun surgery, divergent change, and comments covering for unclear code.

Safety rules: have tests first (write characterization tests for legacy code); make one change at a time and run tests after each; commit often so you can roll back; use the IDE's automated refactorings; and never mix refactoring with behavior changes in the same commit — reviewers can't tell which is which. The "make the change easy, then make the easy change" approach (Kent Beck) is the everyday version.

## Testing strategy: unit, integration and end-to-end
tags: testing, unit tests, integration tests, end-to-end, test pyramid, mocks
ask: How many tests are enough? | When should I use mocks?
The test pyramid suggests many fast unit tests, fewer integration tests and a handful of end-to-end tests. The "testing trophy" variant emphasizes integration tests because they catch more real bugs per test. Either way the goal is confidence per minute of test time.

- Unit tests check one piece of logic in isolation, run in milliseconds and pinpoint failures.
- Integration tests check real collaborators together — your code with a real database (Testcontainers, an in-memory Room database) or HTTP layer.
- End-to-end tests drive the whole system like a user. They're slow and flaky-prone, so cover only critical journeys.

Test doubles: stubs return canned answers, fakes are working lightweight implementations (an in-memory repository), mocks verify interactions. Prefer fakes and real objects; over-mocking couples tests to implementation and makes refactoring painful. Good tests are deterministic, independent, readable (Arrange-Act-Assert) and test behavior, not private details. Coverage is a flashlight, not a target.

## Test-driven development and BDD
tags: TDD, BDD, red green refactor, Gherkin, test first
ask: Is TDD worth it? | What does a good first test look like?
Test-driven development cycles red, green, refactor: write a small failing test, write the simplest code that passes it, then clean up while tests stay green. Benefits: designs that are naturally testable and decoupled, a fast feedback loop, and a regression suite as a by-product. It shines for business logic, parsers, calculations and bug fixes (write the test that reproduces the bug first). It's less useful for exploratory UI work or spikes where you don't know the shape yet.

Tips: start with the simplest case, take small steps, and use the tests to discover the API you wish you had.

Behavior-driven development (BDD) frames tests as examples of behavior in shared language — Given a work order due yesterday, When the daily check runs, Then it's marked overdue. Tools like Cucumber turn Gherkin scenarios into executable tests, but the bigger win is the conversation with stakeholders that produces clear examples.

## Debugging methodically
tags: debugging, bugs, git bisect, root cause, troubleshooting
ask: What do you do when you can't reproduce a bug? | How does git bisect work?
Debugging is the scientific method applied to code.
- Reproduce reliably, ideally with an automated test. Capture exact inputs, versions and environment.
- Read the actual error and stack trace slowly; the answer is often there.
- Form a hypothesis, predict what you'll see, then test it with a debugger, logs or assertions. Change one thing at a time.
- Narrow the search: binary search through code paths, inputs or history. git bisect finds the commit that introduced a bug by testing halfway points between a good and a bad commit — automate it with git bisect run.
- Question assumptions: is that code even running? Is the config what you think? Is the cache stale?
- Explain the problem out loud (rubber-duck debugging); it surfaces hidden assumptions.
- When fixed, find the root cause, not just the symptom (Five Whys), add a regression test, and look for the same bug pattern elsewhere.
For intermittent bugs suspect concurrency, time, ordering, external services or resource limits, and add logging around the suspicious boundary.

## Branching strategies
tags: branching, trunk-based development, GitFlow, feature branches, version control
ask: Trunk-based or GitFlow? | How long should a branch live?
Trunk-based development: everyone integrates small changes into main at least daily, using short-lived branches and pull requests, with feature flags hiding unfinished work. It minimizes merge pain and is what DORA research associates with high performance. It needs good automated tests and CI.

GitHub Flow is a lightweight version: branch from main, open a pull request, review, merge, deploy. Main is always deployable.

GitFlow uses long-lived develop and main branches plus feature, release and hotfix branches. It suits software with scheduled, versioned releases and multiple supported versions (installed products, some mobile apps), but adds overhead and merge conflicts for continuously deployed services.

Whatever the model: keep branches short-lived (days, not weeks), rebase or merge from main often, keep pull requests small, and protect main with required checks.

## Code review that helps
tags: code review, pull requests, feedback, collaboration
ask: What should reviewers focus on? | How small should a pull request be?
Code review exists to share knowledge, catch defects and keep the codebase coherent — not to prove who's smarter.

For authors: keep pull requests small (a few hundred lines at most), single-purpose, and well described — what, why, how it was tested, screenshots for UI. Review your own diff first. Separate refactoring from behavior changes.

For reviewers: prioritize correctness, security, design and readability over style (leave style to linters). Ask questions instead of issuing orders ("What happens if this list is empty?"). Label comment weight: blocking, suggestion, nit. Praise good work specifically. Review promptly — waiting days for a review kills flow. Approve when the change improves the codebase even if it isn't how you'd have written it.

Google's guideline is a good north star: approve once the change definitely improves overall code health, even if it isn't perfect.

## Continuous integration and delivery
tags: CI, CD, continuous delivery, deployment, feature flags, canary, blue-green
ask: What belongs in a CI pipeline? | Canary or blue-green deployments?
Continuous integration means merging to main frequently with an automated build and test on every change; a broken build is fixed immediately. Continuous delivery keeps main always releasable, and continuous deployment releases every passing change automatically.

A solid pipeline: build once, then promote the same artifact through environments; run fast checks first (lint, unit tests), then slower ones (integration, security scans); keep it under about ten minutes; cache dependencies; and make failures easy to read.

Release safely: feature flags decouple deploy from release; blue-green deployments switch traffic between two identical environments for instant rollback; canary releases send a small percentage of traffic to the new version and watch metrics before expanding; staged rollouts do the same for mobile apps. Database changes use the expand-and-contract pattern: add new columns, migrate, switch code, then remove old ones in a later release, so every step is backward compatible.

## Dependencies and supply chain
tags: dependencies, semantic versioning, lockfiles, SBOM, supply chain, security
ask: How do you keep dependencies up to date safely? | What is an SBOM?
Every dependency is code you didn't write but are responsible for. Before adding one, check maintenance activity, license, security history, size and whether a few lines of your own code would do.

Semantic versioning (MAJOR.MINOR.PATCH) signals breaking changes, features and fixes — but trust it with care. Commit lockfiles (package-lock.json, poetry.lock, Gradle lockfiles or version catalogs) for reproducible builds. Update regularly in small batches with automation (Dependabot, Renovate) and good tests; big-bang upgrades after two years are painful.

Supply-chain risks include typosquatted packages, compromised maintainers, malicious install scripts and dependency confusion. Defenses: pin versions, verify checksums and signatures, use private registries or proxies, scan for known vulnerabilities, review new dependencies, and generate a Software Bill of Materials (SBOM, in SPDX or CycloneDX format) so you know what you ship. Frameworks such as SLSA describe levels of build integrity.

## Kotlin essentials
tags: Kotlin, null safety, data classes, sealed classes, extension functions, Android
ask: What makes Kotlin better than Java? | When should I use a sealed class?
Kotlin is concise, null-safe and fully interoperable with Java.
- Null safety: types are non-null by default; String? may be null. Use ?. (safe call), ?: (Elvis) for defaults, and avoid !! except where you can prove non-null.
- val is read-only, var is mutable — prefer val.
- Data classes generate equals, hashCode, toString and copy; ideal for immutable state.
- Sealed classes and interfaces model closed hierarchies (Loading, Success, Error); when expressions over them are exhaustive, so the compiler catches missing cases.
- Extension functions add behavior to existing types without inheritance.
- Scope functions: let (transform nullable values), apply (configure an object), also (side effects), run and with (compute in a context).
- Collections: map, filter, groupBy, associateBy, fold, and sequences for lazy large pipelines.
- Default and named arguments replace telescoping constructors.
- Smart casts after is-checks, string templates, and expression-bodied functions keep code short.
BlackLab itself is written in Kotlin with Jetpack Compose.

## Kotlin coroutines and Flow
tags: coroutines, Flow, StateFlow, structured concurrency, dispatchers, Kotlin
ask: StateFlow or SharedFlow? | How does cancellation work in coroutines?
Coroutines are lightweight, suspendable computations. suspend functions can pause without blocking a thread.

Structured concurrency: coroutines launch inside a CoroutineScope (viewModelScope, lifecycleScope), and when the scope is cancelled, its children are cancelled too — no leaks. launch starts fire-and-forget work; async returns a Deferred for a result. coroutineScope waits for children and fails if one fails; supervisorScope lets siblings fail independently.

Dispatchers: Main for UI, IO for blocking I/O, Default for CPU-heavy work. Use withContext to switch. Cancellation is cooperative: suspending calls check it, and long loops should call ensureActive() or yield(). Don't swallow CancellationException.

Flow is a cold asynchronous stream with operators (map, filter, combine, debounce, flatMapLatest). StateFlow holds a current value and is ideal for UI state; SharedFlow broadcasts events to multiple collectors without a current value. In Compose, collect with collectAsStateWithLifecycle so collection stops in the background.

## Android app architecture
tags: Android, architecture, ViewModel, Room, WorkManager, lifecycle, MVVM
ask: How should an Android app be layered? | When do I use WorkManager?
Google's recommended architecture has a UI layer (Compose screens plus ViewModels holding UI state), an optional domain layer (use cases for reusable business logic), and a data layer (repositories that own data sources such as Room databases, DataStore and network APIs).

Principles: unidirectional data flow (state flows down, events flow up); a single source of truth for each piece of data; the UI observes state as StateFlow; and the ViewModel survives configuration changes.

Key Jetpack pieces: Room for SQLite with compile-time checked queries and migrations; DataStore for preferences; WorkManager for deferrable, guaranteed background work that survives restarts; Navigation for screens; Hilt or manual dependency injection.

Respect the lifecycle: don't hold Activity references in long-lived objects, stop collecting when not visible, and use foreground services only for user-visible ongoing work (recording, playback, navigation) with the right service type. Android also enforces background limits, runtime permissions and scoped storage; design around them early.

## Jetpack Compose
tags: Compose, Jetpack Compose, recomposition, state, side effects, Android UI
ask: Why is my composable recomposing so much? | How do side effects work in Compose?
Compose is Android's declarative UI toolkit: UI is a function of state, and when state changes, affected composables recompose.

State: remember keeps values across recompositions; rememberSaveable survives configuration changes and process death; mutableStateOf makes reads observable. Hoist state to the lowest common parent and pass values down with event callbacks up, so composables are stateless and reusable.

Side effects: LaunchedEffect runs suspend work tied to keys; DisposableEffect registers and cleans up listeners; rememberCoroutineScope launches from callbacks; SideEffect publishes to non-Compose code; derivedStateOf avoids recomposing on every change of a fast-moving input.

Performance: keep composables skippable by passing stable, immutable parameters; use keys in LazyColumn items; defer state reads (lambda-based modifiers like offset { }) to the layout or draw phase; avoid heavy work in composition; and check with the Layout Inspector's recomposition counts. Baseline profiles improve startup and scrolling smoothness.

## Python for engineers
tags: Python, virtual environments, typing, packaging, uv, idioms
ask: How should I set up a Python project? | What are Python's must-know idioms?
Python is the lingua franca of scripting, data and AI.
- Isolate every project in a virtual environment. Modern tooling: uv (very fast installs, lockfiles and Python version management), or venv plus pip, or Poetry. Declare dependencies in pyproject.toml.
- Use type hints and check them with mypy or pyright; add dataclasses or Pydantic models for structured data.
- Idioms: list, dict and set comprehensions; enumerate and zip; context managers (with open(...)) for cleanup; f-strings; pathlib for paths; generators for streaming large data; unpacking.
- Know the traps: mutable default arguments (def f(x=[])), late-binding closures in loops, and integer versus float division.
- Concurrency: threads for I/O-bound work (the GIL limits CPU parallelism, though free-threaded builds are arriving), multiprocessing for CPU-bound work, asyncio for many concurrent network calls.
- Quality tools: Ruff for linting and formatting, pytest for tests.
For facilities work, Python plus pandas is excellent for crunching meter readings, work-order exports and budgets.

## JavaScript and TypeScript essentials
tags: JavaScript, TypeScript, event loop, promises, async, Node.js
ask: How does the JavaScript event loop work? | Why use TypeScript?
JavaScript runs on a single thread with an event loop: synchronous code runs to completion; then microtasks (promise callbacks) run, then macrotasks (timers, I/O events). Long synchronous work freezes the UI, so break it up or move it to a Web Worker.

Async: promises represent future values; async/await makes them read like synchronous code. Run independent work concurrently with Promise.all (or Promise.allSettled when failures shouldn't cancel the rest) instead of awaiting in sequence. Always handle rejections.

Gotchas: == coerces types (use ===); this depends on how a function is called (arrow functions capture it lexically); floating-point math (0.1 + 0.2); and mutation through shared object references.

TypeScript adds static types: interfaces and type aliases, union types and narrowing, generics, and discriminated unions for state machines. Enable strict mode. Types catch whole classes of bugs and make refactoring safe, and editors use them for autocompletion. Node.js runs JavaScript on servers; npm and pnpm manage packages.

## SQL fluency
tags: SQL, joins, window functions, CTE, query optimization, N+1
ask: How do window functions work? | What is the N+1 query problem?
SQL is worth mastering; it outlives most frameworks.
- Joins: INNER keeps matches, LEFT keeps all rows from the left side, and FULL OUTER keeps both. Watch for row multiplication when joining one-to-many twice.
- Aggregation: GROUP BY with COUNT, SUM, AVG; HAVING filters groups.
- Common table expressions (WITH) make complex queries readable; recursive CTEs walk hierarchies such as building › floor › room › asset.
- Window functions compute across related rows without collapsing them: ROW_NUMBER() OVER (PARTITION BY asset ORDER BY date DESC) finds the latest inspection per asset; LAG compares with the previous reading; running totals use SUM() OVER (ORDER BY ...).
- Performance: read the plan with EXPLAIN (ANALYZE); add indexes for WHERE, JOIN and ORDER BY columns; avoid functions on indexed columns in filters; select only needed columns.
- The N+1 problem: one query for a list, then one query per item. Fix it with a join, an IN query, or eager loading in the ORM.
Always use parameterized queries — never build SQL by concatenating user input.

## Data structures and complexity
tags: data structures, Big-O, complexity, hash map, tree, heap, graph
ask: When should I use a heap? | Explain Big-O simply
Big-O describes how cost grows with input size: O(1) constant, O(log n) logarithmic (binary search), O(n) linear, O(n log n) (good sorting), O(n²) quadratic (nested loops — fine at 100 items, painful at a million).

Core structures:
- Array or list: O(1) index access, O(n) search, appends amortized O(1).
- Hash map and set: O(1) average lookup, insert and delete; the workhorse for de-duplication, counting and indexing.
- Balanced tree (sorted map): O(log n) operations with ordering and range queries.
- Heap (priority queue): O(1) peek at the min or max, O(log n) insert and remove; great for "top K", scheduling and Dijkstra.
- Stack (LIFO) and queue or deque (FIFO): undo, parsing, BFS.
- Graph (adjacency list): networks, dependencies, routes.
- Trie: prefix search and autocomplete.
Pick the structure by the operations you need most often. Space complexity matters too, especially on phones.

## Algorithm patterns
tags: algorithms, two pointers, sliding window, BFS, DFS, dynamic programming, binary search
ask: How do I recognize a dynamic programming problem? | Teach me the sliding window pattern
Most problems are variations on a handful of patterns:
- Two pointers: sorted arrays, pair sums, de-duplicating in place.
- Sliding window: longest or shortest subarray or substring meeting a condition; expand right, shrink left.
- Binary search: on sorted data, or on the answer itself ("smallest capacity that works").
- BFS: shortest path in unweighted graphs, level-by-level processing. DFS: exploring all paths, connected components, cycle detection, topological sort (dependency ordering).
- Dijkstra for weighted shortest paths; union-find for connectivity.
- Dynamic programming: overlapping subproblems plus optimal substructure. Define the state, the recurrence and base cases; memoize top-down or fill a table bottom-up. Classic examples: knapsack, edit distance, longest increasing subsequence.
- Greedy: make the locally best choice when you can prove it's globally optimal (interval scheduling).
- Backtracking: permutations, combinations, constraint puzzles.
Practice by naming the pattern before coding.

## Error handling
tags: error handling, exceptions, result types, fail fast, resilience
ask: Exceptions or result types? | How do you write a good error message?
Errors are part of the design, not an afterthought.
- Fail fast on programmer errors (invalid state, broken invariants) with assertions or exceptions — hiding them spreads corruption.
- Handle expected failures (network down, file missing, validation errors) explicitly. Kotlin's Result, sealed result types, or Either make the failure path visible in the type system; exceptions are fine when failure is truly exceptional.
- Catch at the right level: where you can do something useful (retry, fall back, show a message). Don't catch and ignore. Don't catch broad Exception deep in libraries.
- Preserve context: wrap low-level errors with what you were trying to do ("Couldn't save work order 42: disk full"), and keep the cause.
- Error messages for users say what happened and what to do next, without stack traces or blame. Messages for developers include identifiers and values.
- Clean up with finally, use, or try-with-resources so failures don't leak resources.
- In coroutines, never swallow CancellationException.

## Logging well
tags: logging, structured logs, log levels, correlation ID, privacy
ask: What log levels should I use? | What should never go in logs?
Logs are for the person debugging at 2 a.m., often you.
- Use structured logging (key-value or JSON) so logs are searchable: event=work_order_saved id=42 duration_ms=18.
- Levels: ERROR for failures needing attention, WARN for recoverable oddities, INFO for significant business events, DEBUG for details off by default. Don't log errors you've handled as ERROR.
- Include context: request or correlation IDs, user or tenant IDs (pseudonymous where possible), versions.
- Never log secrets, passwords, tokens, full card numbers or sensitive personal data. Mask or omit them. Logs leak — they get copied to tools and tickets.
- Log once, at the boundary where you handle the error, not at every layer.
- Watch volume and cost: sample noisy debug logs, set retention policies.
- On mobile, keep release builds quiet; logcat is readable by anyone with debugging access to the device.

## Secure coding
tags: secure coding, injection, XSS, secrets, cryptography, input validation
ask: How do I store secrets safely? | What crypto mistakes are most common?
- Validate input at trust boundaries: allow-lists over block-lists, with type, length and range checks.
- Prevent injection: parameterized SQL, safe APIs for shell commands (no string concatenation), and context-aware output encoding against XSS. Use framework defaults that escape automatically.
- Authorization on every server request; don't trust client-side checks or hidden fields.
- Secrets never go in source code or repos. Use a secret manager or environment injection in CI; on Android, the Keystore for keys. Rotate secrets and scan repositories for leaks — BlackLab runs a secret scan before every push.
- Cryptography: don't invent your own. Use high-level libraries (libsodium, Tink), AES-GCM for encryption, random IVs, a CSPRNG for tokens, Argon2 or bcrypt for passwords, and TLS everywhere. Never use MD5 or SHA-1 for security, or ECB mode.
- Deserialize untrusted data carefully; prefer simple formats such as JSON with schemas.
- Keep dependencies patched and run static analysis (CodeQL, Semgrep) in CI.

## Performance in everyday code
tags: performance, profiling, memory, allocation, optimization
ask: What are easy performance wins? | When is premature optimization OK?
Knuth's line is about small inefficiencies: premature optimization is the root of much evil, but choosing the right data structure and avoiding needless work isn't premature.

Everyday wins:
- Avoid repeated work: hoist invariant calculations out of loops, memoize expensive pure functions, cache parsed results.
- Use the right structure: a set for membership tests instead of a list scan.
- Batch I/O: one query with an IN clause instead of a hundred; bulk inserts inside one transaction.
- Stream large data instead of loading it all into memory.
- Reduce allocations in hot paths (on Android, avoid allocating inside onDraw or tight animation loops).
- Do slow work off the main thread; keep the UI thread for UI.
- Measure with real profilers: Android Studio profilers and Perfetto, async-profiler for the JVM, py-spy for Python, Chrome DevTools for JavaScript.
Write the clear version first, measure, then optimize the hot spot — usually a small fraction of the code.

## Containers and Docker
tags: Docker, containers, images, Dockerfile, multi-stage, compose
ask: How do multi-stage builds work? | Containers versus virtual machines?
Containers package an app with its dependencies into an image that runs the same everywhere. Unlike virtual machines, they share the host kernel, so they start in seconds and use little overhead.

Dockerfile tips:
- Use small, trusted, pinned base images (official slim or distroless images).
- Order layers from least to most frequently changing — copy dependency manifests and install before copying source — to maximize cache hits.
- Multi-stage builds compile in one stage and copy only the artifacts into a minimal runtime stage.
- Run as a non-root user; add a .dockerignore; don't bake secrets into images.
- One main process per container; log to stdout and stderr.

Docker Compose defines multi-container setups (app, database, cache) for local development with one command. Images are stored in registries such as Docker Hub or GitHub Container Registry. Scan images for vulnerabilities and rebuild regularly to pick up base-image patches.

## Kubernetes basics
tags: Kubernetes, k8s, pods, deployments, services, orchestration
ask: Do I actually need Kubernetes? | What's the difference between a pod and a deployment?
Kubernetes orchestrates containers across a cluster. Core objects:
- Pod: one or more containers that share a network namespace; the smallest unit, and ephemeral.
- Deployment: declares the desired number of pod replicas and handles rolling updates and rollbacks.
- Service: a stable network address that load-balances across matching pods.
- Ingress or Gateway: routes external HTTP traffic to services.
- ConfigMap and Secret: configuration and credentials injected into pods.
- StatefulSet for databases and other stateful apps; Job and CronJob for batch work.

Health checks matter: liveness probes restart stuck containers; readiness probes keep traffic away until a pod is ready. Set resource requests and limits so the scheduler can place pods well. You describe desired state in YAML (often templated with Helm or Kustomize) and controllers reconcile reality toward it.

Kubernetes is powerful but complex. For a small team or a single app, a managed platform (Cloud Run, App Service, Fly.io, a simple VM) is often the better choice.

## Cloud fundamentals and infrastructure as code
tags: cloud, AWS, Azure, GCP, serverless, Terraform, infrastructure as code
ask: When does serverless make sense? | Why use Terraform?
Cloud service models: IaaS gives you virtual machines and networks; PaaS runs your code with managed scaling (App Service, Cloud Run, Elastic Beanstalk); serverless functions (Lambda, Cloud Functions) run small handlers on demand and scale to zero; SaaS is finished software.

Serverless fits spiky, event-driven workloads and small teams; watch for cold starts, execution time limits, and costs at steady high load. Managed databases, queues and storage remove a great deal of operational toil.

Infrastructure as code (Terraform or OpenTofu, Pulumi, AWS CDK, Bicep) declares infrastructure in versioned files. You get reviewable changes, reproducible environments, drift detection and disaster recovery. Keep state files secure and remote, use modules for reuse, and run plan in CI before apply.

Cost hygiene: tag resources by owner and project, set budgets and alerts, shut down idle environments, and right-size instances. The cloud bill is an architecture metric.

## Linux and shell productivity
tags: Linux, shell, bash, command line, grep, ssh, permissions
ask: What shell commands should every developer know? | How do Linux file permissions work?
The command line multiplies productivity.
- Navigation and files: cd, ls -la, find, tree, du -sh, df -h.
- Text: grep -rn (or ripgrep, rg), sed for substitutions, awk for columns, sort | uniq -c | sort -nr for counting, head and tail -f for live logs, jq for JSON.
- Pipes and redirection: chain small tools with |, write with > and append with >>, send errors with 2>&1.
- Processes: ps aux, top or htop, kill, background jobs, nohup or tmux for long-running sessions.
- Networking: curl -v, ssh with keys (never passwords), scp or rsync, ss -tulpn to see listening ports.
- Permissions: rwx for owner, group and others; chmod 600 for private keys, chmod +x for scripts; avoid chmod 777.
- Scripting: start bash scripts with set -euo pipefail, quote variables ("$file"), and prefer Python once logic gets complex.
History search (Ctrl+R), aliases and a good prompt pay off every day.

## Developer productivity and flow
tags: productivity, flow, IDE, automation, focus, tooling
ask: How do I protect deep-work time? | What tools give the biggest productivity boost?
Productivity comes from flow — long stretches of focused work — more than from typing speed.
- Protect focus: block calendar time, batch meetings, silence notifications, and keep a scratch list for interrupting thoughts.
- Master your IDE: navigation (go to definition, find usages), automated refactorings, multi-cursor editing, the debugger, and keyboard shortcuts. Ten minutes learning shortcuts saves hours.
- Automate repetitive work: scripts, Makefile or task runners, snippets, code generation, pre-commit hooks.
- Keep feedback loops fast: incremental builds, focused test runs, hot reload.
- Use AI assistants well: for boilerplate, exploring unfamiliar APIs, writing tests and explaining code — but review everything, and keep your own understanding sharp.
- Write things down: a work log, decision notes and command snippets turn into a personal knowledge base.
- Energy matters: sleep, breaks and exercise are performance tools, not luxuries.

## Writing documentation
tags: documentation, README, Diátaxis, docs, technical writing
ask: What makes a great README? | How do you keep docs from going stale?
Documentation is a product for future readers, including you.

The Diátaxis framework sorts docs by need: tutorials (learning by doing), how-to guides (solving a specific task), reference (accurate, complete facts such as APIs and configuration), and explanation (background and why). Mixing them confuses readers.

A strong README answers: what is this, why does it exist, how do I install and run it, how do I use it, how do I contribute, and where do I get help. Include a quick-start that works when copied and pasted.

Keep docs alive: store them next to the code (docs as code), review them in pull requests, generate reference docs from code where possible, add runnable examples that CI tests, and delete obsolete pages — wrong docs are worse than none. Write plainly: short sentences, active voice, concrete examples, and headings people can scan.

## Working with legacy code
tags: legacy code, characterization tests, seams, maintenance, Michael Feathers
ask: How do you get legacy code under test? | Rewrite or refactor?
Michael Feathers defines legacy code as code without tests: you can't change it with confidence.

Strategy:
- Identify change points: where must you change, and what could break?
- Find seams: places where you can alter behavior without editing the code there (constructor parameters, interfaces, overridable methods) so you can substitute test doubles.
- Write characterization tests that capture what the code currently does, even if it's wrong, before changing anything.
- Use sprout method or sprout class (write new, tested code and call it from the old code) and wrap method (wrap old behavior with new) to add features safely.
- Break dependencies with small, mechanical, low-risk refactorings.

Rewriting from scratch is tempting but risky: the old code encodes years of bug fixes and edge cases. Prefer incremental replacement (strangler fig). Respect the code — it shipped and paid the bills — and leave each area a little better each visit.

## Design patterns quick reference
tags: design patterns, Gang of Four, strategy, observer, factory, adapter, decorator, builder
ask: Which design patterns matter most today? | Strategy versus state pattern?
Patterns are named solutions to recurring problems. The most useful today:
- Strategy: swap algorithms behind an interface (pricing rules, export formats). In Kotlin often just a function parameter.
- Observer: notify subscribers of changes — the root of listeners, Flow and reactive UI.
- Factory and factory method: centralize object creation when the concrete type varies.
- Builder: construct complex objects step by step; Kotlin's named and default arguments often replace it.
- Adapter: make an existing interface fit the one you need (wrapping a vendor SDK).
- Decorator: add behavior by wrapping (logging, caching or retry around a repository).
- Facade: a simple interface over a complex subsystem.
- Command: represent actions as objects (undo/redo, queued jobs).
- State: an object's behavior changes with its state; sealed classes and state machines model it cleanly.
- Repository: abstract data access behind a collection-like interface.
Patterns are vocabulary, not goals. Reach for one when the problem appears, not in advance.

## Functional programming ideas
tags: functional programming, immutability, pure functions, higher-order functions
ask: Why does immutability reduce bugs? | What's a pure function?
Functional programming concepts improve code in any language.
- Pure functions return the same output for the same input and have no side effects. They're trivial to test, cache and parallelize.
- Immutability: values don't change after creation; you create new ones (Kotlin's copy on data classes). It eliminates whole classes of bugs from shared mutable state, especially with concurrency.
- Higher-order functions take or return functions: map, filter, reduce, fold, flatMap. They replace manual loops with intent-revealing pipelines.
- Composition: build behavior by combining small functions.
- Algebraic data types: sealed classes (sum types) and data classes (product types) model domains precisely, and pattern matching with when handles every case.
- Make illegal states unrepresentable: design types so invalid combinations can't be constructed.
- Push side effects to the edges: a functional core with an imperative shell keeps business logic pure and I/O at the boundaries.
You don't need a purely functional language; Kotlin, TypeScript and Python support these styles well.

## Reading an unfamiliar codebase
tags: onboarding, code reading, new codebase, learning, navigation
ask: Where do you start in a big codebase? | How do you ramp up on a new team fast?
Reading code is a skill you can practice.
- Start from the outside: README, architecture docs, build files and dependency manifests tell you the stack and structure.
- Run it locally, then use it like a user. Behavior gives the code meaning.
- Follow one request or feature end to end: entry point (main activity, route, controller), through services, to storage. Use go to definition and find usages heavily.
- Read tests; they're executable documentation of intended behavior.
- Use version control history: git log on key files, git blame for why a line exists, and recent pull requests for current conventions.
- Draw a quick map of modules and data flow; you'll refine it.
- Make a small, real change early (fix a typo, a tiny bug); shipping something builds context and confidence.
- Ask focused questions after you've tried: "I traced X to Y but couldn't find where Z happens — where should I look?"
AI assistants help summarize files and answer "where is X handled?", but verify against the code.

## Growing from junior to senior and beyond
tags: career, senior engineer, staff engineer, growth, mentoring, learning
ask: What separates a senior engineer from a mid-level one? | How do I become a staff engineer?
Levels differ mostly in scope and judgment, not raw coding speed.
- Junior: completes well-defined tasks with guidance; learning the tools and codebase.
- Mid-level: owns features end to end; works independently; writes solid, tested code.
- Senior: owns ambiguous problems and systems; makes sound trade-offs; anticipates operational issues; raises the team's level through reviews and mentoring; communicates clearly with non-engineers.
- Staff and principal: work across teams and multi-quarter horizons; set technical direction; find the most important problems; lead through influence rather than authority; write the documents that align people.

Habits that accelerate growth: ship and finish things, take on-call and learn from incidents, write design documents, seek feedback, teach what you learn, read other people's code, and understand the business problem behind every ticket. A background in operations leadership — managing budgets, vendors, safety and people — maps directly onto senior and staff-level expectations: prioritizing, communicating trade-offs and owning outcomes.
