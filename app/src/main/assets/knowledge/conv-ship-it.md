---
id: conv-ship-it
title: Ship It: DevOps & GitHub
kind: conversation
summary: Get code from laptop to users safely — CI/CD, GitHub workflows, releases, incidents, security hardening and building a public portfolio.
draws: github, software-development, software-engineering, ai-engineering, ai-development
persona: You are a pragmatic DevOps and platform engineer who lives in GitHub. You love automation, short feedback loops and boring, reliable releases. You give step-by-step, copy-ready guidance in words, think about security by default, and tell it straight when a process is overkill.
opener: Let's ship something. Setting up a repo, fixing a pipeline, planning a release, or cleaning up after an incident?
starters: Set up a new repo the right way | My GitHub Actions workflow keeps failing | Plan a release checklist for an Android app | How do I run a blameless postmortem? | How do I make my GitHub profile stand out?
---

## Setting up a new repository the right way
tags: repository setup, GitHub, branch protection, templates, checklist
ask: Is this repo public or private? | Solo project or a team?
A checklist for a fresh repository:
- Create it with a README, a .gitignore for your stack, and a license (if public).
- Main branch protection or a ruleset: pull requests required, status checks required, no force pushes.
- Merge settings: pick one strategy (squash is a good default) and enable automatic deletion of head branches.
- CI from day one: a workflow that builds, lints and tests on every pull request. It's far easier to keep green than to make green later.
- Security: Dependabot alerts and updates, secret scanning with push protection, CodeQL default setup, and a SECURITY.md.
- Templates: an issue form for bugs, a pull request template with a short checklist.
- Project hygiene: description, topics and website link; a CHANGELOG; CODEOWNERS once more than one person is involved.
- Secrets: never in the repo — use Actions secrets, environment secrets, or keep signing keys local and gitignored, as BlackLab does with its keystore.
Fifteen minutes of setup saves weeks of cleanup.

## Fixing a failing GitHub Actions workflow
tags: GitHub Actions, CI failures, debugging, troubleshooting
ask: What does the failing step's log say? | Did it ever pass, and what changed since?
Debugging a red workflow:
- Read the failing step's log from the bottom up; the first error is usually the real one. gh run view --log-failed gets it in the terminal.
- Ask what changed: your code, a dependency (unpinned versions drift), the runner image (ubuntu-latest moves forward), an action's version, or an expired secret or token.
- Reproduce locally with the same commands and versions. For tricky cases, tools like act run workflows locally, or temporarily add a debug step that prints environment details (never secrets).
- Enable step debug logging by setting the ACTIONS_STEP_DEBUG secret to true, or re-run with debug logging.
- Check permissions: many failures are a GITHUB_TOKEN missing a permission (contents: write to push tags, packages: write to publish).
- Caching problems: a corrupted or mismatched cache can cause weird failures — change the cache key to bust it.
- Flaky tests: re-run once to confirm, then fix the flake rather than adding retries.
Pin action versions and runner images for repeatability, and update them deliberately.

## Release checklist for an Android app
tags: Android release, APK, signing, versioning, checklist, GitHub Releases
ask: Are you releasing through the Play Store or GitHub? | Do you have a staged rollout plan?
A dependable Android release routine:
- Bump versionCode (must always increase) and versionName (semantic versioning).
- Update the CHANGELOG and release notes in plain language.
- Run the full checks: lint, unit tests, instrumented tests on an emulator, and a secret scan.
- Build the release variant with R8 minification, and test that exact build on a real device — minification bugs only show up there.
- Sign with the same release key every time; losing it means existing installs can't update. Back the keystore up securely and never commit it.
- Generate a SHA-256 checksum for the APK.
- Tag the commit (v1.2.0), push the tag, and create a GitHub Release with the APK and its .sha256 file attached — BlackLab's in-app updater checks both.
- For the Play Store, use an app bundle and staged rollouts, and watch Android vitals for crashes before widening.
- Verify the update path from the previous version, not just a fresh install, especially with database migrations.

## Incident response basics
tags: incident response, outage, on-call, communication, mitigation
ask: Are you in an incident right now or planning ahead? | Who needs updates during an outage?
When something breaks in production:
- Declare it early. A clear "we have an incident" beats people quietly investigating in parallel.
- Assign roles even on a small team: an incident commander who coordinates and decides, someone hands-on fixing, and someone communicating with users and stakeholders.
- Mitigate first, diagnose later. Roll back the last deploy, flip the feature flag, fail over, or scale up — restore service, then find the root cause.
- Communicate on a cadence: what's affected, what we're doing, and when the next update comes, even if nothing has changed.
- Keep a timeline as you go — actions, observations, timestamps. It makes the postmortem accurate.
- Close it formally when service is restored and monitoring confirms it.

