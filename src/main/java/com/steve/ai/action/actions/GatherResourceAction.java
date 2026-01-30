package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

public class GatherResourceAction extends BaseAction {
    private String resourceType;
    private int quantity;

    public GatherResourceAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        resourceType = task.getStringParameter("resource");
        quantity = task.getIntParameter("quantity", 1);
        
        if (resourceType == null || resourceType.isBlank()) {
            result = ActionResult.failure("Missing resource type");
            return;
        }

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("block", resourceType);
        params.put("quantity", quantity);
        steve.getActionExecutor().enqueueTask(new com.steve.ai.action.Task("mine", params));
        result = ActionResult.success("Gathering " + quantity + " " + resourceType);
    }

    @Override
    protected void onTick() {
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Gather " + quantity + " " + resourceType;
    }
}
