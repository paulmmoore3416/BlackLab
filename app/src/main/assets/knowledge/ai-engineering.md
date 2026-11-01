---
id: ai-engineering
title: AI Engineering
kind: domain
summary: Building real products on large language models — prompting, RAG, agents, tools, evals, safety, cost and on-device AI.
---

## What AI engineering is
tags: AI engineering, LLM applications, foundation models, ML engineering
ask: How is AI engineering different from ML engineering? | What skills does an AI engineer need?
AI engineering is building products on top of foundation models — large language models and multimodal models — instead of training models from scratch. Classic ML engineering centers on data collection, feature engineering and training; AI engineering centers on adapting a capable pretrained model to a task through prompts, context, retrieval, tools, fine-tuning and evaluation, then running it reliably at acceptable cost and latency.

Core skills: prompt and context design, retrieval-augmented generation, tool and agent design, evaluation methodology, guarding against failure modes (hallucination, prompt injection), observability, and the usual software engineering discipline — APIs, testing, security, deployment. The biggest mindset shift is that outputs are probabilistic: you engineer for distributions of behavior, measured by evals, rather than for single deterministic answers.

A good first principle: start with the simplest thing that could work — one well-written prompt to a strong model — measure it, and add complexity (retrieval, tools, agents, fine-tuning) only when evals show you need it.

## How LLMs work, in practice
tags: LLM, tokens, context window, temperature, top-p, sampling, next-token prediction
ask: What does temperature actually do? | Why do models make things up?
A large language model predicts the next token — a word piece of roughly four characters of English on average — given all previous tokens. Generating text is repeated sampling from that predicted distribution.

Key knobs and limits:
- Context window: the maximum tokens the model can attend to at once (prompt plus output). Everything the model "knows" about your situation must fit here.
- Temperature: scales randomness. Near 0 is focused and repeatable (extraction, classification, code); higher values are more varied (brainstorming).
- Top-p (nucleus) and top-k limit sampling to the most likely tokens.
- Max output tokens caps response length and cost.
- Stop sequences end generation at a marker.

Models know what they learned during training up to a knowledge cutoff, plus whatever you put in the context. They don't look facts up unless you give them tools or documents. Hallucinations happen because the model produces plausible continuations even when it lacks knowledge; grounding, citations and permission to say "I don't know" reduce them. Pricing and speed are typically measured per input and output token.

## Prompt engineering fundamentals
tags: prompt engineering, prompts, few-shot, system prompt, instructions, chain of thought
ask: What makes a prompt good? | When do examples help?
Treat the model like a brilliant new colleague with no context.
- Be explicit about the task, the audience, the format and what good looks like. Say what to do rather than only what not to do.
- Give context and the reason behind instructions ("This summary is read aloud while driving, so no bullet symbols").
- Use a system prompt for role, rules and persistent behavior; put task-specific material in the user turn.
- Structure long prompts with clear sections or tags (instructions, documents, examples) so the model can tell them apart.
- Few-shot examples teach format and judgment faster than descriptions; make them diverse and representative.
- For complex reasoning, ask the model to think step by step or use a model's built-in extended thinking, then give the final answer in a defined place.
- Specify output format precisely (JSON schema, headings, length).
- Put long documents before the question; ask the model to quote relevant parts before answering.
Iterate like engineering: keep a test set, change one thing at a time, and compare outputs side by side.

## Structured outputs and tool use
tags: tool use, function calling, structured output, JSON schema, tools
ask: How does function calling work? | How do you make JSON output reliable?
Tool use (function calling) lets a model request actions. You describe tools with names, descriptions and JSON-schema parameters; the model replies with a tool call; your code executes it and returns the result; the model continues. This is how assistants check calendars, query databases or send email.

Design tools like good APIs: clear names and descriptions (the model reads them), few required parameters, unambiguous types and enums, meaningful errors the model can recover from, and results that are concise. Prefer a few well-scoped tools over dozens of overlapping ones.

For structured outputs, use the provider's JSON or structured-output mode when available, which constrains generation to your schema. Otherwise give the schema plus an example, validate with a parser (Pydantic, Zod, kotlinx.serialization), and retry with the validation error in the prompt. Keep schemas flat where possible.

Safety: tools that act — sending, buying, deleting — should require confirmation or run with least privilege. BlackLab's assistant asks before sending email for this reason.

