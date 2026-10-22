package com.codexgui.ui;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.conversation.SessionCoordinator;
import com.codexgui.provider.TurnEvent;
import com.codexgui.model.ChangeEntry;
import com.codexgui.service.ConversationChangeTracker;
import com.codexgui.service.CodexAppServerService;
import com.codexgui.service.FileReferenceText;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConversationCoordinatorTest {
    @TempDir Path root;

    @Test
    void completedFileChangesReadObjectKindsAndWholeFileContentsFromLiveAndHistoryItems() throws Exception {
        var header = root.resolve("JYInteractionPlacementHelper.h");
        var content = "#pragma once\nclass FJYInteractionPlacementHelper {};\n";
        Files.writeString(header, content);
        var updated = root.resolve("updated.cpp"); Files.writeString(updated, "after\n");
        var deleted = root.resolve("deleted.cpp");
        var constructor = ConversationChangeTracker.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        var tracker = constructor.newInstance(root);
        var registry = new SessionRegistry();
        var codex = new CodexAppServerService(null);
        try {
            var presenter = new BridgeEventPresenter(null, null, registry, root);
            var coordinator = new ConversationCoordinator(null, null, codex, null, null, null,
                tracker, null, registry, null, null, null, null, null, presenter, null);
            var item = JsonParser.parseString("""
                {"id":"patch","type":"fileChange","status":"completed","changes":[
                  {"path":"JYInteractionPlacementHelper.h","kind":{"type":"add"}},
                  {"path":"updated.cpp","kind":{"type":"update","move_path":null},"diff":"@@ -1 +1 @@\\n-before\\n+after\\n"},
                  {"path":"deleted.cpp","kind":{"type":"delete"},"diff":"deleted content\\n"}
                ]}
                """).getAsJsonObject();
            item.getAsJsonArray("changes").get(0).getAsJsonObject().addProperty("diff", content);
            var render = ConversationCoordinator.class.getDeclaredMethod("renderCompletedItem",
                com.codexgui.conversation.ConversationSession.class, JsonObject.class);
            render.setAccessible(true);
            render.invoke(coordinator, registry.active(), item);

            var sessionId = registry.activeSessionId().value();
            assertEquals(List.of(ChangeEntry.Kind.ADDED, ChangeEntry.Kind.MODIFIED, ChangeEntry.Kind.DELETED),
                tracker.listSummaries(sessionId).stream().map(ConversationChangeTracker.ChangeSummary::kind).toList());
            tracker.revertAll(sessionId);
            assertFalse(Files.exists(header));
            assertEquals("before\n", Files.readString(updated));
            assertEquals("deleted content\n", Files.readString(deleted));

            // A failed or still-proposed addition must not claim an existing user file.
            Files.writeString(header, "user file\n");
            for (var status : List.of("inProgress", "failed", "declined")) {
                item.addProperty("status", status);
                render.invoke(coordinator, registry.active(), item);
            }
            assertTrue(tracker.listSummaries(sessionId).isEmpty());
        } finally { tracker.dispose(); codex.dispose(); }
    }

    @Test
    void restoredHistoryRetainsReferenceTagsForTextInputsAndOlderMentions() throws Exception {
        var registry = new SessionRegistry();
        var codex = new CodexAppServerService(null);
        try {
            var presenter = new BridgeEventPresenter(null, null, registry, root);
            var workspace = new WorkspaceInputCoordinator(null, null, null, root, registry::active, null);
            var coordinator = new ConversationCoordinator(null, null, codex, null, null, null,
                null, null, registry, null, null, null, workspace, null, presenter, null);
            var first = root.resolve("Interaction DropComponent.h");
            var second = root.resolve("Deleted.cpp");
            var content = new com.google.gson.JsonArray();
            var reference = new JsonObject(); reference.addProperty("type", "text");
            reference.addProperty("text", FileReferenceText.format(first)); content.add(reference);
            var question = new JsonObject(); question.addProperty("type", "text");
            question.addProperty("text", "这是什么组件？"); content.add(question);
            var legacy = new JsonObject(); legacy.addProperty("type", "mention");
            legacy.addProperty("path", second.toString()); content.add(legacy);

            assertEquals(List.of(first.toString(), second.toString()), coordinator.userMessageReferencePaths(content));
            assertEquals("@" + first + "\n这是什么组件？\n@" + second, coordinator.userMessageText(content));
            reference.addProperty("text", "普通正文中提到 [引用文件] 不应被当成标签");
            assertEquals(List.of(second.toString()), coordinator.userMessageReferencePaths(content));
        } finally { codex.dispose(); }
    }

    @Test
    void snapshotsOnlyTheCurrentSessionsCommandBeforeUiDispatch() throws Exception {
        var path = root.resolve("deleted.cpp"); Files.writeString(path, "before\n");
        var constructor = ConversationChangeTracker.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        var tracker = constructor.newInstance(root);
        var registry = new SessionRegistry(); var sessions = new SessionCoordinator(registry);
        var background = registry.create(SessionId.of("background"));
        var handle = sessions.beginTurn(background.id());
        var codex = new CodexAppServerService(null);
        try {
            var presenter = new BridgeEventPresenter(null, null, registry, root);
            var coordinator = new ConversationCoordinator(null, null, codex, null, null, null,
                tracker, null, registry, sessions, null, null, null, null, presenter, null);
            var started = new TurnEvent.Item(handle, TurnEvent.Item.Phase.STARTED, "cmd", "commandExecution",
                Map.of("command", "opaque shell wrapper", "cwd", root.toString(),
                    "commandActions", List.of(Map.of("type", "unknown", "command", "Remove-Item deleted.cpp"))));
            coordinator.captureCodexCommand(started);
            Files.delete(path);
            tracker.commandCompleted(background.id().value(), "cmd");
            assertEquals(1, tracker.listSummaries(background.id().value()).size());
            assertTrue(tracker.listSummaries(registry.activeSessionId().value()).isEmpty());
            tracker.acceptAll(background.id().value());
            Files.writeString(path, "new baseline\n");
            sessions.cancel(background.id());
            coordinator.captureCodexCommand(started);
            Files.delete(path); tracker.commandCompleted(background.id().value(), "cmd");
            assertTrue(tracker.listSummaries(background.id().value()).isEmpty());

            var moveHandle = sessions.beginTurn(background.id());
            var source = root.resolve("old.txt"); Files.writeString(source, "source before\n");
            var destination = root.resolve("destination.txt"); Files.writeString(destination, "destination before\n");
            coordinator.captureCodexCommand(new TurnEvent.Item(moveHandle, TurnEvent.Item.Phase.STARTED, "move", "fileChange",
                Map.of("changes", List.of(Map.of("path", source.toString(), "kind", Map.of("type", "update", "move_path", destination.toString()))))));
            Files.writeString(destination, "after\n"); Files.delete(source);
            tracker.commandCompleted(background.id().value(), "move");
            tracker.trackProviderFile(background.id().value(), source.toString(), "update",
                "@@ -1 +1 @@\n-source before\n+after\n", destination.toString());
            tracker.revertAll(background.id().value());
            assertEquals("source before\n", Files.readString(source));
            assertEquals("destination before\n", Files.readString(destination));
        } finally { tracker.dispose(); codex.dispose(); }
    }

    @Test
    void routesNativeNameUpdatesToTheMatchingCodexSessionDuringAndAfterTurns() throws Exception {
        var registry = new SessionRegistry();
        var active = registry.active();
        active.threadId("active-thread"); active.title("前台标题");
        var background = registry.create(SessionId.of("background"));
        background.threadId("background-thread");
        var codex = new CodexAppServerService(null);
        try {
            var presenter = new BridgeEventPresenter(null, null, registry, Path.of("."));
            var coordinator = new ConversationCoordinator(null, null, codex, null, null, null,
                null, null, registry, null, null, null, null, null, presenter, null);
            var handler = ConversationCoordinator.class.getDeclaredMethod("handleNotification", String.class, JsonObject.class);
            handler.setAccessible(true);

            var turn = background.beginTurn();
            handler.invoke(coordinator, "thread/name/updated", JsonParser.parseString("""
                {"threadId":"background-thread","threadName":"运行中标题"}
                """).getAsJsonObject());
            assertEquals("运行中标题", background.title());
            background.completeTurn(turn);
            handler.invoke(coordinator, "thread/name/updated", JsonParser.parseString("""
                {"threadId":"background-thread","threadName":"首轮自动标题"}
                """).getAsJsonObject());
            assertEquals("首轮自动标题", background.title());
            assertEquals(active.id(), registry.activeSessionId());
            assertEquals("前台标题", active.title());

            for (var invalid : new String[]{
                "{\"threadId\":\"unknown-thread\",\"threadName\":\"错误标题\"}",
                "{\"threadName\":\"缺少会话 ID\"}",
                "{\"threadId\":\"background-thread\",\"threadName\":null}",
                "{\"threadId\":\"background-thread\",\"threadName\":\"   \"}"
            }) handler.invoke(coordinator, "thread/name/updated", JsonParser.parseString(invalid).getAsJsonObject());
            assertEquals("首轮自动标题", background.title());
            assertEquals("前台标题", active.title());
            background.provider("claude");
            handler.invoke(coordinator, "thread/name/updated", JsonParser.parseString("""
                {"threadId":"background-thread","threadName":"其他渠道标题"}
                """).getAsJsonObject());
            assertEquals("首轮自动标题", background.title());
        } finally {
            codex.dispose();
        }
    }
}
