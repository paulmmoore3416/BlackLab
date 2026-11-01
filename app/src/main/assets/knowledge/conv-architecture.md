---
id: conv-architecture
title: Architecture Roundtable
kind: conversation
summary: Talk through system design like two senior engineers at a whiteboard — trade-offs, scaling stories, failure modes and design reviews.
draws: software-engineering, software-development, ai-engineering, ai-development, github
persona: You are a seasoned principal engineer who loves whiteboard sessions. You think out loud about trade-offs, ask sharp clarifying questions, use concrete numbers, tell short war stories, and connect designs back to business outcomes. Friendly, direct, never condescending.
opener: Grab a marker. What are we designing today — something real you're working on, or should I throw a classic at you like a URL shortener or a work-order system for a few hundred buildings?
starters: Design a work-order system for 300 buildings | When should I split a monolith? | How do I run a design review? | What are one-way and two-way door decisions? | Walk me through designing a chat app
---

## How to run a design conversation
tags: design discussion, whiteboard, facilitation, requirements, trade-offs
ask: Want to practice on a real system you own? | Should we start with requirements or the data model?
The best design conversations follow a rhythm: clarify, constrain, sketch, stress, decide. Start by restating the problem in one sentence and naming who it's for. Then pin down the two or three quality attributes that really matter — is it latency, cost, correctness, or time to ship? Most bad designs optimize the wrong one.

Next, put rough numbers on the board: users, requests per second at peak, data growth per year. Numbers kill hand-waving. Then sketch the simplest thing that could work, and only then attack it: what breaks at ten times the load, what happens when the database fails over, what's the worst thing a malicious user could do?

Close by writing down the decision, the alternatives you rejected and why, and what would make you revisit it. That last part is gold six months later.

## Designing a work-order system for a portfolio of buildings
tags: work orders, CMMS, facilities, system design, case study
ask: Should technicians work offline? | How would you prioritize incoming requests automatically?
Let's design a work-order platform for about 300 buildings. Requirements first: requesters submit issues with photos; dispatchers triage and assign; technicians receive jobs on phones, often in basements with no signal; preventive maintenance generates jobs on schedules; managers want SLAs and cost reporting.

Scale is modest — maybe 2,000 work orders a day — so correctness, offline support and integrations matter more than raw throughput. A modular monolith on a relational database fits: modules for requests, assets, scheduling, inventory and reporting. Technicians get an offline-first mobile app with a local database and sync, client-generated IDs, and per-field merge for conflicts.

Preventive maintenance is a scheduler that materializes jobs a few weeks ahead from rules (calendar or runtime-based). Photos go to object storage with thumbnails. Integrations — building automation alarms, ERP for costs — come in through a queue so outages don't drop events. Reporting reads from replicas or a nightly warehouse. AI fits nicely at intake: classify trade and priority, and extract asset details from photos, with a human confirming.

## Monolith first, split with a reason
tags: monolith, microservices, decomposition, team topology
ask: What's the first service you'd extract? | How do you know boundaries are right?
My default is a modular monolith until there's a reason to split, and "microservices are modern" isn't a reason. Good reasons: a component needs to scale very differently (image processing versus CRUD), deploy on its own cadence, use a different technology, or belong to a separate team that keeps colliding with yours.

Before splitting, make the boundary real inside the monolith: separate packages or modules, no reaching into another module's tables, communication through interfaces or events. If you can't keep a boundary clean inside one codebase, a network won't fix it — it'll just make the mess slower and harder to debug.

When you do extract, pick the module with the cleanest seam and the clearest benefit, give it its own data, put a strangler-style router in front, and measure. One question I always ask: can each service be deployed without coordinating with others? If not, you've built a distributed monolith.

## One-way and two-way doors
tags: decisions, reversibility, risk, speed, leadership
ask: Which decisions in your current project are one-way doors? | How do you speed up two-way door decisions?
Jeff Bezos popularized a simple lens: most decisions are two-way doors — if you're wrong, you walk back through. Make those fast, with the people closest to the work. A few are one-way doors — expensive or impossible to reverse — and deserve slow, careful thought.

In architecture, one-way doors include your core data model and identifiers, public API contracts other people build on, choice of primary database for a large dataset, data retention and privacy commitments, and vendor lock-in with heavy migration costs. Two-way doors include most library choices, internal service boundaries in a monolith, UI frameworks for internal tools, and caching strategies.

The skill is converting one-way doors into two-way ones: hide vendors behind interfaces, version APIs from day one, keep data exportable, use feature flags. Facility leaders know this instinctively — you don't pour a foundation the way you rearrange furniture.

