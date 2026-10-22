package com.codexgui.changelist.vcs;

import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.FileStatus;
import com.codexgui.changelist.core.ChangedFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.lang.reflect.Proxy;

import static com.codexgui.changelist.vcs.FileVcsOperations.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

class FileVcsOperationsTest {
    private final NativeChanges.DiffTarget unopened = NativeChanges.DiffTarget.lazy(project -> {
        throw new AssertionError("Opening a menu must not read file contents or diff revisions");
    });
    private final ContentRevision revision = (ContentRevision) Proxy.newProxyInstance(ContentRevision.class.getClassLoader(),
            new Class<?>[]{ContentRevision.class}, (proxy, method, args) -> {
                throw new AssertionError("Menu queried revision: " + method.getName());
            });
    private final FileStatus status = (FileStatus) Proxy.newProxyInstance(FileStatus.class.getClassLoader(),
            new Class<?>[]{FileStatus.class}, (proxy, method, args) -> {
                throw new AssertionError("Menu queried UI status: " + method.getName());
            });
    private final NativeChanges.DiffTarget opened = new NativeChanges.DiffTarget(new Change(revision, revision, status), null);

    private ChangedFile file(ChangedFile.Kind kind) { return new ChangedFile("file", Path.of("file.cpp"), kind, true); }

    @Test void binaryFilesHaveTheSameVcsOperationsAsTextFiles() {
        var modified = file(ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT);
        assertTrue(FileVcsOperations.available(REVERT, modified, unopened, true, true));
        assertTrue(FileVcsOperations.available(CHECKOUT, modified, unopened, true, true));
        assertFalse(FileVcsOperations.available(CANCEL_CHECKOUT, modified, unopened, true, true));
        assertTrue(FileVcsOperations.available(CANCEL_CHECKOUT, file(ChangedFile.Kind.MODIFIED), opened, true, true));
        assertFalse(FileVcsOperations.available(CHECKOUT, file(ChangedFile.Kind.MODIFIED), opened, true, true));
    }

    @Test void localAdditionsAndDeletionsDoNotExposeInvalidCheckoutCommands() {
        for (var operation : FileVcsOperations.Operation.values()) {
            assertFalse(FileVcsOperations.available(operation, file(ChangedFile.Kind.UNVERSIONED), unopened, true, true));
        }
        assertTrue(FileVcsOperations.available(REVERT, file(ChangedFile.Kind.DELETED), unopened, true, true));
        assertFalse(FileVcsOperations.available(CHECKOUT, file(ChangedFile.Kind.DELETED), unopened, true, true));
        assertTrue(FileVcsOperations.available(REVERT, file(ChangedFile.Kind.ADDED), opened, true, true));
        assertTrue(FileVcsOperations.available(CANCEL_CHECKOUT, file(ChangedFile.Kind.ADDED), opened, true, true));
    }

    @Test void nativeRollbackWorksWithoutPerforceWhileCheckoutRemainsPerforceOnly() {
        var modified = file(ChangedFile.Kind.MODIFIED);
        assertTrue(FileVcsOperations.available(REVERT, modified, opened, false, true));
        assertFalse(FileVcsOperations.available(REVERT, modified, opened, false, false));
        assertFalse(FileVcsOperations.available(REVERT, modified, unopened, false, true));
        assertFalse(FileVcsOperations.available(CHECKOUT, modified, unopened, false, true));
        assertFalse(FileVcsOperations.available(CANCEL_CHECKOUT, modified, opened, false, true));
        assertFalse(FileVcsOperations.available(REVERT, modified, null, true, true));
    }

    @Test void acknowledgedServerStateOverridesStaleNativeHandlesWithoutLoadingDiffs() {
        var modified = file(ChangedFile.Kind.MODIFIED);
        assertTrue(FileVcsOperations.available(CANCEL_CHECKOUT, modified, unopened.withOpened(true), true, true));
        assertFalse(FileVcsOperations.available(CHECKOUT, modified, unopened.withOpened(true), true, true));
        assertTrue(FileVcsOperations.available(CHECKOUT, modified, opened.withOpened(false), true, true));
        assertFalse(FileVcsOperations.available(CANCEL_CHECKOUT, modified, opened.withOpened(false), true, true));
    }
}