## Retrieval-augmented generation (RAG)
tags: RAG, retrieval-augmented generation, retrieval, grounding, citations, knowledge base
ask: Why is my RAG system giving bad answers? | RAG or long context?
RAG gives a model up-to-date or private knowledge by retrieving relevant passages and putting them in the prompt. The pipeline:
- Ingest: load documents, clean them, extract text (OCR for scans, table handling).
- Chunk: split into passages sized for retrieval.
- Index: embed chunks into vectors and/or build a keyword index (BM25).
- Retrieve: find candidates for the query, often hybrid (dense plus keyword).
- Rerank: reorder candidates with a cross-encoder or LLM for precision.
- Generate: answer from the retrieved context with instructions to cite sources and to say when the answer isn't there.

Most RAG failures are retrieval failures: the right passage never reaches the model. Debug by inspecting what was retrieved for failing questions. Fixes: better chunking, hybrid search, query rewriting, metadata filters, reranking, and contextual chunk headers (prepend document title and section to each chunk). Evaluate retrieval (recall at k) separately from generation (faithfulness, answer quality).

BlackLab's "Ask my files" and its knowledge bases are small on-device RAG systems: BM25 retrieval feeding Gemini Nano.

## Chunking strategies
tags: chunking, RAG, text splitting, documents, context
ask: What chunk size should I use? | How do you chunk tables and PDFs?
Chunking decides what a "unit of retrieval" is; it quietly determines RAG quality.
- Fixed-size chunks (for example 200–800 tokens) with 10–20% overlap are a simple baseline.
- Structure-aware chunking follows the document: headings, sections, paragraphs, list items, code functions. It keeps ideas intact and is usually better.
- Semantic chunking splits where the topic shifts, based on embedding similarity between sentences.
- Small-to-big (parent document retrieval): retrieve precise small chunks, then pass their larger parent section to the model for context.
- Contextual retrieval: prepend a short description of where the chunk sits ("From the 2024 HVAC service contract, section 5: response times") before embedding, so isolated chunks keep their meaning.

Special content: keep tables intact or convert them to text rows with headers repeated; keep code blocks whole; strip boilerplate such as headers, footers and navigation. Store metadata (source, date, author, section) with each chunk for filtering and citations. Tune chunk size with evals — there's no universal best number.

## Embeddings and vector search
tags: embeddings, vector search, vector database, cosine similarity, HNSW, hybrid search, reranking
ask: How do embeddings capture meaning? | Do I need a vector database?
An embedding model maps text to a vector so that similar meanings land close together. Similarity is usually cosine similarity or dot product on normalized vectors. Embeddings power semantic search, clustering, deduplication, recommendations and classification.

Vector search at scale uses approximate nearest neighbor (ANN) indexes such as HNSW graphs or IVF with product quantization, trading a little recall for large speedups. Options range from a library (FAISS) to a Postgres extension (pgvector) to dedicated vector databases (Pinecone, Weaviate, Qdrant, Milvus). For under about a million chunks, pgvector or even brute force is often enough.

Dense retrieval struggles with exact terms — part numbers, error codes, names — where keyword search (BM25) excels. Hybrid search combines both, often with reciprocal rank fusion, and a reranker then scores the top candidates jointly with the query for precision.

Practical notes: embed queries and documents with the same model; re-embed everything when you change models; and benchmark on your own data rather than trusting leaderboards alone.

## Agents: what they are and when to use them
tags: agents, agentic, autonomy, tool loop, planning, workflows
ask: When is an agent overkill? | What makes agents fail?
An agent is an LLM running in a loop: it decides what to do next, calls tools, observes results, and repeats until the task is done. Workflows, by contrast, are LLM calls orchestrated through predefined code paths. Anthropic's guidance draws exactly this line and recommends the simplest option that works: a single call, then a workflow, and only then an agent.

Agents fit open-ended tasks where the number of steps can't be predicted — coding, research, multi-system troubleshooting. They cost more, take longer and can compound errors, so they need guardrails: clear tool definitions, step and budget limits, checkpoints for human approval on risky actions, sandboxed execution, and good observability.

What makes agents work: strong tool design (the agent-computer interface deserves as much care as a human UI), ground truth from the environment at each step (test results, API responses), the ability to recover from errors, and context management for long tasks — summarizing progress, keeping notes outside the context window, and handing off between sub-agents.

