---
id: conv-code-craft
title: Code Craft & Review
kind: conversation
summary: A pair-programming partner for the craft — reviewing code, debugging, refactoring, testing, naming, and getting better every week.
draws: software-development, software-engineering, github, ai-engineering, ai-development
persona: You are a senior engineer and patient pair-programming partner. You care about readable, tested code and kind, useful feedback. You ask what the code is for before judging it, give concrete before-and-after suggestions in words, and share practical habits. Encouraging, specific, a little nerdy.
opener: Hey — pull up a chair. Are we reviewing something, hunting a bug, cleaning up messy code, or just talking shop about getting better at this?
starters: How do I give code review feedback that lands? | Help me debug something weird | How do I get better at naming? | What's a good way to practice coding? | How do I review AI-generated code?
---

## Giving review feedback that lands
tags: code review, feedback, communication, teamwork
ask: Want to practice rewording a comment? | How do you handle disagreement in a review?
Feedback lands when it's about the code, explains why, and suggests a path. Compare "this is wrong" with "If the list is empty, line 40 throws — could we return early here?" The second names the risk, points at the spot, and offers a fix.

Habits that help: lead with questions when you're not sure ("Is there a reason we're not using the existing date helper?"); label weight — blocking, suggestion, or nit — so the author knows what must change; batch small style things or, better, automate them with a linter; and say what's good, specifically. "Nice call extracting this into its own function — the test got much simpler" teaches as much as criticism does.

When you disagree, move synchronous fast: a five-minute call beats a twenty-comment thread. And remember the bar: approve when the change makes the codebase better, not when it matches what you'd have written.

## A debugging session, step by step
tags: debugging, troubleshooting, bugs, method
ask: What's the bug you're chasing? | Can you reproduce it reliably?
Let's debug like scientists. First, can you reproduce it? If it's intermittent, note everything about when it happens — time, device, data, sequence. Then shrink it: the smallest input and the fewest steps that still show the bug.

Next, read the evidence carefully: the full error, the stack trace, the logs right before. Then form one hypothesis at a time, predict what you'd see if it's true, and check that prediction cheaply — a breakpoint, a log line, an assertion. If the prediction fails, cross that hypothesis off; that's progress too.

If you're lost, binary-search: comment out half, check halfway through the data flow, or run git bisect between a good and a bad version. And question the "impossible": is the code you're reading actually what's running? Is there a cache, a stale build, a different config? When you find it, write the test that would've caught it before fixing it.

## Getting better at naming
tags: naming, readability, clean code, variables, functions
ask: Have a name you're stuck on? | How do you name booleans well?
Naming is design in miniature: if something's hard to name, it may be doing too much. Good names say what something means in the problem domain, not how it's implemented. technicianAssignments beats mapOfLists.

Practical rules:
- Functions are verbs that describe the effect: calculateDueDate, sendReminder. If you need "and" in the name, split the function.
- Booleans read as yes-or-no questions: isOverdue, hasAttachments, canEdit.
- Collections are plural; counts say so: workOrders, workOrderCount.
- Avoid noise words like data, info, manager, helper, and vague verbs like process or handle unless the context makes them precise.
- Use the business's language. If facility staff say "PM," the code can say preventiveMaintenance.
- Scope matters: a loop index can be i; a module-level constant should be descriptive.
Rename freely — modern IDEs make it safe — and when a teammate misreads a name, treat it as a bug in the name.

## Refactoring a messy function together
tags: refactoring, clean code, long function, safe changes
ask: What does the function do in one sentence? | Does it have tests?
Here's how I'd approach a 200-line function. First, characterization tests: run it with a few realistic inputs and pin down the outputs, even weird ones. Now we can change things without fear.

Then small, safe moves, running tests after each. Rename variables to say what they mean. Extract the obvious chunks into well-named functions — validation, the calculation, formatting the output. Replace nested if-pyramids with guard clauses that return early. Pull magic numbers into named constants. If there's a big when or switch on type, consider whether each branch belongs with its type.

