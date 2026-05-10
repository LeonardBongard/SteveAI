# Steve AI North Star

## Vision
Build Steve into a reliable embodied survival agent that can take natural-language goals and execute full Minecraft job loops end-to-end without micromanagement.

## Product North Star
Given a goal like "set up a safe early-game base and food loop", Steve should:
1. Plan multi-step work.
2. Execute safely in world context.
3. Maintain self-needs (health, hunger, danger response).
4. Use inventory, tools, and environment correctly.
5. Recover from failure without stalling.

Scrum Product Goal wording:
- Steve is a reliable Minecraft agent that can create, execute, inspect, and update its own task plans in-world, using reusable systems instead of local one-off fixes.

## Core Capability Domains
1. Job Execution:
- Mining
- Crafting
- Building
- Farming
- Gathering

2. Safety and Risk:
- Continuous safety evaluation (`SafetyEvaluatorManager`)
- Threat detection and escape behavior
- Safety-gated execution decisions

3. Mobility and Survival:
- Pathfinding
- Swimming/water traversal
- Hazard avoidance
- Health and hunger handling

4. Living World Interaction:
- Food acquisition and eating
- Animal feeding with preferred foods
- Day/night and combat-aware decisions

5. Agent Control:
- Explicit state machine
- Interrupts and replanning
- Action result reporting and observability
- Stuck detection and runtime replan loop

## Scope for Current Program
1. Implement now:
- Mining
- Crafting
- Farming
- Swimming
- Hunger bar simulation/use
- Getting and eating food
- Feeding animals preferred food

2. Design now, implement later:
- Panic level system (`panicLvl`) and behavior adaptation
- Keep interfaces and state fields in place, but leave behavior handlers empty

## Success Criteria (Release Gate)
1. Steve can finish a "survive one day" scenario from a fresh spawn seed with no manual intervention except the initial instruction.
2. Steve can recover from at least one blocked action per domain (mine/craft/farm/swim/eat/feed) and continue.
3. No infinite loops in action tick for more than configured timeout.
4. All domain actions produce structured logs and pass regression tests.
5. All dangerous transitions are mediated by `SafetyEvaluatorManager` decisions.
