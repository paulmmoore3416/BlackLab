---
id: conv-ai-builder
title: AI Builder's Lab
kind: conversation
summary: Brainstorm and pressure-test AI projects — from idea to prototype to production, with honest talk about RAG, agents, evals, cost and hype.
draws: ai-engineering, ai-development, software-engineering, software-development, github
persona: You are a hands-on AI engineer who has shipped real LLM products and also knows classic ML. You're enthusiastic but allergic to hype, push for evals and simple baselines, and love turning messy operational problems into practical AI projects. Concrete, curious, candid about limits.
opener: Welcome to the lab. Got an AI idea you want to pressure-test, something that's misbehaving, or want me to pitch a few projects that'd actually move the needle in facility operations?
starters: Pitch me AI projects for facility operations | My RAG answers are bad — help | Should I build an agent for this? | How do I start writing evals? | Give me a 90-day AI learning plan
---

## AI projects that pay off in facility operations
tags: facilities, use cases, operations, ROI, ideas
ask: Which of these would your team use first? | What data do you already have?
Practical AI projects for facility operations, roughly from quickest win to biggest lift:
- Work-order intake triage: classify incoming requests by trade, priority and location, and draft the work order from a free-text email or a photo. Measurable by reduced dispatcher time and fewer misroutes.
- Technician note cleanup and summaries: turn voice notes into clean closeout notes, parts used and follow-ups — dictation plus summarization, exactly what BlackLab already does on the phone.
- Document Q&A over O&M manuals, warranties and service contracts: "what's the warranty on RTU-4's compressor?" with citations.
- Inspection and compliance assistant: checklists from photos, deficiency extraction, report drafting.
- Energy anomaly detection on meter and BAS data: catch simultaneous heating and cooling, schedules overriding, or drifting baselines.
- Predictive maintenance for critical assets using trends and work-order history.
- Vendor and contract analysis: compare bids, flag unusual terms.
Start with the one where you already have data, a clear owner and a measurable metric.

## Idea to prototype in a weekend
tags: prototype, MVP, rapid prototyping, LLM app, weekend project
ask: What idea do you want to prototype? | What would success look like after the weekend?
Here's a weekend plan for an LLM prototype:
- Friday night: write the problem in one paragraph, collect 20 real examples of inputs and what a great output looks like. Those examples are your first eval set.
- Saturday morning: one prompt, a strong model, run all 20 examples, read every output. Note failure patterns.
- Saturday afternoon: fix the biggest failure with the cheapest lever — clearer instructions, a couple of examples, structured output, or retrieval if the model lacks knowledge.
- Saturday night: wrap it in the simplest interface someone can use — a script, a small web page, a phone shortcut.
- Sunday: put it in front of one real user, watch them, and add their failures to the eval set.

Resist building infrastructure — no vector database, agent framework or fine-tuning until the evals show you need them. If it isn't useful with a great prompt and good examples, more machinery rarely saves it.

## RAG troubleshooting clinic
tags: RAG, troubleshooting, retrieval, chunking, debugging
ask: What does a bad answer look like — wrong, vague or made up? | Have you looked at what's being retrieved?
When RAG answers are bad, first find out which half is failing. Take ten bad answers and look at the retrieved passages. If the right information isn't in them, it's a retrieval problem; if it is there and the answer is still wrong, it's a generation problem.

Retrieval fixes: improve chunking so passages keep context (headings, section titles); add keyword search alongside embeddings for part numbers, codes and names; rewrite vague user queries into better search queries; filter by metadata (building, date, document type); add a reranker; and retrieve a few more candidates.

Generation fixes: tell the model to answer only from the context and say when it can't; ask it to quote the relevant lines first; put the question after the documents; reduce noise by passing fewer, better passages.

Also check the data itself — scanned PDFs with bad OCR, duplicated outdated versions of a document, and tables flattened into nonsense cause a huge share of RAG failures.

## Should this be an agent?
tags: agents, workflows, design decision, complexity
ask: Can you list the steps the task usually takes? | What's the cost of a mistake here?
Ask: can I write down the steps? If the task follows a predictable sequence — extract, classify, look something up, draft — build a workflow: fixed steps with LLM calls where needed. It's cheaper, faster, easier to test and easier to trust.

Reach for an agent when the path genuinely varies: the number and order of steps depend on what's found along the way — debugging, research, multi-system troubleshooting. Even then, give it a tight toolset, a budget on steps and cost, checkpoints for human approval before consequential actions, and good logging.

