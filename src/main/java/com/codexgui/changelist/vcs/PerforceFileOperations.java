package com.codexgui.changelist.vcs;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.vcsUtil.VcsUtil;
import com.codexgui.changelist.core.ChangedFile;
import com.codexgui.changelist.core.ProjectScope;
import org.jetbrains.idea.perforce.application.PerforceNumberNameSynchronizer;
import org.jetbrains.idea.perforce.perforce.P4File;
import org.jetbrains.idea.perforce.perforce.PerforceContentRevision;
import org.jetbrains.idea.perforce.perforce.PerforceRunner;
import org.jetbrains.idea.perforce.perforce.PerforceSettings;
import org.jetbrains.idea.perforce.perforce.connections.PerforceConnectionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Explicit file commands use the IDE's connection, credentials and native error reporting. */
public final class PerforceFileOperations implements FileVcsOperations {
    private final Project project;
    record State(Path path, String action, long haveRevision, String depot, String movedDepot, boolean binary) {
        State(Path path, String action, long haveRevision, String depot, String movedDepot) {
            this(path, action, haveRevision, depot, movedDepot, false);
        }
        boolean opened() { return action != null && !action.isBlank(); }
    }
    private record CheckoutList(String id, String label, Long number) {}

    public PerforceFileOperations(Project project) { this.project = project; }

    @Override public Result perform(Operation operation, List<Path> paths, Runnable beforeMutation) throws VcsException {
        var scope = ProjectScope.forBasePath(project.getBasePath());
        validatePaths(scope, paths);
        boolean mapped = ReadAction.nonBlocking(() -> paths.stream().allMatch(path -> {
            var vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(VcsUtil.getFilePath(path, false));
            return vcs != null && "Perforce".equals(vcs.getName());
        })).expireWith(project).executeSynchronously();
        if (!mapped) throw new VcsException("文件的 Perforce 映射已变化，请刷新后重试");
        var settings = PerforceSettings.getSettings(project);
        if (!settings.ENABLED) throw new VcsException("Perforce 当前处于离线状态");
        var connections = PerforceConnectionManager.getInstance(project);
        if (!connections.isInitialized()) throw new VcsException("Perforce 连接尚未就绪，请稍后重试");
        var connection = connections.getConnectionForFile(paths.getFirst().toFile());
        if (connection == null) throw new VcsException("IDE 未配置此文件的 Perforce 连接");
        var runner = PerforceRunner.getInstance(project);
        List<State> states = new ArrayList<>();
        for (Path path : paths) {
            ProgressManager.checkCanceled();
            var other = connections.getConnectionForFile(path.toFile());
            if (other == null || !connection.getConnectionKey().equals(other.getConnectionKey())) {
                throw new VcsException("不能同时操作不同 Perforce 工作区中的文件");
            }
            if (Files.isDirectory(path)) throw new VcsException("文件已变为目录，请刷新后重试");
            var stat = runner.fstat(P4File.create(path.toFile()));
            if (stat == null) throw new VcsException("无法读取文件的当前 Perforce 状态");
            long have;
            try { have = Long.parseLong(stat.haveRev == null ? "0" : stat.haveRev); }
            catch (NumberFormatException error) { throw new VcsException("无法读取文件的工作区基准版本"); }
            states.add(new State(path, stat.action, have, stat.depotFile, stat.movedFile,
                    (stat.headType != null && stat.headType.startsWith("binary"))
                            || NativeChanges.knownBinaryName(path.getFileName().toString())));
        }
        var command = command(operation, scope, states);
        CheckoutList checkoutList = operation == Operation.CHECKOUT ? ReadAction.nonBlocking(() -> {
            var list = ChangeListManager.getInstance(project).getDefaultChangeList();
            Long number = PerforceNumberNameSynchronizer.getInstance(project)
                    .getNumber(connection.getConnectionKey(), list.getName());
            return new CheckoutList(list.getId(), list.isBlank() ? "工作区修改" : list.getName(), number);
        }).expireWith(project).executeSynchronously() : null;
        if (checkoutList != null && checkoutList.number() != null && checkoutList.number() > 0) {
            command = new String[]{"edit", "-c", checkoutList.number().toString(), command[1]};
        }
        if (operation == Operation.CHECKOUT && !Files.isRegularFile(paths.getFirst())) {
            throw new VcsException("本地文件不存在，无法迁出");
        }
        ProgressManager.checkCanceled();
        beforeMutation.run();
        var output = runner.executeP4Command(command, connection);
        try { runner.checkError(output, connection); }
        finally {
            output.cleanup();
            for (Path path : paths) P4File.create(path.toFile()).invalidateFstat();
        }
        List<Update> updates = new ArrayList<>();
        for (State state : states) {
            var path = state.path();
            boolean exists = Files.exists(path);
            ChangedFile.Kind kind = operation == Operation.CHECKOUT ? ChangedFile.Kind.MODIFIED
                    : operation == Operation.REVERT && state.haveRevision() > 0 ? null
                    : state.haveRevision() == 0 ? (exists ? ChangedFile.Kind.UNVERSIONED : null)
                    : exists ? ChangedFile.Kind.MODIFIED_WITHOUT_CHECKOUT : ChangedFile.Kind.DELETED;
            var filePath = VcsUtil.getFilePath(path, false);
            NativeChanges.DiffTarget target = kind == null ? null : kind == ChangedFile.Kind.UNVERSIONED
                    ? new NativeChanges.DiffTarget(null, filePath)
                    : NativeChanges.DiffTarget.lazy(project -> new Change(
                            new PerforceContentRevision(project, filePath, state.haveRevision()),
                            Files.exists(path) ? CurrentContentRevision.create(filePath) : null))
                            .withOpened(operation == Operation.CHECKOUT);
            updates.add(new Update(path, kind, state.binary(), target,
                    checkoutList == null ? "local" : checkoutList.id(),
                    checkoutList == null ? "本地变更" : checkoutList.label()));
        }
        return new Result(List.copyOf(updates));
    }

