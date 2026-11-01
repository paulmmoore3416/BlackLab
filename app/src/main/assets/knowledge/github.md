---
id: github
title: GitHub & Git
kind: domain
summary: Everything GitHub — Git itself, repositories, pull requests, Actions, Projects, security features, Pages, Packages, Copilot, the CLI and the API.
---

## Git fundamentals
tags: git, commits, branches, HEAD, staging, remotes, basics
ask: What actually is a commit? | What does HEAD mean?
Git is a distributed version control system: every clone has the full history.
- A commit is a snapshot of the whole project plus metadata (author, message, timestamp) and a pointer to its parent commit(s), identified by a hash.
- The staging area (index) is where you assemble the next commit: git add stages changes, git commit records them.
- A branch is just a movable pointer to a commit. HEAD points to the branch (or commit) you have checked out.
- Remotes are other copies of the repository, usually named origin. git fetch downloads new commits; git pull fetches and integrates; git push uploads your commits.
- The working tree is your files on disk.

Everyday commands: git status, git diff (unstaged) and git diff --staged, git add -p (stage hunks interactively in a terminal), git commit, git log --oneline --graph, git switch -c new-branch, git restore. Write commit messages with a short imperative subject ("Fix overdue badge on Home") and a body explaining why. Commit small, logical units — they make review, revert and bisect easy.

## Merge, rebase and history tools
tags: git, merge, rebase, cherry-pick, stash, reflog, bisect
ask: Merge or rebase? | How do I recover a commit I lost?
Merge combines branches with a merge commit that preserves both histories exactly. Rebase replays your commits on top of another branch, producing a linear history; it rewrites commit hashes, so never rebase commits others have already pulled (the golden rule). A common team habit: rebase your private feature branch on main to stay current, then merge through a pull request.

Other power tools:
- git cherry-pick <hash> copies a specific commit onto your branch (backporting a fix).
- git stash shelves uncommitted work; git stash pop brings it back.
- git reflog records where HEAD has been; it's the safety net for recovering "lost" commits after a bad reset or rebase — find the hash and branch from it.
- git bisect binary-searches history to find the commit that introduced a bug.
- git blame shows who last changed each line, and git log -S "text" finds commits that added or removed a string.
- Interactive rebase (git rebase -i) lets you squash, reorder and edit commits before sharing them.

## Undoing things safely
tags: git, undo, reset, revert, restore, amend, force push
ask: Reset or revert — which should I use? | How do I undo a pushed commit?
Pick the tool by whether the commit is shared:
- Discard local file changes: git restore <file> (or git restore --staged <file> to unstage).
- Fix the last commit before pushing: git commit --amend (message or forgotten files).
- Move a branch pointer back: git reset --soft HEAD~1 keeps changes staged; --mixed (the default) keeps them unstaged; --hard discards them. Use only on unshared commits.
- Undo a commit that's already pushed or shared: git revert <hash> creates a new commit that reverses it. History stays intact and nobody's clone breaks.
- Recover from a mistake: git reflog, then git branch rescue <hash>.

Force pushing rewrites remote history. If you must (after rebasing your own branch), use git push --force-with-lease, which refuses if someone else pushed in the meantime. Protect main with branch protection so force pushes are blocked. If a secret was committed, reverting isn't enough — it's still in history. Rotate the secret immediately, then purge history (git filter-repo) if needed.

## Repositories and their settings
tags: repository, repo settings, public, private, fork, template, visibility
ask: Fork or branch — when do I use which? | What settings should a new repo have?
A GitHub repository holds code plus issues, pull requests, Actions, wiki, releases and settings.

Visibility: public (anyone can see; free Actions minutes are generous for public repos), private (invited collaborators), and internal (everyone in an enterprise). You can change visibility, but going public exposes all history — scan for secrets first.

Forks are your own copy of someone else's repo, used to propose changes to projects you can't push to. Within your own repos, use branches. Template repositories let you start new projects with the same structure and files.

Recommended settings for a new repo: a default branch named main; branch protection or rulesets on main; merge options chosen deliberately (often squash merges only); "Automatically delete head branches" on; Dependabot alerts and security updates on; secret scanning and push protection on; a description, topics and website link for discoverability; and issue templates. Archive repos you no longer maintain so they become read-only.

