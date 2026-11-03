package com.codexgui.changelist.vcs;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.ThrowableRunnable;
import com.intellij.vcsUtil.VcsUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real IDE dirty scopes, error retention and change publication; no user's P4 server or ticket is used. */
@EnabledIfEnvironmentVariable(named = "CODEDECK_REAL_VCS_REFRESH", matches = "1")
class NativeVcsRefreshIntegrationTest {
    @TempDir Path root;
    private static final String EXPIRED = "Your session has expired, please login again.";

    @Test void manualRefreshReplacesStickyNativeErrorAndPublishesChangesAfterExternalLogin() throws Exception {
        Class.forName("com.intellij.testFramework.TestApplicationManager").getMethod("getInstance").invoke(null);
        var factoryType = Class.forName("com.intellij.testFramework.fixtures.IdeaTestFixtureFactory");
        var factory = factoryType.getMethod("getFixtureFactory").invoke(null);
        var builder = factoryType.getMethod("createFixtureBuilder", String.class, Path.class, boolean.class)
                .invoke(factory, "CodeDeck native refresh", root, false);
        var fixture = Class.forName("com.intellij.testFramework.fixtures.TestFixtureBuilder")
                .getMethod("getFixture").invoke(builder);
        var fixtureType = Class.forName("com.intellij.testFramework.fixtures.IdeaTestFixture");
        onEdt(() -> invoke(fixtureType.getMethod("setUp"), fixture));
        var project = (Project) Class.forName("com.intellij.testFramework.fixtures.IdeaProjectTestFixture")
                .getMethod("getProject").invoke(fixture);
        var vcs = new RecoveringVcs(project, root.resolve("game/recovered.cpp"));
        try {
            Files.createDirectories(vcs.file.getParent());
            Files.writeString(vcs.file, "new content after external login\n");
            onEdt(() -> {
                assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root));
                var manager = (ProjectLevelVcsManagerImpl) ProjectLevelVcsManager.getInstance(project);
                manager.registerVcs(vcs);
                manager.setDirectoryMappings(List.of(new VcsDirectoryMapping(root.toString(), vcs.getName())));
            });
            ((ProjectLevelVcsManagerImpl) ProjectLevelVcsManager.getInstance(project)).waitForInitialized();
            var manager = (ChangeListManagerImpl) ChangeListManager.getInstance(project);
            // Obtain a real provider failure, rather than assigning the manager's exception field.
            assertEquals(EXPIRED, refresh(project, true).strip());
            assertNotNull(manager.getUpdateException());

            vcs.expired = false; // The server now accepts the renewed external login.
            int previousCalls = vcs.calls.get();
            assertEquals(EXPIRED, refresh(project, false).strip(),
                    "A successful partial update retains the IDE's project-wide error (the 2.1.6 defect)");
            assertTrue(vcs.calls.get() > previousCalls, "The provider really ran and recovered");
            assertNull(manager.getChange(VcsUtil.getFilePath(vcs.file, false)),
                    "The sticky native exception also prevents publishing the recovered change");

            assertEquals("", refresh(project, true), "Manual refresh must replace the old native failure");
            assertNull(manager.getUpdateException());
            assertNotNull(manager.getChange(VcsUtil.getFilePath(vcs.file, false)),
                    "Recovery must update the change tree data, not merely hide its error message");

            vcs.expired = true;
            assertEquals(EXPIRED, refresh(project, true).strip(), "A new real failure must still be reported");
            assertNotNull(manager.getUpdateException());
        } finally {
            onEdt(() -> {
                var manager = (ProjectLevelVcsManagerImpl) ProjectLevelVcsManager.getInstance(project);
                manager.setDirectoryMappings(List.of());
                manager.unregisterVcs(vcs);
                invoke(fixtureType.getMethod("tearDown"), fixture);
            });
        }
    }

    private String refresh(Project project, boolean manual) throws Exception {
        var result = new CompletableFuture<String>();
        onEdt(() -> NativeVcsRefresh.refresh(project, root.resolve("game"), true, manual, result::complete));
        return result.get(30, TimeUnit.SECONDS);
    }

    private static final class RecoveringVcs extends AbstractVcs {
        private final Path file;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean expired = true;
        RecoveringVcs(Project project, Path file) { super(project, "CodeDeckRefreshFixture"); this.file = file; }
        @Override public String getDisplayName() { return "CodeDeck refresh fixture"; }
        @Override public ChangeProvider getChangeProvider() {
            return new ChangeProvider() {
                @Override public boolean isModifiedDocumentTrackingRequired() { return false; }
                @Override public void getChanges(VcsDirtyScope scope, ChangelistBuilder builder,
                                                 ProgressIndicator indicator, ChangeListManagerGate gate) throws VcsException {
                    calls.incrementAndGet();
                    if (expired) throw new VcsException(EXPIRED);
                    var path = VcsUtil.getFilePath(file, false);
                    builder.processChange(new Change(new ContentRevision() {
                        @Override public String getContent() { return "before login\n"; }
                        @Override public com.intellij.openapi.vcs.FilePath getFile() { return path; }
                        @Override public VcsRevisionNumber getRevisionNumber() { return new VcsRevisionNumber.Int(1); }
                    }, CurrentContentRevision.create(path)), getKeyInstanceMethod());
                }
            };
        }
    }

    private static void invoke(java.lang.reflect.Method method, Object target) throws Exception {
        try { method.invoke(target); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    private static void onEdt(ThrowableRunnable<Exception> action) throws Exception {
        assertFalse(ApplicationManager.getApplication().isDispatchThread());
        invoke(Class.forName("com.intellij.testFramework.EdtTestUtil")
                .getMethod("runInEdtAndWait", ThrowableRunnable.class), null, action);
    }

    private static void invoke(java.lang.reflect.Method method, Object target, Object argument) throws Exception {
        try { method.invoke(target, argument); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
}
