package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.food.FoodTargetResolver;
import com.steve.ai.resource.KnownResourceManager;
import com.steve.ai.di.ServiceContainer;
import com.steve.ai.di.SimpleServiceContainer;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.SimpleEventBus;
import com.steve.ai.execution.*;
import com.steve.ai.execution.behavior.BehaviorContext;
import com.steve.ai.execution.behavior.BehaviorDefinition;
import com.steve.ai.execution.behavior.BehaviorRegistry;
import com.steve.ai.execution.behavior.BehaviorScheduler;
import com.steve.ai.execution.behavior.impl.PassiveChestScanBehavior;
import com.steve.ai.execution.behavior.impl.PassiveSafetyPulseBehavior;
import com.steve.ai.execution.behavior.impl.PassiveVisibleScanBehavior;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.TaskPlanner;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.network.chat.Component;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.validation.MinecraftLegalityChecker;
import com.steve.ai.mining.ToolCapabilityMap;
import com.steve.ai.util.ActionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes actions for a Steve entity using the plugin-based action system.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Uses ActionRegistry for dynamic action creation (Factory + Registry patterns)</li>
 *   <li>Uses InterceptorChain for cross-cutting concerns (logging, metrics, events)</li>
 *   <li>Uses AgentStateMachine for explicit state management</li>
 *   <li>Falls back to legacy switch statement if registry lookup fails</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation
    private final LinkedList<Task> taskQueue;

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle

    // NEW: Async planning support (non-blocking LLM calls)
    private CompletableFuture<ResponseParser.ParsedResponse> planningFuture;
    private boolean isPlanning = false;
    private String pendingCommand;  // Store command while planning

    // NEW: Plugin architecture components
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;
    private final SafetyEvaluatorManager safetyEvaluatorManager;
    private final BehaviorRegistry behaviorRegistry;
    private final BehaviorScheduler behaviorScheduler;
    private final Map<String, BehaviorDefinition> knownBehaviors;
    private final java.util.Set<String> disabledBehaviors;
    private String lastDebugStatus;
    private UUID lastCommandingPlayerId;
    private int safetyActionCooldownTicks;
    private boolean safetyRecoveryActive;
    private SafetyState lastLoggedSafetyState;
    private PanicLevel lastLoggedPanicLevel;
    private SafetyDecision lastLoggedSafetyDecision;
    private int foodRecoveryCooldownTicks;
    private int chestScanCooldownTicks;
    private final Deque<String> recentTaskSignatures;
    private final Map<String, Integer> failedTaskAttempts;
    private final Map<String, Integer> failedCraftAttemptsByItem;
    private final Map<String, TaskTreeNode> taskTree;
    private String activeTaskNodeId;
    private long taskNodeSeq;
    private static final int MAX_RECENT_TASK_SIGNATURES = 40;
    private static final int MAX_REPEAT_GUARD = 6;
    private static final int MAX_PATHFIND_REPEAT_GUARD = 3;
    private static final int MAX_FAILED_TASK_ATTEMPTS = 3;
    private static final int MAX_FAILED_CRAFT_ATTEMPTS_PER_ITEM = 4;
    private static final int MAX_TASK_TREE_RETRIES = 2;
    private static final int MAX_DYNAMIC_DEFERRALS_PER_TASK = 3;
    private static final int MAX_TOOL_RECOVERY_RETRIES = 2;
    private static final String META_DYNAMIC_DEFERS = "__dynamic_defers";
    private static final String META_TOOL_RECOVERY_RETRY = "__tool_recovery_retry";
    private static final String META_NODE_ID = "__task_node_id";
    private static final String META_PARENT_ID = "__task_parent_id";
    private static final int PASSIVE_BEHAVIOR_TICK_BUDGET = 8;
    private final List<TaskExpansionRule> expansionRules;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
        this.planningFuture = null;
        this.pendingCommand = null;
        this.lastDebugStatus = null;
        this.lastCommandingPlayerId = null;
        this.safetyActionCooldownTicks = 0;
        this.safetyRecoveryActive = false;
        this.lastLoggedSafetyState = null;
        this.lastLoggedPanicLevel = null;
        this.lastLoggedSafetyDecision = null;
        this.foodRecoveryCooldownTicks = 0;
        this.chestScanCooldownTicks = 0;
        this.recentTaskSignatures = new ArrayDeque<>();
        this.failedTaskAttempts = new HashMap<>();
        this.failedCraftAttemptsByItem = new HashMap<>();
        this.taskTree = new LinkedHashMap<>();
        this.activeTaskNodeId = null;
        this.taskNodeSeq = 0L;
        this.expansionRules = List.of(
            this::expandMineToolPrerequisites
        );

        // Initialize plugin architecture components
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, steve.getSteveName());
        this.interceptorChain = new InterceptorChain();
        this.safetyEvaluatorManager = new DefaultSafetyEvaluatorManager();
        this.behaviorRegistry = new BehaviorRegistry();
        this.behaviorScheduler = new BehaviorScheduler(behaviorRegistry);
        this.knownBehaviors = new LinkedHashMap<>();
        this.disabledBehaviors = new LinkedHashSet<>();

        // Setup interceptors
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, steve.getSteveName()));

        // Build action context
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .safetyEvaluatorManager(safetyEvaluatorManager)
            .build();

        SteveMod.LOGGER.debug("ActionExecutor initialized with plugin architecture for Steve '{}'",
            steve.getSteveName());
        registerDefaultBehaviors();
        updateDebugStatus("Idle");
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            SteveMod.LOGGER.info("Initializing TaskPlanner for Steve '{}'", steve.getSteveName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    /**
     * Processes a natural language command using ASYNC non-blocking LLM calls.
     *
     * <p>This method returns immediately and does NOT block the game thread.
     * The LLM response is processed in tick() when the CompletableFuture completes.</p>
     *
     * <p><b>Non-blocking flow:</b></p>
     * <ol>
     *   <li>User sends command</li>
     *   <li>This method starts async LLM call, returns immediately</li>
     *   <li>Game continues running normally (no freeze!)</li>
     *   <li>tick() checks if planning is done</li>
     *   <li>When done, tasks are queued and execution begins</li>
     * </ol>
     *
     * @param command The natural language command from the user
     */
    public void processNaturalLanguageCommand(String command) {
        processNaturalLanguageCommand(command, null);
    }

    public void processNaturalLanguageCommand(String command, ServerPlayer commandingPlayer) {
        SteveMod.LOGGER.info("Steve '{}' processing command (async): {}", steve.getSteveName(), command);
        this.lastCommandingPlayerId = commandingPlayer != null ? commandingPlayer.getUUID() : null;

        // If already planning, ignore new commands
        if (isPlanning) {
            SteveMod.LOGGER.warn("Steve '{}' is already planning, ignoring command: {}", steve.getSteveName(), command);
            sendToGUI(steve.getSteveName(), "Hold on, I'm still thinking about the previous command...");
            return;
        }

        // Cancel any current actions
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        clearTaskTree();
        resetLoopGuards();

        try {
            // Store command and start async planning
            this.pendingCommand = command;
            this.isPlanning = true;
            updateDebugStatus("Planning: " + command);

            // Send immediate feedback to user
            sendToGUI(steve.getSteveName(), "Thinking...");
            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendDebugToChat("Thinking about: " + command);
            }

            // Start async LLM call - returns immediately!
            planningFuture = getTaskPlanner().planTasksAsync(steve, command);

            SteveMod.LOGGER.info("Steve '{}' started async planning for: {}", steve.getSteveName(), command);

        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
            isPlanning = false;
            planningFuture = null;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error starting async planning", e);
            sendToGUI(steve.getSteveName(), "Oops, something went wrong!");
            isPlanning = false;
            planningFuture = null;
        }
    }

    /**
     * Legacy synchronous command processing (blocking).
     *
     * <p><b>Warning:</b> This method blocks the game thread for 30-60 seconds during LLM calls.
     * Use {@link #processNaturalLanguageCommand(String)} instead for non-blocking execution.</p>
     *
     * @param command The natural language command
     * @deprecated Use {@link #processNaturalLanguageCommand(String)} instead
     */
    @Deprecated
    public void processNaturalLanguageCommandSync(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command (SYNC - blocking!): {}", steve.getSteveName(), command);

        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        clearTaskTree();
        resetLoopGuards();

        try {
            // BLOCKING CALL - freezes game for 30-60 seconds!
            ResponseParser.ParsedResponse response = getTaskPlanner().planTasks(steve, command);

            if (response == null) {
                sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                return;
            }

            currentGoal = response.getPlan();
            steve.getMemory().setCurrentGoal(currentGoal);

            taskQueue.clear();
            enqueueTasks(response.getTasks());

            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
            }
        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
        }

        SteveMod.LOGGER.info("Steve '{}' queued {} tasks", steve.getSteveName(), taskQueue.size());
    }
    
    /**
     * Send a message to the GUI pane (client-side only, no chat spam)
     */
    private void sendToGUI(String steveName, String message) {
        if (steve.level().isClientSide()) {
            com.steve.ai.client.SteveGUI.addSteveMessage(steveName, message);
            return;
        }

        if (lastCommandingPlayerId == null) {
            return;
        }

        if (steve.level().getServer() == null) {
            return;
        }

        ServerPlayer player = steve.level().getServer().getPlayerList().getPlayer(lastCommandingPlayerId);
        if (player != null) {
            player.displayClientMessage(Component.literal("<" + steveName + "> " + message), false);
        }
    }

    private void sendDebugToChat(String message) {
        if (!steve.level().isClientSide()) {
            steve.sendChatMessage("[DEBUG] " + message);
        }
    }

    public void tick() {
        ticksSinceLastAction++;
        if (safetyActionCooldownTicks > 0) {
            safetyActionCooldownTicks--;
        }
        if (foodRecoveryCooldownTicks > 0) {
            foodRecoveryCooldownTicks--;
        }
        if (chestScanCooldownTicks > 0) {
            chestScanCooldownTicks--;
        }
        runPassiveBehaviors();

        // Check if async planning is complete (non-blocking check!)
        if (isPlanning && planningFuture != null && planningFuture.isDone()) {
            try {
                ResponseParser.ParsedResponse response = planningFuture.get();

                if (response != null) {
                    currentGoal = response.getPlan();
                    steve.getMemory().setCurrentGoal(currentGoal);

                    taskQueue.clear();
                    enqueueTasks(response.getTasks());
                    updateDebugStatus("Plan: " + currentGoal);

                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
                        sendDebugToChat("Plan: " + currentGoal);
                    }

                    SteveMod.LOGGER.info("Steve '{}' async planning complete: {} tasks queued",
                        steve.getSteveName(), taskQueue.size());
                } else {
                    sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                    SteveMod.LOGGER.warn("Steve '{}' async planning returned null response", steve.getSteveName());
                    updateDebugStatus("Plan failed");
                }

            } catch (java.util.concurrent.CancellationException e) {
                SteveMod.LOGGER.info("Steve '{}' planning was cancelled", steve.getSteveName());
                sendToGUI(steve.getSteveName(), "Planning cancelled.");
                updateDebugStatus("Planning cancelled");
            } catch (Exception e) {
                SteveMod.LOGGER.error("Steve '{}' failed to get planning result", steve.getSteveName(), e);
                sendToGUI(steve.getSteveName(), "Oops, something went wrong while planning!");
                updateDebugStatus("Planning error");
            } finally {
                isPlanning = false;
                planningFuture = null;
                pendingCommand = null;
            }
        }

        BaseAction action = currentAction;
        if (action != null) {
            if (action.isComplete()) {
                ActionResult result = action.getResult();
                Task completedTask = action.getTask();
                String completedNodeId = nodeIdOf(completedTask);
                SteveMod.LOGGER.info("Steve '{}' - Action completed: {} (Success: {})", 
                    steve.getSteveName(), result.getMessage(), result.isSuccess());
                
                steve.getMemory().addAction(action.getDescription());
                markTaskNodeFinished(completedNodeId, result.isSuccess());
                recordTaskOutcome(completedTask, result.isSuccess());
                if (!result.isSuccess()) {
                    maybeQueueFailureRecovery(completedTask, result);
                }
                
                if (!result.isSuccess() && result.requiresReplanning()) {
                    // Action failed, need to replan
                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Problem: " + result.getMessage());
                        sendDebugToChat("Problem: " + result.getMessage());
                    }
                    updateDebugStatus("Problem: " + result.getMessage());
                }

                if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                    sendDebugToChat("Done: " + result.getMessage());
                }
                if (result.isSuccess()) {
                    updateDebugStatus("Done: " + result.getMessage());
                }

                currentAction = null;
                activeTaskNodeId = parentNodeIdOf(completedTask);
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' - Ticking action: {}", 
                        steve.getSteveName(), action.getDescription());
                }
                action.tick();
                // Tick can complete/cancel action and clear currentAction in the same server tick.
                BaseAction stillActive = currentAction;
                updateDebugStatus("Action: " + (stillActive != null ? stillActive.getDescription() : action.getDescription()));
                return;
            }
        }

        if (!isPlanning && !taskQueue.isEmpty() && !allowActionTick(null)) {
            return;
        }

        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                Task nextTask = taskQueue.poll();
                markTaskNodeDequeued(nextTask);
                if (expandTaskDynamically(nextTask)) {
                    ticksSinceLastAction = 0;
                    return;
                }
                executeTask(nextTask);
                ticksSinceLastAction = 0;
                return;
            }
        }

        if (!isPlanning && taskQueue.isEmpty() && currentAction == null && !taskTree.isEmpty()) {
            if (tryResumeFromTaskTree()) {
                String goalLabel = (currentGoal == null || currentGoal.isBlank()) ? "direct task plan" : currentGoal;
                updateDebugStatus("Resuming goal: " + goalLabel);
                return;
            }
            finalizeGoalIfExhausted();
        }
        
        // When completely idle (no tasks, no goal), follow nearest player
        if (taskQueue.isEmpty() && currentAction == null && currentGoal == null) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
                updateDebugStatus("Idle: following player");
            } else if (idleFollowAction.isComplete()) {
                // Restart idle following if it stopped
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
                updateDebugStatus("Idle: following player");
            } else {
                // Continue idle following
                idleFollowAction.tick();
                updateDebugStatus("Idle: following player");
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        if (!isPlanning && taskQueue.isEmpty() && currentAction == null && idleFollowAction == null) {
            updateDebugStatus("Idle");
        }
    }

    private void executeTask(Task task) {
        markTaskNodeInProgress(task);
        activeTaskNodeId = nodeIdOf(task);
        SteveMod.LOGGER.info("Steve '{}' executing task: {} (action type: {})", 
            steve.getSteveName(), task, task.getAction());
        MinecraftLegalityChecker.CheckResult legality = MinecraftLegalityChecker.validateTaskForExecution(steve, task);
        if (!legality.legal()) {
            String nodeId = nodeIdOf(task);
            SteveMod.LOGGER.warn(
                "[LEGALITY] Steve '{}' blocked task action={} reason={} params={}",
                steve.getSteveName(),
                task.getAction(),
                legality.reason(),
                task.getParameters()
            );
            sendToGUI(steve.getSteveName(), "Blocked illegal action: " + task.getAction() + " (" + legality.reason() + ")");
            markTaskNodeFinished(nodeId, false);
            activeTaskNodeId = parentNodeIdOf(task);
            return;
        }
        if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
            sendDebugToChat("Starting: " + task.getAction() + " " + task.getParameters());
        }
        updateDebugStatus("Action: " + task.getAction() + " " + task.getParameters());
        
        BaseAction createdAction = createAction(task);

        if (createdAction == null) {
            String nodeId = nodeIdOf(task);
            SteveMod.LOGGER.error("FAILED to create action for task: {}", task);
            sendToGUI(steve.getSteveName(), "I could not execute task: " + task.getAction());
            markTaskNodeFinished(nodeId, false);
            activeTaskNodeId = parentNodeIdOf(task);
            return;
        }

        currentAction = createdAction;
        SteveMod.LOGGER.info("Created action: {} - starting now...", createdAction.getClass().getSimpleName());
        createdAction.start();
        SteveMod.LOGGER.info("Action started! Is complete: {}", createdAction.isComplete());

        // Defensive guard: start() may finish/cancel/rewrite currentAction in custom actions.
        if (currentAction != createdAction && currentAction == null) {
            SteveMod.LOGGER.warn(
                "Steve '{}' action '{}' cleared currentAction during start()",
                steve.getSteveName(),
                createdAction.getClass().getSimpleName()
            );
        }
    }

    /**
     * Creates an action using the plugin registry with legacy fallback.
     *
     * <p>First attempts to create the action via ActionRegistry (plugin system).
     * If the registry doesn't have the action or creation fails, falls back
     * to the legacy switch statement for backward compatibility.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown action type
     */
    private BaseAction createAction(Task task) {
        String actionType = task.getAction();

        // Try registry-based creation first (plugin architecture)
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, steve, task, actionContext);
            if (action != null) {
                SteveMod.LOGGER.debug("Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }

        // Fallback to legacy switch statement for backward compatibility
        SteveMod.LOGGER.debug("Using legacy fallback for action: {}", actionType);
        return createActionLegacy(task);
    }

    /**
     * Legacy action creation using switch statement.
     *
     * <p>Kept for backward compatibility during migration to plugin system.
     * Will be removed in a future version once all actions are registered
     * via plugins.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown
     * @deprecated Use ActionRegistry instead
     */
    @Deprecated
    private BaseAction createActionLegacy(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine" -> new MineBlockAction(steve, task);
            case "place" -> new PlaceBlockAction(steve, task);
            case "craft" -> new CraftItemAction(steve, task);
            case "smelt" -> new SmeltItemAction(steve, task);
            case "attack" -> new CombatAction(steve, task);
            case "feed" -> new FeedAnimalAction(steve, task);
            case "follow" -> new FollowPlayerAction(steve, task);
            case "gather" -> new GatherResourceAction(steve, task);
            case "farm" -> new FarmCropAction(steve, task);
            case "retrieve_chest" -> new RetrieveFromChestAction(steve, task);
            case "build" -> new BuildStructureAction(steve, task);
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    public void stopCurrentAction() {
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        taskQueue.clear();
        currentGoal = null;
        clearTaskTree();
        resetLoopGuards();

        // Reset state machine
        stateMachine.reset();
    }

    public boolean isExecuting() {
        return currentAction != null || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Returns the event bus for subscribing to action events.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the agent state machine.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for adding custom interceptors.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Returns the action context.
     *
     * @return ActionContext instance
     */
    public ActionContext getActionContext() {
        return actionContext;
    }

    public SafetySnapshot getCurrentSafetySnapshot() {
        if (steve.level().isClientSide()) {
            return SafetySnapshot.safe();
        }
        return safetyEvaluatorManager.evaluate(steve, actionContext);
    }

    public SafetySnapshot sampleSafetySnapshot(String actionType) {
        if (steve.level().isClientSide()) {
            return SafetySnapshot.safe();
        }
        String safeType = (actionType == null || actionType.isBlank()) ? "background" : actionType;
        SafetySnapshot snapshot = safetyEvaluatorManager.evaluate(steve, actionContext);
        SafetyDecision decision = safetyEvaluatorManager.recommend(snapshot, safeType);
        logSafetySnapshotIfChanged(snapshot, decision, safeType);
        return snapshot;
    }

    /**
     * Checks if the agent is currently planning (async LLM call in progress).
     *
     * @return true if planning
     */
    public boolean isPlanning() {
        return isPlanning;
    }

    public String getTaskStatusSummaryForUi() {
        List<String> lines = new ArrayList<>();

        if (isPlanning) {
            String cmd = pendingCommand == null || pendingCommand.isBlank() ? "..." : pendingCommand;
            lines.add("[PLANNING] " + truncateUi(cmd, 80));
        }

        if (currentAction != null) {
            lines.add("[RUNNING] " + truncateUi(currentAction.getDescription(), 80));
        }

        if (currentGoal != null && !currentGoal.isBlank()) {
            lines.add("[GOAL] " + truncateUi(currentGoal, 80));
        }

        int queued = 0;
        for (Task queuedTask : taskQueue) {
            if (queuedTask == null) {
                continue;
            }
            lines.add("[QUEUED] " + taskLabelForUi(queuedTask));
            queued++;
            if (queued >= 8) {
                break;
            }
        }
        if (taskQueue.size() > queued) {
            lines.add("[QUEUED] +" + (taskQueue.size() - queued) + " more");
        }

        int nodeLines = 0;
        for (TaskTreeNode node : taskTree.values()) {
            if (node == null || node.status == TaskTreeStatus.COMPLETED) {
                continue;
            }
            if (node.queued && node.status == TaskTreeStatus.PENDING) {
                continue;
            }
            lines.add("[" + nodeStatusLabel(node) + "] " + nodeLabelForUi(node));
            nodeLines++;
            if (nodeLines >= 8) {
                break;
            }
        }

        if (lines.isEmpty()) {
            return "No active tasks";
        }
        return String.join("\n", lines);
    }

    public void registerBehavior(BehaviorDefinition behavior) {
        if (behavior == null || behavior.id() == null || behavior.id().isBlank()) {
            return;
        }
        knownBehaviors.put(behavior.id(), behavior);
        if (!disabledBehaviors.contains(behavior.id())) {
            behaviorRegistry.register(behavior);
        }
    }

    public void unregisterBehavior(String behaviorId) {
        if (behaviorId == null || behaviorId.isBlank()) {
            return;
        }
        disabledBehaviors.remove(behaviorId);
        knownBehaviors.remove(behaviorId);
        behaviorRegistry.unregister(behaviorId);
    }

    public boolean setBehaviorEnabled(String behaviorId, boolean enabled) {
        if (behaviorId == null || behaviorId.isBlank()) {
            return false;
        }
        BehaviorDefinition behavior = knownBehaviors.get(behaviorId);
        if (behavior == null) {
            return false;
        }
        if (enabled) {
            disabledBehaviors.remove(behaviorId);
            behaviorRegistry.register(behavior);
        } else {
            disabledBehaviors.add(behaviorId);
            behaviorRegistry.unregister(behaviorId);
        }
        return true;
    }

    public List<BehaviorStatus> getBehaviorStatuses() {
        List<BehaviorStatus> statuses = new ArrayList<>();
        for (BehaviorDefinition behavior : knownBehaviors.values()) {
            boolean enabled = !disabledBehaviors.contains(behavior.id());
            statuses.add(new BehaviorStatus(
                behavior.id(),
                behavior.lane(),
                behavior.lanePriority(),
                behavior.priority(),
                behavior.cooldownTicks(),
                behavior.budgetCost(),
                enabled
            ));
        }
        statuses.sort(java.util.Comparator
            .comparingInt(BehaviorStatus::lanePriority)
            .thenComparing(BehaviorStatus::lane)
            .thenComparingInt(BehaviorStatus::priority)
            .thenComparing(BehaviorStatus::id));
        return statuses;
    }

    public java.util.Set<String> getKnownBehaviorIds() {
        return Collections.unmodifiableSet(knownBehaviors.keySet());
    }

    public void enqueueTask(Task task) {
        tryEnqueueTask(task);
    }

    public boolean tryEnqueueTask(Task task) {
        if (task == null) {
            return false;
        }
        Task tagged = withTaskTreeMetadata(task);
        if (shouldBlockEnqueue(tagged)) {
            SteveMod.LOGGER.warn(
                "[TASK_GUARD] Steve '{}' blocked repeated task enqueue: {} {}",
                steve.getSteveName(),
                tagged.getAction(),
                tagged.getParameters()
            );
            return false;
        }
        taskQueue.add(tagged);
        rememberTaskSignature(tagged);
        markTaskNodeQueued(tagged);
        return true;
    }

    public void enqueueTasks(java.util.List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (Task task : tasks) {
            enqueueTask(task);
        }
    }

    public boolean allowActionTick(BaseAction action) {
        if (steve.level().isClientSide()) {
            return true;
        }

        String actionType = actionTypeOf(action);
        SafetySnapshot snapshot = safetyEvaluatorManager.evaluate(steve, actionContext);
        SafetyDecision decision = safetyEvaluatorManager.recommend(snapshot, actionType);
        logSafetySnapshotIfChanged(snapshot, decision, actionType);

        if (safetyRecoveryActive && snapshot.isSafe()) {
            safetyRecoveryActive = false;
            SteveMod.LOGGER.info("[SAFETY] Steve '{}' recovered (score={}, state={})",
                steve.getSteveName(), snapshot.safetyScore(), snapshot.state());
        }

        if (decision != SafetyDecision.RETREAT) {
            if (steve.tryAutoEatIfNeeded(snapshot.isSafe())) {
                SteveMod.LOGGER.info("[FOOD] Steve '{}' auto-ate food (food={}, sat={})",
                    steve.getSteveName(),
                    steve.getFoodLevel(),
                    steve.getSaturationLevel());
                updateDebugStatus("Eating (food " + steve.getFoodLevel() + ")");
                return false;
            }
            maybeQueueFoodRecovery(snapshot);
            return true;
        }

        // Enforce combat rule and panic rule: high/critical panic retreats before risky actions.
        boolean panicForcesRetreat = snapshot.panicLevel() == PanicLevel.HIGH || snapshot.panicLevel() == PanicLevel.CRITICAL;
        if ("attack".equals(actionType) || snapshot.state() == SafetyState.CRITICAL || snapshot.state() == SafetyState.DANGER || panicForcesRetreat) {
            initiateRetreat(snapshot, actionType);
            return false;
        }
        return true;
    }

    private void runPassiveBehaviors() {
        if (steve.level().isClientSide()) {
            return;
        }
        BehaviorContext behaviorContext = new BehaviorContext(
            steve,
            this,
            steve.level().getGameTime()
        );
        behaviorScheduler.tick(behaviorContext, PASSIVE_BEHAVIOR_TICK_BUDGET);
    }

    private void registerDefaultBehaviors() {
        registerBehavior(new PassiveSafetyPulseBehavior());
        registerBehavior(new PassiveVisibleScanBehavior());
        registerBehavior(new PassiveChestScanBehavior());
    }

    public record BehaviorStatus(
        String id,
        String lane,
        int lanePriority,
        int priority,
        int cooldownTicks,
        int budgetCost,
        boolean enabled
    ) {}

    private void logSafetySnapshotIfChanged(SafetySnapshot snapshot, SafetyDecision decision, String actionType) {
        if (snapshot == null || decision == null) {
            return;
        }
        boolean changed = snapshot.state() != lastLoggedSafetyState
            || snapshot.panicLevel() != lastLoggedPanicLevel
            || decision != lastLoggedSafetyDecision;
        if (!changed) {
            return;
        }
        lastLoggedSafetyState = snapshot.state();
        lastLoggedPanicLevel = snapshot.panicLevel();
        lastLoggedSafetyDecision = decision;
        String reasons = snapshot.reasons().isEmpty() ? "none" : String.join(",", snapshot.reasons());
        SteveMod.LOGGER.info(
            "[SAFETY] Steve '{}' action={} state={} score={} panic={}:{} decision={} reasons={}",
            steve.getSteveName(),
            actionType,
            snapshot.state(),
            snapshot.safetyScore(),
            snapshot.panicLevel(),
            snapshot.panicScore(),
            decision,
            reasons
        );
    }

    private void initiateRetreat(SafetySnapshot snapshot, String actionType) {
        if (safetyActionCooldownTicks > 0) {
            return;
        }
        safetyActionCooldownTicks = 20;
        safetyRecoveryActive = true;

        if (currentAction != null && !(currentAction instanceof PathfindAction)) {
            currentAction.cancel();
            currentAction = null;
        }

        BlockPos retreatPos = snapshot.retreatTarget() != null ? snapshot.retreatTarget() : steve.blockPosition();
        Task retreatTask = createRetreatTask(retreatPos);
        taskQueue.addFirst(retreatTask);

        String reason = snapshot.reasons().isEmpty() ? "unsafe-state" : snapshot.reasons().get(0);
        SteveMod.LOGGER.warn(
            "[SAFETY] Steve '{}' retreating before '{}': state={}, score={}, reason={}, target={}",
            steve.getSteveName(),
            actionType,
            snapshot.state(),
            snapshot.safetyScore(),
            reason,
            retreatPos
        );
        updateDebugStatus("Safety retreat: " + snapshot.state() + "/" + snapshot.panicLevel() + " (" + snapshot.safetyScore() + ")");
        sendToGUI(steve.getSteveName(), "Unsafe right now, retreating to stabilize.");
    }

    private Task createRetreatTask(BlockPos retreatPos) {
        Map<String, Object> params = new HashMap<>();
        params.put("x", retreatPos.getX());
        params.put("y", retreatPos.getY());
        params.put("z", retreatPos.getZ());
        return new Task("pathfind", params);
    }

    private String actionTypeOf(BaseAction action) {
        if (action == null || action.getTask() == null) {
            return "idle";
        }
        String taskAction = action.getTask().getAction();
        return taskAction == null ? "unknown" : taskAction.toLowerCase();
    }

    private Task withTaskTreeMetadata(Task task) {
        if (task == null) {
            return null;
        }
        Map<String, Object> src = task.getParameters();
        Map<String, Object> params = new HashMap<>();
        if (src != null) {
            params.putAll(src);
        }

        String nodeId = stringParam(params.get(META_NODE_ID));
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = "n" + (++taskNodeSeq);
            params.put(META_NODE_ID, nodeId);
        }
        String parentId = stringParam(params.get(META_PARENT_ID));
        if ((parentId == null || parentId.isBlank()) && activeTaskNodeId != null && !activeTaskNodeId.isBlank()) {
            parentId = activeTaskNodeId;
            params.put(META_PARENT_ID, parentId);
        }

        registerTaskTreeNode(nodeId, parentId, task.getAction(), params);
        return new Task(task.getAction(), params);
    }

    private void registerTaskTreeNode(String nodeId, String parentId, String action, Map<String, Object> params) {
        TaskTreeNode existing = taskTree.get(nodeId);
        if (existing != null) {
            return;
        }
        TaskTreeNode node = new TaskTreeNode(nodeId, parentId, action, sanitizeTaskParams(params));
        taskTree.put(nodeId, node);
    }

    private void markTaskNodeQueued(Task task) {
        TaskTreeNode node = nodeOf(task);
        if (node == null) {
            return;
        }
        node.status = TaskTreeStatus.PENDING;
        node.queued = true;
    }

    private void markTaskNodeDequeued(Task task) {
        TaskTreeNode node = nodeOf(task);
        if (node == null) {
            return;
        }
        node.queued = false;
    }

    private void markTaskNodeInProgress(Task task) {
        TaskTreeNode node = nodeOf(task);
        if (node == null) {
            return;
        }
        node.status = TaskTreeStatus.IN_PROGRESS;
        node.queued = false;
    }

    private void markTaskNodeFinished(String nodeId, boolean success) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        TaskTreeNode node = taskTree.get(nodeId);
        if (node == null) {
            return;
        }
        node.status = success ? TaskTreeStatus.COMPLETED : TaskTreeStatus.FAILED;
        // Craft actions can return "success" for legal intermediate steps
        // (e.g., move to station or queue subplan). Keep the node pending until
        // the expected crafted item is actually present.
        if (success && "craft".equalsIgnoreCase(node.action) && !isTaskNodeSatisfied(node)) {
            node.status = TaskTreeStatus.PENDING;
        }
        node.queued = false;
    }

    private boolean tryResumeFromTaskTree() {
        pruneSatisfiedTaskNodes();

        TaskTreeNode candidate = null;
        for (TaskTreeNode node : taskTree.values()) {
            if (node.queued) {
                continue;
            }
            if (node.status == TaskTreeStatus.FAILED && node.retries < MAX_TASK_TREE_RETRIES) {
                candidate = node;
                break;
            }
        }
        if (candidate == null) {
            for (TaskTreeNode node : taskTree.values()) {
                if (node.queued) {
                    continue;
                }
                if (node.status == TaskTreeStatus.PENDING) {
                    candidate = node;
                    break;
                }
            }
        }
        if (candidate == null) {
            return false;
        }

        Task retry = recreateTaskFromNode(candidate);
        if (retry == null) {
            return false;
        }
        if (candidate.status == TaskTreeStatus.FAILED) {
            candidate.retries++;
        }
        taskQueue.addFirst(retry);
        candidate.status = TaskTreeStatus.PENDING;
        candidate.queued = true;
        SteveMod.LOGGER.info(
            "[GOAL] Steve '{}' resumed task node={} action={} retry={}/{} goal='{}'",
            steve.getSteveName(),
            candidate.nodeId,
            candidate.action,
            candidate.retries,
            MAX_TASK_TREE_RETRIES,
            currentGoal
        );
        return true;
    }

    private void pruneSatisfiedTaskNodes() {
        for (TaskTreeNode node : taskTree.values()) {
            if (node == null || node.status != TaskTreeStatus.PENDING || node.queued) {
                continue;
            }
            if (!isTaskNodeSatisfied(node)) {
                continue;
            }
            node.status = TaskTreeStatus.COMPLETED;
            node.queued = false;
            SteveMod.LOGGER.info(
                "[GOAL] Steve '{}' auto-completed satisfied node={} action={}",
                steve.getSteveName(),
                node.nodeId,
                node.action
            );
        }
    }

    private boolean isTaskNodeSatisfied(TaskTreeNode node) {
        if (node == null || node.action == null) {
            return false;
        }
        String action = node.action.toLowerCase();
        if (!"craft".equals(action)) {
            return false;
        }
        Object rawItem = node.params.get("item");
        if (rawItem == null) {
            return false;
        }
        String itemName = rawItem.toString().trim();
        if (itemName.isEmpty()) {
            return false;
        }
        String normalized = itemName.contains(":") ? itemName : "minecraft:" + itemName;
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) {
            return false;
        }
        var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            return false;
        }
        int required = parsePositive(node.params.get("quantity"), 1);
        return steve.getItemCount(item) >= Math.max(1, required);
    }

    private int parsePositive(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.toString().trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Task recreateTaskFromNode(TaskTreeNode node) {
        if (node == null || node.action == null || node.action.isBlank()) {
            return null;
        }
        Map<String, Object> params = new HashMap<>(node.params);
        params.put(META_NODE_ID, node.nodeId);
        if (node.parentId != null && !node.parentId.isBlank()) {
            params.put(META_PARENT_ID, node.parentId);
        }
        return new Task(node.action, params);
    }

    private void clearTaskTree() {
        taskTree.clear();
        activeTaskNodeId = null;
        taskNodeSeq = 0L;
    }

    private TaskTreeNode nodeOf(Task task) {
        String nodeId = nodeIdOf(task);
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        return taskTree.get(nodeId);
    }

    private String nodeIdOf(Task task) {
        if (task == null || task.getParameters() == null) {
            return null;
        }
        return stringParam(task.getParameters().get(META_NODE_ID));
    }

    private String parentNodeIdOf(Task task) {
        if (task == null || task.getParameters() == null) {
            return null;
        }
        return stringParam(task.getParameters().get(META_PARENT_ID));
    }

    private String stringParam(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Map<String, Object> sanitizeTaskParams(Map<String, Object> params) {
        Map<String, Object> copy = new HashMap<>();
        if (params == null || params.isEmpty()) {
            return copy;
        }
        copy.putAll(params);
        copy.remove(META_NODE_ID);
        copy.remove(META_PARENT_ID);
        return copy;
    }

    private void maybeQueueFoodRecovery(SafetySnapshot snapshot) {
        if (foodRecoveryCooldownTicks > 0) {
            return;
        }
        if (steve.getFoodLevel() > 10) {
            return;
        }
        if (steve.hasEdibleFood(false) || steve.hasEdibleFood(true) && steve.getFoodLevel() <= 4) {
            return;
        }
        if (snapshot.state() == SafetyState.DANGER || snapshot.state() == SafetyState.CRITICAL) {
            return;
        }

        boolean emergency = steve.getFoodLevel() <= 4;
        int chestRadius = KnownResourceManager.configuredChestRadius();
        if (steve.tryAcquireFoodFromKnownChests(chestRadius, emergency)) {
            SteveMod.LOGGER.info("[FOOD] Steve '{}' withdrew food from known chest", steve.getSteveName());
            foodRecoveryCooldownTicks = 60;
            return;
        }
        if (chestScanCooldownTicks <= 0) {
            steve.scanNearbyChests(chestRadius);
            chestScanCooldownTicks = 100;
            if (steve.tryAcquireFoodFromKnownChests(chestRadius, emergency)) {
                SteveMod.LOGGER.info("[FOOD] Steve '{}' found food in newly scanned chest", steve.getSteveName());
                foodRecoveryCooldownTicks = 60;
                return;
            }
        }

        if (hasPendingAction("gather") || hasPendingAction("craft") || hasPendingAction("smelt")) {
            return;
        }

        enqueueFoodRecoveryTasks();
        foodRecoveryCooldownTicks = 200;
        updateDebugStatus("Seeking food");
        sendToGUI(steve.getSteveName(), "Low food and no edibles. Gathering food resources.");
        SteveMod.LOGGER.info("[FOOD] Steve '{}' queued food recovery tasks", steve.getSteveName());
    }

    private boolean hasPendingAction(String actionName) {
        for (Task queued : taskQueue) {
            if (queued != null && actionName.equalsIgnoreCase(queued.getAction())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasQueuedTask(String actionName, String parameterKey, String parameterValue) {
        if (actionName == null || actionName.isBlank()) {
            return false;
        }
        String expectedAction = actionName.trim().toLowerCase();
        String expectedValue = parameterValue == null ? null : parameterValue.trim().toLowerCase();
        for (Task queued : taskQueue) {
            if (queued == null || queued.getAction() == null) {
                continue;
            }
            if (!expectedAction.equals(queued.getAction().trim().toLowerCase())) {
                continue;
            }
            if (parameterKey == null || parameterKey.isBlank()) {
                return true;
            }
            Object raw = queued.getParameters().get(parameterKey);
            if (raw == null) {
                continue;
            }
            String actualValue = raw.toString().trim().toLowerCase();
            if (expectedValue == null || expectedValue.equals(actualValue)) {
                return true;
            }
        }
        return false;
    }

    private void enqueueFoodRecoveryTasks() {
        java.util.List<Task> fallbackTasks = FoodTargetResolver.buildFallbackFoodTasks();
        for (int i = fallbackTasks.size() - 1; i >= 0; i--) {
            taskQueue.addFirst(fallbackTasks.get(i));
        }
    }

    private void updateDebugStatus(String status) {
        if (status == null || status.isBlank()) {
            status = "Idle";
        }
        if (status.length() > 120) {
            status = status.substring(0, 117) + "...";
        }
        if (status.equals(lastDebugStatus)) {
            return;
        }
        lastDebugStatus = status;
        steve.setDebugStatus(status);
    }

    private boolean shouldBlockEnqueue(Task task) {
        String action = task.getAction() == null ? "" : task.getAction().toLowerCase();
        if (!"gather".equals(action) && !"craft".equals(action) && !"smelt".equals(action) && !"pathfind".equals(action)) {
            return false;
        }
        String signature = taskSignature(task);
        boolean sameQueued = hasQueuedTaskSignature(signature);
        Integer failures = failedTaskAttempts.get(signature);
        if ("pathfind".equals(action)) {
            if (failures != null && failures >= MAX_PATHFIND_REPEAT_GUARD) {
                return true;
            }
        } else if (failures != null && failures >= MAX_FAILED_TASK_ATTEMPTS && sameQueued) {
            return true;
        }
        if ("craft".equals(action)) {
            String craftItemKey = craftItemKey(task);
            if (craftItemKey != null) {
                Integer craftFailures = failedCraftAttemptsByItem.get(craftItemKey);
                if (craftFailures != null && craftFailures >= MAX_FAILED_CRAFT_ATTEMPTS_PER_ITEM && sameQueued) {
                    return true;
                }
            }
        }
        int repeats = 0;
        int repeatGuard = "pathfind".equals(action) ? MAX_PATHFIND_REPEAT_GUARD : MAX_REPEAT_GUARD;
        for (String recent : recentTaskSignatures) {
            if (signature.equals(recent)) {
                repeats++;
            }
        }
        if ("pathfind".equals(action)) {
            return repeats >= repeatGuard;
        }
        return repeats >= repeatGuard && sameQueued;
    }

    private boolean hasQueuedTaskSignature(String signature) {
        if (signature == null || signature.isBlank() || taskQueue.isEmpty()) {
            return false;
        }
        for (Task queued : taskQueue) {
            if (queued == null) {
                continue;
            }
            if (signature.equals(taskSignature(queued))) {
                return true;
            }
        }
        return false;
    }

    private void maybeQueueFailureRecovery(Task failedTask, ActionResult result) {
        if (failedTask == null || result == null || result.isSuccess()) {
            return;
        }
        String message = result.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        String normalized = message.trim().toLowerCase();
        if (!normalized.startsWith("missing tool:")) {
            return;
        }
        String rawTool = normalized.substring("missing tool:".length()).trim();
        if (rawTool.isEmpty()) {
            return;
        }
        String toolItemId = normalizeMissingToolToItemId(rawTool);
        if (toolItemId == null) {
            return;
        }
        String requiredToolPath = pathOf(toolItemId);
        boolean alreadyHasTool = alreadyHasToolForRecovery(toolItemId);
        boolean hasQueuedToolCraft = hasQueuedTask("craft", "item", requiredToolPath);
        boolean queuedToolCraftNow = false;

        if (!alreadyHasTool && !hasQueuedToolCraft) {
            Map<String, Object> recoveryParams = new HashMap<>();
            recoveryParams.put("item", requiredToolPath);
            recoveryParams.put("quantity", 1);
            Task recoveryTask = withTaskTreeMetadata(new Task("craft", recoveryParams));
            if (recoveryTask != null && !shouldBlockEnqueue(recoveryTask)) {
                taskQueue.addFirst(recoveryTask);
                rememberTaskSignature(recoveryTask);
                markTaskNodeQueued(recoveryTask);
                queuedToolCraftNow = true;
                SteveMod.LOGGER.info(
                    "[RECOVERY] Steve '{}' queued missing-tool recovery: craft {} (from failure '{}')",
                    steve.getSteveName(),
                    toolItemId,
                    message
                );
            }
        }

        boolean toolWillBeAvailable = alreadyHasTool || hasQueuedToolCraft || queuedToolCraftNow;
        if (!toolWillBeAvailable) {
            return;
        }

        Task retryTask = buildToolRecoveryRetryTask(failedTask);
        if (retryTask == null || shouldBlockEnqueue(retryTask)) {
            return;
        }

        if (alreadyHasTool) {
            taskQueue.addFirst(retryTask);
        } else {
            taskQueue.addLast(retryTask);
        }
        rememberTaskSignature(retryTask);
        markTaskNodeQueued(retryTask);
        SteveMod.LOGGER.info(
            "[RECOVERY] Steve '{}' queued post-tool retry for {} {}",
            steve.getSteveName(),
            retryTask.getAction(),
            retryTask.getParameters()
        );
    }

    private Task buildToolRecoveryRetryTask(Task failedTask) {
        if (failedTask == null || failedTask.getAction() == null) {
            return null;
        }
        int currentRetry = toolRecoveryRetryCount(failedTask);
        if (currentRetry >= MAX_TOOL_RECOVERY_RETRIES) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        if (failedTask.getParameters() != null) {
            params.putAll(failedTask.getParameters());
        }
        params.remove(META_NODE_ID);
        params.put(META_TOOL_RECOVERY_RETRY, currentRetry + 1);
        return withTaskTreeMetadata(new Task(failedTask.getAction(), params));
    }

    private int toolRecoveryRetryCount(Task task) {
        if (task == null || task.getParameters() == null) {
            return 0;
        }
        Object raw = task.getParameters().get(META_TOOL_RECOVERY_RETRY);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String normalizeMissingToolToItemId(String rawTool) {
        if (rawTool == null || rawTool.isBlank()) {
            return null;
        }
        String tool = rawTool.trim().toLowerCase();
        if (tool.startsWith("minecraft:")) {
            return tool;
        }
        return switch (tool) {
            case "pickaxe" -> "minecraft:wooden_pickaxe";
            case "axe" -> "minecraft:wooden_axe";
            case "shovel" -> "minecraft:wooden_shovel";
            case "hoe" -> "minecraft:wooden_hoe";
            case "wood_pickaxe" -> "minecraft:wooden_pickaxe";
            default -> tool.contains(":") ? tool : "minecraft:" + tool;
        };
    }

    private boolean alreadyHasToolForRecovery(String toolItemId) {
        String normalized = toolItemId == null ? "" : toolItemId.trim().toLowerCase();
        if (normalized.endsWith("_pickaxe")) {
            return steve.hasPickaxeAtLeast(ToolCapabilityMap.ToolTier.detectPickaxeTier(normalized));
        }
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item != null && steve.getItemCount(item) > 0;
    }

    private String pathOf(String namespacedId) {
        if (namespacedId == null) {
            return null;
        }
        int idx = namespacedId.indexOf(':');
        if (idx < 0 || idx >= namespacedId.length() - 1) {
            return namespacedId;
        }
        return namespacedId.substring(idx + 1);
    }

    private void finalizeGoalIfExhausted() {
        if (taskTree.isEmpty()) {
            return;
        }
        boolean hasRunnable = false;
        boolean hasPendingOrQueued = false;
        boolean allCompleted = true;
        for (TaskTreeNode node : taskTree.values()) {
            if (node == null) {
                continue;
            }
            if (node.status != TaskTreeStatus.COMPLETED) {
                allCompleted = false;
            }
            if (node.queued || node.status == TaskTreeStatus.PENDING || node.status == TaskTreeStatus.IN_PROGRESS) {
                hasPendingOrQueued = true;
            }
            if (!node.queued && (node.status == TaskTreeStatus.PENDING
                || (node.status == TaskTreeStatus.FAILED && node.retries < MAX_TASK_TREE_RETRIES))) {
                hasRunnable = true;
            }
        }
        if (hasRunnable || hasPendingOrQueued) {
            return;
        }

        String finishedGoal = (currentGoal == null || currentGoal.isBlank()) ? "direct task plan" : currentGoal;
        if (allCompleted) {
            SteveMod.LOGGER.info("[GOAL] Steve '{}' completed goal '{}'", steve.getSteveName(), finishedGoal);
            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "Goal complete: " + finishedGoal);
            }
            updateDebugStatus("Goal complete");
        } else {
            SteveMod.LOGGER.warn("[GOAL] Steve '{}' exhausted goal '{}' with no runnable tasks", steve.getSteveName(), finishedGoal);
            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "I got stuck on this goal and need a new instruction.");
            }
            updateDebugStatus("Goal stuck");
        }
        currentGoal = null;
        steve.getMemory().setCurrentGoal(null);
        clearTaskTree();
    }

    private void rememberTaskSignature(Task task) {
        String signature = taskSignature(task);
        recentTaskSignatures.addLast(signature);
        while (recentTaskSignatures.size() > MAX_RECENT_TASK_SIGNATURES) {
            recentTaskSignatures.removeFirst();
        }
    }

    private void recordTaskOutcome(Task task, boolean success) {
        if (task == null) {
            return;
        }
        String signature = taskSignature(task);
        if (success) {
            failedTaskAttempts.remove(signature);
            if ("craft".equalsIgnoreCase(task.getAction())) {
                String craftItemKey = craftItemKey(task);
                if (craftItemKey != null) {
                    failedCraftAttemptsByItem.remove(craftItemKey);
                }
            }
            return;
        }

        failedTaskAttempts.merge(signature, 1, Integer::sum);
        if ("craft".equalsIgnoreCase(task.getAction())) {
            String craftItemKey = craftItemKey(task);
            if (craftItemKey != null) {
                int attempts = failedCraftAttemptsByItem.merge(craftItemKey, 1, Integer::sum);
                if (attempts >= MAX_FAILED_CRAFT_ATTEMPTS_PER_ITEM) {
                    SteveMod.LOGGER.warn(
                        "[TASK_GUARD] Steve '{}' craft capped after {} failures for {}",
                        steve.getSteveName(),
                        attempts,
                        craftItemKey
                    );
                }
            }
        }
    }

    private void resetLoopGuards() {
        recentTaskSignatures.clear();
        failedTaskAttempts.clear();
        failedCraftAttemptsByItem.clear();
    }

    private boolean expandTaskDynamically(Task task) {
        if (task == null || task.getAction() == null || expansionRules.isEmpty()) {
            return false;
        }
        for (TaskExpansionRule rule : expansionRules) {
            try {
                if (rule.expand(task)) {
                    return true;
                }
            } catch (Exception ex) {
                SteveMod.LOGGER.warn(
                    "[DYNAMIC_EXPAND] Steve '{}' rule failure for task {}",
                    steve.getSteveName(),
                    task,
                    ex
                );
            }
        }
        return false;
    }

    private boolean expandMineToolPrerequisites(Task task) {
        if (task == null || task.getAction() == null || !"mine".equalsIgnoreCase(task.getAction())) {
            return false;
        }
        if (dynamicDeferralCount(task) >= MAX_DYNAMIC_DEFERRALS_PER_TASK) {
            return false;
        }

        String blockRaw = firstNonBlank(
            task.getStringParameter("block"),
            task.getStringParameter("blockType"),
            task.getStringParameter("resource")
        );
        if (blockRaw == null || blockRaw.isBlank()) {
            return false;
        }
        Block targetBlock = ActionUtils.parseBlock(blockRaw);
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            return false;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(targetBlock).toString();
        ToolCapabilityMap.ToolRequirement requirement = ToolCapabilityMap.getRequirement(blockId);
        if (requirement == null || requirement.required() == ToolCapabilityMap.ToolType.NONE) {
            return false;
        }

        String requiredToolItemId = requiredToolItemId(requirement);
        if (requiredToolItemId == null || requiredToolItemId.isBlank()) {
            return false;
        }
        if (alreadyHasToolForRecovery(requiredToolItemId)) {
            return false;
        }
        String requiredToolPath = pathOf(requiredToolItemId);
        if (requiredToolPath == null || requiredToolPath.isBlank()) {
            return false;
        }
        if (hasQueuedTask("craft", "item", requiredToolPath)) {
            return false;
        }

        Task deferred = incrementDynamicDefers(task);
        Task craftTool = new Task("craft", Map.of(
            "item", requiredToolPath,
            "quantity", 1
        ));

        enqueueDerivedFront(deferred, task);
        enqueueDerivedFront(craftTool, task);

        SteveMod.LOGGER.info(
            "[DYNAMIC_EXPAND] Steve '{}' injected prerequisite craft {} before mine {}",
            steve.getSteveName(),
            requiredToolItemId,
            blockId
        );
        return true;
    }

    private String requiredToolItemId(ToolCapabilityMap.ToolRequirement requirement) {
        if (requirement == null) {
            return null;
        }
        ToolCapabilityMap.ToolType toolType = requirement.required();
        return switch (toolType) {
            case PICKAXE -> {
                ToolCapabilityMap.ToolTier tier = requirement.minTier() == null
                    ? ToolCapabilityMap.ToolTier.WOOD
                    : requirement.minTier();
                if (tier == ToolCapabilityMap.ToolTier.NONE) {
                    tier = ToolCapabilityMap.ToolTier.WOOD;
                }
                yield "minecraft:" + tier.label() + "_pickaxe";
            }
            case AXE -> "minecraft:wooden_axe";
            case SHOVEL -> "minecraft:wooden_shovel";
            case HOE -> "minecraft:wooden_hoe";
            default -> null;
        };
    }

    private void enqueueDerivedFront(Task derived, Task parentTask) {
        if (derived == null) {
            return;
        }
        Task tagged = withParentMetadata(derived, nodeIdOf(parentTask));
        if (tagged == null) {
            return;
        }
        if (shouldBlockEnqueue(tagged)) {
            SteveMod.LOGGER.warn(
                "[DYNAMIC_EXPAND] Steve '{}' blocked derived task {} {}",
                steve.getSteveName(),
                tagged.getAction(),
                tagged.getParameters()
            );
            return;
        }
        taskQueue.addFirst(tagged);
        rememberTaskSignature(tagged);
        markTaskNodeQueued(tagged);
    }

    private Task withParentMetadata(Task task, String parentNodeId) {
        if (task == null) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        if (task.getParameters() != null) {
            params.putAll(task.getParameters());
        }
        if (parentNodeId != null && !parentNodeId.isBlank() && !params.containsKey(META_PARENT_ID)) {
            params.put(META_PARENT_ID, parentNodeId);
        }
        return withTaskTreeMetadata(new Task(task.getAction(), params));
    }

    private Task incrementDynamicDefers(Task task) {
        Map<String, Object> params = new HashMap<>();
        if (task.getParameters() != null) {
            params.putAll(task.getParameters());
        }
        int next = dynamicDeferralCount(task) + 1;
        params.put(META_DYNAMIC_DEFERS, next);
        return new Task(task.getAction(), params);
    }

    private int dynamicDeferralCount(Task task) {
        if (task == null || task.getParameters() == null) {
            return 0;
        }
        Object raw = task.getParameters().get(META_DYNAMIC_DEFERS);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String taskSignature(Task task) {
        String action = task.getAction() == null ? "unknown" : task.getAction().toLowerCase();
        Map<String, Object> params = task.getParameters() == null ? Map.of() : task.getParameters();
        String core;
        if ("gather".equals(action)) {
            core = String.valueOf(params.getOrDefault("resource", "none"))
                + "|q=" + String.valueOf(params.getOrDefault("quantity", 0));
        } else if ("craft".equals(action) || "smelt".equals(action)) {
            core = String.valueOf(params.getOrDefault("item", "none"))
                + "|q=" + String.valueOf(params.getOrDefault("quantity", 0));
        } else if ("pathfind".equals(action)) {
            int x = parseInt(params.get("x"));
            int y = parseInt(params.get("y"));
            int z = parseInt(params.get("z"));
            core = "x=" + x + "|y=" + y + "|z=" + z;
        } else {
            core = String.valueOf(params);
        }
        return action + "|" + core;
    }

    private int parseInt(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(raw.toString().trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String craftItemKey(Task task) {
        if (task == null || task.getParameters() == null) {
            return null;
        }
        Object raw = task.getParameters().get("item");
        if (raw == null) {
            return null;
        }
        String normalized = raw.toString().trim().toLowerCase();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private String taskLabelForUi(Task task) {
        if (task == null) {
            return "unknown";
        }
        String action = task.getAction() == null ? "unknown" : task.getAction();
        Map<String, Object> params = task.getParameters();
        if (params == null || params.isEmpty()) {
            return truncateUi(action, 80);
        }
        Object item = firstPresent(params, "item", "block", "resource", "target", "entity");
        Object quantity = params.get("quantity");
        String detail = item == null ? params.toString() : String.valueOf(item);
        String suffix = quantity == null ? "" : " x" + quantity;
        return truncateUi(action + " " + detail + suffix, 80);
    }

    private String nodeLabelForUi(TaskTreeNode node) {
        if (node == null) {
            return "unknown";
        }
        Object item = firstPresent(node.params, "item", "block", "resource", "target", "entity");
        Object quantity = node.params.get("quantity");
        String detail = item == null ? node.params.toString() : String.valueOf(item);
        String suffix = quantity == null ? "" : " x" + quantity;
        return truncateUi(node.action + " " + detail + suffix, 80);
    }

    private String nodeStatusLabel(TaskTreeNode node) {
        if (node == null) {
            return "UNKNOWN";
        }
        return switch (node.status) {
            case PENDING -> node.queued ? "QUEUED" : "PENDING";
            case IN_PROGRESS -> "IN_PROGRESS";
            case COMPLETED -> "DONE";
            case FAILED -> "FAILED";
        };
    }

    private Object firstPresent(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = params.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String truncateUi(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (maxLen <= 3 || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 3) + "...";
    }

    private enum TaskTreeStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private static final class TaskTreeNode {
        private final String nodeId;
        private final String parentId;
        private final String action;
        private final Map<String, Object> params;
        private TaskTreeStatus status;
        private boolean queued;
        private int retries;

        private TaskTreeNode(String nodeId, String parentId, String action, Map<String, Object> params) {
            this.nodeId = nodeId;
            this.parentId = parentId;
            this.action = action;
            this.params = params == null ? Map.of() : new HashMap<>(params);
            this.status = TaskTreeStatus.PENDING;
            this.queued = false;
            this.retries = 0;
        }
    }

    @FunctionalInterface
    private interface TaskExpansionRule {
        boolean expand(Task task);
    }
}