## Branch protection and rulesets
tags: branch protection, rulesets, required reviews, status checks, merge queue, signed commits
ask: What should I protect on main? | What is a merge queue?
Branch protection rules and the newer repository rulesets enforce how code reaches important branches. Rulesets can target many branches or tags by pattern, apply across an organization, layer together, and offer a bypass list and an "evaluate" mode to test rules before enforcing them.

Common protections for main:
- Require a pull request before merging, with at least one approving review; dismiss stale approvals when new commits are pushed; require review from code owners.
- Require status checks (CI builds, tests, linters, CodeQL) to pass, and require branches to be up to date.
- Require conversation resolution before merging.
- Require signed commits and/or linear history.
- Block force pushes and deletions.

A merge queue helps busy repositories: approved pull requests enter a queue, GitHub tests each one combined with the changes ahead of it, and merges only if checks pass — so main never breaks from two individually green pull requests that conflict logically. Workflows need the merge_group event to run in the queue.

## Pull requests from start to merge
tags: pull requests, PR, code review, draft, reviewers, merge strategies, squash
ask: Squash, merge or rebase — which merge strategy? | How do I write a great PR description?
A pull request proposes merging one branch into another and hosts the review conversation.

Workflow: push a branch and open a pull request (or a draft pull request to signal work in progress and get early CI feedback). Write a description: what changed, why, how it was tested, screenshots, and linked issues ("Fixes #42" closes the issue on merge). Request reviewers, or let CODEOWNERS request them automatically.

Review tools: line comments, multi-line comments, suggested changes that the author can commit with one click, review states (comment, approve, request changes), "viewed" checkboxes per file, and re-requesting review after updates.

Merge strategies:
- Merge commit: keeps every commit plus a merge commit; full history.
- Squash and merge: one tidy commit per pull request on main; popular for clean history.
- Rebase and merge: replays commits linearly without a merge commit.
Pick one per repository for consistency. Auto-merge merges a pull request as soon as requirements pass. Keep pull requests small; large ones get rubber-stamped.

## CODEOWNERS
tags: CODEOWNERS, code owners, reviews, ownership, monorepo
ask: How does CODEOWNERS work? | Can CODEOWNERS enforce reviews?
A CODEOWNERS file (in the repository root, .github/ or docs/) maps file patterns to the people or teams responsible for them, using gitignore-style patterns. The last matching pattern wins. For example, a line with *.kt and @android-team, a line with /docs/ and @docs-writers, and a line with /.github/workflows/ and @platform-team.

When a pull request touches matching files, code owners are requested for review automatically. Combined with the branch protection or ruleset option "Require review from Code Owners," their approval becomes mandatory — useful for sensitive areas like CI workflows, infrastructure, security code and public APIs.

Tips: assign teams rather than individuals so ownership survives people changes; keep the file reviewed and current; protect the CODEOWNERS file itself by owning it; and avoid owning everything with one team, which creates bottlenecks. GitHub flags errors in CODEOWNERS syntax on the file's page.

## Issues, labels and templates
tags: issues, labels, milestones, issue templates, issue forms, sub-issues, triage
ask: How should I organize issues? | What are issue forms?
Issues track bugs, features, tasks and questions. Each has a title, Markdown body, assignees, labels, milestone, project fields, and links to pull requests. Sub-issues break large work into children with progress tracking, and issue types (at the organization level) classify work such as bug, feature or task.

Organizing:
- Labels categorize (bug, enhancement, priority:high, area:android, good first issue). Keep a small, consistent set.
- Milestones group issues toward a release or date and show progress.
- Linking: "Fixes #12" in a pull request closes the issue when merged; mentioning #12 cross-links.

Templates in .github/ISSUE_TEMPLATE/: Markdown templates pre-fill text, while issue forms (YAML) render structured fields — dropdowns, checkboxes, required inputs — so bug reports arrive with versions, steps and logs. A config.yml can disable blank issues and point questions to Discussions. Saved replies speed up triage responses. Issues are searchable with qualifiers such as is:open label:bug assignee:@me.

## GitHub Projects
tags: GitHub Projects, project boards, roadmap, iterations, custom fields, automation
ask: How do I set up a project board? | Can Projects track work across repos?
GitHub Projects is a flexible planning tool built on issues and pull requests, spanning multiple repositories.

Views: Table (spreadsheet-style with sorting, grouping and filtering), Board (kanban columns by status or any field), and Roadmap (timeline by dates or iterations). Each view saves its own filters and layout.

