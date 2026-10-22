package com.codexgui.ui;

import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.ConversationEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ConversationTitlesTest {
    private final SessionRegistry sessions = new SessionRegistry();
    private final CompletableFuture<String> generated = new CompletableFuture<>();
    private final CompletableFuture<Void> persisted = new CompletableFuture<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final ArrayList<String> saved = new ArrayList<>();
    private final ArrayList<String> published = new ArrayList<>();
    private final ArrayList<Throwable> failures = new ArrayList<>();
    private final ConversationTitles titles = new ConversationTitles(sessions, (model, user, assistant) -> {
        assertEquals("current-model", model); assertTrue(user.contains("发现一个问题")); assertFalse(assistant.isBlank());
        requests.incrementAndGet(); return generated;
    }, (threadId, title) -> { saved.add(threadId + ":" + title); return persisted; }, Runnable::run,
        session -> published.add(session.title()), refreshes::incrementAndGet, failures::add);

    private void firstTurn() {
        var session = sessions.active(); session.threadId("main-thread");
        session.transcript().add(new ConversationEntry(ConversationEntry.Kind.USER, "你", "我发现一个问题：自动改名后页签没有更新。", null));
        session.transcript().add(new ConversationEntry(ConversationEntry.Kind.ASSISTANT, "Codex", "已经定位到标题更新链路。", null));
    }

    @Test
    void firstSuccessfulTurnGeneratesPersistsAndPublishesASummaryWithoutNativeNameNotifications() {
        firstTurn(); var background = sessions.active();
        titles.completed(background, "current-model"); titles.completed(background, "current-model");
        assertEquals(1, requests.get());
        sessions.activate(SessionId.of("another-tab"));
        generated.complete("修复会话标题同步");
        assertEquals("新会话", background.title());
        assertEquals(java.util.List.of("main-thread:修复会话标题同步"), saved);
        persisted.complete(null);
        assertEquals("修复会话标题同步", background.title());
        assertEquals(java.util.List.of("修复会话标题同步"), published);
        assertEquals("another-tab", sessions.activeSessionId().value());
        assertEquals(1, refreshes.get());
        titles.completed(background, "current-model"); assertEquals(1, requests.get());
    }

    @Test
    void manualRenameAndReusedTabsRejectLateGeneration() {
        firstTurn(); var session = sessions.active();
        titles.completed(session, "current-model");
        titles.named(session); session.title("我自己的标题");
        generated.complete("自动生成的标题");
        assertTrue(saved.isEmpty()); assertEquals("我自己的标题", session.title());

        titles.reset(session); session.title("新会话"); session.threadId("new-thread");
        titles.completed(session, "current-model");
        assertEquals(java.util.List.of("new-thread:自动生成的标题"), saved);
        session.threadId("replacement-thread"); titles.reset(session);
        persisted.complete(null);
        assertEquals("新会话", session.title()); assertTrue(published.isEmpty());
    }

    @Test
    void manualTitleWinsWhenPersistenceAcknowledgementArrivesLater() {
        firstTurn(); var session = sessions.active();
        titles.completed(session, "current-model"); generated.complete("自动标题");
        titles.named(session); session.title("手动标题"); persisted.complete(null);
        assertEquals("手动标题", session.title()); assertTrue(published.isEmpty());
    }

    @Test
    void unnamedHistoryGetsASummaryOnItsNextSuccessfulTurnWhileNamedHistoryIsPreserved() {
        firstTurn(); var session = sessions.active(); session.title("第一段原始消息预览");
        titles.historyLoaded(session, true); titles.completed(session, "current-model"); assertEquals(0, requests.get());
        titles.historyLoaded(session, false); titles.completed(session, "current-model");
        generated.complete("历史会话的概括标题"); persisted.complete(null);
        assertEquals("历史会话的概括标题", session.title());
    }

    @Test
    void failuresKeepTheExistingTitleAndClosedSessionsCannotBeRenamed() {
        firstTurn(); var session = sessions.active();
        titles.completed(session, "current-model"); generated.completeExceptionally(new IllegalStateException("offline"));
        assertEquals("新会话", session.title()); assertEquals(1, failures.size()); assertTrue(saved.isEmpty());
        titles.reset(session); sessions.remove(session.id());
        titles.completed(session, "current-model"); assertTrue(saved.isEmpty());
    }
}
