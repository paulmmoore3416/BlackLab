---
id: ai-development
title: AI Development
kind: domain
summary: How models are built — machine learning fundamentals, neural networks, transformers, training and fine-tuning, MLOps and applied AI for operations.
---

## Machine learning in one page
tags: machine learning, supervised, unsupervised, reinforcement learning, basics
ask: What problems is machine learning good at? | Supervised or unsupervised — how do I choose?
Machine learning builds models that learn patterns from data instead of following hand-written rules.
- Supervised learning learns from labeled examples: classification predicts categories (is this work order urgent?), regression predicts numbers (energy use tomorrow).
- Unsupervised learning finds structure without labels: clustering (group similar buildings by usage), dimensionality reduction, anomaly detection.
- Self-supervised learning creates labels from the data itself (predict the next word) and is how large language models are pretrained.
- Reinforcement learning learns by trial and error to maximize reward (robotics, games, and fine-tuning language models from feedback).

The workflow: define the problem and success metric, gather and clean data, split into training, validation and test sets, choose a baseline, train, evaluate, iterate, deploy, then monitor for drift. Always compare against a simple baseline — "predict the average" or a rule of thumb. If ML can't beat it convincingly, you don't need ML.

## Bias, variance and overfitting
tags: overfitting, underfitting, bias-variance, regularization, generalization
ask: How do I know if my model is overfitting? | What does regularization do?
A model's error comes from bias (too simple to capture the pattern — underfitting), variance (too sensitive to the particular training data — overfitting) and irreducible noise.

Signs: underfitting shows poor performance on both training and validation data; overfitting shows excellent training performance but worse validation performance. Learning curves (error versus training-set size) make this visible.

Fighting overfitting:
- More and more diverse data, and data augmentation.
- Regularization: L2 (weight decay) shrinks weights; L1 drives some to zero (feature selection); dropout randomly disables neurons during training.
- Simpler models or fewer features.
- Early stopping when validation loss stops improving.
- Cross-validation for reliable estimates.
Fighting underfitting: more expressive models, better features, longer training, less regularization. Deep learning adds a twist — very large models can generalize well despite enormous capacity (double descent) — but validation data remains the judge.

## Data preparation and feature engineering
tags: feature engineering, data cleaning, data leakage, normalization, encoding
ask: What is data leakage? | How do you handle missing values?
Most ML effort goes into data.
- Cleaning: fix types, remove duplicates, handle outliers deliberately, and standardize units (Fahrenheit versus Celsius, kWh versus MWh).
- Missing values: drop, impute (median, most frequent, model-based), or add an "is missing" indicator — missingness itself can be informative.
- Encoding: one-hot for low-cardinality categories, target or embedding encodings for high-cardinality ones.
- Scaling: standardize or normalize numeric features for distance-based and gradient-based models (not needed for tree models).
- Feature engineering: domain knowledge beats algorithms. For equipment data: runtime hours, cycles per day, time since last service, temperature deltas, rolling averages and day-of-week or season.

Data leakage is when information unavailable at prediction time sneaks into training — future data, target-derived features, or preprocessing fit on the whole dataset before splitting. It produces great offline scores and bad real-world results. Split first, fit transforms on training data only, and respect time order for time-series problems.

## Classic machine learning algorithms
tags: algorithms, linear regression, logistic regression, decision trees, random forest, gradient boosting, XGBoost, k-means
ask: Why is gradient boosting so popular? | Which algorithm should I try first?
For tabular data, classic algorithms often beat deep learning.
- Linear regression: predicts numbers as a weighted sum of features; interpretable and a strong baseline.
- Logistic regression: classification with probabilities; fast and interpretable.
- Decision trees: if-then splits; intuitive but prone to overfitting alone.
- Random forests: many trees on random subsets of rows and features, averaged; robust with little tuning.
- Gradient boosting (XGBoost, LightGBM, CatBoost): trees built sequentially, each correcting previous errors; usually the top performer on structured data.
- k-nearest neighbors: predicts from similar examples; simple but slow at scale.
- Support vector machines: strong on smaller, high-dimensional datasets.
- k-means: clusters data into k groups around centroids. DBSCAN finds arbitrarily shaped clusters and flags noise.
- Naive Bayes: fast text classification baseline.