Custom fields: single select (status, priority), text, number (estimate), date, and iteration fields for sprints with breaks. Items can be issues, pull requests, or draft items that convert to issues later.

Automation: built-in workflows set status when items are added, closed or merged; auto-add pulls in new issues matching a filter; auto-archive cleans up; and GitHub Actions or the GraphQL API can do more. Insights charts show burn-up and status over time.

A practical setup for a solo builder: one project for your roadmap, a Board view by Status (Backlog, Ready, In progress, Done), a Roadmap view by target date, and a Priority field.

## GitHub Actions fundamentals
tags: GitHub Actions, CI/CD, workflows, YAML, jobs, steps, runners, events
ask: How does a GitHub Actions workflow work? | What triggers can start a workflow?
GitHub Actions runs automation defined in YAML files under .github/workflows/.
- Events trigger workflows: push, pull_request, schedule (cron), workflow_dispatch (a manual button with inputs), release, issues, workflow_run, and many more. Filters limit by branch, tag or path.
- A workflow contains jobs; each job runs on a runner — GitHub-hosted Ubuntu, Windows or macOS virtual machines (including larger and ARM runners), or self-hosted machines.
- Jobs run in parallel by default; needs: creates dependencies between them.
- Each job has steps: either run: shell commands, or uses: a reusable action (actions/checkout, actions/setup-java, actions/cache).
- Expressions use ${{ }} syntax with contexts such as github, env, secrets, matrix and steps outputs; if: conditions control execution.

A typical Android CI job: checkout, set up a JDK, cache Gradle, then run ./gradlew lint test assembleDebug, and upload artifacts. BlackLab's repository runs CI, device tests and a secret scan this way. View runs in the Actions tab; re-run failed jobs and enable debug logging when troubleshooting.

## Advanced GitHub Actions
tags: GitHub Actions, matrix, caching, artifacts, reusable workflows, composite actions, concurrency, environments
ask: What's the difference between reusable workflows and composite actions? | How do I speed up my CI?
Power features:
- Matrix builds run a job across combinations (OS × version) with include and exclude rules and fail-fast control.
- Caching (actions/cache or the built-in caching in setup actions) restores dependencies keyed by lockfile hashes — often the biggest speedup.
- Artifacts (upload-artifact and download-artifact) pass files between jobs and keep build outputs for download, with retention limits.
- Job outputs pass small values to dependent jobs.
- Reusable workflows (workflow_call) share an entire workflow with inputs and secrets across repositories; composite actions bundle several steps into one action. Use reusable workflows for whole pipelines and composite actions for repeated step sequences.
- Concurrency groups cancel superseded runs (cancel-in-progress) and serialize deployments.
- Environments (staging, production) add protection rules — required reviewers, wait timers, branch restrictions — plus environment-specific secrets and deployment history.
- Path filters skip irrelevant runs in monorepos.
Speed tips: cache aggressively, split slow suites into parallel jobs, use path filters, avoid redundant builds, and choose larger runners for heavy builds.

## Securing GitHub Actions
tags: Actions security, GITHUB_TOKEN, permissions, pinning, pull_request_target, OIDC, supply chain
ask: How do I lock down GITHUB_TOKEN? | Why is pull_request_target dangerous?
CI pipelines are a prime attack target. Hardening checklist:
- Least-privilege GITHUB_TOKEN: set permissions: at the top of each workflow (for example contents: read) and grant writes only to jobs that need them. Make read-only the default in repository or organization settings.
- Pin third-party actions to a full commit SHA rather than a mutable tag; tags can be moved to malicious code. Let Dependabot update pinned SHAs.
- Beware pull_request_target and workflow_run: they run with secrets and write access in the context of the base repository. Never check out and execute untrusted pull request code in them.
- Prevent script injection: don't interpolate untrusted input (issue titles, branch names, PR bodies) directly into run: scripts; pass it through environment variables instead.
- Use OpenID Connect (OIDC) to get short-lived cloud credentials (AWS, Azure, GCP) instead of storing long-lived keys as secrets.
- Protect secrets with environments and required reviewers for deployments.
- Restrict which actions may run (allow lists) at the organization level.
- Treat self-hosted runners as sensitive — never use them on public repositories, where anyone can trigger jobs.
- Generate artifact attestations for build provenance.

