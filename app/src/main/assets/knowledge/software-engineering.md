---
id: software-engineering
title: Software Engineering
kind: domain
summary: Architecture, distributed systems, reliability, security and the trade-offs behind systems that last.
---

## What software engineering actually is
tags: fundamentals, trade-offs, engineering, programming
ask: How is engineering different from just coding? | What trade-offs come up most often?
Programming is getting a computer to do something; software engineering is programming integrated over time and over people. The questions change from "does it work?" to "will it still work, and can others change it safely, in three years?" Three forces dominate: time (code must survive changing requirements, dependencies and platforms), scale (more users, more data, more engineers), and trade-offs (every decision buys something and costs something).

Good engineers make trade-offs explicit: consistency versus availability, speed of delivery versus robustness, flexibility versus simplicity. They write down why a choice was made, measure instead of guessing, and prefer decisions that are cheap to reverse. A useful habit is asking "what would make this decision wrong?" before committing to it.

## Requirements and quality attributes
tags: requirements, non-functional, quality attributes, user stories, acceptance criteria
ask: How do you write good acceptance criteria? | Which quality attributes matter most?
Functional requirements say what the system does; quality attributes (non-functional requirements) say how well: performance, availability, security, scalability, maintainability, usability, cost. Architecture is mostly driven by the quality attributes, so make them measurable: "p95 search latency under 300 ms at 200 requests per second," not "fast."

