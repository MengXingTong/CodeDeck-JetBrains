package com.codexgui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProviderSettingsCoordinatorTest {
    @Test
    void resolvesProviderModelFromLastSelectionSuggestionOrFirstItem() {
        var models = List.of("first-model", "suggested-model", "saved-model");

        assertEquals("saved-model", ProviderSettingsCoordinator.selectProviderModel(
            models, "saved-model", "suggested-model"));
        assertEquals("suggested-model", ProviderSettingsCoordinator.selectProviderModel(
            models, "missing-model", "suggested-model"));
        assertEquals("first-model", ProviderSettingsCoordinator.selectProviderModel(
            models, "missing-model", "missing-suggestion"));
        assertEquals("", ProviderSettingsCoordinator.selectProviderModel(
            List.of(), "saved-model", "suggested-model"));
    }
}