This mirrors facility emergency response: stabilize the situation, protect people, communicate, then investigate. The same calm structure works whether it's a burst pipe or a broken deploy.

## Running a blameless postmortem
tags: postmortem, retrospective, blameless, root cause, learning
ask: What incident do you want to review? | How do you make action items actually happen?
A blameless postmortem assumes people acted reasonably given what they knew, and asks how the system let the failure happen. Blame makes people hide information; curiosity surfaces it.

Structure: a summary of impact (who was affected, for how long), a timeline of detection, response and recovery, contributing factors (usually several — a latent bug, a missing alert, a confusing runbook, a risky deploy process), what went well, where we got lucky, and action items.

Ask "how" and "what made sense at the time" rather than "who" and "why didn't you." Five Whys helps go deeper, but real incidents rarely have one root cause.

Action items need owners and dates, and should target prevention (tests, safeguards), detection (alerts) and mitigation (faster rollback). Track them like any other work. Share postmortems widely — they're some of the most valuable documents an organization writes.

## Designing a CI/CD pipeline
tags: CI/CD, pipeline design, deployment, environments, automation
ask: How long does your pipeline take today? | How do you deploy now — by hand?
A solid pipeline flows from fast to slow checks and from safe to risky environments:
- On every pull request: format and lint, compile, unit tests, security scans, and a build of the artifact. Aim for under ten minutes.
- On merge to main: the same plus integration tests, then build the release artifact once, version it, and store it.
- Deploy that same artifact to staging automatically; run smoke tests.
- Promote to production with an approval (GitHub environments with required reviewers) or automatically if your tests and monitoring are strong — through a canary or staged rollout.
- After deploy: automated health checks and an easy, rehearsed rollback.

Principles: build once and promote, keep configuration separate from code, make every step reproducible, cache dependencies, run independent jobs in parallel, and make failures loud and specific. Treat the pipeline itself as code — reviewed, versioned and tested — because it's production infrastructure for your team.

## Security hardening for a GitHub project
tags: security, hardening, GitHub, supply chain, secrets, checklist
ask: Has a secret ever leaked from one of your repos? | Are your actions pinned?
A practical hardening pass:
- Account: two-factor authentication with a passkey or security key; review authorized OAuth apps and personal access tokens; prefer fine-grained, expiring tokens.
- Repository: branch protection or rulesets on main; required reviews; signed commits if you can; secret scanning with push protection; Dependabot; CodeQL.
- Actions: set GITHUB_TOKEN permissions to read-only by default; pin third-party actions to commit SHAs; avoid pull_request_target with untrusted code; pass untrusted input through environment variables, not inline scripts; use OIDC for cloud credentials; protect deployment environments.
- Secrets: never in code; rotate on any suspicion; keep signing keys out of the repo entirely.
- Dependencies: lockfiles, regular small updates, review new packages, and an SBOM for releases.
- Releases: checksums and, ideally, attestations so users can verify what they download.
Run a local secret scan before pushing — BlackLab's scripts/secret-scan.sh is a good habit worth copying.

## Semantic versioning and changelogs
tags: semantic versioning, versioning, changelog, releases, communication
ask: How do you decide between a minor and a major bump? | Who reads your changelog?
Semantic versioning communicates impact: MAJOR for breaking changes, MINOR for new backward-compatible features, PATCH for backward-compatible fixes. For apps rather than libraries, "breaking" means users must change how they work — a removed feature or a data migration they'll notice.

Changelogs are for humans. Group entries under Added, Changed, Fixed, Removed and Security (the Keep a Changelog format), write them in user terms ("Summaries can now be saved as notes") rather than commit-speak, and link to issues or pull requests. Keep an Unreleased section at the top that fills up as work merges.

Automation helps: conventional commit messages let tools like release-please generate changelog entries and version bumps, and GitHub's auto-generated release notes summarize merged pull requests by label. Still, a human pass that highlights what matters most is worth five minutes per release.

## Feature flags and safe rollouts
tags: feature flags, rollout, canary, dark launch, risk
ask: Do you use flags today? | How do you clean up old flags?
Feature flags separate deploying code from releasing features. Merge unfinished work behind a flag that's off, ship it, then turn it on — for yourself first, then a small group, then everyone. If something goes wrong, flip it off in seconds without a deploy.

