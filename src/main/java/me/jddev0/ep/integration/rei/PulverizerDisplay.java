package me.jddev0.ep.integration.rei;

import me.jddev0.ep.recipe.PulverizerRecipe;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryIngredients;

import java.util.Arrays;
import java.util.List;

public record PulverizerDisplay(PulverizerRecipe recipe) implements Display {
    @Override
    public List<EntryIngredient> getInputEntries() {
        return List.of(
                EntryIngredients.ofIngredient(recipe.getInput())
        );
    }

    @Override
    public List<EntryIngredient> getOutputEntries() {
        return Arrays.stream(recipe.getMaxOutputCounts()).map(EntryIngredients::of).toList();
    }

    @Override
    public CategoryIdentifier<PulverizerDisplay> getCategoryIdentifier() {
        return PulverizerCategory.CATEGORY;
    }
}