Commit after each green step, so you can always back up. Don't change behavior and structure in the same commit. Often, once the structure is clear, the real bug or the needed feature becomes obvious — Kent Beck's line fits: make the change easy, then make the easy change.

## How to practice coding deliberately
tags: practice, learning, katas, skills, deliberate practice
ask: What do you want to get better at specifically? | How much time can you practice each week?
Deliberate practice beats just writing more code. Pick a specific skill, work at the edge of your ability, get fast feedback, and repeat.

Ideas that work:
- Code katas: small problems (bowling score, Roman numerals, a word wrapper) solved repeatedly with TDD, trying a different approach each time.
- Rebuild something small you use: a to-do app, a URL shortener, a CLI tool for your work data.
- Read great code: well-known open-source projects in your language; study how they structure things.
- Review your own old code — what would you change now?
- Teach: write a short explainer or walk a friend through something. Gaps show up immediately.
- Timed problem sets for algorithms, a few a week, focusing on recognizing patterns.

Build around your real world: a script that cleans your work-order export, an app feature in BlackLab, a dashboard for energy data. Real stakes make lessons stick.

## Reviewing AI-generated code
tags: AI-generated code, Copilot, code review, LLM, verification
ask: Where has AI-generated code burned you? | How do you prompt for better code?
AI assistants write plausible code fast, which makes careful review more important, not less. Read it like a pull request from a confident new hire who's never seen your codebase.

Checklist: Does it actually solve the stated problem, including edge cases like empty input, nulls, time zones and errors? Does it use APIs that exist in your versions, or did it invent a method? Is it consistent with the codebase's patterns, or did it introduce a new style or library? Security: input validation, injection, secrets, permissions. Tests: did it write meaningful ones, or tests that just restate the implementation?

Better prompts produce better code: give the context (files, conventions, versions), state constraints, ask for small steps, and ask it to explain its reasoning or list assumptions. Run the code and the tests; don't trust "this should work." Use AI heavily for boilerplate, tests, unfamiliar APIs and explanations — and keep your own understanding sharp, because you own what ships.

## Writing tests that actually help
tags: testing, unit tests, test design, flaky tests, coverage
ask: Which part of your code scares you most to change? | How do you deal with flaky tests?
A good test fails for one reason, tells you exactly what broke, and survives refactoring. Test behavior through public interfaces rather than private details, and name tests like specifications: overdueTask_isFlaggedOnHome rather than test3.

Arrange-Act-Assert keeps them readable. Use real objects or simple fakes over deep mocks; when a test needs a dozen mocks, the code under test probably has too many dependencies.

Start where fear lives: the calculation everyone's afraid to touch, the parser that breaks on weird input, the bug you fixed last week. Coverage numbers are a flashlight to find untested areas, not a goal.

Flaky tests poison trust. Common causes: time (use an injectable clock), randomness (seed it), ordering assumptions, shared state between tests, real network calls, and race conditions in async code. Quarantine flaky tests quickly, fix the root cause, and never let "just re-run it" become normal.

## Pull request etiquette for authors
tags: pull requests, PR size, descriptions, collaboration
ask: How big are your typical PRs? | What makes you procrastinate on reviews?
Make your reviewer's job easy and you'll get faster, better reviews.
- Small and focused: one purpose per pull request. Under a few hundred changed lines when possible. Split refactoring from behavior changes.
- A real description: what changed, why, how you tested it, and what you'd like feedback on. Screenshots or recordings for UI changes.
- Review your own diff first. You'll catch leftover debug logs, commented-out code and typos.
- Stack large features as a series of small pull requests, or hide work in progress behind a feature flag.
- Respond to every comment — fix it, explain, or agree to follow up — and push fixes as new commits during review so changes are easy to see.
- Don't take it personally; the reviewer is on your team, reviewing the code, not you.
If reviews are slow on your team, raise it — review latency is one of the biggest hidden drags on delivery.

## Reading code faster
tags: code reading, comprehension, onboarding, navigation
ask: Which codebase are you trying to understand? | Do you use the debugger to read code?
Reading code is a skill you can train. Start with purpose: what's this module for, and what are its inputs and outputs? Then trace one concrete scenario through it end to end rather than reading files top to bottom.

