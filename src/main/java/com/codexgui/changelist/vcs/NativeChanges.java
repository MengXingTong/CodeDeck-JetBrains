package com.codexgui.changelist.vcs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.*;
import com.codexgui.changelist.core.ChangeTree;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import com.codexgui.changelist.core.IgnoreRules;
import com.codexgui.changelist.settings.SidebarSettings;
import com.intellij.vcsUtil.VcsUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Reads the shared IDE cache, including Perforce and Git providers; it owns no VCS connection. */
public final class NativeChanges {
    private NativeChanges() {}
    @FunctionalInterface public interface DiffLoader { Change load(Project project) throws IOException; }
    public record DiffTarget(Change change, FilePath unversionedPath, FilePath modifiedWithoutCheckoutPath,
                             DiffLoader loader, Boolean openedOverride) {
        public DiffTarget(Change change, FilePath unversionedPath, FilePath modifiedWithoutCheckoutPath, DiffLoader loader) {
            this(change, unversionedPath, modifiedWithoutCheckoutPath, loader, null);
        }
        public DiffTarget(Change change, FilePath unversionedPath) { this(change, unversionedPath, null, null); }
        public DiffTarget(Change change, FilePath unversionedPath, FilePath modifiedWithoutCheckoutPath) {
            this(change, unversionedPath, modifiedWithoutCheckoutPath, null);
        }
        public static DiffTarget lazy(DiffLoader loader) { return new DiffTarget(null, null, null, loader); }
        public boolean opened() { return openedOverride != null ? openedOverride : change != null; }
        public DiffTarget withOpened(boolean opened) {
            return new DiffTarget(change, unversionedPath, modifiedWithoutCheckoutPath, loader, opened);
        }

        public Change resolve(Project project) throws IOException {
            if (change != null) return change;
            if (loader != null) return loader.load(project);
            if (modifiedWithoutCheckoutPath == null) {
                return new Change(null, CurrentContentRevision.create(unversionedPath));
            }
            // This VFS/provider lookup happens only on explicit diff, never while collecting the tree.
            var file = modifiedWithoutCheckoutPath.getVirtualFile();
            if (file == null || !file.isValid()) throw new IOException("文件已不存在，请刷新变更列表");
            var current = ChangeListManager.getInstance(project).getChange(file);
            if (current != null) return current; // The file may have been checked out since the snapshot.
            var vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file);
            var provider = vcs == null ? null : vcs.getDiffProvider();
            // Compare with the workspace's current revision (Perforce 'have'), rather than an empty file or depot HEAD.
            var before = provider == null ? null : provider.createCurrentFileContent(file);
            if (before == null) throw new IOException("IDE 无法读取此文件的工作区基准版本");
            return new Change(before, CurrentContentRevision.create(modifiedWithoutCheckoutPath));
        }
    }
    public record Snapshot(List<ChangeTree.Group> groups, Map<String, DiffTarget> targets, String vcsNames,
                           Path projectRoot) {}

    public static Snapshot read(Project project, boolean showUnversioned) {
        ProjectScope scope = ProjectScope.forBasePath(project.getBasePath());
        String names = Arrays.stream(ProjectLevelVcsManager.getInstance(project).getAllActiveVcss())
                .map(vcs -> vcs.getDisplayName()).sorted().reduce((a, b) -> a + " / " + b).orElse("");
        if (scope.root() == null) return new Snapshot(List.of(), Map.of(), names, null);
        ChangeListManager manager = ChangeListManager.getInstance(project);
        var settings = project.getService(SidebarSettings.class).snapshot();
        var unopened = project.getService(UnopenedChanges.class);
        return collect(scope, manager.getChangeLists(),
                showUnversioned ? manager.getUnversionedFilesPaths() : List.of(),
                manager.getModifiedWithoutEditing().stream().map(file -> {
                    ProgressManager.checkCanceled();
                    return VcsUtil.getFilePath(file);
                }).toList(), settings.scanUnopened && unopened != null ? unopened.snapshot().stream()
                        .filter(entry -> showUnversioned || entry.kind() != ChangedFile.Kind.UNVERSIONED).toList() : List.of(), names,
                settings.ignoreRules(scope), NativeChanges::knownBinaryName, ProgressManager::checkCanceled);
    }

    /** Filter before classification, grouping or creating diff targets; IDE VCS roots can be much wider. */
    static Snapshot collect(ProjectScope scope, Iterable<LocalChangeList> lists, Iterable<FilePath> unversioned,
                            Iterable<FilePath> modifiedWithoutCheckout,
                            String names, Predicate<String> binaryName, Runnable checkCanceled) {
        return collect(scope, lists, unversioned, modifiedWithoutCheckout, List.of(), names,
                IgnoreRules.none(scope), binaryName, checkCanceled);
    }

    static Snapshot collect(ProjectScope scope, Iterable<LocalChangeList> lists, Iterable<FilePath> unversioned,
                            Iterable<FilePath> modifiedWithoutCheckout, Iterable<UnopenedChanges.Entry> discovered,
                            String names, IgnoreRules ignores, Predicate<String> binaryName, Runnable checkCanceled) {
        if (scope.root() == null) return new Snapshot(List.of(), Map.of(), names, null);
        List<ChangeTree.Group> groups = new ArrayList<>();
        Map<String, DiffTarget> targets = new HashMap<>();
        Set<Path> represented = new HashSet<>();
        for (LocalChangeList list : lists) {
            checkCanceled.run();
            List<ChangedFile> files = new ArrayList<>();
            for (Change change : list.getChanges()) {
                checkCanceled.run();
                FilePath path = path(change);
                if (path == null || path.isDirectory() || path.isNonLocal() || !scope.contains(path.getPath())
                        || ignores.isIgnored(Path.of(path.getPath()))) continue;
                String id = list.getId() + ":" + key(change);
                files.add(describe(change, id, binaryName));
                targets.put(id, new DiffTarget(change, null));
                represented.add(Path.of(path.getPath()).normalize());
            }
            files.sort(Comparator.comparing(f -> f.path().toString()));
            if (!files.isEmpty()) groups.add(new ChangeTree.Group(list.getId(),
                    list.isBlank() ? "工作区修改" : list.getName(), List.copyOf(files)));
        }
        List<ChangedFile> localFiles = new ArrayList<>();
        // Native changelists win; a full preview then supersedes stale writable-file cache entries.
        for (UnopenedChanges.Entry entry : discovered) {
            checkCanceled.run();
            Path local = entry.path();
            if (!scope.contains(local) || ignores.isIgnored(local) || !represented.add(local.normalize())) continue;
            String id = "unopened-preview:" + local;
            var file = new ChangedFile(id, local, entry.kind(),
                    entry.binary() || binaryName.test(local.getFileName().toString()));
            localFiles.add(file);
            targets.put(id, entry.target());
        }
        for (FilePath path : modifiedWithoutCheckout) {
            checkCanceled.run();
            if (path.isDirectory() || path.isNonLocal() || !scope.contains(path.getPath())
                    || ignores.isIgnored(Path.of(path.getPath()))) continue;
            Path local = Path.of(path.getPath());
            if (!represented.add(local.normalize())) continue;
            String id = "modified-without-checkout:" + path.getPath();
            localFiles.add(new ChangedFile(id, local, ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT,
                    binaryName.test(local.getFileName().toString())));
            targets.put(id, new DiffTarget(null, null, path));
        }
        for (FilePath path : unversioned) {
            checkCanceled.run();
            if (path.isDirectory() || path.isNonLocal() || !scope.contains(path.getPath())
                    || ignores.isIgnored(Path.of(path.getPath()))) continue;
            if (!represented.add(Path.of(path.getPath()).normalize())) continue;
            String id = "unversioned:" + path.getPath();
            // CurrentContentRevision.create() detects contents and can read disk. Defer it until explicit diff.
            localFiles.add(describeUnversioned(path, id, binaryName));
            targets.put(id, new DiffTarget(null, path));
        }
        localFiles.sort(Comparator.comparing(f -> f.path().toString()));
        if (!localFiles.isEmpty()) groups.add(new ChangeTree.Group("local", "本地变更", List.copyOf(localFiles)));
        return new Snapshot(List.copyOf(groups), Map.copyOf(targets), names, scope.root());
    }

    public static FilePath path(Change change) {
        ContentRevision revision = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
        return revision == null ? null : revision.getFile();
    }
    public static boolean isBinary(Change change) {
        return binary(change.getBeforeRevision(), NativeChanges::knownBinaryName)
                || binary(change.getAfterRevision(), NativeChanges::knownBinaryName);
    }
    static boolean knownBinaryName(String name) {
        var type = FileTypeRegistry.getInstance().getFileTypeByFileName(name);
        return type != UnknownFileType.INSTANCE && type.isBinary();
    }
    private static boolean binary(ContentRevision revision, Predicate<String> binaryName) {
        return revision != null && (revision instanceof BinaryContentRevision
                || binaryName.test(Path.of(revision.getFile().getPath()).getFileName().toString()));
    }

    /** Metadata only: never asks FilePath for its VirtualFile, FileType, charset or contents. */
    static ChangedFile describe(Change change, String id, Predicate<String> binaryName) {
        FilePath file = path(change);
        return new ChangedFile(id, Path.of(file.getPath()), kind(change),
                binary(change.getBeforeRevision(), binaryName) || binary(change.getAfterRevision(), binaryName));
    }
    static ChangedFile describeUnversioned(FilePath path, String id, Predicate<String> binaryName) {
        Path local = Path.of(path.getPath());
        return new ChangedFile(id, local, ChangedFile.Kind.UNVERSIONED,
                binaryName.test(local.getFileName().toString()));
    }
    private static String key(Change change) {
        String before = change.getBeforeRevision() == null ? "" : change.getBeforeRevision().getFile().getPath();
        String after = change.getAfterRevision() == null ? "" : change.getAfterRevision().getFile().getPath();
        return before + " -> " + after;
    }
    private static ChangedFile.Kind kind(Change change) {
        return switch (change.getType()) {
            case NEW -> ChangedFile.Kind.ADDED;
            case DELETED -> ChangedFile.Kind.DELETED;
            case MOVED -> ChangedFile.Kind.MOVED;
            default -> ChangedFile.Kind.MODIFIED;
        };
    }
}