User stories ("As a facility manager, I want overdue work orders flagged so that nothing slips") capture intent; acceptance criteria make them testable, often in Given/When/Then form. Separate the problem from the proposed solution, rank requirements (MoSCoW: must, should, could, won't), and identify constraints early: regulations, budget, existing systems, team skills. Most project failures trace back to misunderstood requirements, not bad code.

## Modularity, coupling and cohesion
tags: modularity, coupling, cohesion, design, boundaries
ask: How do you spot tight coupling? | What makes a good module boundary?
Cohesion measures how strongly the parts of a module belong together; coupling measures how much modules depend on each other. Aim for high cohesion and low coupling: a change to one business rule should touch one module.

Signs of trouble: shotgun surgery (one change touches many files), modules that share database tables directly, circular dependencies, and "utility" packages that everything imports. Good boundaries follow business capabilities (billing, scheduling, inventory) rather than technical layers alone. Depend on stable abstractions, communicate through narrow interfaces, and keep data ownership clear — exactly one module writes a given piece of data. Connascence is a sharper vocabulary for coupling: prefer weak forms (name, type) over strong ones (timing, execution order, shared meaning of magic values).

## SOLID principles
tags: SOLID, object-oriented, design principles, single responsibility, dependency inversion
ask: Which SOLID principle matters most in practice? | Can SOLID be overdone?
SOLID is five object-oriented design guidelines:
- Single Responsibility: a class should have one reason to change (one stakeholder or concern).
- Open/Closed: open for extension, closed for modification — add behavior with new code (strategies, plugins) rather than editing stable code.
- Liskov Substitution: a subtype must work anywhere its base type is expected, without surprising callers.
- Interface Segregation: many small, client-specific interfaces beat one fat interface.
- Dependency Inversion: high-level policy depends on abstractions, not on low-level details like databases or HTTP clients.

In practice, Single Responsibility and Dependency Inversion pay off the most: they make code testable and keep infrastructure swappable. SOLID can be overdone — interfaces with one implementation and layers of indirection add cost. Apply it where change actually happens.

## Deep modules and information hiding
tags: abstraction, information hiding, deep modules, API design, complexity
ask: What's a shallow module? | How do you reduce complexity in a codebase?
John Ousterhout's "A Philosophy of Software Design" frames complexity as anything that makes a system hard to understand or modify, showing up as change amplification, cognitive load and unknown unknowns. His key idea is the deep module: a simple interface hiding a lot of functionality (a file system read call hides caching, scheduling and disk layout). Shallow modules have interfaces almost as complex as their implementation and add little value.

Information hiding means each module encapsulates design decisions likely to change — formats, algorithms, storage — so those decisions don't leak into callers. Other practical takeaways: define errors out of existence where possible (make operations naturally safe), pull complexity downward into the module rather than pushing it to every caller, and design it twice — sketch two alternatives before choosing.

## Layered, hexagonal and clean architecture
tags: architecture, hexagonal, ports and adapters, clean architecture, layers
ask: When is hexagonal architecture worth it? | How do layers go wrong?
Layered architecture stacks presentation, application, domain and data layers, each depending only on the one below. It's simple but tends to make everything depend on the database.

Hexagonal architecture (ports and adapters) puts the domain at the center. The domain defines ports — interfaces such as "WorkOrderRepository" or "Notifier" — and adapters implement them for the outside world (a SQL database, an email service, a REST controller, a test fake). Clean Architecture and Onion Architecture are close cousins with the same rule: source-code dependencies point inward, toward business rules.

Benefits: domain logic is testable without infrastructure, and you can swap a database or UI without rewriting rules. Costs: more types and mapping. For small CRUD apps a well-organized layered design is fine; for long-lived systems with real business rules, ports and adapters pays back.

## Monoliths, microservices and the modular monolith
tags: microservices, monolith, modular monolith, service boundaries, architecture
ask: When should a team move to microservices? | What is a modular monolith?
A monolith is one deployable unit. It's simple to build, test, deploy and debug, and transactions are easy. Microservices split the system into independently deployable services that own their data. They allow independent scaling and team autonomy but bring distributed-systems costs: network failures, eventual consistency, versioning, observability, and operational overhead.

Microservices solve an organizational problem — many teams stepping on each other — more than a technical one. A common, healthy path: start with a modular monolith (strict internal module boundaries, separate schemas or table ownership, communication through interfaces), then extract a service only when there's a clear reason: a different scaling profile, a different release cadence, or a team boundary. Red flags: services that must deploy together, chatty synchronous call chains, and a shared database — the "distributed monolith."

## Domain-driven design
tags: DDD, domain-driven design, bounded context, aggregates, ubiquitous language
ask: How do you find bounded contexts? | What is an aggregate?
Domain-driven design (DDD) aligns software structure with the business domain. Strategic DDD: build a ubiquitous language with domain experts and use the same words in code; split the domain into bounded contexts — areas where a model and its terms have one consistent meaning ("asset" in maintenance versus "asset" in accounting). Context maps describe how contexts relate (customer/supplier, anti-corruption layer, shared kernel).

Tactical DDD: entities have identity; value objects are defined by their values and are immutable (Money, Address); aggregates are consistency boundaries with one root entity that guards invariants, saved and loaded as a unit; domain events record that something meaningful happened ("WorkOrderCompleted"); repositories retrieve aggregates.

Keep aggregates small, reference other aggregates by ID, and accept eventual consistency between them. DDD is worth it where the domain is complex; for simple CRUD it's overhead.

## Event-driven architecture, event sourcing and CQRS
tags: events, event-driven, event sourcing, CQRS, pub/sub
ask: Events versus commands — what's the difference? | When does event sourcing make sense?
In event-driven architecture, components publish events ("PaymentReceived") and others react, without the publisher knowing who listens. Commands are requests to do something and have one handler; events are facts about the past and can have many subscribers. This decouples services and enables scalable, asynchronous workflows, at the cost of harder tracing and eventual consistency.

Event sourcing stores state as an append-only log of events and rebuilds current state by replaying them. You get a perfect audit trail, temporal queries and the ability to build new read models later; the costs are schema evolution of events, replay performance (use snapshots) and a steeper learning curve.

CQRS (Command Query Responsibility Segregation) separates the write model from read models optimized for queries. It pairs naturally with event sourcing but doesn't require it. Use these patterns for audit-heavy or high-scale domains, not by default.

## API design
tags: API, REST, gRPC, GraphQL, versioning, idempotency, pagination
ask: How should APIs be versioned? | REST, gRPC or GraphQL — which one?
Good APIs are consistent, predictable and hard to misuse. For REST: model resources as nouns (/work-orders/123), use HTTP methods by meaning (GET safe, PUT and DELETE idempotent, POST for creation and actions), return proper status codes (400 validation, 401 unauthenticated, 403 forbidden, 404, 409 conflict, 429 rate limited), and give errors a consistent body with a machine-readable code.

Design for change: additive changes are safe; removals and renames break clients, so version explicitly (/v2 or a header) and deprecate with timelines. Make writes idempotent with idempotency keys so clients can retry safely. Paginate with cursors rather than offsets for large or changing collections. Document with OpenAPI.

gRPC suits internal service-to-service calls (fast, typed, streaming). GraphQL suits client-driven data fetching across many entities, but needs care with caching, authorization and query cost limits. REST remains the safest default for public APIs.

## Distributed systems fundamentals
tags: distributed systems, CAP, PACELC, consistency, fallacies, network
ask: Explain CAP in plain English | What are the fallacies of distributed computing?
The fallacies of distributed computing are assumptions that bite: the network is reliable, latency is zero, bandwidth is infinite, the network is secure, topology doesn't change, there is one administrator, transport cost is zero, the network is homogeneous.

CAP says that during a network partition a system must choose between consistency (every read sees the latest write) and availability (every request gets a response). PACELC extends it: Else, when there's no partition, you trade latency against consistency. Real systems pick per operation — a bank balance wants strong consistency; a "likes" counter can be eventually consistent.

Consistency models range from linearizable (behaves like one copy) through sequential and causal to eventual consistency. Practical rules: assume any remote call can fail, time out or be duplicated; design operations to be idempotent; use timeouts everywhere; and never rely on synchronized clocks for ordering — use logical clocks, version numbers or a single leader.

## Replication and consensus
tags: replication, consensus, Raft, Paxos, quorum, leader election
ask: How does Raft work? | What does a quorum guarantee?
Replication keeps copies of data on several machines for availability and read scale. Leader/follower replication sends all writes through one leader; it's simple but the leader is a bottleneck and failover is tricky. Multi-leader and leaderless (Dynamo-style) designs accept writes in several places and must resolve conflicts.

Quorums: with N replicas, writing to W and reading from R where W + R > N guarantees a read overlaps the latest write. Typical: N=3, W=2, R=2.

Consensus algorithms such as Raft and Paxos let a cluster agree on a value or log despite failures, as long as a majority is up. Raft elects a leader with randomized timeouts; the leader appends entries to followers and commits once a majority acknowledges. Consensus underpins etcd, Consul, ZooKeeper and the control planes of Kubernetes and many databases. Rule of thumb: use a proven implementation; never write your own consensus for production.

## Scalability, partitioning and statelessness
tags: scalability, sharding, partitioning, horizontal scaling, stateless, load balancing
ask: How do you choose a shard key? | Vertical or horizontal scaling first?
Vertical scaling (a bigger machine) is simple and often the right first step. Horizontal scaling (more machines) needs work: keep application servers stateless — sessions in a shared store or signed tokens — so a load balancer can send any request anywhere.

Data is the hard part. Read replicas scale reads. Partitioning (sharding) scales writes and storage by splitting data across nodes. Choose a shard key that spreads load evenly and keeps related data together (tenant ID for a SaaS app). Avoid keys that create hot spots, like timestamps or a single big customer. Consistent hashing reduces data movement when nodes join or leave. Cross-shard queries and transactions get expensive, so design access patterns first.

Also scale with asynchrony: queues absorb bursts, background workers smooth load, and caches and CDNs remove work entirely. Measure before scaling — most systems hit database limits first.

## Caching strategies
tags: caching, cache invalidation, CDN, cache-aside, TTL, Redis
ask: How do you handle cache invalidation? | What's a cache stampede?
Caching trades freshness for speed. Common patterns:
- Cache-aside (lazy loading): the app checks the cache, falls back to the database, then fills the cache. Most common.
- Read-through and write-through: the cache layer loads and writes to the database itself; write-through keeps the cache fresh at the cost of write latency.
- Write-behind: writes go to the cache and are flushed later — fast but risky on failure.

Invalidation strategies: time-to-live (simple, bounded staleness), explicit delete on write, or versioned keys. Watch for the cache stampede: when a hot key expires, thousands of requests hit the database at once. Fixes include request coalescing (single flight), early probabilistic refresh, and jittered TTLs. Cache at many levels — browser, CDN, application, database buffer pool — and always measure hit rate. A cache that hides a slow query can become a hidden dependency: if it empties, can the database survive?

## Databases, indexes and transactions
tags: databases, SQL, NoSQL, indexes, transactions, isolation levels, ACID
ask: When should I pick NoSQL over Postgres? | What do isolation levels actually prevent?
Relational databases (PostgreSQL, MySQL, SQL Server) offer schemas, joins, constraints and ACID transactions; they're the right default for most business data. NoSQL families fit specific shapes: key-value (Redis, DynamoDB) for simple lookups at scale, document stores (MongoDB) for nested records, wide-column (Cassandra) for huge write volumes, graph databases for relationship-heavy queries, time-series databases for sensor and metrics data.

Indexes speed reads but slow writes and use space. B-tree indexes support equality and range queries; put the most selective, equality-filtered columns first in composite indexes, and check plans with EXPLAIN.

Isolation levels trade correctness for concurrency. Read Committed prevents dirty reads; Repeatable Read prevents non-repeatable reads; Serializable prevents anomalies like write skew. Many bugs come from read-modify-write races — use row locks (SELECT … FOR UPDATE), atomic updates or optimistic concurrency with a version column.

## Queues, messaging and delivery guarantees
tags: queues, messaging, Kafka, RabbitMQ, outbox, idempotency, dead letter
ask: Is exactly-once delivery real? | What is the transactional outbox?
Message queues (RabbitMQ, SQS) distribute work to consumers; logs (Kafka, Kinesis) keep an ordered, replayable stream that many consumers read at their own pace.

Delivery guarantees: at-most-once can lose messages; at-least-once can duplicate them; "exactly-once" in practice means at-least-once delivery plus idempotent processing (deduplicate by message ID, or make the effect naturally idempotent). Ordering usually holds only within a partition or key.

The dual-write problem — updating a database and publishing a message — can't be made atomic directly. The transactional outbox pattern writes the event into an outbox table in the same transaction as the data, and a relay publishes it afterward. Use dead-letter queues for messages that keep failing, with alerting and a replay path. Monitor consumer lag; a growing backlog is an early warning.

## SLIs, SLOs and error budgets
tags: reliability, SRE, SLO, SLI, error budget, availability
ask: How do you pick a good SLO? | What happens when the error budget runs out?
Site Reliability Engineering defines reliability from the user's point of view. A Service Level Indicator (SLI) is a measurement, such as the percentage of requests that succeed within 300 ms. A Service Level Objective (SLO) is the target for that indicator over a window — 99.9% over 28 days. An SLA is a contractual promise with penalties, and should be looser than the SLO.

The error budget is the allowed unreliability: 99.9% leaves 0.1%, about 40 minutes of downtime in 28 days. While budget remains, teams ship features; when it's spent, they prioritize reliability work. This turns arguments about "stability versus velocity" into data.

Choose few SLIs that reflect user pain (availability, latency, freshness, correctness), measure as close to the user as possible, and avoid 100% targets — they're impossible and freeze change. Each extra nine costs roughly ten times more.

## Resilience patterns
tags: resilience, retries, backoff, circuit breaker, timeouts, bulkhead, rate limiting
ask: How do retries make outages worse? | When do you use a circuit breaker?
Failures are normal; resilience limits their blast radius.
- Timeouts on every remote call, shorter than the caller's own timeout.
- Retries only for transient errors and idempotent operations, with exponential backoff and jitter so clients don't retry in sync. Cap total attempts and use a retry budget — naive retries multiply load during an outage (retry storms).
- Circuit breakers stop calling a failing dependency for a while and fail fast, then probe with a few requests (half-open) before closing again.
- Bulkheads isolate resources (separate thread or connection pools) so one slow dependency can't exhaust everything.
- Rate limiting and load shedding protect a service from overload; prefer serving some requests well over all requests badly.
- Backpressure lets consumers signal producers to slow down.
- Graceful degradation: serve cached or partial results when a dependency is down.
Test these with fault injection and game days, not just in theory.

## Observability: logs, metrics and traces
tags: observability, monitoring, logging, metrics, tracing, OpenTelemetry, RED, USE
ask: What should I alert on? | Logs, metrics or traces — where do I start?
Monitoring tells you whether known things are wrong; observability lets you ask new questions about unknown problems. Three signals:
- Metrics: cheap numeric time series for dashboards and alerts.
- Logs: detailed, structured events (JSON with request IDs) for investigation.
- Traces: the path of one request across services, with timing for each span.

Useful frameworks: RED for services (Rate, Errors, Duration) and USE for resources (Utilization, Saturation, Errors). The four golden signals are latency, traffic, errors and saturation. OpenTelemetry is the vendor-neutral standard for emitting all three signals.

Alert on symptoms users feel (SLO burn rate, error rate, latency), not on every cause (high CPU). Every alert should be actionable and link to a runbook. Watch cardinality — labels like user ID explode metrics cost. Propagate a correlation ID through every hop so logs and traces join up.

## Security engineering and threat modeling
tags: security, threat modeling, STRIDE, OWASP, least privilege, defense in depth
ask: How do you run a quick threat model? | What's on the OWASP Top 10?
Threat modeling asks four questions: what are we building, what can go wrong, what will we do about it, and did we do a good job? Draw a data-flow diagram with trust boundaries, then walk STRIDE: Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege.

Core principles: least privilege (minimal permissions for people, services and tokens), defense in depth (several independent controls), secure defaults, fail closed, minimize attack surface, and never trust input crossing a trust boundary.

The OWASP Top 10 for web apps covers broken access control (the most common), cryptographic failures, injection, insecure design, security misconfiguration, vulnerable and outdated components, identification and authentication failures, software and data integrity failures, logging and monitoring failures, and server-side request forgery. Bake security into the pipeline: dependency scanning, secret scanning, static analysis, and code review checklists.

## Authentication and authorization
tags: authentication, authorization, OAuth, OIDC, JWT, RBAC, sessions
ask: OAuth versus OpenID Connect? | Are JWTs a good idea for sessions?
Authentication proves who you are; authorization decides what you may do. Don't build either from scratch when a vetted provider or library exists.

OAuth 2.0 is a delegation protocol: it lets an app obtain an access token to call an API on a user's behalf. OpenID Connect adds identity on top — an ID token describing the user — and is what "Sign in with Google" uses. For browser and mobile apps, use the Authorization Code flow with PKCE; the implicit flow is deprecated.

JWTs are signed, self-contained tokens. They're great for short-lived access tokens between services, but awkward as long-lived sessions because revocation is hard. Validate the signature, issuer, audience and expiry, and never accept "alg: none."

Authorization models: role-based (RBAC) is simple; attribute-based (ABAC) and relationship-based (ReBAC, as in Google Zanzibar) handle finer rules. Enforce authorization on the server for every request — hidden buttons are not security.

## Performance engineering
tags: performance, latency, percentiles, profiling, Amdahl, load testing
ask: Why do percentiles matter more than averages? | How do you find a bottleneck?
Measure before optimizing. Averages hide pain; use percentiles — p50 for typical experience, p95 and p99 for the tail that users remember. In fan-out systems tail latency compounds: if one request touches 100 backends, a 1% slow rate per backend makes most requests slow.

Approach: define the goal, build a reproducible benchmark or load test, profile (CPU, memory, I/O, locks), fix the biggest bottleneck, and re-measure. Amdahl's law limits speedup by the part you can't parallelize. Little's law (L = λ × W) relates concurrency, throughput and latency and is handy for sizing pools.

Common wins: fewer round trips (batching, avoiding N+1 queries), the right indexes, caching, streaming instead of buffering, connection pooling, and doing work asynchronously. Know rough latency numbers: memory ~100 ns, SSD read ~100 µs, same-datacenter round trip ~0.5 ms, cross-continent ~100+ ms.

## Technical debt
tags: technical debt, refactoring, maintenance, prioritization
ask: How do you convince leadership to pay down debt? | Is all technical debt bad?
Technical debt is the future cost of choosing an easier solution now. Like financial debt it isn't automatically bad; deliberate, prudent debt ("ship now, refactor after launch") can be smart. Martin Fowler's quadrant separates deliberate from inadvertent and prudent from reckless debt.

Interest shows up as slower feature delivery, more bugs, onboarding pain and fear of change. To manage it: make it visible (a debt register with impact and effort), tie it to business outcomes ("this module causes 40% of incidents"), and pay it continuously — leave code better than you found it, reserve a steady slice of capacity, and refactor in the area you're about to change anyway.

Prioritize debt by interest rate: code that changes often and causes pain matters; ugly code that nobody touches can wait. Hotspot analysis (change frequency times complexity from version history) finds where to start.

## Documenting architecture: ADRs and the C4 model
tags: documentation, ADR, architecture decision records, C4 model, diagrams
ask: What goes in an ADR? | How detailed should architecture diagrams be?
Architecture Decision Records capture significant decisions in short documents stored with the code: title, status, context (forces at play), decision, and consequences (good and bad). They answer the most common question new engineers ask — "why is it like this?" — and prevent re-litigating settled choices. Superseded ADRs stay as history.

The C4 model gives diagrams consistent zoom levels: System Context (the system, its users and external systems), Containers (deployable units such as apps, databases and queues), Components (major parts inside a container), and Code (optional, usually generated). Most teams only need the first two or three. Keep diagrams as code (Structurizr, Mermaid, PlantUML) so they're versioned and reviewed.

Good documentation is minimal and maintained: README for getting started, ADRs for decisions, runbooks for operations, and diagrams for orientation.

## Estimation and planning under uncertainty
tags: estimation, planning, forecasting, story points, uncertainty
ask: How do you forecast without story points? | Why are estimates always wrong?
Software estimates are uncertain because much of the work is discovery. The cone of uncertainty shows estimates can be off by a factor of four early in a project, narrowing as unknowns are resolved.

Better practices: break work into small, similar-sized pieces; estimate in ranges with confidence ("80% likely within 3–5 weeks"); identify the riskiest unknowns and spike them first; and track actual throughput. Probabilistic forecasting with Monte Carlo simulation over historical throughput (items finished per week) often beats story points and needs less ceremony.

Communicate estimates as forecasts, not promises, and update them as you learn. Protect scope, not just dates: fixed date plus fixed scope plus fixed quality rarely works, so agree on what flexes. Hofstadter's law applies — it always takes longer than you expect, even when you account for Hofstadter's law.

## Engineering metrics: DORA and SPACE
tags: DORA, metrics, deployment frequency, lead time, SPACE, productivity
ask: What are the DORA metrics? | How do you measure developer productivity without gaming it?
The DORA research (Accelerate) found four key metrics that predict software delivery performance: deployment frequency, lead time for changes (commit to production), change failure rate, and time to restore service. Elite teams deploy on demand with lead times under a day, low failure rates and fast recovery — speed and stability go together. DORA later added reliability as a fifth area.

The SPACE framework reminds us productivity is multi-dimensional: Satisfaction and well-being, Performance (outcomes), Activity, Communication and collaboration, and Efficiency and flow. Use several metrics across dimensions, at team level, for learning rather than for ranking individuals.

Goodhart's law warns that when a measure becomes a target it stops being a good measure: lines of code, commit counts and story points are easily gamed. Pair quantitative metrics with qualitative signals such as developer surveys and retrospectives.

## Concurrency fundamentals
tags: concurrency, threads, race condition, deadlock, async, locks, actors
ask: How do you avoid deadlocks? | Async or threads — which is better?
Concurrency is dealing with many things at once; parallelism is doing many things at once. Shared mutable state is the root of most concurrency bugs.
- Race condition: the result depends on timing, typically a read-modify-write that isn't atomic.
- Deadlock: threads wait on each other forever. Prevent it by always acquiring locks in a consistent global order, using timeouts, or avoiding nested locks.
- Starvation and livelock: threads never make progress despite being active.

Tools: mutexes and read-write locks, atomic variables, thread-safe collections, immutable data, message passing (actors, channels), and confinement — keep state owned by one thread.

Async/await (Kotlin coroutines, JavaScript promises, Python asyncio) handles many I/O-bound tasks on few threads; it doesn't make CPU-bound work faster. Structured concurrency ties task lifetimes to scopes so nothing leaks. Rule: minimize shared state, and when sharing is unavoidable, make the synchronization obvious and testable.

## Batch and stream data processing
tags: data engineering, batch, streaming, pipelines, ETL, Kafka, idempotent
ask: Batch or streaming — how do you choose? | How do you make pipelines rerunnable?
Batch processing handles bounded data on a schedule (nightly ETL, monthly reports); it's simple, cheap and easy to rerun. Stream processing handles unbounded data continuously (sensor alerts, fraud detection) with low latency but more complexity: event time versus processing time, windows, late data and state management.

Classic patterns: Lambda architecture runs batch and streaming paths side by side; Kappa architecture uses one streaming path with replay from a log such as Kafka. Many teams now use the medallion approach in a lakehouse: raw (bronze), cleaned (silver) and business-ready (gold) layers.

Make pipelines idempotent and rerunnable: partition outputs by date, overwrite partitions instead of appending blindly, and track high-water marks. Validate data contracts at boundaries (schemas, null rates, ranges), because bad data silently propagates. For facility operations, building-sensor telemetry is a natural stream; monthly energy reporting is a natural batch.

## Evolutionary architecture and the strangler fig
tags: evolutionary architecture, strangler fig, migration, legacy, fitness functions
ask: How do you replace a legacy system safely? | What is a fitness function?
Evolutionary architecture accepts that requirements will change and designs for incremental, guided change. Fitness functions are automated checks that protect important architectural qualities — dependency rules, performance budgets, security scans — running in the build pipeline so the architecture can't silently decay.

The strangler fig pattern replaces a legacy system gradually. Put a facade or routing layer in front of it, build new functionality alongside, route one capability at a time to the new system, and retire the old pieces once traffic is gone. It avoids the big-bang rewrite, which often fails because the old system's hidden behavior is underestimated and the business can't freeze for a year.

Supporting techniques: branch by abstraction (introduce an interface, build the new implementation behind it, switch), feature flags, parallel runs comparing old and new outputs, and an anti-corruption layer so the old model doesn't leak into the new one.

## Offline-first and sync design
tags: offline-first, sync, mobile, conflict resolution, CRDT, local-first
ask: How do apps resolve sync conflicts? | What are CRDTs?
Offline-first apps treat the local database as the source of truth for the user interface and sync in the background — instant interactions, works without a signal. BlackLab works this way: everything lives on the phone and syncs optionally.

The hard part is conflicts when two devices change the same data. Options:
- Last-writer-wins by timestamp: simple, but loses data, and clocks drift.
- Field-level merge: track changes per field so edits to different fields both survive.
- Operational transformation: transform concurrent edits against each other (classic Google Docs).
- CRDTs (conflict-free replicated data types): data structures designed so concurrent updates always merge to the same result (counters, sets, sequences for text). Libraries such as Automerge and Yjs make them practical.

Also design for sync hygiene: stable IDs (UUIDs) created on the client, tombstones for deletes, version vectors or per-record revision numbers, idempotent sync endpoints, and showing users pending or failed sync state honestly.

## Approaching a system design problem
tags: system design, interview, design process, capacity estimation, framework
ask: Walk me through designing a URL shortener | How do you estimate capacity quickly?
A repeatable approach for design discussions or interviews:
- Clarify requirements: core features, users, scale, and the most important quality attributes. Write down what's out of scope.
- Estimate: requests per second (daily volume ÷ 86,400, then times 2–10 for peak), storage per year, bandwidth. Round aggressively.
- Define the API and the data model first; they constrain everything else.
- Sketch a high-level design: clients, load balancer, stateless services, data stores, cache, queues.
- Deep dive on the hardest parts: the hot path, the bottleneck, consistency needs, failure modes.
- Discuss trade-offs and alternatives explicitly, then how you'd evolve the design as scale grows ten times.

Example: a URL shortener needs a key-generation strategy (base62 of a counter or random IDs with collision checks), a read-heavy key-value store, aggressive caching and a CDN, plus analytics written asynchronously. Saying what you'd measure and monitor shows production thinking.