## Running a design review that people like
tags: design review, design doc, RFC, feedback, process
ask: What should a design doc contain? | How do you handle a reviewer who wants to redesign everything?
A good design review is a conversation about risk, not a performance. Send a short design doc two days ahead: context and goals, non-goals, the proposed design with a diagram, alternatives considered, risks and open questions, rollout and rollback plan, and how you'll know it worked.

In the meeting, spend the time on the open questions and the riskiest parts, not on reading the doc aloud. Reviewers should ask "what happens when…" questions: when traffic doubles, when this dependency is down, when data is corrupted, when a user does something weird. Keep a running list of decisions and action items.

If a reviewer wants to redesign everything, separate "this won't work" from "I'd do it differently." The first needs addressing; the second needs a strong argument tied to requirements. Time-box debates, and let the owner decide after hearing input. Write down what was decided — a short ADR is enough.

## Failure-mode brainstorming
tags: failure modes, resilience, FMEA, pre-mortem, reliability
ask: Want to run a pre-mortem on something you're building? | What fails most often in practice?
My favorite design exercise is the pre-mortem: imagine it's six months from now and the system failed badly. What happened? People find risks they'd never raise when asked "any concerns?"

Then walk the system box by box and arrow by arrow, asking what happens if this is slow, down, wrong, duplicated or overloaded. Engineers borrowed failure mode and effects analysis from manufacturing for exactly this — rate each failure by severity, likelihood and detectability.

In practice, the usual suspects are boring: expired certificates, full disks, a config change, a dependency's rate limit, a retry storm, a slow query after data grew, a timezone or daylight-saving bug, and a cron job running twice. Design for detection as much as prevention: health checks, alerts on symptoms, and runbooks. Facility operations has the same instinct — you plan for the chiller failing on the hottest day, because that's when it fails.

## Designing a chat or messaging system
tags: chat, messaging, real-time, WebSockets, system design
ask: How would you handle read receipts? | How do you store messages at scale?
For a chat app, start with requirements: one-to-one and group chats, real-time delivery, history, read receipts, push notifications for offline users, maybe attachments.

Real-time delivery uses persistent connections — WebSockets — to a fleet of gateway servers. A user's connection lands on one gateway, so you need a presence and routing layer (which gateway holds which user), often in Redis. Sending a message: the client posts it with a client-generated ID (for idempotency), the service stores it, assigns a per-conversation sequence number for ordering, then fans it out to online members' gateways and queues push notifications for offline ones.

Storage is write-heavy and append-only, partitioned by conversation ID, with messages sorted by sequence — a wide-column store or a sharded relational database works. Clients sync by asking "everything after sequence N," which also handles reconnects. Large groups change the fan-out math: switch to fan-out on read. Attachments go to object storage with signed URLs. End-to-end encryption changes everything server-side, so decide early — it's a one-way door.

## Rate limiters and protecting services
tags: rate limiting, token bucket, API protection, design
ask: Token bucket or sliding window? | Where should rate limiting live?
Rate limiting protects services from abuse and overload and keeps usage fair. Classic algorithms: the token bucket (tokens refill at a fixed rate, each request spends one, and bursts up to the bucket size are allowed — the most common choice), the leaky bucket (smooths output to a steady rate), fixed windows (simple but allow bursts at window edges), and sliding windows (more accurate, a bit more work).

Where it lives: at the edge (API gateway or load balancer) for coarse protection, and in the application for business rules (five password attempts per account per hour). In a fleet of servers, counters need to be shared — Redis with atomic increments or Lua scripts is the standard approach — or approximated per node if exactness doesn't matter.

Return 429 Too Many Requests with a Retry-After header so well-behaved clients back off. Rate-limit by the right key — user, API key, IP address, or tenant — and remember that limits are part of your API contract, so document them.

## Build versus buy
tags: build vs buy, vendors, SaaS, total cost of ownership, strategy
ask: How do you evaluate a vendor's lock-in? | When is building worth it?
The rule I use: build what differentiates you, buy what doesn't. Authentication, payments, email delivery, monitoring and CMMS basics are solved problems — buying them is usually cheaper than the engineers you'd need to build and maintain them. Build when the capability is core to your advantage, when nothing on the market fits your workflow, or when vendor constraints (data residency, offline needs, integration limits) are deal-breakers.

Compare total cost of ownership over three to five years: licenses versus salaries, maintenance, security patching, on-call, upgrades and opportunity cost. Engineers routinely underestimate maintenance — the build is maybe a third of the lifetime cost.

For vendors, evaluate exit costs up front: can you export all your data in a usable format? Are there APIs for everything? What happens if they're acquired or raise prices? A facility director runs this play for chillers and service contracts all the time; software is the same game with faster depreciation.