## Secrets and variables
tags: secrets, variables, environment secrets, organization secrets, Dependabot secrets, configuration
ask: Where should I store API keys for CI? | What's the difference between secrets and variables?
GitHub stores configuration for Actions at several levels:
- Secrets are encrypted values (API keys, signing passwords) exposed to workflows as ${{ secrets.NAME }} and masked in logs. They can be set at repository, environment or organization level (with repository access policies).
- Variables (${{ vars.NAME }}) hold non-sensitive configuration in plain text — region names, feature toggles, version numbers.
- Environment secrets are only available to jobs that reference that environment, and can sit behind required reviewers, which makes them ideal for production deploy keys.
- Dependabot secrets are separate, because Dependabot-triggered workflows don't receive regular secrets.
- Codespaces secrets are for development environments.

Practices: never echo secrets or write them to artifacts; secrets aren't passed to workflows triggered from forks; rotate regularly and immediately after any suspected leak; prefer OIDC over stored cloud keys; and keep signing keys (such as an Android keystore) as base64-encoded secrets decoded at build time — or, as BlackLab does, sign locally and never put the key in the repository at all.

## GitHub Packages and Container Registry
tags: GitHub Packages, container registry, ghcr, Docker, npm, Maven, publishing
ask: How do I publish a Docker image to GitHub? | How do I use GitHub Packages with Gradle?
GitHub Packages hosts packages next to your code: the Container registry (ghcr.io) for Docker and OCI images, plus npm, Maven and Gradle, NuGet and RubyGems registries.

Containers: tag images as ghcr.io/owner/image:tag, authenticate with a token, and push. In Actions, log in with docker/login-action using the GITHUB_TOKEN (with packages: write permission), then build and push with docker/build-push-action. Images can be public or private and linked to a repository, inheriting its access settings.

Maven and Gradle: add the GitHub Packages repository URL and credentials to your build file, then publish with the Gradle publishing plugin; consumers need a token with read:packages, even for public packages in some registries.

Tips: use immutable version tags plus a moving latest tag; sign images and generate attestations for provenance; set retention policies to delete untagged or old versions; and scan images for vulnerabilities before release.

## Releases and tags
tags: releases, tags, semantic versioning, release notes, assets, changelog
ask: How do I automate release notes? | How should I version releases?
Tags mark specific commits (annotated tags carry a message and tagger). Releases are GitHub pages built on tags, with notes and downloadable assets.

Creating a release: from the web UI, with gh release create v1.2.0 --generate-notes app.apk app.apk.sha256, or from an Actions workflow on tag push. Auto-generated release notes list merged pull requests and new contributors, and a .github/release.yml file groups them by label (Features, Fixes, Maintenance).

Versioning: semantic versioning (MAJOR.MINOR.PATCH) communicates impact. Conventional commits plus tools such as release-please or semantic-release can bump versions, update the changelog and open release pull requests automatically.

Assets: attach binaries with checksums (SHA-256) so users and updaters can verify downloads. BlackLab's in-app updater depends on exactly this: each release carries the APK and its .sha256 file, and the app refuses releases without a matching checksum and signature. Mark prereleases clearly; "latest" points to the newest full release. Keep a human-readable CHANGELOG too.

## GitHub Pages
tags: GitHub Pages, static site, hosting, Jekyll, custom domain, documentation site
ask: How do I deploy a site with GitHub Pages? | Can I use a custom domain?
GitHub Pages hosts static websites free from a repository: documentation, portfolios, project pages and single-page apps. BlackLab Desktop's web version is served this way.

Publishing sources:
- Deploy from a branch: a folder (root or /docs) on a branch is published, optionally built with Jekyll.
- GitHub Actions: build with any generator (Vite, Astro, Hugo, MkDocs, Docusaurus) and deploy with actions/upload-pages-artifact and actions/deploy-pages. This is the modern, flexible option.

Site URLs are username.github.io for user sites and username.github.io/repository for project sites. Custom domains are supported with CNAME or A records, HTTPS is provisioned automatically, and verifying the domain prevents takeover.

Limits: static content only (no server code, so use APIs or serverless functions for dynamic needs), a published size limit of about 1 GB, and bandwidth soft limits. Pages from private repositories need a paid plan, and the published site itself is public unless you use enterprise access control.

