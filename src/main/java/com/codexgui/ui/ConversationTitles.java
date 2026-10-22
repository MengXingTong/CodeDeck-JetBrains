package com.codexgui.ui;

import com.codexgui.conversation.ConversationSession;
import com.codexgui.conversation.SessionId;
import com.codexgui.conversation.SessionRegistry;
import com.codexgui.model.ConversationEntry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Runs title generation once per unnamed conversation and protects user edits from late replies. */
final class ConversationTitles {
    interface Generator { CompletableFuture<String> generate(String model, String user, String assistant); }
    private final SessionRegistry sessions;
    private final Generator generator;
    private final BiFunction<String, String, CompletableFuture<?>> rename;
    private final Consumer<Runnable> dispatch;
    private final Consumer<ConversationSession> publish;
    private final Runnable refreshHistory;
    private final Consumer<Throwable> failure;
    private final Map<SessionId, Object> pending = new HashMap<>();
    private final Set<SessionId> named = new HashSet<>();
    private final Set<SessionId> previews = new HashSet<>();

    ConversationTitles(SessionRegistry sessions, Generator generator,
                       BiFunction<String, String, CompletableFuture<?>> rename, Consumer<Runnable> dispatch,
                       Consumer<ConversationSession> publish, Runnable refreshHistory, Consumer<Throwable> failure) {
        this.sessions = sessions; this.generator = generator; this.rename = rename; this.dispatch = dispatch;
        this.publish = publish; this.refreshHistory = refreshHistory; this.failure = failure;
    }

    void reset(ConversationSession session) {
        pending.remove(session.id()); named.remove(session.id()); previews.remove(session.id());
    }

    void named(ConversationSession session) {
        pending.remove(session.id()); named.add(session.id()); previews.remove(session.id());
    }

    void historyLoaded(ConversationSession session, boolean hasName) {
        reset(session);
        if (hasName) named(session); else previews.add(session.id());
    }

    void completed(ConversationSession session, String model) {
        var originalTitle = session.title();
        if (sessions.find(session.id()) != session || !Objects.equals(session.provider(), "codex") || session.threadId() == null || session.threadId().isBlank()
            || named.contains(session.id()) || pending.containsKey(session.id())
            || (!previews.contains(session.id()) && !originalTitle.matches("(?:新会话|New chat)(?: \\d+)?"))) return;
        var user = session.transcript().stream().filter(entry -> entry.kind() == ConversationEntry.Kind.USER)
            .map(ConversationEntry::body).findFirst().orElse("");
        var assistant = session.transcript().stream().filter(entry -> entry.kind() == ConversationEntry.Kind.ASSISTANT)
            .map(ConversationEntry::body).filter(body -> !body.isBlank()).findFirst().orElse("");
        if (user.isBlank() || assistant.isBlank()) return;
        var threadId = session.threadId(); var token = new Object();
        pending.put(session.id(), token);
        CompletableFuture.completedFuture("").thenCompose(ignored -> generator.generate(model, user, assistant))
            .whenComplete((title, error) -> dispatch.accept(() -> {
                if (!current(session, threadId, originalTitle, token)) return;
                if (error != null || title == null || title.isBlank()) {
                    pending.remove(session.id(), token);
                    if (error != null) failure.accept(error);
                    return;
                }
                CompletableFuture.completedFuture("").thenCompose(ignored -> rename.apply(threadId, title))
                    .whenComplete((ignored, renameError) -> dispatch.accept(() -> {
                        if (current(session, threadId, originalTitle, token)) {
                            pending.remove(session.id(), token);
                            if (renameError == null) {
                                session.title(title); named(session); publish.accept(session);
                            } else failure.accept(renameError);
                        }
                        if (renameError == null && sessions.find(session.id()) == session && Objects.equals(session.threadId(), threadId)) refreshHistory.run();
                    }));
            }));
    }

    private boolean current(ConversationSession session, String threadId, String title, Object token) {
        return pending.get(session.id()) == token && sessions.find(session.id()) == session
            && Objects.equals(session.provider(), "codex") && Objects.equals(session.threadId(), threadId)
            && Objects.equals(session.title(), title);
    }

    void clear() { pending.clear(); named.clear(); previews.clear(); }
}