Then ask about the cost of mistakes. An agent drafting a summary that a human reviews can be fairly autonomous; an agent issuing purchase orders or changing building setpoints needs strict limits and approvals. A good middle ground is "agent proposes, human disposes": it prepares the work order, email or change, and a person approves with one tap.

## Writing your first evals
tags: evals, evaluation, testing, quality, metrics
ask: What does a great output look like for your use case? | How many examples could you gather this week?
Evals turn "it seems better" into numbers. Start tiny:
- Gather 20–50 real inputs, including the hard and weird ones.
- For each, write what a good output must contain or must not do. For extraction, that's the exact expected fields; for summaries, a short checklist ("mentions the due date," "no invented costs").
- Score automatically where you can — exact match, JSON schema checks, keyword or regex checks — and use an LLM judge with a specific rubric for fuzzier qualities like completeness and tone.
- Calibrate the judge: grade 20 outputs yourself and confirm the judge mostly agrees.
- Run the suite on every prompt or model change and keep a simple results table over time.

Most importantly, read outputs. Patterns in failures — always missing dates, overly long answers, trouble with abbreviations — tell you exactly what to fix. Add every production failure to the set; your eval suite becomes the institutional memory of what "good" means.

## Hype versus reality
tags: hype, AI limits, expectations, realism, strategy
ask: What AI claim have you heard that smelled off? | Where have you seen AI genuinely deliver?
A sanity check for AI claims: what's the baseline, what's the eval, and what happens when it's wrong? Demos are cherry-picked; production is the long tail of weird inputs.

What reliably works today: drafting and summarizing text, extracting structure from messy documents, classification and routing, answering questions over your documents with citations, code assistance, and speech-to-text. Agents handle well-scoped tasks with good tools, though reliability falls as tasks get longer and less defined.

What still needs care: exact numbers and calculations (use tools), high-stakes decisions without review, long multi-step autonomy, and anything requiring knowledge the model doesn't have and you didn't provide.

The pattern that wins is boring: pick a narrow, high-volume task, measure the current cost, add AI with a human in the loop, measure again, and expand. Most value comes from many small, reliable improvements rather than one magical system.

## Building on-device AI like BlackLab
tags: on-device, Gemini Nano, mobile AI, privacy, offline, Android
ask: What would you add to BlackLab's assistant next? | What runs well on a phone model?
BlackLab is a good case study in on-device AI. The assistant runs a deterministic command router first — fast, offline and predictable for things like "add a task" or "what's on my calendar." Unrecognized requests go to Gemini Nano through ML Kit's Prompt API, which either rewrites them into a command or answers directly. Ask-my-files uses on-device BM25 search to find passages, then Nano writes a cited answer. When Nano isn't available, it falls back to extractive answers, so nothing breaks.

What runs well on a small model: rewriting, short summaries, classification, extraction from a page or two, and turning loose phrasing into structured commands. What doesn't: long documents in one go (so summaries use map-reduce chunking), deep reasoning, and broad world knowledge — which is why the knowledge bases ship inside the app as searchable notes.

Design lessons: keep prompts short and explicit, constrain output formats, always have a non-AI path, and respect privacy as a feature, not an afterthought.

## The real cost of AI features
tags: cost, budget, tokens, pricing, economics
ask: How many requests a day do you expect? | Would a smaller model pass your evals?
Costs sneak up through volume and context size. A quick estimate: requests per day times tokens per request times price per token. The surprise is usually input tokens — long system prompts, retrieved documents and chat history are sent with every call. Agents multiply it again with many calls per task.

Levers, in order of impact: choose the smallest model that passes your evals; route easy requests to cheap models; use prompt caching for stable prefixes; retrieve fewer, better passages; summarize long histories; cap output length; batch offline work at discount rates; and cache repeated answers.

On-device models change the math entirely — zero per-call cost and no data leaving the phone — at the price of capability. Hybrid designs get the best of both: do the routine work locally, escalate hard cases to a cloud model with the user's consent.

Always tie cost to value: minutes saved per task times tasks per month times labor rate. AI that saves dispatchers an hour a day pays for a lot of tokens.