## Dependabot
tags: Dependabot, dependencies, security updates, version updates, alerts, dependabot.yml
ask: How do I configure Dependabot? | How do I stop Dependabot from flooding me with PRs?
Dependabot keeps dependencies healthy in three ways:
- Dependabot alerts warn when your dependency graph includes packages with known vulnerabilities from the GitHub Advisory Database.
- Security updates automatically open pull requests that bump vulnerable dependencies to patched versions.
- Version updates, configured in .github/dependabot.yml, open regular pull requests to keep dependencies current, for each ecosystem and directory (gradle, npm, pip, docker, github-actions and more).

Taming the noise: schedule weekly or monthly; group related updates into one pull request (for example all AndroidX libraries, or all patch versions); ignore specific packages or major versions; limit open pull requests; and add labels and reviewers. Auto-merge low-risk patch updates when CI passes, using a workflow with the fetch-metadata action.

Include the github-actions ecosystem so pinned action SHAs get updated too. Dependabot only helps if CI is trustworthy — good tests make dependency updates routine instead of scary.

## Code scanning, secret scanning and push protection
tags: CodeQL, code scanning, secret scanning, push protection, Advanced Security, SAST
ask: How do I enable CodeQL? | What happens when push protection blocks a secret?
GitHub's application security features:
- Code scanning analyzes code for vulnerabilities and shows alerts on pull requests and in the Security tab. CodeQL is GitHub's semantic analysis engine — it treats code as data and queries it for patterns like SQL injection or unsafe deserialization. Default setup enables it in a few clicks for supported languages, including Java and Kotlin, JavaScript and TypeScript, Python, C#, Go, Ruby, C and C++, and Swift; advanced setup uses a workflow for custom queries. Third-party tools can upload results in the SARIF format.
- Secret scanning detects committed tokens and keys from many providers; for public repositories, partners are notified so leaked credentials can be revoked automatically.
- Push protection blocks pushes containing detected secrets before they ever reach GitHub; a developer must remove the secret or explicitly bypass with a reason.
- Copilot Autofix can suggest fixes for code scanning alerts.

These features are free for public repositories; private repositories need GitHub Advanced Security (sold as Secret Protection and Code Security). A local pre-push secret scan, like BlackLab's scripts/secret-scan.sh, adds another layer.

## Security advisories and SECURITY.md
tags: security policy, SECURITY.md, vulnerability reporting, security advisories, CVE, disclosure
ask: How should people report vulnerabilities in my project? | How do I publish a security advisory?
A SECURITY.md file tells people how to report vulnerabilities privately and which versions you support with fixes. GitHub shows it in the repository's Security tab.

Private vulnerability reporting, when enabled, gives researchers a "Report a vulnerability" form so reports never appear in public issues.

Repository security advisories provide a private workspace to handle a report: discuss with the reporter, collaborate on a fix in a temporary private fork, request a CVE identifier (GitHub is a CVE Numbering Authority), and publish the advisory when the fix ships. Published advisories feed the GitHub Advisory Database, which triggers Dependabot alerts for everyone who depends on the affected package.

Good disclosure practice: acknowledge reports quickly, keep the reporter informed, credit them, fix and release before public disclosure, and describe impact, affected versions and remediation clearly. BlackLab's repository includes a SECURITY.md for this reason.

