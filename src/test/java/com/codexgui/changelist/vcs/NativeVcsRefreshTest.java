package com.codexgui.changelist.vcs;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeVcsRefreshTest {
    private final Path root = Path.of("project").toAbsolutePath();

    @Test void manualRefreshWaitsForVfsThenDirtyScopeThenProviderCompletion() {
        var backend = new DeferredBackend();
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        assertEquals(List.of("prepare", "vfs"), backend.events);
        assertTrue(results.isEmpty(), "Reading the old cache while VFS is pending must not finish refresh");
        backend.vfsCompleted.run();
        assertEquals(List.of("prepare", "vfs", "invalidate", "all", "wait"), backend.events);
        assertEquals(root, backend.path);
        assertTrue(backend.directory);
        assertTrue(backend.fullRefresh, "A manual retry must also reset the IDE's project-wide update error");
        assertTrue(results.isEmpty(), "An in-flight provider update must not be reported as synced");
        backend.updateCompleted.run();
        assertEquals(List.of(""), results);
    }

    @Test void authenticationErrorIsReadAfterUpdateAndRetryCanSucceed() {
        var backend = new DeferredBackend();
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run();
        backend.error = "Your session has expired, please login again.";
        backend.updateCompleted.run();
        assertEquals(List.of(backend.error), results);

        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run();
        backend.error = "";
        backend.updateCompleted.run();
        assertEquals(List.of("Your session has expired, please login again.", ""), results);
    }

    @Test void externalLoginRecoversOnlyAfterManualRefreshInvalidatesTheProviderCache() {
        var cachedFailure = new String[]{"Your session has expired, please login again."};
        var ticketValid = new boolean[]{false};
        var backend = new DeferredBackend() {
            @Override public void invalidateProvider() {
                super.invalidateProvider();
                cachedFailure[0] = null;
            }
            @Override public String updateError() {
                if (cachedFailure[0] == null)
                    cachedFailure[0] = ticketValid[0] ? "" : "Your session has expired, please login again.";
                return cachedFailure[0];
            }
        };
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run(); backend.updateCompleted.run();
        assertFalse(results.getFirst().isBlank(), "A genuinely expired ticket must still fail");

        ticketValid[0] = true; // P4V logs in without touching the IDE's cached connection info.
        NativeVcsRefresh.refresh(backend, root, true, false, results::add);
        backend.updateCompleted.run();
        assertEquals(results.getFirst(), results.getLast(), "Dirtying files alone reproduces the cached error");

        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        assertEquals(results.getFirst(), backend.updateError(), "Wait until VFS has finished before invalidating");
        backend.vfsCompleted.run();
        assertEquals(2, results.size(), "Cache invalidation alone is not a completed VCS update");
        backend.updateCompleted.run();
        assertEquals("", results.getLast());
    }

    @Test void providerInvalidationFailureDoesNotStartAnUpdateOrLeaveRefreshRunning() {
        var backend = new DeferredBackend() {
            @Override public void invalidateProvider() { throw new IllegalStateException("Cannot reset connection cache"); }
        };
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run();
        assertEquals(List.of("Cannot reset connection cache"), results);
        assertNull(backend.updateCompleted);
    }

    @Test void offlineOrFrozenIntegrationDoesNotAcknowledgeTheCachedResult() {
        var backend = new DeferredBackend();
        backend.unavailable = "Perforce is offline";
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        assertEquals(List.of("Perforce is offline"), results);
        assertTrue(backend.events.isEmpty());
    }

    @Test void goingOfflineDuringRefreshStillReportsFailure() {
        var backend = new DeferredBackend();
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run();
        backend.unavailable = "Perforce is offline";
        backend.updateCompleted.run();
        assertEquals(List.of("Perforce is offline"), results);
    }

    @Test void targetedRefreshDirtiesOnlyTheRequestedPathAndStillChecksProviderErrors() {
        var backend = new DeferredBackend();
        var results = new ArrayList<String>();
        var file = root.resolve("deleted.cs");
        NativeVcsRefresh.refresh(backend, file, false, false, results::add);
        assertEquals(List.of("dirty", "wait"), backend.events);
        assertEquals(file, backend.path);
        assertFalse(backend.directory);
        assertFalse(backend.fullRefresh, "File operations must retain their incremental scope");
        backend.error = "Connection timed out";
        backend.updateCompleted.run();
        assertEquals(List.of("Connection timed out"), results);
    }

    @Test void preparationFailureReleasesTheCallerWithoutStartingAnUpdate() {
        var backend = new DeferredBackend();
        backend.prepareError = new IllegalStateException("Cannot prepare refresh");
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        assertEquals(List.of("Cannot prepare refresh"), results);
        assertNull(backend.vfsCompleted);
        assertNull(backend.updateCompleted);
    }

    @Test void failureInTheDeferredVfsCallbackReleasesTheCaller() {
        var backend = new DeferredBackend();
        backend.dirtyError = new IllegalStateException("Cannot dirty scope");
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run();
        assertEquals(List.of("Cannot dirty scope"), results);
        assertNull(backend.updateCompleted);
    }

    @Test void failureWhileReadingTheCompletedUpdateDoesNotLeaveRefreshRunning() {
        var backend = new DeferredBackend() {
            @Override public String updateError() { throw new IllegalStateException("Cannot read update result"); }
        };
        var results = new ArrayList<String>();
        NativeVcsRefresh.refresh(backend, root, true, true, results::add);
        backend.vfsCompleted.run();
        backend.updateCompleted.run();
        assertEquals(List.of("Cannot read update result"), results);
    }

    private static class DeferredBackend implements NativeVcsRefresh.Backend {
        final List<String> events = new ArrayList<>();
        String unavailable = "", error = "";
        Runnable vfsCompleted, updateCompleted;
        RuntimeException prepareError, dirtyError;
        Path path;
        boolean directory, fullRefresh;
        @Override public String unavailable() { return unavailable; }
        @Override public void prepare() {
            events.add("prepare");
            if (prepareError != null) throw prepareError;
        }
        @Override public void refreshFiles(Runnable completed) { events.add("vfs"); vfsCompleted = completed; }
        @Override public void invalidateProvider() { events.add("invalidate"); }
        @Override public void markDirty(Path path, boolean directory, boolean fullRefresh) {
            events.add(fullRefresh ? "all" : "dirty");
            if (dirtyError != null) throw dirtyError;
            this.path = path; this.directory = directory; this.fullRefresh = fullRefresh;
        }
        @Override public void afterUpdate(Runnable completed) { events.add("wait"); updateCompleted = completed; }
        @Override public String updateError() { return error; }
    }
}