Kinds of flags: release flags (temporary, for rollout), experiment flags (A/B tests), ops flags or kill switches (to disable expensive features under load), and permission flags (features for certain users or plans).

Costs: every flag doubles the paths through code. Keep them few and short-lived, name them clearly, give each an owner and an expiry, and remove release flags soon after full rollout. Test both states in CI for important flags.

For mobile apps, flags are even more valuable, because you can't roll back an installed binary quickly — server-controlled flags or remote configuration let you disable a broken feature without waiting for users to update.

## On-call without burning out
tags: on-call, alerts, burnout, sustainability, operations
ask: How noisy are your alerts? | What happens when you get paged at 3 a.m.?
On-call is sustainable when alerts are rare, actionable and owned.
- Every page should mean a user-impacting problem that needs a human now. Everything else becomes a ticket or a dashboard.
- Alert on symptoms (error rates, latency, SLO burn) rather than causes (CPU at 80%).
- Every alert links to a runbook: what it means, how to check, how to mitigate.
- Review pages weekly: delete noisy alerts, fix recurring causes, automate common fixes.
- Rotate fairly, hand off cleanly with notes, and compensate or give time back after rough nights.
- Make escalation normal — asking for help is a strength, not a failure.

Facility directors know this world: the 2 a.m. call about a flooded basement. The same principles apply — clear escalation paths, good documentation, trusted vendors on call, and fixing root causes so the same call doesn't come again next month.

## Building a GitHub portfolio that stands out
tags: portfolio, GitHub profile, career, open source, personal brand
ask: Which of your projects are you proudest of? | Who do you want the portfolio to impress?
Recruiters and hiring managers skim. Make it easy for them:
- A profile README: who you are in two lines, what you build, your stack, and links to your best two or three projects.
- Pin your best repositories — finished, useful projects rather than many half-done tutorials.
- Each pinned repo gets an excellent README: the problem, screenshots or a demo GIF, how to run it, key design decisions, and what you learned. BlackLab's README with screenshots and a feature list is a solid model.
- Show engineering maturity: tests, CI badges, releases, issues and a changelog — signs you ship like a professional.
- Write about your work: short posts or docs explaining a tricky problem you solved.
- Real-world angle: projects that solve problems from your actual job (facility operations, maintenance, energy) stand out more than another clone app, because they show domain judgment.
Consistency over time — steady commits and finished releases — tells a better story than a burst of activity.

## Contributing to open source
tags: open source, contributing, community, pull requests, maintainers
ask: Is there a project you use that you'd like to give back to? | What's held you back from contributing?
Contributing to open source builds skills and reputation. A low-stress path:
- Start with projects you use. You already understand the problem.
- Read CONTRIBUTING.md and the code of conduct; look for labels like "good first issue" or "help wanted."
- Begin small: documentation fixes, clearer error messages, a missing test, a reproducible bug report. Maintainers love those.
- Comment on an issue before starting bigger work so you don't duplicate effort or build something they won't accept.
- Follow the project's conventions exactly, keep pull requests focused, and include tests.
- Be patient and gracious with review; maintainers are often volunteers.

Maintaining your own project is a different job: set expectations in the README, use issue templates, automate CI and releases, and say no kindly to features that don't fit. Sustainable maintenance beats heroic bursts.

## Automating the boring stuff with GitHub
tags: automation, GitHub Actions, gh CLI, scheduled jobs, productivity
ask: What chore do you repeat every week? | Want ideas for scheduled workflows?
GitHub can run your chores:
- Scheduled workflows (cron) to generate weekly reports, check links in docs, clean up stale branches, or refresh data files.
- Labeling and triage: automatically label pull requests by changed paths, assign reviewers, and close stale issues politely after a warning.
- Release automation: draft release notes as pull requests merge, publish on tag push, and attach build artifacts and checksums.
- Dependency upkeep: Dependabot with grouped updates and auto-merge for safe patches.
- Notifications: post to chat or email when deploys finish or nightly jobs fail.
- The gh CLI in scripts: bulk-edit issues, create repos from templates, and query the API with jq for reports.

A personal example: a weekly workflow that summarizes merged pull requests and open issues into a markdown report, committed to the repo or sent by email. Start with the chore that annoys you most; one small automation often pays for itself within a month.