## The GitHub CLI
tags: gh, GitHub CLI, command line, automation, pull requests, workflows
ask: What are the most useful gh commands? | Can gh call the API directly?
The GitHub CLI (gh) brings GitHub to the terminal. Authenticate with gh auth login. Useful commands:
- Repositories: gh repo create, gh repo clone owner/repo, gh repo view --web.
- Pull requests: gh pr create --fill, gh pr checkout 123 (check out someone's pull request locally), gh pr view, gh pr diff, gh pr review --approve, gh pr merge --squash --auto, gh pr checks --watch.
- Issues: gh issue create, gh issue list --label bug, gh issue view 42.
- Actions: gh run list, gh run watch, gh run view --log-failed, gh workflow run deploy.yml -f env=prod.
- Releases: gh release create v1.0.0 --generate-notes file.apk, gh release download.
- Search: gh search code "TODO" --owner you, gh search issues.
- API: gh api repos/{owner}/{repo}/pulls for REST and gh api graphql -f query='...' for GraphQL, with built-in authentication, pagination (--paginate) and jq filtering (--jq).
- Extensions add commands (gh extension install), and gh alias set creates shortcuts.
gh is ideal for scripting repetitive GitHub chores.

## The GitHub API, apps and tokens
tags: GitHub API, REST, GraphQL, GitHub Apps, OAuth Apps, personal access token, rate limits
ask: GitHub App or personal access token? | REST or GraphQL?
GitHub offers a REST API (resource endpoints, simple and well documented) and a GraphQL API (fetch exactly the fields you need in one request — efficient for nested data such as projects or pull requests with reviews). Octokit libraries wrap both.

Authentication options:
- Fine-grained personal access tokens: scoped to specific repositories and permissions, with expiry. Prefer them over classic tokens, which grant broad scopes.
- GitHub Apps: the recommended way to build integrations. They act as themselves (not as a user), request granular permissions, install per organization or repository, get short-lived installation tokens, and receive webhooks. They also have higher rate limits that scale with installations.
- OAuth Apps act on behalf of a user with broad scopes; they're largely superseded by GitHub Apps for new work.
- GITHUB_TOKEN inside Actions for automation in the same repository.

Rate limits apply — about 5,000 REST requests per hour for authenticated users, and point-based limits for GraphQL. Use conditional requests (ETags), webhooks instead of polling, and pagination carefully.

## Webhooks
tags: webhooks, events, integrations, automation, payload, signature
ask: How do I verify a webhook really came from GitHub? | Webhooks or Actions?
Webhooks send an HTTP POST to your server when events happen: push, pull_request, issues, release, workflow_run and many more. They can be configured on a repository, an organization or a GitHub App.

Handling them well:
- Verify authenticity: configure a secret, then check the X-Hub-Signature-256 header (an HMAC-SHA256 of the raw body) with a constant-time comparison before trusting the payload.
- Respond quickly (within about ten seconds) with a 2xx status, and do the real work asynchronously from a queue.
- Make handlers idempotent; deliveries can be retried or duplicated. Use the X-GitHub-Delivery identifier to deduplicate.
- Subscribe only to the events you need.
- Inspect recent deliveries and redeliver failed ones from the webhook settings page while debugging; tools like smee.io forward webhooks to a local machine during development.

Use webhooks when an external system needs to react to GitHub events; use Actions when the automation can live entirely inside GitHub.

## GitHub Copilot
tags: Copilot, AI pair programmer, code completion, Copilot Chat, coding agent, code review
ask: How do I get the most out of Copilot? | What can the Copilot coding agent do?
GitHub Copilot is GitHub's AI coding assistant, with several surfaces:
- Code completions in editors (VS Code, JetBrains IDEs including Android Studio, Visual Studio, Xcode, Neovim) suggest lines and blocks as you type, including next-edit suggestions.
- Copilot Chat answers questions about code, explains, refactors, writes tests and fixes errors, in the IDE, on github.com and on mobile. Agent mode in the IDE can make multi-file edits and run commands.
- The Copilot coding agent works asynchronously: assign it an issue and it plans, codes in a secure cloud environment, runs tests and opens a pull request for your review.
- Copilot code review leaves review comments on pull requests.
- Model choice lets you select among models from several providers for chat and agents.

Getting good results: keep relevant files open, write descriptive function names and comments, give examples, and add repository custom instructions (.github/copilot-instructions.md) describing conventions, build commands and architecture. Always review generated code for correctness, security and licensing; enable the duplicate-detection filter if you need to avoid matches with public code. Plans range from a free tier to individual, business and enterprise tiers.

## Codespaces and dev containers
tags: Codespaces, dev containers, devcontainer.json, cloud development, environments
ask: What is a dev container? | When are Codespaces worth paying for?
GitHub Codespaces gives you a cloud development environment — a container on a virtual machine — that opens in VS Code in the browser or on the desktop, or in JetBrains IDEs, in seconds.

Dev containers define the environment as code in .devcontainer/devcontainer.json: base image or Dockerfile, features (preinstalled tools like Node, Java or the GitHub CLI), editor extensions and settings, forwarded ports, and lifecycle commands such as postCreateCommand to install dependencies. The same file works locally with Docker and the Dev Containers extension, so everyone gets an identical setup.

Benefits: onboarding in minutes, no "works on my machine," powerful machines on demand, and safe environments for reviewing untrusted code. Prebuilds prepare environments ahead of time so large repositories start fast.

Costs: billed by core-hours and storage beyond the free monthly allowance for personal accounts; set spending limits and idle timeouts. For Android development the emulator needs local hardware acceleration, so Codespaces suits the backend and web parts more than device testing.

## Organizations, teams and permissions
tags: organizations, teams, permissions, roles, SSO, audit log, enterprise
ask: How should I structure teams and permissions? | What repository roles are there?
Organizations are shared accounts for groups, with central billing, policies and members.

Repository roles: Read (view and clone), Triage (manage issues and pull requests without write access), Write (push), Maintain (manage the repository without destructive settings) and Admin (full control). Custom roles exist on enterprise plans.

Teams group members (and can nest) to grant repository access, receive review requests through CODEOWNERS, and be @mentioned. Manage access through teams rather than individuals.

Security and governance: require two-factor authentication for all members; set base permissions low (none or read); restrict who can create repositories or change visibility; use the audit log to review actions; and set organization-wide rulesets and Actions policies. Enterprise plans add SAML single sign-on, SCIM provisioning, Enterprise Managed Users, IP allow lists and data residency options.

Outside collaborators get access to specific repositories without joining the organization — useful for contractors.

## Repository hygiene and community files
tags: README, LICENSE, CONTRIBUTING, CODE_OF_CONDUCT, gitignore, gitattributes, community health
ask: What files should every repo have? | Which license should I choose?
A professional repository includes:
- README.md: what the project is, screenshots, install and quick start, usage, and links. It's the front door. BlackLab's README follows this pattern.
- LICENSE: without one, others legally can't reuse your code. MIT and Apache-2.0 are permissive (Apache adds an explicit patent grant); GPL-3.0 requires derivatives to stay open; choosealicense.com helps decide.
- CONTRIBUTING.md: how to set up, coding standards, and the pull request process.
- CODE_OF_CONDUCT.md: expectations for community behavior (the Contributor Covenant is common).
- SECURITY.md: how to report vulnerabilities.
- .gitignore: build outputs, IDE files, local configuration and secrets such as keystores and local.properties.
- .gitattributes: line endings, Git LFS tracking, diff and merge settings, and linguist overrides.
- CHANGELOG.md: human-readable history of changes.
- Templates in .github/: issues, pull requests, funding.
GitHub's Community Standards page checks these. Default community files can live in an organization's .github repository.

## Large files, big repos and Git LFS
tags: Git LFS, large files, sparse checkout, shallow clone, repository size, performance
ask: When should I use Git LFS? | How do I speed up cloning a huge repo?
Git stores every version of every file, so large binaries bloat history forever. GitHub blocks files over 100 MB and warns above 50 MB.

Git Large File Storage (LFS) replaces large files (videos, design files, datasets, APKs if you insist) with small pointer files in Git, storing the content separately. Track patterns with git lfs track "*.psd", commit the resulting .gitattributes, and push. LFS has storage and bandwidth quotas. Release binaries usually belong in GitHub Releases instead, which is why BlackLab attaches APKs to releases.

Working with big repositories:
- Shallow clones (git clone --depth 1) fetch only recent history — great for CI.
- Partial clones (--filter=blob:none) download file contents on demand.
- Sparse checkout (git sparse-checkout set path/) checks out only the directories you need in monorepos.
- Scalar and git maintenance optimize performance for very large repositories.

If big files already landed in history, rewrite with git filter-repo (and coordinate with everyone, since hashes change).

## Merge conflicts
tags: merge conflicts, git, resolving conflicts, rerere
ask: How do I resolve a merge conflict? | How can I avoid conflicts in the first place?
A merge conflict happens when two branches change the same lines (or one edits a file the other deleted), and Git can't decide automatically.

Resolving: Git marks conflicted files with <<<<<<<, ======= and >>>>>>> sections showing both versions. Edit the file to the correct combined result (not necessarily either side), remove the markers, run the tests, git add the file, then continue the merge or rebase (git merge --continue or git rebase --continue). To bail out, use git merge --abort or git rebase --abort. GitHub's web editor handles simple conflicts in pull requests; IDEs offer three-way merge tools that make complex ones easier.

Avoiding conflicts: keep branches short-lived; merge or rebase from main often; keep pull requests small; separate formatting-only changes into their own pull requests; and agree on formatters so whitespace doesn't cause conflicts. git rerere ("reuse recorded resolution") remembers how you resolved a conflict and reapplies it automatically when the same one recurs during repeated rebases.

## Commit hygiene: conventional commits and signing
tags: conventional commits, commit messages, signed commits, GPG, SSH signing, co-authors
ask: What are conventional commits? | How do I sign my commits?
Good commit messages make history useful.
- Conventional Commits format: type(scope): summary — for example feat(assistant): add Garage voice commands or fix(pdf): keep rotation on save. Types include feat, fix, docs, refactor, test, chore, perf and ci; a "!" or a BREAKING CHANGE footer marks breaking changes. Tools can then generate changelogs and determine semantic version bumps automatically.
- Subject under about 72 characters, imperative mood; a body explaining why; and references to issues.

Signed commits prove authorship. Sign with GPG, SSH keys or S/MIME (git config commit.gpgsign true, and gpg.format ssh for SSH signing), and upload the public key to GitHub; commits then show a "Verified" badge. Vigilant mode flags unsigned commits attributed to you. Commits made through the GitHub web interface are signed by GitHub automatically.

Co-authorship: add Co-authored-by: Name <email> trailers at the end of the message to credit pair programmers — or AI assistants — and GitHub shows all authors.

## Discussions, wikis and gists
tags: GitHub Discussions, wiki, gists, community, Q&A, snippets
ask: Should I use Discussions or issues? | What are gists good for?
Beyond issues, GitHub offers other collaboration spaces:
- Discussions: a forum for questions, ideas, announcements and show-and-tell, with categories, threaded replies, marked answers and polls. Use Discussions for open-ended conversation and support; keep issues for actionable work. Discussions can be converted to issues when they become concrete.
- Wikis: simple Markdown documentation pages attached to a repository, stored in a separate Git repository. Fine for lightweight documentation, though many projects prefer docs in the main repository (reviewed in pull requests) or a Pages site.
- Gists: small, shareable snippets or notes, each a mini Git repository with history; public or secret (unlisted, not private). Great for sharing a script, a config example or a quick write-up, and they can be embedded in web pages.

There are also GitHub Sponsors for funding open-source maintainers, and profile READMEs (a repository named after your username) that turn your GitHub profile into a personal landing page — a strong career signal.

## Workflows: GitHub Flow and trunk-based on GitHub
tags: GitHub Flow, workflow, trunk-based, feature flags, deployment, team process
ask: What's the simplest team workflow on GitHub? | How do feature flags fit in?
GitHub Flow is a simple, effective workflow:
- main is always deployable.
- Create a descriptively named branch for each change.
- Commit and push early; open a draft pull request for visibility and CI.
- Discuss and review in the pull request; CI must pass.
- Merge (often squash) and deploy promptly; delete the branch.

Trunk-based development takes it further: very short-lived branches (hours to a day or two), frequent integration, and feature flags to hide incomplete work so code merges early without exposing unfinished features. Merge queues keep main green under high merge volume.

Supporting GitHub features: branch protection or rulesets, required checks, CODEOWNERS, auto-merge, deployment environments with approvals, and automatically deleted head branches. For mobile apps with store releases, add release branches or tags for each version so you can hotfix a shipped version while main moves on — the tag-per-release pattern BlackLab uses.

## GitHub Marketplace and GitHub Models
tags: Marketplace, GitHub Apps, actions marketplace, GitHub Models, AI models, integrations
ask: How do I pick trustworthy Marketplace actions? | What is GitHub Models?
GitHub Marketplace lists Actions and Apps. When choosing an action: prefer verified creators and official actions (actions/*, or vendor organizations such as docker/* and aws-actions/*), check stars, recent maintenance and open issues, read its source — it runs with your workflow's permissions — and pin it to a commit SHA. Apps from the Marketplace install with explicit permissions you approve; review them like any third-party access.

GitHub Models provides a catalog of AI models from multiple providers that you can try in a playground, compare side by side, and call through an API using a GitHub token — handy for prototyping AI features without separate accounts. It supports prompt files stored in repositories and lightweight evaluations, so prompt changes can be reviewed and tested like code; free usage is rate-limited, with paid options for more. It pairs naturally with Actions for experiments in automation — summarizing issues, triaging labels, or drafting release notes — with human review of the results.