Typical order for a tabular problem: a simple baseline, then logistic or linear regression, then gradient boosting with sensible defaults, then tune. scikit-learn implements most of these with a consistent fit and predict API.

## Evaluation metrics
tags: metrics, accuracy, precision, recall, F1, ROC-AUC, RMSE, confusion matrix, calibration
ask: Precision or recall — which matters more? | Why is accuracy misleading?
Choose metrics that reflect the real cost of mistakes.

Classification: the confusion matrix counts true and false positives and negatives.
- Accuracy misleads on imbalanced data: if 1% of equipment fails, predicting "never fails" is 99% accurate and useless.
- Precision: of the items flagged, how many were right? Matters when false alarms are costly.
- Recall: of the real positives, how many did we catch? Matters when misses are costly — like a failing chiller.
- F1 balances the two. The threshold you choose moves the trade-off.
- ROC-AUC measures ranking quality across thresholds; PR-AUC is more informative for rare positives.
- Calibration: do predicted probabilities match reality? (Of things predicted at 70%, do about 70% happen?)

Regression: MAE (average absolute error, robust and in original units), RMSE (penalizes large errors), MAPE (percentage error; unstable near zero), and R² (variance explained).

Always report metrics on a held-out test set touched only once, and break results down by segment (building, equipment type) to find hidden weaknesses.

## Cross-validation and hyperparameter tuning
tags: cross-validation, hyperparameters, tuning, grid search, Bayesian optimization, validation
ask: How many folds should I use? | Grid search or random search?
Hyperparameters are settings you choose rather than learn: tree depth, learning rate, regularization strength.

Cross-validation gives reliable performance estimates: k-fold (commonly 5 or 10) trains on k−1 folds and validates on the remaining one, rotating through all folds. Use stratified folds for imbalanced classes, group folds when rows share an entity (all readings from one building in the same fold), and time-series splits (train on the past, validate on the future) for temporal data.

Search strategies:
- Grid search tries every combination; it gets expensive quickly.
- Random search samples combinations and usually finds good settings faster.
- Bayesian optimization (Optuna, Hyperopt) models which settings look promising and focuses there.
- Early-stopping-based methods such as Hyperband drop bad candidates quickly.

Keep a final test set completely out of tuning; otherwise you overfit to your validation process. Record every experiment so results are reproducible.

## Neural network fundamentals
tags: neural networks, backpropagation, gradient descent, activation functions, optimizers, Adam, learning rate
ask: How does backpropagation work? | Why does the learning rate matter so much?
A neural network stacks layers of simple units. Each computes a weighted sum of its inputs plus a bias, then applies a nonlinear activation function (ReLU, GELU, sigmoid, tanh). Without nonlinearity, any stack of layers collapses into one linear function.

Training minimizes a loss function by adjusting weights:
- Forward pass computes predictions and the loss.
- Backpropagation uses the chain rule to compute the gradient of the loss with respect to every weight.
- Gradient descent nudges weights against the gradient, scaled by the learning rate.

Mini-batch stochastic gradient descent uses small batches for efficiency and helpful noise. Adam and AdamW adapt per-parameter step sizes and are the default for most deep learning. The learning rate is the most important hyperparameter: too high diverges, too low crawls. Schedules such as warmup followed by cosine decay help.

Stabilizers: normalization layers (batch norm, layer norm), residual connections that let gradients flow through deep networks, careful initialization, and gradient clipping.