## Agent and workflow design patterns
tags: agent patterns, prompt chaining, routing, orchestrator, evaluator-optimizer, parallelization
ask: Explain orchestrator-workers | Which pattern fits a document-processing pipeline?
Common building blocks for LLM systems:
- Prompt chaining: break a task into fixed sequential steps, each LLM call working on the previous output, with checks (gates) in between. Example: outline, then check the outline, then write.
- Routing: classify an input and send it to a specialized prompt, tool or model — cheap model for easy questions, strong model for hard ones.
- Parallelization: sectioning (independent subtasks run at once) or voting (several attempts, then aggregate) for speed or confidence.
- Orchestrator-workers: a central LLM breaks a task into subtasks dynamically, delegates them, and synthesizes results — useful when subtasks can't be predicted.
- Evaluator-optimizer: one call generates, another critiques against criteria, and the loop repeats until the criteria are met — good for translation, writing and code.
- Autonomous agent: open-ended loop with tools and environment feedback.
Combine patterns pragmatically. A facility work-order triage system might route by category, chain extraction then prioritization, and use an evaluator before auto-creating tickets.

## Model Context Protocol (MCP)
tags: MCP, Model Context Protocol, tools, integrations, servers, clients
ask: What problem does MCP solve? | How do I build an MCP server?
The Model Context Protocol is an open standard, introduced by Anthropic, for connecting AI applications to tools and data. Instead of writing a custom integration for every app-and-service pair, a service exposes an MCP server once, and any MCP-capable client (Claude apps, IDEs, agent frameworks) can use it.

Concepts: servers expose tools (actions the model can call), resources (data the application can read into context) and prompts (reusable templates). Clients connect over a transport — standard input/output for local servers, or streamable HTTP for remote ones — using JSON-RPC messages. Remote servers typically use OAuth for authorization.

Building a server: official SDKs exist for Python, TypeScript and other languages; you declare tools with names, descriptions and input schemas and implement handlers. Design tools around user tasks rather than mirroring every API endpoint, return concise results, and paginate large outputs.

Security: an MCP server runs with whatever permissions you give it. Install servers only from trusted sources, scope credentials narrowly, and treat tool outputs (web pages, emails, documents) as untrusted input that might contain prompt injection.

## Evaluations (evals)
tags: evals, evaluation, testing LLMs, LLM-as-judge, metrics, golden dataset
ask: How do I start writing evals? | Can I trust LLM-as-judge?
Evals are tests for AI behavior, and the most important practice in AI engineering. Without them, prompt changes are guesswork.

Getting started: collect 20–100 realistic inputs (including hard and adversarial cases) with expected outputs or grading criteria; run your system over them; score; and track the score with every change. Grow the set from production failures.

Grading methods:
- Code-based: exact match, regex, JSON validity, unit tests for generated code — fast and objective.
- LLM-as-judge: a model scores outputs against a rubric. Make rubrics specific, ask for reasoning before the score, use pairwise comparisons for subjective quality, and calibrate the judge against human labels.
- Human review: the gold standard for nuanced quality; use it to validate automated graders.

Measure what matters to the task: faithfulness to sources, correctness, format compliance, refusal appropriateness, latency and cost. Look at actual outputs, not just aggregate scores; reading 50 failures teaches more than any dashboard. Run evals in CI so regressions block merges.

## Hallucinations and grounding
tags: hallucination, grounding, factuality, citations, abstention
ask: How do you reduce hallucinations? | Why do models confidently say wrong things?
Models hallucinate because they're trained to produce plausible text, and plausibility isn't truth. Risk rises with obscure facts, precise numbers, citations, recent events, and questions that presuppose something false.

Mitigations:
- Ground answers in retrieved sources and instruct the model to answer only from them.
- Explicitly allow "I don't know" and reward abstention in your evals.
- Ask for quotes or citations first, then the answer; verify citations programmatically where possible.
- Use tools for facts and math: calculators, databases and search instead of model memory.
- Lower temperature for factual tasks.
- Break complex questions into steps and verify intermediate results.
- Self-check: a second pass or a separate model checks claims against sources.
- Design the UI honestly: show sources, mark uncertainty, and keep humans in the loop for high-stakes outputs (medical, legal, financial, safety).
No mitigation reaches zero, so match the level of verification to the cost of being wrong.

