package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.animal.AnimalFoodResolver;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

public class FeedAnimalAction extends BaseAction {
    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_TICKS = 800;
    private static final int MAX_NO_PROGRESS_TICKS = 120;

    private String species;
    private int targetFeeds;
    private int feedsDone;
    private int ticksRunning;
    private int cooldownTicks;
    private int noProgressTicks;
    private final Set<UUID> fedAnimalIds = new HashSet<>();

    public FeedAnimalAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        species = task.getStringParameter("species", "any");
        targetFeeds = positiveOrDefault(task.getIntParameter("quantity", -1), task.getIntParameter("count", 1));
        feedsDone = 0;
        ticksRunning = 0;
        cooldownTicks = 0;
        noProgressTicks = 0;
        fedAnimalIds.clear();
        SteveMod.LOGGER.info(
            "[FEED] Steve '{}' start species={} targetFeeds={}",
            steve.getSteveName(),
            species,
            targetFeeds
        );
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            SteveMod.LOGGER.warn(
                "[FEED] Steve '{}' timeout species={} progress={}/{}",
                steve.getSteveName(),
                species,
                feedsDone,
                targetFeeds
            );
            if (feedsDone > 0) {
                result = ActionResult.success("Fed " + feedsDone + "/" + targetFeeds + " " + species + " before timeout");
            } else {
                result = ActionResult.failure("Feeding timeout for " + species + " (" + feedsDone + "/" + targetFeeds + ")");
            }
            return;
        }
        if (feedsDone >= targetFeeds) {
            result = ActionResult.success("Fed " + feedsDone + " " + species);
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        Animal target = findTargetAnimal();
        if (target == null) {
            noProgressTicks++;
            if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
                SteveMod.LOGGER.warn(
                    "[FEED] Steve '{}' no valid targets species={} progress={}/{}",
                    steve.getSteveName(),
                    species,
                    feedsDone,
                    targetFeeds
                );
                if (feedsDone > 0) {
                    result = ActionResult.success("Fed " + feedsDone + "/" + targetFeeds + " " + species + " (no more valid targets)");
                } else {
                    result = ActionResult.failure("No target animal found for species: " + species);
                }
            }
            return;
        }

        Item feedItem = selectFeedItem(target);
        if (feedItem == null) {
            SteveMod.LOGGER.warn(
                "[FEED] Steve '{}' no feed item species={} target={} progress={}/{}",
                steve.getSteveName(),
                species,
                target.getType().toString(),
                feedsDone,
                targetFeeds
            );
            result = ActionResult.failure("No preferred food available for species: " + species);
            return;
        }

        double distSqr = steve.distanceToSqr(target.getX(), target.getY(), target.getZ());
        if (distSqr > 9.0) {
            steve.getNavigation().moveTo(target, 1.1);
            return;
        }

        ItemStack single = steve.extractItem(feedItem, 1);
        if (single.isEmpty()) {
            SteveMod.LOGGER.warn(
                "[FEED] Steve '{}' failed to extract feed item={} species={} progress={}/{}",
                steve.getSteveName(),
                feedItem.toString(),
                species,
                feedsDone,
                targetFeeds
            );
            result = ActionResult.failure("Could not consume feed item: " + feedItem.toString());
            return;
        }

        boolean wasInLove = target.isInLove();
        // Playerless breeding trigger for autonomous behavior.
        target.setInLove(null);
        boolean nowInLove = target.isInLove();
        if (!wasInLove && !nowInLove) {
            noProgressTicks++;
            SteveMod.LOGGER.warn(
                "[FEED] Steve '{}' feed did not trigger love mode species={} target={} item={} stallTicks={}",
                steve.getSteveName(),
                species,
                target.getType().toString(),
                feedItem.toString(),
                noProgressTicks
            );
            if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
                result = ActionResult.failure("Could not trigger feeding state for " + species);
            }
            return;
        }
        fedAnimalIds.add(target.getUUID());
        feedsDone++;
        noProgressTicks = 0;
        cooldownTicks = 10;
        SteveMod.LOGGER.info(
            "[FEED] Steve '{}' fed target={} item={} progress={}/{}",
            steve.getSteveName(),
            target.getType().toString(),
            feedItem.toString(),
            feedsDone,
            targetFeeds
        );
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Feed " + targetFeeds + " " + species;
    }

    private Animal findTargetAnimal() {
        Vec3 pos = steve.position();
        AABB box = new AABB(
            pos.x - SEARCH_RADIUS, pos.y - 6, pos.z - SEARCH_RADIUS,
            pos.x + SEARCH_RADIUS, pos.y + 6, pos.z + SEARCH_RADIUS
        );
        List<Animal> nearby = steve.level().getEntitiesOfClass(Animal.class, box);
        List<Animal> candidates = new ArrayList<>();
        for (Animal animal : nearby) {
            if (animal == null || !animal.isAlive() || animal.isBaby()) {
                continue;
            }
            if (fedAnimalIds.contains(animal.getUUID())) {
                continue;
            }
            if (!AnimalFoodResolver.speciesMatches(animal, species)) {
                continue;
            }
            candidates.add(animal);
        }
        candidates.sort(Comparator.comparingDouble(steve::distanceToSqr));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private Item selectFeedItem(Animal animal) {
        for (Item preferred : AnimalFoodResolver.preferredFoods(species)) {
            if (steve.getItemCount(preferred) <= 0) {
                continue;
            }
            if (!AnimalFoodResolver.canFeed(animal, new ItemStack(preferred, 1))) {
                continue;
            }
            return preferred;
        }
        for (Item candidate : AnimalFoodResolver.allKnownFoodItems()) {
            if (candidate == null || steve.getItemCount(candidate) <= 0) {
                continue;
            }
            if (AnimalFoodResolver.canFeed(animal, new ItemStack(candidate, 1))) {
                return candidate;
            }
        }
        return null;
    }

    private int positiveOrDefault(int preferred, int fallback) {
        if (preferred > 0) {
            return preferred;
        }
        return Math.max(1, fallback);
    }
}