## Loss functions
tags: loss function, cross-entropy, mean squared error, objective, training
ask: Which loss should I use for classification? | What is cross-entropy?
The loss function defines what "good" means during training.
- Mean squared error (MSE) for regression; penalizes large errors heavily. Mean absolute error and Huber loss are more robust to outliers.
- Cross-entropy for classification: binary cross-entropy for yes/no, categorical cross-entropy (with softmax) for multiple classes. It strongly penalizes confident wrong predictions.
- Language models use cross-entropy on next-token prediction; perplexity is its exponential, a measure of how "surprised" the model is by text.
- Contrastive losses (InfoNCE, triplet) pull similar items together and push different ones apart; they train embedding models.
- Focal loss and class weights help with heavy class imbalance.
- Ranking losses train search and recommendation models.

The loss should match what you care about, but sometimes it's only a proxy (you optimize cross-entropy but report F1). Watch training and validation loss together: diverging curves signal overfitting.

## Convolutional neural networks
tags: CNN, convolution, computer vision, image classification, ResNet
ask: Why do CNNs work so well on images? | What is transfer learning?
Convolutional neural networks process grid data such as images. Convolution layers slide small learned filters across the image, detecting local patterns — edges, then textures, then parts, then objects — in deeper layers. Weight sharing makes them efficient and translation-tolerant; pooling or strides reduce spatial size.

Landmark architectures: LeNet, AlexNet (which kicked off the deep learning wave in 2012), VGG, ResNet (residual connections enabled very deep networks), EfficientNet and MobileNet (efficient enough for phones). Vision Transformers now rival or beat CNNs at scale, and hybrids are common.

Transfer learning is the practical superpower: start from a model pretrained on a large dataset and fine-tune it on your smaller one. A few hundred labeled photos of equipment conditions can produce a useful classifier this way. Augmentation (flips, crops, color and brightness changes) multiplies limited data. On phones, compact models run in real time — CameraX plus ML Kit or LiteRT (formerly TensorFlow Lite).

## Recurrent networks and sequence models
tags: RNN, LSTM, GRU, sequence models, time series, history
ask: Why did transformers replace RNNs? | Are LSTMs still useful?
Recurrent neural networks process sequences one step at a time, carrying a hidden state forward, which suits text, speech and sensor streams. Plain RNNs struggle with long-range dependencies because gradients vanish or explode over many steps. LSTMs and GRUs add gates that control what to remember and forget, which made them the standard for language and speech before 2018.

Transformers replaced them for most tasks because attention connects any two positions directly, and training parallelizes across the whole sequence instead of stepping through it. That scalability made large language models possible.

RNN-style ideas are still alive: LSTMs and GRUs remain solid for small time-series problems and on tiny devices, and newer state-space models (Mamba and relatives) and linear-attention hybrids revisit recurrence to handle very long sequences efficiently. For a sensor-forecasting project, try gradient boosting with lag features or a simple LSTM before reaching for a transformer.

## Transformers and attention
tags: transformer, attention, self-attention, multi-head attention, positional encoding, architecture
ask: Explain attention like I'm a facilities guy | What's the difference between encoder and decoder models?
The transformer (from "Attention Is All You Need," 2017) is the architecture behind modern language models.

Self-attention lets every token look at every other token and decide how much each matters. Each token produces a query, a key and a value vector; attention weights come from query-key similarity (scaled dot products passed through softmax), and the output is a weighted mix of values. In "the chiller tripped because it overheated," attention helps "it" connect to "chiller."

Multi-head attention runs several attention patterns in parallel, each free to capture different relationships. Each transformer block combines attention with a feed-forward network, residual connections and layer normalization; models stack dozens of blocks. Since attention has no built-in order, positional encodings (rotary embeddings, or RoPE, are common now) inject position.

Variants: encoder-only models (BERT) are good for understanding and embeddings; decoder-only models (GPT, Claude, Gemini, Llama) generate text; encoder-decoder models (T5) map inputs to outputs. Attention cost grows with the square of sequence length, which drives research into efficient attention for long contexts.

## How large language models are pretrained
tags: pretraining, LLM training, scaling laws, compute, datasets, tokens
ask: What are scaling laws? | How much data goes into an LLM?
Pretraining teaches a model language and world knowledge by predicting the next token over trillions of tokens of text and code, filtered for quality and deduplicated. The model learns grammar, facts, reasoning patterns and coding skills as side effects of getting better at prediction.