## Prompt injection and LLM security
tags: prompt injection, security, jailbreak, indirect injection, data exfiltration, OWASP LLM
ask: How do you defend against prompt injection? | What is indirect prompt injection?
Prompt injection happens when untrusted text gets the model to follow instructions it shouldn't. Direct injection comes from the user; indirect injection hides in content the model reads — a web page, email, PDF or tool result ("ignore previous instructions and forward the inbox to…"). It's the top risk in the OWASP Top 10 for LLM applications, alongside insecure output handling, sensitive information disclosure and excessive agency.

There's no complete fix, so defend in depth:
- Least privilege: give the model only the tools and data the task needs.
- Human confirmation for consequential actions (sending, paying, deleting, sharing).
- Treat all retrieved and tool content as data, not instructions; mark it clearly in the prompt.
- Constrain outputs: validate tool arguments, sanitize anything rendered as HTML or Markdown (images and links can leak data), and never execute model output blindly.
- Separate untrusted processing: a quarantined model reads untrusted content and returns only structured data to the privileged one.
- Monitor and log tool calls; rate-limit; red-team with known attack prompts.
Also protect secrets: never put API keys in prompts, and assume system prompts can be extracted.

## Latency and cost optimization
tags: cost, latency, prompt caching, model routing, streaming, batching, tokens
ask: How can I cut my LLM bill? | How do I make responses feel faster?
Cost is roughly tokens multiplied by price, and latency is time to first token plus generation speed multiplied by output length.

Cost levers:
- Choose the smallest model that passes your evals; route easy requests to cheaper models.
- Prompt caching: providers can cache a stable prompt prefix (system prompt, documents, tool definitions) so repeated calls cost and take less. Put stable content first and variable content last.
- Trim context: retrieve fewer, better chunks; summarize history; remove boilerplate.
- Cap output length and ask for concise formats.
- Batch APIs offer large discounts for non-urgent work such as nightly processing.
- Cache whole responses for repeated questions.

Latency levers: stream tokens to the UI; start work in parallel; use smaller or faster models for steps the user waits on; reduce output tokens, which dominate generation time; and keep connections warm. Perceived latency matters too — show progress, partial results and what the system is doing. Track cost per task, not just per call; agents can make dozens of calls per request.

## Context engineering and memory
tags: context engineering, context window, memory, compaction, long context, conversation history
ask: How do assistants remember things between chats? | Long context or RAG?
Context engineering is deciding exactly what goes into the model's limited context for each call: instructions, relevant knowledge, tool definitions, conversation history and state. More isn't always better; irrelevant or contradictory context degrades answers, and quality can drop in very long contexts.

Techniques:
- Keep system prompts clear and minimal but complete.
- Retrieve just-in-time instead of preloading everything; let agents fetch files or records when needed.
- Compaction: summarize older conversation turns into a running summary while keeping recent turns verbatim.
- External memory: store facts, preferences and task notes in a database or files, and load the relevant ones per request. Agents can keep a notes file of progress for long tasks.
- Sub-agents with clean contexts handle focused subtasks and return condensed results.
- Structured state (JSON) beats long prose for things like task lists.

Long context versus RAG: long context is simpler and better for reasoning across one big document; RAG scales to large collections and keeps costs down. Many systems combine them — retrieve generously, then let a long-context model reason over the results.

## Choosing models
tags: model selection, frontier models, open-weight, small models, on-device, benchmarks
ask: How do I pick the right model? | Open-weight or API models?
Choose models with your own evals, weighing quality, latency, cost, context length, modality, privacy and deployment constraints.