    private static void validatePaths(ProjectScope scope, List<Path> paths) throws VcsException {
        if (paths.isEmpty() || paths.size() > 2 || paths.stream().anyMatch(path -> !scope.contains(path))) {
            throw new VcsException("已跳过项目目录外的文件或无效的文件选择");
        }
    }

    /** Recheck server state before mutating anything; never sync HEAD or use a recursive filespec. */
    static String[] command(Operation operation, ProjectScope scope, List<State> states) throws VcsException {
        validatePaths(scope, states.stream().map(State::path).toList());
        if (states.size() == 2 && states.stream().anyMatch(state -> !state.opened()
                || !state.action().startsWith("move/"))) {
            throw new VcsException("移动关系已变化，请刷新后重试");
        }
        for (State state : states) {
            if (state.opened() && state.action().startsWith("move/")) {
                boolean paired = state.movedDepot() != null && states.stream().anyMatch(other -> other != state
                        && state.movedDepot().equals(other.depot()) && other.opened()
                        && other.action().startsWith("move/") && !state.action().equals(other.action()) && state.depot() != null
                        && state.depot().equals(other.movedDepot()));
                if (!paired) throw new VcsException("移动关系已变化或另一端位于项目外，请刷新后重试");
            }
        }
        List<String> command = new ArrayList<>();
        switch (operation) {
            case CHECKOUT -> {
                if (states.size() != 1 || states.getFirst().opened() || states.getFirst().haveRevision() <= 0) {
                    throw new VcsException("文件已迁出或尚未纳入版本控制，请刷新后重试");
                }
                command.add("edit");
            }
            case CANCEL_CHECKOUT -> {
                if (states.stream().anyMatch(state -> !state.opened())) {
                    throw new VcsException("文件当前没有迁出，请刷新后重试");
                }
                command.add("revert"); command.add("-k");
            }
            case REVERT -> {
                if (states.stream().allMatch(State::opened)) {
                    command.add("revert");
                    // Newly branched/integrated files have no workspace baseline; keep them like local adds.
                    if (states.size() == 1 && states.getFirst().haveRevision() == 0) command.add("-k");
                }
                else if (states.size() == 1 && !states.getFirst().opened() && states.getFirst().haveRevision() > 0) {
                    // Includes allwrite modifications and missing files, using the synced revision.
                    return new String[]{"sync", "-f", PerforcePreview.encodePath(states.getFirst().path())
                            + "#" + states.getFirst().haveRevision()};
                } else throw new VcsException("文件状态已变化或没有基准版本，请刷新后重试");
            }
        }
        // Perforce reverts a move through move/add; that also reverts its paired move/delete.
        for (State state : states) {
            if (!"move/delete".equals(state.action())) command.add(PerforcePreview.encodePath(state.path()));
        }
        return command.toArray(String[]::new);
    }
}