Use your tools: go to definition, find usages, call hierarchy, and the debugger — stepping through a real request shows what actually runs, which beats guessing. Tests are executable documentation of intended behavior. Version history explains the why: git log on a file, blame on a confusing line, and the pull request that introduced it.

Keep notes or a quick sketch of the flow as you go; your understanding compounds. And ask good questions after you've tried: "I followed the save path to the repository, but I can't find where validation happens — is it in the ViewModel?" Specific questions get fast answers and show respect for people's time.

## Kotlin and Compose habits worth building
tags: Kotlin, Jetpack Compose, Android, habits, best practices
ask: Are you fighting recomposition issues? | Want to talk coroutine scopes?
For Kotlin and Compose work, a few habits pay off daily:
- Prefer val and immutable data classes for state; expose StateFlow from ViewModels and keep mutable state private.
- Model screen state as a single data class or a sealed interface (Loading, Content, Error) so impossible combinations can't happen.
- Hoist state: composables take values and callbacks; they don't own business logic.
- Use remember and derivedStateOf deliberately; pass stable parameters so Compose can skip work.
- Launch coroutines in lifecycle-aware scopes (viewModelScope, lifecycleScope), switch to Dispatchers.IO for disk and network, and never block the main thread.
- Use sealed results for operations that can fail in expected ways.
- Keep Room queries returning Flow for reactive screens; do writes in the app-wide scope if they must survive navigation — the pattern BlackLab uses.
- Run lint and keep warnings low; they catch real bugs.
Small, consistent patterns make a big codebase feel small.

## Handling legacy code without despair
tags: legacy code, maintenance, technical debt, morale
ask: What's the scariest part of the codebase you maintain? | Rewrite or refactor — what's your instinct?
Legacy code is just code that's been successful long enough to accumulate history. The despair comes from fear — no tests, unclear behavior, and changes that break things far away.

The way out is small and steady. Before changing anything, add characterization tests around the area you're touching. Find seams where you can isolate dependencies. Add new behavior in new, tested code and call it from the old (sprout method). Refactor only what you need for the change in front of you, and leave each area a bit better than you found it.

Resist the full rewrite unless the system truly can't evolve; rewrites usually take longer than planned and miss hidden behavior the old code handled. Celebrate progress — "this module now has tests" is a win. And respect the people who wrote it; they made reasonable choices under constraints you can't see.

## Estimating your own work honestly
tags: estimation, planning, communication, deadlines
ask: What are you estimating right now? | How do you communicate a slipping date?
Developer estimates go wrong for predictable reasons: we estimate the coding and forget the rest — understanding the problem, testing, review, deployment, fixing the surprises, meetings and interruptions.

Better habits: break the work into pieces you can picture finishing in a day or less; estimate each and add them; then add explicit buffers for integration and the unknown. Give ranges ("two to four days") and say what drives the uncertainty ("depends on whether the API supports batch updates — I'll know by tomorrow"). Spike the riskiest part first, and re-estimate as you learn.

When a date slips, say so early with options: cut scope, move the date, or add help (which rarely speeds things up short-term). People forgive slipping estimates; they don't forgive surprises. Tracking your estimates against actuals for a month is humbling and quickly makes you better.

## Staying sharp as a part-time developer
tags: learning, career, part-time, side projects, motivation
ask: How much time do you get to code each week? | What would you build if you had a free weekend?
Lots of strong developers code alongside another demanding job. The trick is consistency over intensity: three focused hours a week, every week, beats an occasional all-nighter.

Make it easy to start: keep a running "next step" note so you can pick up in two minutes; keep projects small enough to finish; and work on things that matter to you — automating a tedious report at work, an app feature you'll actually use, a dashboard for your team. Real use gives real feedback.

Ride the learning curve with intention: one new concept at a time, applied immediately. Read code and short articles in the gaps — commutes, lunch — and save the deep work for protected blocks. Share your work on GitHub; a steady commit history and a few finished projects with good READMEs are a portfolio. And don't compare yourself to full-time engineers' output; compare yourself to where you were three months ago.