Options:
- Frontier API models (Anthropic's Claude family, OpenAI's GPT models, Google's Gemini) give the best quality for complex reasoning, coding and agents, with no infrastructure to run. Within a family, larger tiers trade cost and speed for capability.
- Open-weight models (Llama, Mistral, Qwen, Gemma and others) can run on your own hardware: full data control, customization through fine-tuning, and predictable cost at scale, but you own serving, scaling and safety.
- Small and on-device models (Gemini Nano on Pixel phones, small Gemma or Phi models) offer offline use, privacy and zero per-call cost, but limited reasoning and context. They're great for summarizing, rewriting, classification and command parsing — which is why BlackLab uses Gemini Nano and falls back to rules.

Benchmarks are a starting filter, not a decision; contamination and mismatched tasks make them unreliable for your use case. Build a model-agnostic layer so you can switch as models improve — they improve quickly.

## Fine-tuning, prompting or RAG?
tags: fine-tuning, LoRA, RAG, prompting, decision, customization
ask: When is fine-tuning worth it? | Can fine-tuning add knowledge?
A practical decision order:
- Prompting first: clear instructions and examples solve most problems and are instant to iterate.
- RAG when the model needs knowledge it doesn't have — private, changing or very specific information. Fine-tuning is a poor way to add facts; they're hard to update and still hallucinated.
- Fine-tuning when you need consistent behavior that prompting can't reliably produce: a specific style or format, a narrow classification task at high volume, a smaller and cheaper model that matches a bigger one on your task (distillation), or lower latency by removing long instructions.

Methods: supervised fine-tuning on input-output pairs; preference tuning (DPO) for choosing better responses; parameter-efficient techniques like LoRA and QLoRA, which train small adapter matrices instead of all weights and make fine-tuning affordable on a single GPU.

Requirements: hundreds to thousands of high-quality, representative examples; a held-out eval set; and a plan to re-tune when the base model changes. Measure against a strong prompted baseline — fine-tuning often loses to a better prompt on a better model.

## Observability for LLM applications
tags: observability, tracing, LLM monitoring, feedback, logging prompts, LLMOps
ask: What should I log for an LLM app? | How do you catch quality regressions in production?
LLM apps need observability beyond normal application monitoring.

Log and trace for every request: the prompt template version, model and parameters, retrieved documents, tool calls and results, output, latency per step, token counts and cost, and any errors or retries. Agent traces should show each step of the loop. Tools such as Langfuse, LangSmith, Arize Phoenix and OpenTelemetry-based tracing help.

Monitor quality in production: user feedback (thumbs, edits, regenerations), automated online evals on a sample of traffic (an LLM judge checking faithfulness or policy), drift in input topics, refusal rates and failure categories.

Close the loop: turn production failures into eval cases, fix, and re-run the suite before shipping. Privacy matters — prompts often contain personal data, so redact or minimize, restrict access, and set retention limits. For on-device AI, keep diagnostics on the device or get explicit consent before sharing.

## Deploying LLM features safely
tags: LLMOps, deployment, prompt versioning, A/B testing, fallbacks, rollouts
ask: How do you version prompts? | What should happen when the model API is down?
Treat prompts, model choices and retrieval settings as versioned configuration, deployed through the same review and CI process as code, with evals as gates.

Rollout practices:
- Shadow mode: run the new version alongside the old without showing results; compare offline.
- A/B tests or canaries with quality, cost and user-satisfaction metrics.
- Feature flags to switch versions instantly.

Resilience: set timeouts; retry transient errors with backoff; fall back to another model or provider, a cached answer or a non-AI path (BlackLab's rule-based router works when Gemini Nano isn't available); and degrade gracefully with honest messaging.

Pin model versions where providers allow it, and re-run evals before moving to a new version — even improvements can break your prompts or formats. Rate limits and quotas need handling with queues and backpressure. Document model cards and known limitations for stakeholders.

## Data for AI systems
tags: data, datasets, labeling, synthetic data, privacy, data quality
ask: How much data do I need? | Is synthetic data any good?
Data quality drives AI quality, even when you don't train models — your documents feed RAG, your examples teach prompts, and your eval sets define success.

Practices:
- Curate representative data that covers real-world variety, including edge cases and messy inputs.
- Write clear labeling guidelines with examples; measure agreement between labelers; review disagreements.
- Synthetic data generated by LLMs can bootstrap eval sets and training data, especially for rare cases. Check it for diversity and correctness, and don't let it replace real samples — models trained mostly on synthetic data can drift.
- Deduplicate and clean: duplicated or contaminated data inflates eval scores.
- Version datasets and record lineage so results are reproducible.
- Privacy: minimize personal data, get consent, anonymize or pseudonymize, and respect retention and deletion rules. Check licenses and terms for any data you use.
Keep a strict separation between data used to build the system and data used to evaluate it.

## Multimodal AI: vision, speech and documents
tags: multimodal, vision, speech recognition, TTS, OCR, document AI
ask: How do you extract data from scanned forms? | What makes voice assistants feel natural?
Modern models handle images, audio and documents alongside text.

Vision: models can describe images, read charts and screenshots, and extract text. For forms and nameplates, combining OCR (fast and precise on characters) with an LLM (understanding layout and meaning) works well — BlackLab's Snap-to-Work-Order does exactly this with on-device OCR. Validate extracted values such as model and serial numbers with patterns.

Speech: speech-to-text (Whisper, platform recognizers, on-device models) turns audio into text; text-to-speech produces voices. Voice assistant quality depends on latency (stream recognition and responses), endpointing (knowing when the user finished), barge-in (letting users interrupt), and writing replies for the ear: short sentences, no symbols, numbers spoken naturally. In the car, keep answers brief and let the user ask for more.

Documents: PDFs mix text layers, scans, tables and images. Extract structure (headings, tables) rather than flat text, and keep page references for citations.

## On-device AI
tags: on-device AI, edge AI, Gemini Nano, AICore, quantization, NPU, privacy
ask: What can Gemini Nano do well? | Why run AI on the phone instead of the cloud?
On-device AI runs models locally on the phone's NPU, GPU or CPU. Benefits: privacy (data never leaves the device), offline operation, low and predictable latency, and no per-call cost. Limits: smaller models with less knowledge and weaker reasoning, shorter context windows, memory and battery constraints, and model availability that varies by device.

On Android, Gemini Nano runs inside the system AICore service on supported devices, including recent Pixels, and is reached through ML Kit's GenAI APIs (summarization, proofreading, rewriting, image description and a general Prompt API). The model is downloaded and updated by the system, so it adds nothing to your app's size.

Techniques that make local models practical: quantization (8-bit or 4-bit weights), distillation from larger models, LoRA adapters for specific tasks, and careful prompt design with short contexts. A hybrid architecture is common: try on-device first, fall back to rules or (with consent) a cloud model for harder requests. BlackLab follows this pattern — rules, then Gemini Nano, then local search — so it works with no signal at all.

## Responsible AI and governance
tags: responsible AI, ethics, bias, governance, NIST AI RMF, EU AI Act, transparency
ask: What does responsible AI look like in practice? | What is the EU AI Act?
Responsible AI turns values into engineering practices:
- Fairness: test performance across user groups; look for biased outputs; avoid using AI for high-stakes decisions about people without strong safeguards.
- Transparency: tell users when they're interacting with AI and what it can't do; show sources; document models and data (model cards).
- Human oversight: keep people in the loop for consequential decisions, with easy override and appeal.
- Privacy and security: minimize data, protect it, and defend against misuse.
- Reliability: evals, monitoring and incident response for AI behavior.

Frameworks: the NIST AI Risk Management Framework organizes the work into Govern, Map, Measure and Manage. ISO/IEC 42001 defines an AI management system. The EU AI Act takes a risk-based approach: banned practices (such as social scoring), strict requirements for high-risk systems (employment, critical infrastructure, education and others), transparency duties for systems like chatbots and generated content, and obligations for general-purpose model providers. Check current guidance for your jurisdiction; rules continue to evolve.

## Serving and inference
tags: inference, serving, vLLM, KV cache, batching, quantization, throughput, GPU
ask: How does continuous batching work? | What limits inference speed?
Serving your own models means managing GPUs efficiently.
- Generation is memory-bandwidth bound: each new token reads all model weights, so smaller or quantized weights mean faster tokens.
- The KV cache stores attention keys and values for previous tokens so they aren't recomputed; it grows with context length and concurrent requests, and often limits how many users a GPU can serve.
- Continuous batching (in servers like vLLM, TGI and TensorRT-LLM) adds and removes requests from a running batch every step, greatly improving throughput. PagedAttention manages KV-cache memory in pages to reduce waste.
- Quantization (8-bit, 4-bit; formats like GPTQ, AWQ and GGUF) cuts memory and speeds inference with small quality loss — evaluate it on your tasks.
- Speculative decoding uses a small draft model to propose tokens that the large model verifies in parallel.
- Prefix caching reuses computation for shared prompt prefixes.
Size roughly: weights need parameters times bytes per parameter (a 7-billion-parameter model in 16-bit is about 14 GB), plus KV cache and overhead. Measure tokens per second, time to first token and cost per million tokens.

## Designing conversational assistants
tags: conversation design, chatbot, persona, system prompt, dialogue, voice assistant
ask: How do you give an assistant a personality without it getting annoying? | How should an assistant handle ambiguity?
Great assistants feel helpful, consistent and honest.
- Persona: define voice, tone and boundaries in the system prompt (warm and concise, never sarcastic about the user). Keep personality light; usefulness comes first.
- Turn-taking: answer the question first, then add context; end with one useful next step or question rather than a list of options.
- Ambiguity: make a reasonable assumption and state it for low-stakes requests; ask one focused clarifying question for high-stakes or truly ambiguous ones.
- Memory: carry relevant facts across turns, summarize long histories, and let users see and correct what's remembered.
- Errors: own mistakes plainly, correct them, and don't over-apologize.
- Boundaries: be clear about what the assistant can't do and when to involve a professional.
- Voice: shorter replies, no visual formatting, numbers and dates said naturally, confirmations before actions, and easy interruption.
- Proactivity: offer help at the right moment (a morning briefing) without nagging.
BlackLab's conversations follow this design: ground answers in knowledge notes, keep replies conversational, and suggest a natural follow-up.

## Testing AI features in software
tags: testing, AI testing, non-determinism, mocks, snapshot tests, property-based tests
ask: How do you unit test code that calls an LLM? | How do you stop flaky AI tests?
Separate deterministic code from model behavior.
- Unit-test the code around the model — prompt assembly, parsers, tool handlers, retries, fallbacks — with the model mocked or faked using recorded responses.
- Contract tests check that parsers handle real model outputs, including malformed ones: extra prose around JSON, missing fields, truncated responses.
- Eval suites, not unit tests, judge model quality; run them on prompt or model changes and nightly, and gate on aggregate thresholds rather than exact strings.
- Property-based checks assert invariants that should always hold: valid JSON, no personally identifiable information leaked, citations point to provided sources, length limits respected.
- Use temperature 0 and fixed seeds where supported for more repeatable integration tests, but don't depend on exact wording.
- Test failure paths: timeouts, rate limits, refusals, empty retrieval and an unavailable on-device model.
- Red-team tests: a standing set of injection and jailbreak prompts that must keep failing.

## AI system reference architecture
tags: architecture, AI architecture, gateway, orchestration, retrieval, guardrails, blueprint
ask: What does a production AI stack look like? | Where do guardrails go?
A typical production LLM application has these layers:
- Interface: chat, voice, API or embedded features, with streaming and feedback capture.
- Gateway: authentication, rate limiting, routing across models and providers, caching, cost tracking and policy enforcement.
- Orchestration: prompt templates, workflow or agent logic, tool calling and state management.
- Knowledge: ingestion pipelines, indexes (vector and keyword), rerankers and access control so users only retrieve what they're allowed to see.
- Tools: well-defined APIs, increasingly exposed through MCP, with least privilege and confirmation for side effects.
- Guardrails: input checks (injection, abuse, personal data), output checks (policy, format, grounding) and human-review queues.
- Evaluation and observability: offline eval suites, online monitoring, tracing and feedback loops.
- Data governance: logging policies, retention, consent and audit.

Start small — one model call with good prompts and logging — and add layers as needs appear. Keep components swappable; models and tools change quickly.

## Estimating AI costs
tags: cost estimation, tokens, pricing, budget, ROI
ask: How do I estimate monthly LLM costs? | How do I justify AI spend to leadership?
A quick worksheet:
- Requests per month: users × tasks per user × calls per task (agents may make 5–50 calls per task).
- Tokens per call: input (system prompt + context + history + user input) and output, measured on real samples. A rough rule for English is about 750 words per 1,000 tokens.
- Cost per call: input tokens × input price + output tokens × output price, adjusted for cached-prompt discounts and batch discounts.
- Add embedding, vector storage, reranking and infrastructure costs, plus about 20–30% headroom for retries and growth.

Then compare against value: hours saved × loaded labor rate, errors avoided, faster response times, or revenue enabled. For facility operations, examples are faster work-order triage, automatic report drafting, and fewer missed preventive-maintenance tasks.

Control spend with budgets and alerts per feature, cheaper models where evals allow, caching, and output limits. Prices fall quickly, so revisit estimates every few months, and always check the provider's current price list rather than remembered numbers.
