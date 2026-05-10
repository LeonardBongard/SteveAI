# Minecraft AI Research Survey (2023–2026)

**Purpose.** Map the post-2022 Minecraft-AI research landscape into thematic clusters so a team building a player-companion AI can decide where to invest. The survey is intended to be self-contained and citation-grounded; every named system has a venue / arxiv link. Direction-setting for the SteveAI codebase is kept strictly in **Appendix A** so the body of the survey stays reusable.

**Scope.** January 2023 – May 2026 in depth, with a brief Foundations preface for the 2022 work that everything else descends from. We do not attempt to cover the full pre-2022 MineRL / Project Malmo era; those are referenced only when needed to motivate a 2023+ design choice.

**Anchor surveys.** We are additive to, not redundant with: [A Survey on Large Language Model-Based Game Agents (Hu et al., ACM CSUR; arXiv 2404.02039)](https://arxiv.org/abs/2404.02039); the [git-disl/awesome-LLM-game-agent-papers](https://github.com/git-disl/awesome-LLM-game-agent-papers) and [Awesome-Minecraft-Agent](https://awesome.ecosyste.ms/lists/lizaijing/awesome-minecraft-agent) lists; and [Awesome-World-Models](https://github.com/leofan90/Awesome-World-Models). Hu et al. is the canonical taxonomy for LLM-based game agents; we reuse their memory/reasoning/perception-action axes in the cross-cutting section.

**A note on confidence.** Where a project ships only a blog post or a live demo with no peer-reviewed paper (e.g. Decart's Oasis at first release), we mark the citation as such rather than crediting it as published research. We do not fabricate arxiv IDs; if a famous system has no verifiable primary source, the survey says so.

---

## 0. Foundations (2022, brief)

These four works define the ground on which essentially every 2023+ Minecraft agent stands.

| Work | Year / Venue | One-line idea | Link |
|---|---|---|---|
| **Crafter** | ICLR 2022 | Procgen 2D Minecraft-like survival benchmark; the lightweight control for ablations. | [arXiv 2109.06780](https://arxiv.org/abs/2109.06780) |
| **VPT (Video PreTraining)** | NeurIPS 2022 | Inverse-dynamics model labels 70k hours of unlabeled YouTube Minecraft, trained behavior-cloning policy at the keyboard/mouse level; first AI to craft a diamond pickaxe (with fine-tuning). | [arXiv 2206.11795](https://arxiv.org/abs/2206.11795) · [OpenAI blog](https://openai.com/index/vpt/) |
| **MineDojo + MineCLIP** | NeurIPS 2022 (Outstanding Paper) | A 3,142-task Minecraft simulator + dataset of 730k YouTube videos / 2.2B transcript words / 7k wiki pages / 340k Reddit posts; MineCLIP is a CLIP-style video–text contrastive model used as a learned reward function. | [Project page](https://minedojo.org/) · [OpenReview](https://openreview.net/forum?id=rc8o_j8I8PX) · [GitHub](https://github.com/MineDojo/MineDojo) |
| **DreamerV3** | arXiv Jan 2023 → Nature 2025 | First model-based RL algorithm to collect diamond from scratch end-to-end without human data or curricula. | [arXiv 2301.04104](https://arxiv.org/abs/2301.04104) · [Nature 2025](https://www.nature.com/articles/s41586-025-08744-2) |

**Why these matter together.** Almost every cluster below borrows one of two backbones: VPT-style low-level keyboard/mouse policies (the "embodied" lineage), or MineDojo-style task suite + MineCLIP reward / grounding (the "semantic" lineage). Crafter exists for ablations cheap enough to run in minutes. DreamerV3 demonstrated that pure RL — not LLMs — could in principle solve the diamond task, which keeps the "do we need an LLM at all?" question alive.

---

## 1. LLM-Prompted Agents with Tool / Skill Libraries

**Framing.** A frozen LLM (typically GPT-4 class) does the planning; execution happens through a high-level scripting interface — usually a Mineflayer-style server-side API or a curated skill library — instead of raw mouse/keyboard. The LLM never sees pixels in the simplest version of this stack: the world is described back to it as text. SteveAI sits squarely in this cluster.

| Work | Year / Venue | Core idea | Link |
|---|---|---|---|
| **DEPS** | NeurIPS 2023 | Describe → Explain → Plan → Select. Adds a goal selector that ranks parallel candidate sub-goals by estimated steps to completion; first zero-shot multi-task agent to solve 70+ Minecraft tasks. | [arXiv 2302.01560](https://arxiv.org/abs/2302.01560) |
| **Voyager** | arXiv May 2023 | GPT-4 + automatic curriculum + ever-growing skill library of executable JS code + iterative prompting using environment feedback / execution errors / self-verification. 3.3× more unique items, 15.3× faster tech-tree progress vs. prior SOTA. | [arXiv 2305.16291](https://arxiv.org/abs/2305.16291) · [Project](https://voyager.minedojo.org/) · [GitHub](https://github.com/MineDojo/Voyager) |
| **GITM (Ghost in the Minecraft)** | arXiv May 2023 | Hierarchical decomposition (goals → sub-goals → actions → operations) over text-based knowledge and memory. +47.5% on ObtainDiamond; trained on a single 32-CPU node — 10,000× cheaper than VPT or DreamerV3. | [arXiv 2305.17144](https://arxiv.org/abs/2305.17144) · [GitHub](https://github.com/OpenGVLab/GITM) |
| **Plan4MC** | NeurIPS 2023 FMDM Workshop | RL-trained primitive skills + LLM-generated skill dependency graph + skill search algorithm; 40 evaluation tasks each requiring 2–30 chained skills. | [arXiv 2303.16563](https://arxiv.org/abs/2303.16563) · [GitHub](https://github.com/PKU-RL/Plan4MC) |
| **RL-GPT** | arXiv Feb 2024 | Two-tier hierarchy: a "slow" agent decides which sub-tasks should be code vs. which should be RL-trained, a "fast" agent writes the code. Reaches diamond inside a day. | [arXiv 2402.19299](https://arxiv.org/abs/2402.19299) |
| **Odyssey** | arXiv Jul 2024 | 40 primitive skills + 183 compositional skills, expressly aimed at moving past the ObtainDiamond fixation toward more diverse open-world play. | [arXiv 2407.15325](https://arxiv.org/abs/2407.15325) · [GitHub](https://github.com/zju-vipa/Odyssey) |
| **MindCraft** (open-source) | 2023–ongoing | Mineflayer + multi-LLM (OpenAI / Anthropic / Gemini / Groq / Ollama) bot framework. The closest practical analogue to SteveAI; ships configurable bot profiles and code-execution sandboxing. | [github.com/mindcraft-bots/mindcraft](https://github.com/mindcraft-bots/mindcraft) · [community fork](https://github.com/mindcraft-ce/mindcraft-ce) |

**What this cluster has shown.** Three things, robustly. First, *prompting alone scales surprisingly far*: Voyager's auto-curriculum + skill library produced lifelong-learning behavior with no fine-tuning whatsoever. Second, *text-only world descriptions are sufficient for tech-tree completion*: GITM showed you can hit competitive ObtainDiamond numbers with a CPU-only setup if the LLM is good enough, because the bottleneck is planning, not perception. Third, *the planner/executor split is robust*: every successful work in this cluster cleanly separates a slow LLM planner from a fast deterministic / RL executor.

**Where it stalls.** (a) **Cost & latency.** GPT-4-class agents need an LLM call per skill invocation; in real-time gameplay this either freezes the bot or burns API credits. (b) **Brittleness off-distribution.** Agents that ace tech-tree tasks (oak → planks → table → wooden pickaxe → stone → iron → diamond) often fail the moment a task isn't on the canonical Minecraft progression chain — Odyssey's existence is partial evidence that the field knows this. (c) **Vision blindness.** Pure-text agents miss anything not represented in the world-state JSON: where exactly *is* the iron ore the player is pointing at, what does this novel structure look like, who is the entity 12 blocks away. Hu et al.'s survey makes this perception-action gap explicit (arXiv 2404.02039).

**Open problems.** Self-hosted-LLM viability (Ollama-class models are now usable but not yet competitive with GPT-4 on long-horizon planning); skill-library transfer across worlds and patches; grounded references ("that tree", "near my house") that text representations cannot resolve.

---

## 2. Vision-Language-Action (VLA) Embodied Agents

**Framing.** Agents that consume pixel observations + language and emit actions, usually at the keyboard/mouse level. Almost all of these descend from VPT (for the action backbone) and/or MineCLIP (for the text-frame grounding), and most are produced by a small set of labs — primarily CraftJarvis (PKU + Tsinghua) and JiuTian-VL.

| Work | Year / Venue | Core idea | Link |
|---|---|---|---|
| **STEVE-1** | NeurIPS 2023 (Spotlight) | Adapt VPT to follow text/visual instructions in MineCLIP latent space using self-supervised behavioral cloning + hindsight relabeling — total compute cost: $60. | [arXiv 2306.00937](https://arxiv.org/abs/2306.00937) · [GitHub](https://github.com/Shalev-Lifshitz/STEVE-1) |
| **STEVE-Eye** | arXiv Oct 2023 | Equip LLM-based embodied agents with visual perception via a multimodal I/O module on top of pretrained LLMs. | [arXiv 2310.13255](https://arxiv.org/abs/2310.13255) |
| **STEVE (See and Think)** | ECCV 2024 | Three-component agent (vision perception + language reasoning + code action) with the STEVE-21K dataset (600+ vision pairs, 20K QA pairs, 200+ skill-code pairs). 1.5–2.5× speedup on tech tree / block search. | [arXiv 2311.15209](https://arxiv.org/abs/2311.15209) · [GitHub](https://github.com/rese1f/STEVE) |
| **STEVE-Audio** | arXiv Dec 2024 | Adds audio as a goal-conditioning modality; the line of work shows the embodied-agent stack is starting to absorb new sensors. | [arXiv 2412.00949](https://arxiv.org/abs/2412.00949) |
| **GROOT** | ICLR 2024 (Spotlight) | Train an instruction-following controller from gameplay videos using *reference videos* as the goal (no text annotations). 70% win rate over best generalist baseline on a Minecraft SkillForge benchmark. | [arXiv 2310.08235](https://arxiv.org/abs/2310.08235) · [GitHub](https://github.com/CraftJarvis/GROOT) |
| **GROOT-2** | arXiv Dec 2024 | Weakly supervised multi-modal instruction following — extends GROOT to text + image + reference-video instructions jointly. | [arXiv 2412.10410](https://arxiv.org/abs/2412.10410) |
| **JARVIS-1** | arXiv Nov 2023 | Memory-augmented multimodal LLM agent over 200+ Minecraft tasks; 5× more reliable than prior SOTA on diamond-pickaxe; capable of self-instructing and saving experience. | [arXiv 2311.05997](https://arxiv.org/abs/2311.05997) · [GitHub](https://github.com/CraftJarvis/JARVIS-1) |
| **OmniJARVIS** | NeurIPS 2024 | *Unified* vision-language-action tokenization: behavior trajectories are discretized into tokens and added to the LLM vocabulary, so reasoning and action live in one autoregressive stream. | [arXiv 2407.00114](https://arxiv.org/abs/2407.00114) |
| **JARVIS-VLA** | ACL 2025 Findings | Post-trains large VLMs with "Act from Visual Language Post-Training"; first VLA agents able to follow >1k atomic Minecraft tasks (mining/smelting/crafting/cooking/killing). +40% over best baseline. | [arXiv 2503.16365](https://arxiv.org/abs/2503.16365) · [Project](https://craftjarvis.github.io/JarvisVLA/) |
| **MP5** | CVPR 2024 | Modular MLLM-based system with goal-conditioned *active perception* — the agent decides what to look at next. 91% / 22% success on context- vs. process-dependent tasks. | [arXiv 2312.07472](https://arxiv.org/abs/2312.07472) · [GitHub](https://github.com/IranQin/MP5) |
| **MineDreamer** | NeurIPSw 2024 / IROS 2025 (Oral) | "Chain of Imagination": fine-tuned diffusion model imagines the step-by-step visual trajectory, which is fed as a prompt to a VPT policy. Roughly doubles best-baseline performance. | [arXiv 2403.12037](https://arxiv.org/abs/2403.12037) |
| **ROCKET-1** | CVPR 2025 | Visual-temporal context prompting: object-segmentation masks from past frames (via SAM-2 tracking) are concatenated with the visual observation. 76 percentage-point absolute jump on open-world interaction. | [arXiv 2410.17856](https://arxiv.org/abs/2410.17856) · [GitHub](https://github.com/CraftJarvis/ROCKET-1) |
| **MrSteve** | ICLR 2025 | Adds Place Event Memory (PEM) — what / where / when — to STEVE-1; the paper attributes most STEVE-1 failures specifically to the absence of episodic memory in the low-level controller. | [arXiv 2411.06736](https://arxiv.org/abs/2411.06736) |
| **Optimus-1** | NeurIPS 2024 | Hierarchical Directed Knowledge Graph + Abstracted Multimodal Experience Pool; Knowledge-Guided Planner + Experience-Driven Reflector. Near-human on many long-horizon tasks; beats GPT-4V baseline. | [arXiv 2408.03615](https://arxiv.org/abs/2408.03615) · [GitHub](https://github.com/JiuTian-VL/Optimus-1) |
| **Optimus-2** | CVPR 2025 | Goal-Observation-Action Conditioned Policy (GOAP). Includes the MGOA dataset: 25k videos × 8 atomic tasks ≈ 30M GOA pairs. | [arXiv 2502.19902](https://arxiv.org/abs/2502.19902) · [CVPR 2025 paper](https://openaccess.thecvf.com/content/CVPR2025/papers/Li_Optimus-2_Multimodal_Minecraft_Agent_with_Goal-Observation-Action_Conditioned_Policy_CVPR_2025_paper.pdf) |
| **Optimus-3** | arXiv Jun 2025 | Mixture-of-Experts agent with task-level routing + dual-granularity reasoning-aware policy optimization; integrates System-1 reflexive control and System-2 deliberative reasoning end-to-end. | [arXiv 2506.10357](https://arxiv.org/abs/2506.10357) · [GitHub](https://github.com/JiuTian-VL/Optimus-3) |
| **OpenHA** | arXiv Sep 2025 | Systematic comparison of action spaces / tokenizers for VLA hierarchical agents in Minecraft; introduces Chain-of-Action (high-level abstracted action then low-level action, both autoregressive in one VLA). 800+ task benchmark. Headline finding: **no single action space is universally optimal**. | [arXiv 2509.13347](https://arxiv.org/abs/2509.13347) · [GitHub](https://github.com/CraftJarvis/OpenHA) |

**What this cluster has shown.** (a) **Pixel-grounded instruction following is real.** STEVE-1 demonstrated for $60 of compute that you can fine-tune a VPT-class policy to follow MineCLIP-encoded text. (b) **Visual references work better than text references.** ROCKET-1 (segmentation-mask prompting) and MineDreamer (imagined-frame prompting) both show big gains over the same models prompted with words alone — the planner's hardest job is making the executor know *which* object it means. (c) **Memory is a first-class component, not a bolt-on.** MrSteve isolates "no episodic memory" as the primary STEVE-1 failure mode; JARVIS-1 and Optimus-1 build whole architectures around it. (d) **Tokenization unification is converging.** OmniJARVIS and JARVIS-VLA point at a near-future where one autoregressive transformer consumes pixels + text + actions in a single stream, no planner/executor split.

**Where it stalls.** (a) **Compute.** Even the cheap entries (STEVE-1 fine-tune ≈ $60) need someone else to have done VPT first, and full Optimus-class systems are training-cluster jobs. (b) **Action-space orthodoxy.** Most VLA work uses the VPT keyboard/mouse action space because that's where the pretrained weights are; this is great for "punch tree" and bad for "design and build a cathedral." OpenHA (cluster 5) provides the first systematic evidence that no single action space is universally optimal. (c) **Generalization to novel tasks** is still flat — Optimus-1 explicitly cites GPT-4V as a baseline, and even Optimus-3's MoE pitch is structured around *task interference*, which is itself evidence that the underlying policies don't transfer cleanly.

**Open problems.** Real-time perception with limited compute (most VLA agents are not 60 fps); long-context memory that doesn't degrade; sim-to-real-game transfer (most evaluations are MineDojo or Mineflayer-shimmed worlds, not vanilla Minecraft against a human player on the same machine).

---

## 3. Pure RL & Imitation-Learning Agents

**Framing.** No LLM at all in the policy. Two camps: model-free / model-based RL (DreamerV3 lineage), and large-scale imitation learning (VPT lineage). Activity in this cluster has cooled relative to LLM-based agents but produced the most dramatic single 2025 result — Dreamer 4.

| Work | Year / Venue | Core idea | Link |
|---|---|---|---|
| **DreamerV3** | arXiv Jan 2023 → Nature 2025 | One algorithm, 150+ tasks, single config; first RL to obtain diamond from scratch with no human data. | [arXiv 2301.04104](https://arxiv.org/abs/2301.04104) · [GitHub](https://github.com/danijar/dreamerv3) |
| **LS-Imagine** | ICLR 2025 (Oral) | Long Short-term Imagination world model; simulates *jumpy* state transitions to extend the imagination horizon, plus affordance maps from zoom-ins. SOTA on MineDojo. | [arXiv 2410.03618](https://arxiv.org/abs/2410.03618) · [GitHub](https://github.com/qiwang067/LS-Imagine) |
| **Dreamer 4** | arXiv Sep 2025 | First agent to obtain diamonds in Minecraft *purely from offline data* — no environment interaction. Real-time interactive inference on a single GPU via a "shortcut forcing" objective; 100× more data-efficient than VPT-offline. | [arXiv 2509.24527](https://arxiv.org/abs/2509.24527) · [Project](https://danijar.com/project/dreamer4/) |
| **VPT** (foundation reference) | NeurIPS 2022 | Behavior-cloning policy at the keyboard/mouse level; with fine-tuning, first to craft a diamond pickaxe. The action backbone for STEVE-1, ROCKET-1, MineDreamer, MrSteve. | [arXiv 2206.11795](https://arxiv.org/abs/2206.11795) |

**What this cluster has shown.** *Pure RL is no longer obviously inferior to LLM-augmented approaches on long-horizon Minecraft tasks.* Dreamer 4 is the headline: a model-based RL agent that learns *inside* an interactive world model, using only offline data, and still gets to diamond. That undermines the implicit narrative that "you need GPT-4 to play Minecraft." Dreamer 4 also collapses two clusters together — the agent and the world model are the same artifact (see Cluster 4).

**Where it stalls.** (a) **Generality across task styles.** Dreamer 4 plays the diamond progression beautifully; ask it to "build a cute cabin", "follow me", or "defend the village", and the reward signal stops carrying you. (b) **Engineering investment.** The DreamerV3/Dreamer-4 line is a multi-year effort from one lab; nothing in this cluster has a one-week-to-prototype story. (c) **No native language interface.** Pure RL agents take a goal vector or task ID; getting "obtain me 20 iron ore" into them requires bolting on a planner from Cluster 1 or 2.

**Open problems.** RL + LLM as advisor (the LS-Imagine + LLM-prior direction is open); world-model–conditioned policy distillation back into a small model; RL pretraining on top of VPT as a starting policy.

---

## 4. Generative World Models for Minecraft

**Framing.** Models that *simulate* Minecraft frames given actions — neural game engines. Not agents per se, but increasingly the substrate inside which agents are trained or with which players interact. The 2024–2025 explosion in this cluster is the most consequential change since VPT.

| Work | Year / Venue | Core idea | Link |
|---|---|---|---|
| **Genie** | DeepMind, ICML 2024 | 11B-parameter foundation world model trained unsupervised from internet videos; latent action model lets you act in generated 2D worlds without any ground-truth action labels in training. | [arXiv 2402.15391](https://arxiv.org/abs/2402.15391) |
| **DIAMOND** | NeurIPS 2024 (Spotlight) | Diffusion-based world model; argues visual-detail preservation matters for RL-in-imagination. SOTA on Atari-100k; demo trained as a standalone interactive game engine on CS:GO. | [arXiv 2405.12399](https://arxiv.org/abs/2405.12399) · [Project](https://diamond-wm.github.io/) · [GitHub](https://github.com/eloialonso/diamond) |
| **Oasis** (Decart / Etched) | Oct 2024, public live demo + 500M open weights | First publicly playable diffusion-based interactive world model on Minecraft; ~20 fps, autoregressive frame generation conditioned on keyboard/mouse. **Industry release; no peer-reviewed paper at launch.** | [Project](https://oasis-model.github.io/) · [open-oasis weights](https://github.com/etched-ai/open-oasis) · [TechCrunch](https://techcrunch.com/2024/10/31/decarts-ai-simulates-a-real-time-playable-version-of-minecraft/) |
| **MineWorld** (Microsoft) | arXiv Apr 2025 | Real-time, *open-source* autoregressive-Transformer world model on Minecraft; novel parallel decoding lets a 1.2B model run 4–7 fps. F1 = 0.73 on discrete action classification vs. Oasis's 0.41; L1 = 1.02 on camera vs. Oasis's 2.60. | [arXiv 2504.08388](https://arxiv.org/abs/2504.08388) · [GitHub](https://github.com/microsoft/mineworld) |
| **Dreamer 4** | arXiv Sep 2025 | Causal tokenizer + interactive dynamics model in one block-causal transformer, real-time on a single GPU; agent trained inside it solves diamond from offline data only. | [arXiv 2509.24527](https://arxiv.org/abs/2509.24527) |

**What this cluster opens up.** Three genuinely new things, each of which collapses a previous bottleneck:

1. **Training data becomes synthetic.** Once a world model is faithful enough, you can roll out millions of trajectories with zero environment cost and distill them into a small policy. Dreamer 4 already demonstrates the "no environment interaction" version of this.
2. **Player-facing experiences without an actual game engine.** Oasis is a real-time playable Minecraft *that has no Minecraft underneath it* — the Java game is gone, replaced by the model. This reframes "AI in Minecraft" into "AI as the Minecraft."
3. **Action-conditioning is learnable from passive video.** Genie shows the latent-action approach (no ground-truth keys/mouse labels) works at scale; this is the same trick MineDreamer uses for pixel-level instruction following.

**Where it stalls.** (a) **Visual fidelity / hallucination.** Oasis exhibits frequent "hallucinations" — block continuity, inventory consistency, lighting drift — that make it more of a vivid demo than a play-for-an-hour experience. (b) **Throughput vs. realism trade-off.** MineWorld's 4–7 fps comes from explicitly leaning toward speed over fidelity; full-fidelity rollouts at 60+ fps for live play are not yet here. (c) **Memory horizon.** Today's world models drift after seconds-to-minutes; a player-companion needs to remember the house from an hour ago.

**Open problems.** Selective fidelity (high-detail near the player, low-detail far away); coupling a world model with the *real* Minecraft so the model only renders parts of the simulation that demand neural prediction; agent training that exploits the world model's continuous action space rather than reducing to discrete keystrokes.

---

## 5. Multi-Agent Coordination & Benchmarks

**Framing.** Multiple agents — usually LLM-based — collaborating or competing on shared tasks. SteveAI's existing collaborative-build manager belongs in this cluster's pragmatic tradition. The research focus has shifted from "can two agents coordinate at all" (2023) to "can N agents share knowledge under realistic sensory constraints" (2024–2025).

| Work | Year / Venue | Core idea | Link |
|---|---|---|---|
| **MindAgent** | NAACL 2024 | LFM-coordinated multi-agent gaming framework; in-context-learning few-shot prompts for human–AI coordination. | [arXiv 2309.09971](https://arxiv.org/abs/2309.09971) |
| **MineLand** | arXiv Mar 2024 | 64+ agent simulator with *limited* multimodal senses and *physical needs* (hunger, fatigue) — agents have to communicate to survive. ~6,000 programmatic + 1,500 creative + 18 hybrid tasks. | [arXiv 2403.19267](https://arxiv.org/abs/2403.19267) · [GitHub](https://github.com/cocacola-lab/MineLand) |
| **VillagerAgent / VillagerBench** | ACL 2024 (Findings) | DAG-based multi-agent framework: task decomposer builds a DAG of subtasks, controller distributes them, state manager tracks. Beats AgentVerse, reduces hallucinations. | [arXiv 2406.05720](https://arxiv.org/abs/2406.05720) · [ACL Anthology](https://aclanthology.org/2024.findings-acl.964/) · [GitHub](https://github.com/cnsdqd-dyb/VillagerAgent) |
| **TeamCraft** (UCLA) | arXiv Dec 2024 | 55,000-task multi-modal benchmark for collaborative agents; first-person RGB vision, centralized + decentralized control; ships TeamCraft-VLA, a multi-agent VLA reference model. Findings: existing models still fail to generalize to novel goals / scene / agent counts. | [arXiv 2412.05255](https://arxiv.org/abs/2412.05255) · [OpenReview](https://openreview.net/forum?id=GV7JmRqX70) |
| **PillagerBench / TactiCrafter** | arXiv Sep 2025 | Real-time competitive team-vs-team benchmark with rule-based built-in opponents; TactiCrafter is an LLM multi-agent system with tactics + causal + opponent models, GPT-4o best. | [arXiv 2509.06235](https://arxiv.org/abs/2509.06235) |
| **CausalMACE** | EMNLP 2025 (Findings) | Causality-empowered multi-agent framework: task graph + causal-intervention module for dependency management; +12% on multi-agent and +7% on single-agent cooperative tasks. | [arXiv 2508.18797](https://arxiv.org/abs/2508.18797) · [ACL Anthology](https://aclanthology.org/2025.findings-emnlp.777/) |

**What this cluster has shown.** (a) **Coordination under sensor and physical constraints is the right level of difficulty.** MineLand's "limited senses + hunger" framing produces qualitatively richer behavior than "all agents see all state, no resource limits." (b) **Symbolic structure beats unconstrained chat.** VillagerAgent's DAG and CausalMACE's causal model both demonstrate that giving LLM-based teams *explicit shared structure* outperforms letting them coordinate by free-form messages alone. (c) **Multi-modal benchmarks reveal the generalization wall.** TeamCraft's headline result is that the current TeamCraft-VLA fails at unseen agent counts and unseen goals — meaning the multi-agent VLA frontier is still very early.

**Where it stalls.** (a) **Communication tokens are a tax** — most current systems pay full LLM-call cost per coordination message. (b) **Real-time competitive play** is essentially uncharted; PillagerBench is the first serious attempt and even it builds on rule-based opponents because LLM-vs-LLM real-time isn't there yet. (c) **Human-on-team scenarios** — where one of the "agents" is a human and the AI agents have to coordinate with them — barely exist in this cluster (cluster 7 owns this).

**Open problems.** Token-cheap coordination (compressed plans, action tokens shared across agents); persistent shared memory across sessions; emergent role differentiation under explicit reward.

---

## 6. Memory, Skill Libraries, and Lifelong Learning

**Framing.** This is technically cross-cutting — it shows up in every cluster — but enough work makes memory or skill-acquisition the *primary* contribution that it deserves its own section. The headline is: every successful long-horizon agent in 2024+ has a non-trivial memory architecture, and the field has converged on this.

| Memory pattern | Representative work | What it stores |
|---|---|---|
| Growable executable-code skill library | **Voyager** ([arXiv 2305.16291](https://arxiv.org/abs/2305.16291)) | JS skill snippets, retrieved by description embedding |
| Multimodal experience replay | **JARVIS-1** ([arXiv 2311.05997](https://arxiv.org/abs/2311.05997)) | (visual scenario, plan) pairs, used for long-horizon reuse |
| Hierarchical directed knowledge graph | **Optimus-1** ([arXiv 2408.03615](https://arxiv.org/abs/2408.03615)) | World rules + abstracted past trajectories |
| Place Event Memory (what / where / when) | **MrSteve** ([arXiv 2411.06736](https://arxiv.org/abs/2411.06736)) | Episodic spatial events at the *low-level* controller |
| Compositional skill graph | **Odyssey** ([arXiv 2407.15325](https://arxiv.org/abs/2407.15325)) | 40 primitives + 183 composites |
| Behavior tokens in LLM vocabulary | **OmniJARVIS** ([arXiv 2407.00114](https://arxiv.org/abs/2407.00114)) | Trajectory tokens packed alongside text in one autoregressive stream |

**What this cluster has shown.** (a) Memory belongs at *both* levels — the planner needs experience replay (Voyager, JARVIS-1, Optimus-1) and the executor needs episodic place memory (MrSteve). Failing either one collapses long-horizon performance. (b) Skill libraries that grow with use produce qualitatively different behavior from skill libraries that are fixed: Voyager's lifelong-learning curve is well above Plan4MC's fixed-skill curve. (c) Tokenization unification (OmniJARVIS, JARVIS-VLA, Optimus-3) is collapsing memory-vs-action: behavior history *is* a sequence of tokens in the LLM's vocabulary.

**Where it stalls.** (a) **Cross-session persistence is mostly absent in the wild** — academic benchmarks reset each episode, but a player-companion needs to remember last week's house. (b) **Memory is grown, not curated**: there's almost no work on what to forget, even though Voyager's skill library already grows past usability if the agent runs long enough. (c) **No standard memory benchmark.** TeamCraft's generalization findings hint at what's needed but no separate "long-horizon memory" benchmark has emerged.

**Open problems.** Forgetting / consolidation; transfer of skill libraries across worlds and Minecraft versions; memory-as-conversation (the agent literally remembering what the player said three days ago).

---

## 7. Human–AI Interaction & Player-Companion Systems

**Framing.** The most product-relevant cluster for any team building a Minecraft *companion* (rather than a benchmark agent). The core question this cluster is starting to answer empirically is: **does an LLM-driven companion actually improve the player's experience, or does it get in the way?**

| Work | Year / Venue | What it studies | Link |
|---|---|---|---|
| **Talking-to-Build** | ICMI 2025 | 30-participant mixed-methods study comparing an LLM-assisted natural-language interface vs. a command-based interface across simple and complex Minecraft tasks; LLM interface significantly improves performance, engagement, experience. | [arXiv 2507.20300](https://arxiv.org/abs/2507.20300) · [ACM DOI](https://dl.acm.org/doi/10.1145/3716553.3756015) |
| **LLM-Based Teammate in Minecraft** (Cohen, Willett et al.) | HFES 2025 | Iterative design of a Minecraft tower-defense human–machine-team testbed; ships two AI variants (decision-tree white-box vs. LLM-enabled) for direct head-to-head usability study. | [Sage / DOI 10.1177/10711813251374162](https://journals.sagepub.com/doi/10.1177/10711813251374162) |
| **After-Action Review for Mental-Model Alignment** | arXiv Mar 2025 | Uses an LLM to enable post-game free-form Q&A about agent behavior — explicitly aimed at letting humans build accurate mental models of their AI teammates. | [arXiv 2503.19607](https://arxiv.org/abs/2503.19607) |
| **MindCraft** (open-source) | 2023–ongoing | Most-used community Mineflayer + multi-LLM bot; closest practical analogue to a SteveAI-style companion, with bot profiles, sandboxed code execution, and pluggable LLM backends. | [github.com/mindcraft-bots/mindcraft](https://github.com/mindcraft-bots/mindcraft) |

**What this cluster has shown.** (a) **There is empirical, controlled-study evidence that LLM-assisted Minecraft interfaces improve player experience.** Talking-to-Build is the strongest single data point against the "this is just a tech demo" critique of LLM-driven companions. (b) **Mental-model alignment matters.** The After-Action-Review work (and the HFES 2025 testbed) both treat the *human's* understanding of *the AI* as a primary outcome variable — not just task success. This is a more honest framing of "is the companion useful" than ObtainDiamond ever was. (c) **Practical companion bots are tractable today.** MindCraft's open-source community shows the engineering bar is low enough that this is a product-ready surface, not just a research problem.

**Where it stalls.** (a) **Sample sizes are tiny** — 30-participant studies are the norm; we are very early in the empirical-HCI literature for AI companions. (b) **Vision-grounded companions are absent**: the HCI literature in this cluster is text-driven (chat → command), not multimodal. (c) **Latency requirements are largely uncharacterized** — what's the maximum acceptable response delay before the companion feels "frozen"? No paper answers this for Minecraft yet.

**Open problems.** Voice-driven companions (whisper / TTS loops); shared spatial reference ("over there", with the AI looking at where the player is pointing); social trust / repair (what happens after the AI does something the player didn't want).

---

## 8. Benchmarks, Evaluation, and Open-Endedness

**Framing.** Where claims of "agent X is better than Y" are actually validated. Critical because evaluation methodology has historically been the field's weakest link — the canonical "ObtainDiamond" task, while iconic, drove an over-fitting culture that the 2024+ benchmarks are explicitly trying to escape.

| Benchmark | Year / Venue | Tasks | What it measures | Link |
|---|---|---|---|---|
| **MineDojo** | NeurIPS 2022 | 3,142 (programmatic / creative / playthrough) | Open-ended task suite + MineCLIP reward signal | [Project](https://minedojo.org/) · [OpenReview](https://openreview.net/forum?id=rc8o_j8I8PX) |
| **Crafter** | ICLR 2022 | 22 achievements | Cheap procgen ablation testbed | [arXiv 2109.06780](https://arxiv.org/abs/2109.06780) |
| **MCU (Minecraft Universe)** | ICML 2025 | 3,452 atomic + infinite composable tasks | Open-ended task evaluation with 91.5% human alignment | [arXiv 2310.08367](https://arxiv.org/abs/2310.08367) |
| **VillagerBench** | ACL 2024 (Findings) | Multi-agent collaborative tasks | Workload distribution, dynamic adaptation, sync execution | [arXiv 2406.05720](https://arxiv.org/abs/2406.05720) |
| **MineLand benchmark** | arXiv 2024 | ~6,000 prog + ~1,500 creative + 18 hybrid | Multi-agent under sensory + physical-need constraints | [arXiv 2403.19267](https://arxiv.org/abs/2403.19267) |
| **TeamCraft** | UCLA, arXiv 2024 | 55,000 multi-modal task variants | Multi-modal multi-agent generalization | [arXiv 2412.05255](https://arxiv.org/abs/2412.05255) |
| **PillagerBench** | arXiv 2025 | Real-time team-vs-team | Competitive multi-round LLM agent benchmarking | [arXiv 2509.06235](https://arxiv.org/abs/2509.06235) |
| **OpenHA benchmark** | arXiv 2025 | 800+ tasks across action-space variants | Hierarchical-agent + action-space comparison | [arXiv 2509.13347](https://arxiv.org/abs/2509.13347) |
| **Voyager benchmark** | arXiv 2023 | Tech-tree, exploration, novel item discovery | Lifelong-learning vs. fixed-skill comparison | [arXiv 2305.16291](https://arxiv.org/abs/2305.16291) |

**What this cluster has shown.** (a) **The field has explicitly outgrown ObtainDiamond.** MCU, OpenHA, TeamCraft and the Optimus / Voyager evaluation suites all measure something more like "can the agent do hundreds of distinct things" than "can the agent get to diamond." (b) **Compositional task generation works.** MCU's claim of "infinite diverse tasks" via composable atomics, validated by 91.5% human alignment, is a real methodological advance. (c) **Multi-modal multi-agent is the current frontier of difficulty.** TeamCraft is the load-bearing example: even purpose-built multi-modal multi-agent VLA models fail to generalize across goals/scenes/agent-counts — meaning the field has a benchmark whose ceiling is far above the current state of the art.

**Where it stalls.** (a) **Player-experience evaluation is almost all in cluster 7, not here.** The benchmarks are still task-success-oriented; "did the human enjoy it" doesn't appear as a metric. (b) **No standard for real-time / latency.** PillagerBench gestures at real-time but doesn't impose a strict tick budget the way a real game would. (c) **No consensus on cross-version stability** — many benchmarks are pinned to specific Minecraft versions; agents that work on 1.16 may break on 1.21.

**Open problems.** Player-facing evaluation (UX-style metrics, not just task completion); standardized latency / FPS budget for evaluating "real-time playable" claims; cross-version regression suites.

---

## 9. Cross-Cutting Design Axes

These axes recur in every cluster; gathering them here keeps each cluster section above lean. We use, where possible, the same axes as Hu et al. (ACM CSUR 2024) for compatibility with the canonical taxonomy.

### 9.1 Action space

| Action space | Lineage | Used by | Tradeoff |
|---|---|---|---|
| **Low-level keyboard / mouse (~20 actions)** | VPT | STEVE-1, JARVIS-1, ROCKET-1, MineDreamer, MrSteve, Optimus-1/2/3, Dreamer 4 | Maximum behavioral expressiveness; transfers across versions; massive compute cost; no semantic shortcuts |
| **High-level Mineflayer / server API** | Voyager, MindCraft | Voyager, GITM, DEPS, Plan4MC, Odyssey, MindCraft, RL-GPT (mixed) | Cheap, prompt-only viable, expressive enough for tech-tree completion; bound to Mineflayer's surface area; non-pixel-grounded |
| **Learned skill library (executable code)** | Voyager | Voyager, Odyssey, JARVIS-1 (partially) | Compositional, lifelong-learnable; debt accumulates without forgetting policy |
| **Mixed / hierarchical (Chain-of-Action)** | OpenHA | OpenHA's Chain-of-Action | Generates abstracted action then concrete action in one model; OpenHA's headline finding: *no single action space is universally best* |

### 9.2 Perception

| Perception | Used by | Strength | Weakness |
|---|---|---|---|
| Pixels (RGB frames) | VPT lineage, Oasis, MineWorld, Dreamer 4 | Behavior-cloning from internet videos works | Compute-heavy; needs MineCLIP-style grounding to do anything with text |
| Structured state (block IDs, inventory, entities as JSON) | Voyager, GITM, DEPS, MindCraft | Cheap, LLM-friendly | Loses spatial/visual nuance; can't ground "that tree" |
| Hybrid (pixels + structured) | JARVIS-1, MP5, Optimus-1 | Best of both | More moving parts |
| Active perception (agent picks what to look at) | MP5 | Sample-efficient; matches human | Adds a control problem |
| Visual-temporal context (segmentation masks across frames) | ROCKET-1 | Massive open-world-interaction gain (+76 pp) | SAM-2 inference cost |

### 9.3 Planner / executor split

The dominant successful pattern through 2024 was **LLM planner + RL or behavior-cloned executor** (Plan4MC, JARVIS-1, MP5, Optimus-1). The 2024–2025 trend is toward **monolithic VLA** models where one autoregressive transformer replaces both (OmniJARVIS, JARVIS-VLA, Optimus-3, OpenHA's Chain-of-Action). The pure-RL camp (Dreamer 4) is the only successful current monolith *without* a language interface.

### 9.4 Real-time constraints

This is where most of the field is honest about its limitations. Most VLA agents run at single-digit fps; even MineWorld's optimized 1.2B model is 4–7 fps. Real-time playable systems today are either:

- **Text-only LLM planners with async LLM calls** (Voyager, MindCraft, SteveAI itself) — game keeps running while the LLM thinks; this is the "freeze the game" problem the field has solved at the engineering level but at the cost of multi-second action latency.
- **Behavior-cloned policies with no LLM in the inner loop** (STEVE-1, ROCKET-1, MrSteve) — fast enough but only follow simple instructions.
- **World models** (Oasis, MineWorld, Dreamer 4) — real-time on a single GPU but with hallucinations and short memory.

No current system is *all of: pixel-grounded, language-instructable, real-time, long-memory*. This is the central engineering frontier.

### 9.5 Compute / cost regime

| Regime | Examples | Practical implication |
|---|---|---|
| **API-only** (frozen LLM) | Voyager, GITM, DEPS, Plan4MC, MindCraft, SteveAI | Days to prototype; ongoing API cost; vendor lock-in |
| **Cheap fine-tune ($60–$1k)** | STEVE-1 | Weekend project at the bottom end |
| **Single-GPU training** | MineWorld 1.2B, Dreamer 4 inference | One-week to one-month effort |
| **Multi-GPU training** | OmniJARVIS, JARVIS-VLA, Optimus-2/3, ROCKET-1 | Lab-scale; not solo-developer territory |
| **Cluster-scale** | VPT, MineDojo, Genie | Industrial labs only |

### 9.6 Evaluation methodology

Mapped against tactic:
- **Tech-tree completion** (ObtainDiamond, Voyager benchmark) — the legacy metric; still useful as a sanity check but no longer a state-of-the-art-claim differentiator.
- **Task suites** (MineDojo, MCU, OpenHA) — open-ended, the new norm.
- **Multi-agent benchmarks** (VillagerBench, MineLand, TeamCraft, PillagerBench) — coordination-specific.
- **Human-subject studies** (Talking-to-Build, HFES 2025 testbed) — the only way to validate companion-product claims; rare and small-N.

---

## Appendix A — Direction Recommendation for SteveAI

> **Boundary:** This appendix is the only section that should change SteveAI's roadmap. The body above is intended to outlive any single roadmap decision.

### A.1 The decision frame

The product goal is a **Minecraft player-companion that takes natural-language commands and is "kinda multimodal" — sees the world, hears or reads instructions, acts.** Constraints implied by the user's framing of "real impact fast":

1. **Time-to-impact**: weeks, not multi-paper research effort.
2. **Cost / hardware**: must be runnable on consumer hardware (or with a modest API spend); cluster-scale training is out.
3. **Plays in vanilla Minecraft alongside a human player** (not a synthetic benchmark world).

These constraints are sharp. Several clusters that look exciting in research papers — Cluster 4 (world models), the heaviest Cluster 2 entries (Optimus-3 / JARVIS-VLA), Cluster 3's Dreamer 4 — are **out of scope for "weeks to impact"** even though they are the most impressive science.

### A.2 Cluster scoring against the goal

Scores are 1 (weak) to 5 (strong fit) on each axis.

| Cluster | Goal fit | Time-to-impact | Cost | Differentiation | Compounding | Total |
|---|---|---|---|---|---|---|
| **C1** LLM-prompted agents | 4 | **5** | 4 | 2 | 3 | 18 |
| **C2** VLA embodied agents | 5 | 2 | 2 | 4 | 5 | 18 |
| **C3** Pure RL / Dreamer 4 | 2 | 1 | 1 | 5 | 4 | 13 |
| **C4** Generative world models | 2 | 1 | 1 | 5 | 4 | 13 |
| **C5** Multi-agent coordination | 3 | 3 | 4 | 3 | 3 | 16 |
| **C6** Memory / lifelong | 4 | 4 | 4 | 3 | 5 | 20 |
| **C7** HCI / companion | 5 | 5 | 5 | 4 | 4 | **23** |
| **C8** Evaluation | n/a (cross-cutting) | — | — | — | — | — |

Memory (C6) and HCI/companion (C7) score highest because they are exactly where the existing SteveAI codebase already lives, and where the research literature is least crowded with cluster-scale incumbents.

### A.3 What the survey actually argues for

**Lead direction (top 1):** Build a **player-companion** in Cluster 7's mode, anchored on Cluster 1's LLM-prompted skill-library architecture, with a serious investment in Cluster 6's memory model. That is, **commit to the LLM-mod architecture you already have, but stop treating it as the whole product** — the differentiation is in (a) the human-AI experience layer and (b) cross-session memory, neither of which the academic LLM-agent papers solve.

Concretely, this means:

1. **Stop chasing ObtainDiamond / tech-tree benchmarks.** They are mostly solved (GITM, Voyager, JARVIS-1, RL-GPT) and they're not what determines whether a player likes the companion. Use them only as regression checks, not as North Stars.
2. **Add a real episodic-memory layer.** The MrSteve paper is explicit: "no episodic memory" is the dominant low-level failure mode. SteveAI's `SteveMemory` is structurally similar; the gap is cross-session persistence — Voyager / JARVIS-1 / Optimus-1 patterns directly apply.
3. **Add visual grounding cheaply, not deeply.** ROCKET-1's segmentation-mask prompting + STEVE-1-style MineCLIP grounding give 80% of the "see the world" benefit for well under 1% of the cost of training a VPT-class policy. The right SteveAI architecture is *LLM planner with optional vision modules that fire only when the player references something visually*.
4. **Make HCI a first-class measurement.** Run something like the Talking-to-Build / HFES 2025 study design on your own bot — even N=10 internally — and treat the results as primary validation, alongside (not below) the existing in-game scenario tests.

**Secondary direction (top 2):** **Multi-agent coordination via SteveAI's existing collaborative-build manager**, deepened along VillagerAgent's DAG-structured-coordination lines. This is a genuine differentiator vs. the single-companion bots (MindCraft, etc.) that dominate the open-source space, and your codebase is already 70% of the way there.

### A.4 The 2–4 week minimum viable next step (for the lead direction)

A focused four-week sprint that buys real signal:

1. **Week 1 — Cross-session persistent memory.** Replace SteveMemory's in-process memory with a small vector DB (or even a SQLite + embeddings); persist on shutdown; reload on player rejoin. Specifically: episodic place memory ("you last mined iron at X"), conversational memory ("you said you don't want me to chop birch"), and skill library (Voyager-style code skills the LLM has written).
2. **Week 2 — Visual-reference grounding.** Add a SAM-2 + CLIP fast path that activates only when the player uses spatial deixis ("that tree", "over there", "the thing I'm looking at"). Output: a block / entity / region the LLM planner can refer to. No training required; SAM-2 is off-the-shelf.
3. **Week 3 — Run a 10-participant Talking-to-Build-style study on yourself / friends.** Compare command-mode vs. NL-mode SteveAI on three concrete tasks (mine 20 iron, build a small house, defend the player). Measure: task success, perceived helpfulness (1–7 scale), latency-to-first-action, perceived agency.
4. **Week 4 — Public artifact.** Ship a build that includes the above as a single mod, plus a 5-minute video demoing the companion remembering the player across sessions and grounding "that tree" correctly. This is what would separate SteveAI from MindCraft in the open-source field.

### A.5 What you give up by choosing this

Honest list:

- **No state-of-the-art benchmark numbers.** You will not beat Optimus-3 or Dreamer 4 on Minecraft tech-tree benchmarks; you have neither the training cluster nor the training data.
- **No novel-architecture paper.** This direction is integration + product, not new science.
- **Visual fidelity is bounded by SAM-2 / MineCLIP cost.** Tasks that need fine-grained pixel reasoning (precision combat, fine building) will lag the VPT-lineage agents.
- **Vendor exposure.** API-only LLM mode keeps you on OpenAI / Anthropic / Groq for the medium term; Ollama is the mitigation but a step down on planning quality.

If the team's actual North Star is *to publish*, the recommendation flips: lean into Cluster 4 or Cluster 3 with the existing Minecraft instrumentation as a launchpad, accept the cluster-scale training cost. The recommendation above is for *to ship*.

---

## Appendix B — Consolidated Bibliography

For copy-paste convenience. Within-cluster duplicates are listed once.

**Foundations.**
- Hafner, Pasukonis, Ba, Lillicrap. *Mastering Diverse Domains through World Models* (DreamerV3). arXiv 2301.04104, 2023; Nature 2025. <https://arxiv.org/abs/2301.04104>
- Baker et al. *Video PreTraining (VPT): Learning to Act by Watching Unlabeled Online Videos*. NeurIPS 2022. arXiv 2206.11795. <https://arxiv.org/abs/2206.11795>
- Fan et al. *MineDojo: Building Open-Ended Embodied Agents with Internet-Scale Knowledge*. NeurIPS 2022 Outstanding Paper. <https://openreview.net/forum?id=rc8o_j8I8PX>
- Hafner. *Crafter: Benchmarking the Spectrum of Agent Capabilities*. ICLR 2022. arXiv 2109.06780. <https://arxiv.org/abs/2109.06780>

**Cluster 1 — LLM-prompted agents.**
- Wang et al. *DEPS: Describe, Explain, Plan and Select*. NeurIPS 2023. arXiv 2302.01560. <https://arxiv.org/abs/2302.01560>
- Wang et al. *Voyager: An Open-Ended Embodied Agent with LLMs*. arXiv 2305.16291, 2023. <https://arxiv.org/abs/2305.16291>
- Zhu et al. *Ghost in the Minecraft (GITM)*. arXiv 2305.17144, 2023. <https://arxiv.org/abs/2305.17144>
- *Plan4MC: Skill Reinforcement Learning and Planning for Open-World Minecraft Tasks*. NeurIPS 2023 FMDM Workshop. arXiv 2303.16563. <https://arxiv.org/abs/2303.16563>
- Liu et al. *RL-GPT: Integrating RL and Code-as-Policy*. arXiv 2402.19299, 2024. <https://arxiv.org/abs/2402.19299>
- Liu et al. *Odyssey: Empowering Minecraft Agents with Open-World Skills*. arXiv 2407.15325, 2024. <https://arxiv.org/abs/2407.15325>
- *MindCraft* (open-source). <https://github.com/mindcraft-bots/mindcraft>
- Hu et al. *A Survey on Large Language Model-Based Game Agents*. ACM CSUR. arXiv 2404.02039. <https://arxiv.org/abs/2404.02039>

**Cluster 2 — VLA / multimodal embodied agents.**
- Lifshitz et al. *STEVE-1: Generative Model for Text-to-Behavior in Minecraft*. NeurIPS 2023 Spotlight. arXiv 2306.00937. <https://arxiv.org/abs/2306.00937>
- *Steve-Eye: Equipping LLM-based Embodied Agents with Visual Perception in Open Worlds*. arXiv 2310.13255, 2023. <https://arxiv.org/abs/2310.13255>
- Zhao et al. *See and Think: Embodied Agent in Virtual Environment* (STEVE). ECCV 2024. arXiv 2311.15209. <https://arxiv.org/abs/2311.15209>
- *STEVE-Audio: Expanding the Goal Conditioning Modalities of Embodied Agents in Minecraft*. arXiv 2412.00949, 2024. <https://arxiv.org/abs/2412.00949>
- Cai et al. *GROOT: Learning to Follow Instructions by Watching Gameplay Videos*. ICLR 2024 Spotlight. arXiv 2310.08235. <https://arxiv.org/abs/2310.08235>
- *GROOT-2: Weakly Supervised Multi-Modal Instruction Following Agents*. arXiv 2412.10410, 2024. <https://arxiv.org/abs/2412.10410>
- *JARVIS-1: Open-World Multi-task Agents with Memory-Augmented Multimodal Language Models*. arXiv 2311.05997, 2023. <https://arxiv.org/abs/2311.05997>
- *OmniJARVIS: Unified Vision-Language-Action Tokenization*. NeurIPS 2024. arXiv 2407.00114. <https://arxiv.org/abs/2407.00114>
- *JARVIS-VLA: Post-Training Large-Scale Vision Language Models to Play Visual Games with Keyboards and Mouse*. ACL 2025 Findings. arXiv 2503.16365. <https://arxiv.org/abs/2503.16365>
- Qin et al. *MP5*. CVPR 2024. arXiv 2312.07472. <https://arxiv.org/abs/2312.07472>
- *MineDreamer: Learning to Follow Instructions via Chain-of-Imagination*. NeurIPSw 2024 / IROS 2025 Oral. arXiv 2403.12037. <https://arxiv.org/abs/2403.12037>
- Cai et al. *ROCKET-1: Mastering Open-World Interaction with Visual-Temporal Context Prompting*. CVPR 2025. arXiv 2410.17856. <https://arxiv.org/abs/2410.17856>
- *MrSteve*. ICLR 2025. arXiv 2411.06736. <https://arxiv.org/abs/2411.06736>
- Li et al. *Optimus-1*. NeurIPS 2024. arXiv 2408.03615. <https://arxiv.org/abs/2408.03615>
- Li et al. *Optimus-2*. CVPR 2025. arXiv 2502.19902. <https://arxiv.org/abs/2502.19902>
- Li et al. *Optimus-3*. arXiv 2506.10357, 2025. <https://arxiv.org/abs/2506.10357>
- *OpenHA: A Series of Open-Source Hierarchical Agentic Models in Minecraft*. arXiv 2509.13347, 2025. <https://arxiv.org/abs/2509.13347>

**Cluster 3 — RL & imitation learning.**
- Hafner et al. *DreamerV3*. arXiv 2301.04104, 2023; Nature 2025. <https://arxiv.org/abs/2301.04104>
- *Open-World Reinforcement Learning over Long Short-Term Imagination* (LS-Imagine). ICLR 2025 Oral. arXiv 2410.03618. <https://arxiv.org/abs/2410.03618>
- Hafner, Yan, Lillicrap. *Training Agents Inside of Scalable World Models* (Dreamer 4). arXiv 2509.24527, 2025. <https://arxiv.org/abs/2509.24527>

**Cluster 4 — Generative world models.**
- *Genie: Generative Interactive Environments* (DeepMind). ICML 2024. arXiv 2402.15391. <https://arxiv.org/abs/2402.15391>
- Alonso et al. *Diffusion for World Modeling: Visual Details Matter in Atari* (DIAMOND). NeurIPS 2024 Spotlight. arXiv 2405.12399. <https://arxiv.org/abs/2405.12399>
- Decart / Etched. *Oasis* (industry release; no peer-reviewed paper at launch). Project page <https://oasis-model.github.io/>; open weights <https://github.com/etched-ai/open-oasis>; TechCrunch coverage <https://techcrunch.com/2024/10/31/decarts-ai-simulates-a-real-time-playable-version-of-minecraft/>.
- *MineWorld: a Real-Time and Open-Source Interactive World Model on Minecraft* (Microsoft). arXiv 2504.08388, 2025. <https://arxiv.org/abs/2504.08388>
- Hafner, Yan, Lillicrap. *Dreamer 4*. arXiv 2509.24527, 2025. <https://arxiv.org/abs/2509.24527>

**Cluster 5 — Multi-agent.**
- *MindAgent*. NAACL 2024. arXiv 2309.09971. <https://arxiv.org/abs/2309.09971>
- *MineLand: Simulating Large-Scale Multi-Agent Interactions with Limited Multimodal Senses and Physical Needs*. arXiv 2403.19267, 2024. <https://arxiv.org/abs/2403.19267>
- Dong et al. *VillagerAgent / VillagerBench*. ACL 2024 Findings. arXiv 2406.05720. <https://arxiv.org/abs/2406.05720>
- *TeamCraft*. UCLA, arXiv 2412.05255, 2024. <https://arxiv.org/abs/2412.05255>
- *PillagerBench / TactiCrafter*. arXiv 2509.06235, 2025. <https://arxiv.org/abs/2509.06235>
- *CausalMACE*. EMNLP 2025 Findings. arXiv 2508.18797. <https://arxiv.org/abs/2508.18797>

**Cluster 7 — HCI / companion.**
- *Talking-to-Build*. ICMI 2025. arXiv 2507.20300. <https://arxiv.org/abs/2507.20300>
- Cohen, Willett et al. *Building an LLM-Based Teammate in Minecraft*. HFES 2025. <https://journals.sagepub.com/doi/10.1177/10711813251374162>
- *After-Action Review for Mental Model Alignment*. arXiv 2503.19607, 2025. <https://arxiv.org/abs/2503.19607>
- *MindCraft*. <https://github.com/mindcraft-bots/mindcraft>

**Cluster 8 — Evaluation.**
- *MCU: An Evaluation Framework for Open-Ended Game Agents*. ICML 2025. arXiv 2310.08367. <https://arxiv.org/abs/2310.08367>
- Plus benchmarks already cited under their primary clusters: MineDojo, Crafter, VillagerBench, MineLand, TeamCraft, PillagerBench, OpenHA, Voyager.