## Prompt critique session
tags: prompts, prompt engineering, critique, improvement
ask: Want to read me a prompt you're using? | What's the output problem you're seeing?
When I review a prompt, I check a few things:
- Is the task stated plainly in the first lines? Models, like people, anchor on the opening.
- Is there context — who the output is for, why, and what good looks like?
- Are instructions positive and specific ("Write three bullets under 15 words each") rather than vague ("be concise")?
- Are there examples, and are they varied enough not to be copied blindly?
- Is the output format defined exactly, ideally as a schema or template?
- Are documents and data clearly separated from instructions, with labels or tags?
- Does it tell the model what to do when information is missing?
- Is there contradictory or leftover instruction from older versions? Prompts accumulate cruft.

Then test changes one at a time against your eval set. A surprising amount of improvement comes from deleting things — long prompts full of "IMPORTANT" and capital letters often perform worse than a calm, clear one.

## Staying current without drowning
tags: learning, news, staying current, information diet, AI field
ask: Where do you get your AI news now? | How much time a week can you give it?
The AI field moves weekly, and trying to follow everything is a recipe for burnout. A sustainable information diet:
- A few primary sources: official model release notes and documentation from the major labs and platforms you actually use.
- One or two curated newsletters or digests, read weekly rather than daily.
- A handful of thoughtful practitioners rather than hype accounts.
- Hands-on time: thirty minutes trying a new capability on your own problem teaches more than ten threads about it.

Filter questions for any announcement: does this change what I can build, what it costs, or how reliable it is? If not, skip it. Keep a running "try later" list and revisit monthly.

Foundations age slowly — evaluation, retrieval, prompting principles, software engineering, data quality. Invest there, and the weekly news becomes easier to place.

## Ethics gut-checks for AI projects
tags: ethics, responsible AI, privacy, fairness, safety
ask: Who could be affected by your project that isn't in the room? | What's the worst realistic misuse?
Quick gut-checks before shipping an AI feature:
- Who's affected, including people who aren't users — employees being monitored, tenants, contractors?
- What data does it touch, and did people expect it to be used this way? Minimize and protect it.
- What happens when it's wrong, and who catches it? Higher stakes need human review and easy appeal.
- Could it treat groups differently — by language, location, role? Test across them.
- Is it clear to people that AI is involved and what it can't do?
- What's the worst realistic misuse, and have you made it harder?
- Can you turn it off quickly?

In facilities, watch occupancy analytics and camera-based features especially: aggregate data, avoid identifying individuals, involve HR and legal early, and be transparent with staff. Responsible AI isn't a separate project; it's these questions asked at design time and revisited after launch.

## A 90-day AI builder plan
tags: learning plan, roadmap, 90 days, study, projects
ask: How many hours a week can you commit? | What project would you want to finish by day 90?
A 90-day plan for a busy professional, around five hours a week:
- Days 1–30, foundations: how LLMs work (tokens, context, sampling), prompt engineering with structured outputs, and calling a model API from Python. Project: a script that turns work-order emails into structured JSON, with a 30-example eval set.
- Days 31–60, knowledge and tools: embeddings, chunking, hybrid search and RAG with citations; tool calling. Project: Q&A over your O&M manuals and contracts, with retrieval evals (did the right passage come back?).
- Days 61–90, systems: agents versus workflows, MCP, observability, cost control, and deployment. Project: a small assistant that drafts a work order from a photo plus a voice note, asks for confirmation, and logs everything. Publish it on GitHub with a README and an evals section.

Along the way, read one solid explainer a week and keep a learning log. By day 90 you'll have three working projects, real intuition about failure modes, and a portfolio that speaks for itself.

## Classic ML or LLM?
tags: classic ML, LLM, model choice, tabular data, decision
ask: Is your data mostly numbers or mostly text? | How many labeled examples do you have?
Not every problem needs a large language model. Rules of thumb:
- Mostly numeric, tabular data — sensor readings, costs, runtimes — with a clear target: classic ML (gradient boosting) is usually more accurate, cheaper, faster and more explainable. Energy forecasting and failure prediction live here.
- Mostly text, documents, images or speech, with fuzzy categories and little labeled data: LLMs shine, because they bring general knowledge and need only a few examples.
- Lots of labeled text and very high volume: a small fine-tuned classifier may beat an LLM on cost and speed — and an LLM can help label the training data.
- Hybrid is common: an LLM extracts structured fields from messy text (failure mode, part, cause), then classic ML or simple analytics run on those fields.

Start with the simplest approach that could work, set a baseline, and let your evals choose. An honest "a spreadsheet rule handles 90% of this" is a great outcome.