Scaling laws (Kaplan et al., then DeepMind's Chinchilla work) found that loss improves predictably as you increase parameters, data and compute together. Chinchilla showed many models were undertrained for their size; a rough guideline was about 20 training tokens per parameter for compute-optimal training, though modern models often train far beyond that so they're cheaper to run.

Pretraining runs use thousands of GPUs or TPUs for weeks, with distributed parallelism across data, model layers and tensors, mixed precision, and careful handling of loss spikes. The result is a base model: knowledgeable but not yet a helpful assistant — it continues text rather than following instructions. Post-training turns it into one. Data quality, mixture and deduplication matter as much as raw size.

## Post-training: SFT, RLHF and alignment
tags: post-training, SFT, RLHF, RLAIF, Constitutional AI, DPO, alignment, instruction tuning
ask: How does RLHF work? | What is Constitutional AI?
Post-training shapes a base model into a helpful, honest and harmless assistant.
- Supervised fine-tuning (SFT, instruction tuning): train on high-quality examples of instructions and ideal responses, including multi-turn conversations and tool use.
- Reinforcement learning from human feedback (RLHF): people compare pairs of responses; a reward model learns those preferences; the model is optimized (classically with PPO) to produce responses the reward model scores highly, with a penalty for drifting too far from the original.
- Direct Preference Optimization (DPO) and related methods learn directly from preference pairs without a separate reward model or RL loop — simpler and widely used.
- RLAIF and Constitutional AI (introduced by Anthropic) use AI feedback guided by a written set of principles to critique and revise responses, reducing reliance on human labels for harmlessness.
- Reinforcement learning on verifiable tasks (math, code with tests) has driven recent gains in reasoning models that "think" before answering.

Alignment work also includes red-teaming, safety evaluations and interpretability research into what's happening inside models.

## Tokenization
tags: tokenization, tokens, BPE, SentencePiece, vocabulary
ask: Why do models struggle to count letters? | Why do some languages cost more tokens?
Models read tokens, not characters or words. Byte-pair encoding (BPE) builds a vocabulary by repeatedly merging the most frequent pairs of symbols, so common words become single tokens and rare words split into pieces ("unbelievably" might become "un", "believ", "ably"). SentencePiece and similar tools implement this directly on raw text, including spaces. Vocabularies typically hold roughly 32,000 to over 200,000 tokens.

Consequences you'll notice:
- Cost and context limits are counted in tokens; English averages around four characters per token, while many other languages, code and numbers use more.
- Character-level tasks — counting letters, reversing strings, exact spelling — are hard because the model never sees individual letters directly.
- Arithmetic can suffer when numbers split into odd chunks.
- Leading spaces and capitalization create different tokens, so formatting changes can subtly change behavior.
Use the provider's tokenizer or token-counting API to measure prompts precisely.

## Fine-tuning in practice
tags: fine-tuning, LoRA, QLoRA, PEFT, Hugging Face, training data, adapters
ask: How do I fine-tune a small model on my data? | How many examples do I need?
A practical recipe for fine-tuning an open-weight model:
- Define the task and build an eval set first, then measure the base model with good prompting.
- Prepare data in a chat or instruction format: hundreds to a few thousand high-quality, diverse examples. Quality beats quantity; remove duplicates and contradictions.
- Use parameter-efficient fine-tuning. LoRA freezes the base weights and trains small low-rank adapter matrices; QLoRA does this on a 4-bit-quantized base model, letting 7–8-billion-parameter models fine-tune on a single consumer or cloud GPU.
- Typical starting points: a learning rate around 1e-4 to 2e-4 for LoRA, 1–3 epochs, rank 8–64, and watching validation loss to avoid overfitting.
- Tools: Hugging Face Transformers, PEFT and TRL; Unsloth for speed; Axolotl for config-driven runs; or provider-hosted fine-tuning APIs.
- Evaluate against the baseline on your eval set, and check you haven't damaged general abilities (catastrophic forgetting).
Merge the adapter or serve it alongside the base model, and keep data, config and results versioned.

## Embedding models and contrastive learning
tags: embeddings, contrastive learning, sentence transformers, semantic similarity, training embeddings
ask: How are embedding models trained? | Should I fine-tune embeddings for my domain?
Embedding models turn text, images or other data into vectors where distance reflects meaning. They're usually transformer encoders trained with contrastive learning: given an anchor (a question), pull its positive match (the right passage) closer and push negatives (wrong passages) apart. Hard negatives — passages that look similar but are wrong — teach the finest distinctions.

Multimodal models such as CLIP learn a shared space for images and text from captioned images, enabling text-to-image search.

Choosing and adapting:
- Start with a strong general embedding model and benchmark it on your own queries and documents (recall at k).
- Domain jargon (equipment model numbers, facility codes, internal acronyms) may justify fine-tuning on pairs mined from your data — question and answer, title and body, or search query and clicked result.
- Consider dimension and speed: smaller vectors are cheaper to store and search; some models support truncating dimensions (Matryoshka embeddings).
Embeddings complement keyword search rather than replacing it.

## Diffusion models and image generation
tags: diffusion, image generation, Stable Diffusion, generative AI, latent diffusion
ask: How do diffusion models generate images? | What is classifier-free guidance?
Diffusion models learn to reverse a noising process. During training, images are gradually corrupted with noise, and a neural network learns to predict and remove that noise at each step. To generate, start from pure noise and denoise step by step into an image.

Latent diffusion (Stable Diffusion and successors) works in a compressed latent space from an autoencoder, making generation far cheaper than working on pixels. Text conditioning comes from a text encoder, and cross-attention steers the image toward the prompt. Classifier-free guidance strengthens prompt adherence by contrasting conditioned and unconditioned predictions — higher guidance means more literal and less varied results.

Extensions: image-to-image and inpainting edit existing pictures; ControlNet adds structural guides like edges or poses; LoRA adapters teach styles or subjects from a few images. Newer architectures use transformers as the denoiser and flow-matching training, and the same ideas extend to video and audio. Practical concerns include licensing, consent for likenesses, and provenance labeling of generated media.

## The Python ML toolkit
tags: Python, NumPy, pandas, scikit-learn, PyTorch, Hugging Face, Jupyter, tools
ask: What should I learn first for ML in Python? | PyTorch or TensorFlow?
The standard stack:
- NumPy: fast n-dimensional arrays and vectorized math — the foundation of everything else.
- pandas (or Polars for speed): tabular data loading, cleaning, grouping, joining and time series.
- Matplotlib and Seaborn or Plotly: visualization; always plot your data before modeling.
- scikit-learn: classic ML with a consistent API — pipelines, preprocessing, models, metrics and cross-validation.
- XGBoost, LightGBM, CatBoost: gradient boosting.
- PyTorch: the dominant deep learning framework in research and increasingly in production. TensorFlow and Keras remain in many deployments, and JAX is popular for large-scale research.
- Hugging Face: Transformers (pretrained models), Datasets, Tokenizers, PEFT and the Hub for sharing models.
- Jupyter notebooks or VS Code notebooks for exploration; move stable code into modules with tests.
Learn in this order: Python and NumPy, then pandas and plotting, then scikit-learn end to end, then PyTorch fundamentals, then Hugging Face.

## The PyTorch training loop
tags: PyTorch, training loop, DataLoader, optimizer, autograd, GPU
ask: Walk me through a PyTorch training loop | How do I use a GPU in PyTorch?
A minimal PyTorch training loop:
- Data: a Dataset returns individual samples; a DataLoader batches, shuffles and loads them in parallel.
- Model: subclass nn.Module, define layers in __init__ and the computation in forward.
- Loss and optimizer: for example nn.CrossEntropyLoss and torch.optim.AdamW(model.parameters(), lr=3e-4).
- Each step: move the batch to the device, zero the gradients, run the forward pass, compute the loss, call loss.backward() so autograd computes gradients, then optimizer.step() to update the weights.
- Each epoch: switch to model.eval() and wrap validation in torch.no_grad(), compute metrics, then return to model.train().

Use device = "cuda" if available ("mps" on Apple silicon) and move both the model and the tensors there. Speedups: mixed precision (torch.autocast with bfloat16), torch.compile, larger batches with gradient accumulation, and pinned memory in the DataLoader. Save checkpoints of the model and optimizer state, set random seeds for reproducibility, and log metrics to TensorBoard or Weights & Biases. Libraries such as PyTorch Lightning and Hugging Face Accelerate remove boilerplate.

## GPUs, memory and distributed training
tags: GPU, CUDA, VRAM, mixed precision, distributed training, FSDP, DeepSpeed
ask: How much GPU memory do I need to fine-tune a model? | What is FSDP?
GPUs accelerate the matrix math of deep learning; NVIDIA's CUDA ecosystem dominates, with AMD ROCm, Google TPUs and Apple silicon as alternatives.

Memory math for a model with P parameters:
- Inference in 16-bit: about 2P bytes for weights (a 7-billion-parameter model needs about 14 GB), plus KV cache.
- Full fine-tuning with AdamW in mixed precision: roughly 16P bytes or more for weights, gradients and optimizer states — over 100 GB for a 7B model before activations.
- QLoRA: 4-bit base weights (about 0.5P bytes) plus small adapters — feasible on a 16–24 GB GPU.

Techniques: mixed precision (bf16 or fp16), gradient checkpointing (recompute activations to save memory), gradient accumulation (simulate large batches), and 8-bit optimizers.

Distributed training: data parallelism (DDP) copies the model to each GPU and splits batches; FSDP and DeepSpeed ZeRO shard parameters, gradients and optimizer states across GPUs so larger models fit; tensor and pipeline parallelism split the model itself for the largest runs. Cloud GPU rental works well for occasional fine-tuning.

## MLOps
tags: MLOps, experiment tracking, model registry, MLflow, Weights and Biases, DVC, pipelines, drift
ask: What does a minimal MLOps setup look like? | How do you detect model drift?
MLOps applies DevOps discipline to machine learning, where the system depends on code, data and model together.

Core practices:
- Experiment tracking (MLflow, Weights & Biases): log parameters, metrics, code version and artifacts for every run.
- Data and model versioning (DVC, lakeFS, a model registry) so any model can be traced to the exact data and code that produced it.
- Pipelines (Kubeflow, Airflow, Prefect, Dagster, SageMaker or Vertex AI Pipelines) automate data prep, training, evaluation and deployment.
- Validation gates: a new model must beat the current one on held-out data and pass fairness and sanity checks before promotion.
- Serving: batch scoring or online APIs, with versioned endpoints and canary releases.
- Monitoring: data drift (input distributions change), concept drift (the relationship between inputs and outcomes changes), prediction quality once labels arrive, and latency and cost.
- Retraining triggers on a schedule or when drift is detected.

Minimal setup for a small team: git, a tracked notebook-to-script workflow, MLflow, a scheduled retraining job, and a dashboard of model metrics.

## Deploying models to devices and production
tags: deployment, ONNX, LiteRT, TensorFlow Lite, quantization, edge, model serving
ask: How do I run my model on Android? | What does quantization cost in accuracy?
Getting models out of notebooks:
- Online serving: wrap the model in an API (FastAPI, TorchServe, NVIDIA Triton, BentoML), containerize it, autoscale, and monitor latency.
- Batch scoring: run predictions on a schedule and write them to a database — simpler and cheaper when real time isn't needed, such as nightly equipment risk scores.
- Interchange formats: ONNX lets models trained in one framework run in optimized runtimes across platforms.
- Mobile and edge: LiteRT (formerly TensorFlow Lite), ONNX Runtime Mobile, ExecuTorch for PyTorch, Core ML on Apple devices, and ML Kit for ready-made on-device features on Android.

Optimization: post-training quantization to 8-bit usually costs little accuracy and cuts size by about four times; 4-bit needs more care. Pruning removes unimportant weights; distillation trains a small model to mimic a large one. Always re-evaluate after optimizing, and test on real target hardware, since accelerator support varies by device.

## Time-series forecasting
tags: time series, forecasting, seasonality, ARIMA, Prophet, energy, demand
ask: How would you forecast building energy use? | What are lag features?
Time series have order, trend and seasonality, and they need special handling.
- Decompose first: trend, seasonality (daily, weekly, yearly), and residuals. Plot everything.
- Baselines: last value (naive), the same time last week (seasonal naive), and moving averages. They're surprisingly hard to beat.
- Statistical models: exponential smoothing (ETS, Holt-Winters) and ARIMA or SARIMA; Prophet handles multiple seasonalities and holidays conveniently.
- Machine learning: gradient boosting with lag features (the value one hour, one day and one week ago), rolling statistics, calendar features (hour, weekday, holiday) and external drivers such as outdoor temperature or occupancy. Often the strongest practical approach.
- Deep learning and foundation models for time series exist and help at scale, but start simple.

Validate with walk-forward (rolling-origin) splits — never shuffle time. Report errors at the horizon that matters (next-day peak demand). For facilities, weather-normalized energy forecasting helps catch waste, plan demand charges and verify savings from retrofits.

## Anomaly detection and predictive maintenance
tags: anomaly detection, predictive maintenance, sensors, isolation forest, HVAC, condition monitoring
ask: How do I start predictive maintenance with BAS data? | What algorithms detect anomalies?
Predictive maintenance uses equipment data to fix things before they fail — a natural fit for facility operations.

Data sources: building automation system (BAS) trends (supply and return temperatures, pressures, valve and damper positions, run status), vibration, motor current, runtime hours, alarm logs and work-order history (your failure labels).

Approaches, from simple to advanced:
- Rules and thresholds from engineering knowledge (fault detection and diagnostics rules, such as simultaneous heating and cooling).
- Statistical control charts and z-scores on rolling baselines.
- Unsupervised anomaly detection: isolation forests, one-class models, or autoencoders that flag unusual combinations of readings.
- Supervised failure prediction when you have enough labeled failures, using features such as trends, time since service and cycling rates.
- Remaining-useful-life models for critical assets.

Practical tips: start with one critical asset class (chillers, air handlers, pumps), get the data clean and time-aligned, involve technicians to label events, measure precision so alarms stay trustworthy, and feed alerts into work orders. Value comes from fewer emergency calls and longer asset life, not from a fancy model.

## Reinforcement learning basics
tags: reinforcement learning, RL, reward, policy, Q-learning, agents, control
ask: Where is reinforcement learning actually used? | What's the explore-exploit trade-off?
Reinforcement learning (RL) trains an agent to act in an environment to maximize cumulative reward. Key pieces: states, actions, rewards, a policy (what to do in each state), and value functions (how good a state or action is in the long run).

Classic algorithms: Q-learning and Deep Q-Networks learn action values; policy-gradient methods (REINFORCE, PPO) optimize the policy directly; actor-critic methods combine both. Model-based RL learns a model of the environment to plan.

The exploration-exploitation trade-off is central: try new actions to learn, or use what already works? Multi-armed bandits are the simplest version, used in A/B testing and recommendations.

Real uses: game playing, robotics, recommendations, chip design, data-center cooling optimization, HVAC control research, and — most visibly — fine-tuning language models from human or AI feedback and from verifiable rewards. RL is powerful but sample-hungry and finicky; reward hacking (exploiting a poorly specified reward) is a constant risk, so simulate and constrain heavily before letting an RL policy control physical equipment.

## Interpreting models
tags: interpretability, explainability, SHAP, LIME, feature importance, transparency
ask: How do I explain a model's prediction to a manager? | What is SHAP?
Interpretability builds trust, helps debug, and is sometimes required by regulation.
- Inherently interpretable models: linear models (coefficients), small decision trees and rule lists. Prefer them when they perform well enough.
- Global feature importance: permutation importance shuffles a feature and measures how much performance drops; it's more reliable than built-in tree importances.
- SHAP values attribute each prediction to features using game theory, giving consistent local explanations ("this pump is flagged mainly because vibration rose 40% and it's 900 hours past service") and global summaries.
- LIME fits a simple local model around one prediction.
- Partial dependence and ICE plots show how predictions change as one feature varies.
- Counterfactuals answer "what would need to change for a different outcome?"
- For deep and language models, attention maps are unreliable explanations; mechanistic interpretability research studies internal features and circuits.
Explanations describe the model, not necessarily the world — correlations aren't causes.

## Computer vision tasks
tags: computer vision, object detection, segmentation, YOLO, OCR, image classification
ask: What's the difference between detection and segmentation? | How could vision help in facilities?
Common vision tasks:
- Image classification: one label per image (is this filter clean or dirty?).
- Object detection: boxes and labels for each object (find every fire extinguisher in a photo). YOLO-family models are fast and popular; DETR-style transformers are another approach.
- Segmentation: semantic segmentation labels every pixel by class; instance segmentation separates individual objects; the Segment Anything models can segment almost anything from a prompt.
- OCR and document understanding: read nameplates, meters and forms.
- Pose estimation, tracking in video, depth estimation and anomaly detection on surfaces such as cracks, corrosion and leaks.

Facilities ideas: reading analog gauges and meters from photos, detecting roof or pavement damage in drone imagery, checking for blocked exits, counting occupancy for space planning (with privacy safeguards), and inventorying assets from walkthrough photos. Start with pretrained models and modest labeled sets, use augmentation, and evaluate on photos from your own sites — lighting and angles vary more than you expect. Vision-capable language models can also handle many of these tasks from a prompt.

## Privacy-preserving machine learning
tags: privacy, differential privacy, federated learning, anonymization, data protection
ask: Can a model leak its training data? | What is federated learning?
Models can memorize and leak training data, especially rare records, so privacy needs design.
- Data minimization: collect and keep only what you need; strip identifiers early.
- Anonymization is harder than it looks — combinations like ZIP code, birth date and gender can re-identify people. Use aggregation, generalization and suppression thoughtfully.
- Differential privacy adds calibrated noise during training (for example DP-SGD) or to query results, mathematically limiting what can be learned about any one person, at some cost in accuracy.
- Federated learning trains across many devices or sites without centralizing raw data: devices compute updates locally and share only those updates. Keyboard suggestion models are a well-known example.
- Secure enclaves and encryption-based techniques protect data during computation.
- On-device inference keeps personal data on the device entirely — BlackLab's approach for its assistant.
Pair the technical measures with governance: access controls, audits, consent, and clear retention rules.

## A learning roadmap for AI development
tags: learning path, roadmap, math for ML, courses, study plan, career
ask: What math do I really need for ML? | Give me a 6-month AI study plan
A practical roadmap for a working professional:
- Foundations (month 1–2): Python fluency, NumPy and pandas, plotting, and Git. Math as needed — linear algebra (vectors, matrices, dot products), calculus (derivatives and the chain rule for gradients), and probability and statistics (distributions, expectation, Bayes' rule, hypothesis tests).
- Classic ML (month 2–3): scikit-learn end to end on real tabular data — ideally your own facilities data such as work orders, energy and equipment. Learn evaluation and validation deeply.
- Deep learning (month 3–4): PyTorch basics, CNNs with transfer learning, and how transformers and attention work. Resources: fast.ai, Andrej Karpathy's "Neural Networks: Zero to Hero," and the d2l.ai book.
- LLMs and AI engineering (month 4–6): prompting, RAG, tool use, evals; build and deploy a small project end to end.
- Ongoing: read papers selectively, follow a few trusted sources, and build in public on GitHub.

Learn by projects with real stakes: a PM-compliance dashboard, a work-order classifier, an energy anomaly detector. Teaching others what you learn locks it in. Certifications can help structure study, but a portfolio of working projects speaks louder.