## Data model first
tags: data modeling, schema, entities, database design
ask: Want to model a domain together? | How do you model history and auditing?
When I'm stuck on a design, I go back to the data model. Get the nouns, their relationships and their lifecycles right, and the services and screens mostly fall out. Get them wrong and every feature fights the schema.

Questions to ask: what are the core entities and their identifiers? What's one-to-many versus many-to-many? Which data is mutable, and do we need its history? Who owns each piece of data — which module is allowed to write it? What are the invariants that must always hold? What are the read patterns, and how big will each table get?

For history and auditing, consider an append-only event or audit table, or temporal tables with valid-from and valid-to columns. Use surrogate keys (UUIDs or sequences) rather than natural keys that change. Model money as integers in the smallest unit plus a currency, times in UTC with time zones stored separately when they matter, and states as explicit enums with allowed transitions.

## Architecture smells
tags: architecture smells, anti-patterns, technical debt, warning signs
ask: Which smell shows up most in your systems? | How do you fix a shared database between services?
Some warning signs that an architecture is drifting:
- Everything talks to the same database tables, so no one can change a column safely.
- A "common" or "utils" library that every service depends on and changes weekly.
- Synchronous call chains five services deep, where any one being slow makes everything slow.
- Features that always require coordinated changes in several components.
- Configuration explosions — dozens of flags whose combinations no one understands.
- God services or god classes that own too much.
- Cyclic dependencies between modules.
- Dashboards nobody looks at and alerts everybody ignores.

Fixes are usually incremental: assign data ownership and put an API in front of shared tables; break up common libraries by purpose; replace synchronous chains with events or caches where consistency allows; and add fitness functions in CI that enforce dependency rules so the architecture can't silently drift back.

## Designing AI features into a system
tags: AI features, LLM integration, architecture, product design
ask: Where would AI help most in your operations? | How do you keep AI features from being flaky?
When adding AI to a product, I treat the model as an unreliable but brilliant dependency. Wrap it in a service with a clear contract, timeouts, fallbacks and evals. Start with the user's job to be done — "triage incoming requests," "summarize a technician's notes" — not with "add a chatbot."

Architecturally: a gateway for model access, cost tracking and provider switching; retrieval over your own data with permission filtering; tools exposed with least privilege; and a human checkpoint for consequential actions. Log prompts, outputs and feedback for evaluation, with privacy controls.

Design the experience for uncertainty: show sources, let users edit outputs, and make undo easy. Measure the business outcome — time saved per work order, fewer misrouted requests — not just model accuracy. And keep a non-AI path working; when the model is down or wrong, the system should degrade, not break. BlackLab's own assistant falls back from Gemini Nano to rules for exactly this reason.

## Scaling a system ten times
tags: scaling, growth, capacity planning, bottlenecks
ask: What would break first in your system at ten times the load? | How do you capacity plan?
A useful drill: take any system and ask what breaks at ten times the load, then a hundred times. Usually the order is predictable. First the database: slow queries, missing indexes, connection limits, and lock contention. Then shared caches and queues, then third-party rate limits, then the human processes — on-call, deploys, data fixes.

Capacity planning means knowing your current headroom: peak utilization of CPU, memory, connections and storage, and how they grow with users. Load-test to find the knee of the curve rather than guessing.

Typical scaling moves, roughly in order: optimize queries and add indexes; add caching; scale the stateless tier horizontally; add read replicas; move slow work to queues; partition data by tenant or key; and finally split services where scaling profiles differ. Each step adds complexity, so take it when the data says so — not because a conference talk said so.

## Offline-first mobile design
tags: offline-first, mobile, sync, field technicians, conflict resolution
ask: How would you sync technician updates from a basement with no signal? | What conflicts actually happen in practice?
Field apps — technicians in mechanical rooms, inspectors on roofs — must work offline. The pattern: the phone's local database is the source of truth for the UI, every change is written locally first and queued, and a sync engine pushes and pulls in the background when a connection returns.

Key choices: client-generated IDs so records can be created offline; a change log or per-record version numbers to sync incrementally; idempotent sync endpoints so retries are safe; tombstones for deletes; and a clear conflict policy. In practice, most "conflicts" are rare and benign — two people editing different fields. Field-level merging handles those. True same-field conflicts can use last-writer-wins for low-stakes fields and explicit resolution for critical ones like a work order's status.

Show sync state honestly — pending, synced, failed — and never silently drop changes. Photos and large attachments sync separately with resumable uploads. BlackLab itself is offline-first: everything lives on the phone, and sync is optional.
