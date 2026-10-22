package com.codexgui.changelist.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.vcsUtil.VcsUtil;
import com.codexgui.changelist.core.*;
import com.codexgui.changelist.settings.*;
import com.codexgui.changelist.vcs.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.CancellablePromise;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class VcsToolWindowPanel extends JPanel implements Disposable {
    private final Project project;
    private final ToolWindow toolWindow;
    private final SidebarSettings settings;
    private final Tree tree = new Tree(new DefaultMutableTreeNode("VCS Changelist"));
    private final JBLabel summary = new JBLabel("读取 IDE 版本控制状态…");
    private final JBLabel status = new JBLabel("双击文本文件查看 diff；二进制文件仅展示");
    private final AtomicBoolean snapshotNotificationPending = new AtomicBoolean();
    private final FileOperationOverlay fileOperations = new FileOperationOverlay();
    private CancellablePromise<PreparedSnapshot> snapshotPromise;
    private volatile boolean disposed;
    private boolean snapshotAgain, vcsRefreshing, diffBusy, fileActionBusy, hasSnapshot;
    private boolean scanUnopenedEnabled;
    private ScheduledFuture<?> polling;
    private int pollingGeneration;
    private List<ChangeTree.Group> lastGroups = List.of();
    private Map<String, NativeChanges.DiffTarget> targets = Map.of();
    private record PreparedSnapshot(NativeChanges.Snapshot snapshot, DefaultMutableTreeNode root,
                                    Map<String, DefaultMutableTreeNode> nodes) {}

    VcsToolWindowPanel(Project project, ToolWindow toolWindow) {
        super(new BorderLayout()); this.project = project; this.toolWindow = toolWindow;
        settings = project.getService(SidebarSettings.class);
        scanUnopenedEnabled = settings.snapshot().scanUnopened;
        tree.setRootVisible(false); tree.setShowsRootHandles(true); tree.setCellRenderer(new Renderer());
        tree.getEmptyText().setText("当前没有本地 VCS 变更");
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setTransferHandler(new ChangeListFileTransferHandler(ProjectScope.forBasePath(project.getBasePath()).root()));
        tree.setDragEnabled(true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath clicked = tree.getClosestPathForLocation(event.getX(), event.getY());
                Rectangle bounds = clicked == null ? null : tree.getPathBounds(clicked);
                if (bounds == null || event.getY() < bounds.y || event.getY() >= bounds.y + bounds.height) clicked = null;
                if (clicked != null) tree.setSelectionPath(clicked); else tree.clearSelection();
                showContextMenu(clicked, event.getX(), event.getY());
                event.consume();
            }
            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() <= 2) {
                    TreePath clicked = tree.getPathForLocation(event.getX(), event.getY());
                    if (clicked != null) {
                        tree.setSelectionPath(clicked);
                        if (event.getClickCount() == 2) openSelected();
                        else focusExisting(node(clicked).file());
                    }
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "vcs.diff");
        tree.getActionMap().put("vcs.diff", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { openSelected(); }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "vcs.contextMenu");
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "vcs.contextMenu");
        tree.getActionMap().put("vcs.contextMenu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                TreePath selected = tree.getSelectionPath();
                Rectangle bounds = selected == null ? null : tree.getPathBounds(selected);
                showContextMenu(selected, bounds == null ? 8 : bounds.x, bounds == null ? 8 : bounds.y + bounds.height);
            }
        });
        JPanel header = new JPanel(new BorderLayout());
        var toolbar = ActionManager.getInstance().createActionToolbar("VcsChangelistSidebar.Toolbar", actions(), true);
        toolbar.setTargetComponent(tree); header.add(toolbar.getComponent(), BorderLayout.NORTH);
        summary.setBorder(JBUI.Borders.empty(4, 8)); header.add(summary, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH); add(new JBScrollPane(tree), BorderLayout.CENTER);
        status.setBorder(JBUI.Borders.empty(5, 8)); add(status, BorderLayout.SOUTH);

        var bus = project.getMessageBus().connect(this);
        bus.subscribe(ChangeListListener.TOPIC, new ChangeListListener() {
            private void updated() { queueSnapshot(); }
            @Override public void changeListUpdateDone() { fileOperations.nativeUpdateCompleted(); updated(); }
            @Override public void changeListAdded(ChangeList list) { updated(); }
            @Override public void changeListRemoved(ChangeList list) { updated(); }
            @Override public void changeListChanged(ChangeList list) { updated(); }
            @Override public void changeListRenamed(ChangeList list, String oldName) { updated(); }
            @Override public void changesMoved(Collection<? extends Change> items, ChangeList from, ChangeList to) { updated(); }
            @Override public void defaultListChanged(ChangeList from, ChangeList to) { updated(); }
            @Override public void allChangeListsMappingsChanged() { updated(); }
            @Override public void changeListAvailabilityChanged() { updated(); }
            @Override public void changedFileStatusChanged() { updated(); }
            @Override public void changedFileStatusChanged(boolean upToDate) { updated(); }
            @Override public void unchangedFileStatusChanged() { updated(); }
            @Override public void unchangedFileStatusChanged(boolean upToDate) { updated(); }
        });
        bus.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, () -> {
            fileOperations.clear();
            queueSnapshot();
            ApplicationManager.getApplication().invokeLater(() -> { if (!expired()) refreshUnopened(true); });
        });
        bus.subscribe(UnopenedChanges.CHANGED, new UnopenedChanges.Listener() {
            @Override public void changed() { queueSnapshot(); }
            @Override public void pathsChanged(List<Path> paths) {
                var scanner = project.getService(UnopenedChanges.class);
                if (scanner != null) fileOperations.localChanges(paths, scanner.snapshot());
                queueSnapshot();
            }
        });
        bus.subscribe(SidebarSettings.CHANGED, () -> {
            configurePolling(); requestSnapshot();
            boolean enabled = settings.snapshot().scanUnopened;
            if (scanUnopenedEnabled != enabled) { scanUnopenedEnabled = enabled; refreshUnopened(true); }
        });
        configurePolling(); requestSnapshot();
        ProjectLevelVcsManager.getInstance(project).runAfterInitialization(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!expired()) { requestSnapshot(); refreshUnopened(true); }
                }));
    }

    private DefaultActionGroup actions() {
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(action("刷新 VCS", AllIcons.Actions.Refresh, this::refreshVcs));
        actions.add(action("显示 Diff（Enter）", AllIcons.Actions.Diff, this::openSelected));
        actions.add(action("AI 注释选中范围（文件 / 目录 / Changelist）", AllIcons.General.Information,
                () -> showAiBatch(tree.getSelectionPath())));
        actions.add(new AutoRefreshAction()); actions.addSeparator();
        actions.add(action("展开所有目录", AllIcons.Actions.Expandall, () -> {
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        }));
        actions.add(action("折叠所有目录", AllIcons.Actions.Collapseall, () -> {
            for (int i = tree.getRowCount() - 1; i >= 0; i--) tree.collapseRow(i);
        }));
        actions.add(action("忽略列表 / 恢复跟踪", AllIcons.General.Filter, this::showIgnoreList));
        actions.add(action("侧栏设置", AllIcons.General.Settings,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SidebarConfigurable.class)));
        actions.add(action("IDE 版本控制连接", AllIcons.Nodes.PpWeb,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "vcs")));
        return actions;
    }

    private DumbAwareAction action(String label, Icon icon, Runnable callback) {
        return new DumbAwareAction(label, label, icon) {
            @Override public void actionPerformed(@NotNull AnActionEvent event) { callback.run(); }
        };
    }

    private void showContextMenu(TreePath selected, int x, int y) {
        if (expired()) return;
        var menu = new DefaultActionGroup();
        ChangeTree.Node entry = selected == null ? null : node(selected);
        if (entry != null) menu.add(action("AI 注释此文件 / 目录 / Changelist…", AllIcons.General.Information,
                () -> showAiBatch(selected)));
        if (entry != null && entry.path() != null) {
            ChangedFile file = entry.file();
            Path path = entry.path();
            if (file != null) {
                menu.add(menuAction("显示 / 跳转到 Diff", AllIcons.Actions.Diff, !file.binary(), () -> openFileDiff(file)));
                menu.add(menuAction("在编辑器中打开", AllIcons.Actions.Edit,
                        !file.binary() && file.kind() != ChangedFile.Kind.DELETED,
                        () -> NodeFileActions.openInEditor(project, path, this::expired, this::setStatus)));
                menu.addSeparator();
                var target = targets.get(file.id());
                for (var operation : FileVcsOperations.Operation.values()) {
                    var vcsAction = menuAction(operation.label(), null,
                            !fileActionBusy && NodeVcsActions.available(project, operation, file, target),
                            () -> {
                                if (fileActionBusy) return;
                                fileActionBusy = true;
                                NodeVcsActions.perform(project, operation, file, target, this::expired,
                                        this::setStatus, this::updateChangedFiles, () -> fileActionBusy = false);
                            });
                    vcsAction.getTemplatePresentation().setDescription(operation.description());
                    menu.add(vcsAction);
                }
                menu.addSeparator();
            }
            menu.add(menuAction("在项目窗口中定位", AllIcons.General.Locate,
                    file == null || file.kind() != ChangedFile.Kind.DELETED,
                    () -> NodeFileActions.locateInProject(project, path, this::expired, this::setStatus)));
            menu.add(menuAction("在资源管理器中显示", AllIcons.Nodes.Folder, RevealFileAction.isSupported(),
                    () -> NodeFileActions.reveal(project, path, this::expired, this::setStatus)));
            menu.addSeparator();
            menu.add(action("复制绝对路径", AllIcons.Actions.Copy, () -> copyPath(path, false)));
            menu.add(action("复制项目相对路径", AllIcons.Actions.Copy, () -> copyPath(path, true)));
            menu.add(action(file == null ? "复制目录名" : "复制文件名", null,
                    () -> copyText(path.getFileName().toString())));
            if (file == null) {
                menu.addSeparator();
                menu.add(action("展开此目录及子目录", AllIcons.Actions.Expandall, () -> expandSubtree(selected)));
                menu.add(action("折叠此目录及子目录", AllIcons.Actions.Collapseall, () -> collapseSubtree(selected)));
            }
            menu.addSeparator();
            if (ProjectScope.forBasePath(project.getBasePath()).contains(path)) {
                menu.add(action(file == null ? "永久忽略此目录（含子目录）" : "永久忽略此文件", AllIcons.General.Filter,
                        () -> ignorePath(path, file == null)));
            }
            menu.add(action(file == null ? "刷新此目录" : "刷新此文件", AllIcons.Actions.Refresh,
                    () -> { refreshVcs(path, file == null); refreshUnopened(true); }));
        } else {
            menu.add(action("刷新 VCS", AllIcons.Actions.Refresh, this::refreshVcs));
        }
        menu.add(action("忽略列表 / 恢复跟踪…", AllIcons.General.Filter, this::showIgnoreList));
        menu.add(action("侧栏设置", AllIcons.General.Settings,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SidebarConfigurable.class)));
        var popup = ActionManager.getInstance().createActionPopupMenu("VcsChangelistSidebar.ContextMenu", menu);
        popup.setTargetComponent(tree);
        popup.getComponent().show(tree, x, y);
    }

    private void showIgnoreList() { if (!expired()) new IgnoreListDialog(project).show(); }

    private void showAiBatch(TreePath selected) {
        if (expired()) return;
        var sources = new ArrayList<com.codexgui.changelist.ai.AiBatchState.Source>();
        if (selected != null) collectAiSources(node(selected), sources);
        else for (var group : lastGroups) for (var file : group.files()) addAiSource(file, sources);
        if (sources.isEmpty() && project.getService(com.codexgui.changelist.ai.AiSessionStore.class).lastBatch() == null) {
            setStatus("所选范围没有可供 AI 解释的文本变更"); return;
        }
        new com.codexgui.changelist.ai.AiBatchDialog(project, sources, source -> {
            if (expired()) return;
            NativeDiffOpener.open(project, source.path(), source.target(), this::expired, this::setStatus, () -> {});
        }).show();
    }
    private void collectAiSources(ChangeTree.Node node, List<com.codexgui.changelist.ai.AiBatchState.Source> sources) {
        if (node.file() != null) addAiSource(node.file(), sources);
        else for (var child : node.children()) collectAiSources(child, sources);
    }
    private void addAiSource(ChangedFile file, List<com.codexgui.changelist.ai.AiBatchState.Source> sources) {
        var target = targets.get(file.id());
        if (!file.binary() && target != null && sources.stream().noneMatch(s -> s.path().equals(file.path())))
            sources.add(new com.codexgui.changelist.ai.AiBatchState.Source(file.path(), target));
    }

    private void ignorePath(Path path, boolean directory) {
        if (expired()) return;
        String entry = IgnoreRules.literalPath(ProjectScope.forBasePath(project.getBasePath()), path, directory);
        var data = settings.snapshot();
        if (data.ignoredPaths.stream().noneMatch(entry::equalsIgnoreCase)) data.ignoredPaths.add(entry);
        // Re-ignoring a directory also removes explicit restorations within that subtree.
        data.restoredPaths.removeIf(restored -> restored.equalsIgnoreCase(entry)
                || (directory && restored.regionMatches(true, 0, entry, 0, entry.length())));
        settings.update(project, data);
        setStatus("已永久忽略：" + entry + " · 可在忽略列表中恢复跟踪");
    }

    private void refreshUnopened(boolean force) {
        if (expired()) return;
        var scanner = project.getService(UnopenedChanges.class);
        if (scanner != null) scanner.refresh(force);
    }

    private DumbAwareAction menuAction(String label, Icon icon, boolean enabled, Runnable callback) {
        return new DumbAwareAction(label, label, icon) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(enabled); }
            @Override public void actionPerformed(@NotNull AnActionEvent event) { if (enabled && !expired()) callback.run(); }
        };
    }

    private void copyPath(Path path, boolean relative) {
        var scope = ProjectScope.forBasePath(project.getBasePath());
        if (scope.root() == null || (!scope.contains(path) && !scope.root().equals(path.normalize()))) {
            setStatus("已跳过项目目录外的路径"); return;
        }
        String text = relative ? scope.root().relativize(path.normalize()).toString().replace('\\', '/') : path.toString();
        copyText(text.isEmpty() ? "." : text);
    }
    private void copyText(String text) { CopyPasteManager.copyTextToClipboard(text); setStatus("已复制到剪贴板"); }

    private void expandSubtree(TreePath selected) {
        int row = tree.getRowForPath(selected);
        if (row < 0) return;
        // Expanding inserts child rows; stop at the first row outside the selected branch.
        for (int i = row; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            if (!selected.isDescendant(path)) break;
            tree.expandPath(path);
        }
    }
    private void collapseSubtree(TreePath selected) {
        var expanded = tree.getExpandedDescendants(selected);
        if (expanded != null) Collections.list(expanded).stream()
                .sorted(Comparator.comparingInt(TreePath::getPathCount).reversed()).forEach(tree::collapsePath);
        tree.collapsePath(selected);
    }
    private final class AutoRefreshAction extends ToggleAction implements DumbAware {
        AutoRefreshAction() { super("定时刷新（访问服务器）", "默认关闭；启用后窗口显示时定期请求 VCS，可能产生大量流量", AllIcons.Actions.Refresh); }
        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public boolean isSelected(@NotNull AnActionEvent event) { return settings.snapshot().autoRefresh; }
        @Override public void setSelected(@NotNull AnActionEvent event, boolean selected) {
            var data = settings.snapshot(); data.autoRefresh = selected; settings.update(project, data);
        }
    }

    private void configurePolling() {
        if (polling != null) polling.cancel(false);
        int generation = ++pollingGeneration;
        var data = settings.snapshot(); if (!data.autoRefresh) return;
        int seconds = Math.max(5, Math.min(300, data.refreshSeconds));
        polling = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!expired() && generation == pollingGeneration && settings.snapshot().autoRefresh
                            && toolWindow.isVisible()) { refreshProjectScope(); refreshUnopened(false); }
                }), seconds, seconds, TimeUnit.SECONDS);
    }
    private boolean expired() { return disposed || project.isDisposed(); }
    private void queueSnapshot() {
        if (!snapshotNotificationPending.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            snapshotNotificationPending.set(false);
            if (!expired()) requestSnapshot();
        });
    }

    private void refreshVcs() {
        if (expired()) return;
        var scope = ProjectScope.forBasePath(project.getBasePath());
        if (scope.root() == null || !ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
            requestSnapshot(); return;
        }
        setStatus("已请求 IDE 原生 VCS 刷新，等待变更更新…");
        // Invoke the native refresh action so each supported IDE performs its own preparation.
        var nativeRefresh = ActionManager.getInstance().getAction("Vcs.RefreshStatuses");
        if (nativeRefresh != null) ActionManager.getInstance().tryToExecute(nativeRefresh, null, tree, "CodeDeck.Changes.Refresh", true);
        else refreshVcs(scope.root(), true);
        refreshUnopened(true);
        // The action launches asynchronously. An invokeAfterUpdate here could finish before that
        // launch starts; ChangeListListener.changeListUpdateDone rebuilds the tree on completion.
    }

    private void refreshProjectScope() {
        if (expired()) return;
        var scope = ProjectScope.forBasePath(project.getBasePath());
        if (scope.root() == null) { requestSnapshot(); return; }
        refreshVcs(scope.root(), true);
    }

    private void updateChangedFiles(List<Path> paths, FileVcsOperations.Result result) {
        if (result != null) fileOperations.record(result);
        var scanner = project.getService(UnopenedChanges.class);
        if (scanner != null) {
            if (result != null) scanner.operationCompleted(result);
            scanner.refreshPaths(paths);
        }
        // File commands already report their server result. Never dirty or schedule the native VCS here:
        // Perforce's change provider expands even a file-dirty request into a workspace status scan.
        requestSnapshot();
    }
    private void refreshVcs(Path path, boolean directory) {
        if (expired() || vcsRefreshing) return;
        var scope = ProjectScope.forBasePath(project.getBasePath());
        if (scope.root() == null || (!scope.contains(path) && !scope.root().equals(path.normalize()))) {
            requestSnapshot(); return;
        }
        if (!ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) { requestSnapshot(); return; }
        vcsRefreshing = true;
        setStatus("正在刷新 IDE 版本控制状态…");
        // The active Perforce/Git provider performs this refresh using its existing connection and credentials.
        var dirty = VcsDirtyScopeManager.getInstance(project);
        var filePath = VcsUtil.getFilePath(path, directory);
        if (directory) dirty.dirDirtyRecursively(filePath); else dirty.fileDirty(filePath);
        ChangeListManager.getInstance(project).invokeAfterUpdate(true, () -> {
            vcsRefreshing = false;
            if (!expired()) requestSnapshot();
        });
    }

    private void requestSnapshot() {
        if (expired()) return;
        if (snapshotPromise != null) { snapshotAgain = true; return; }
        boolean showUnversioned = settings.snapshot().showUnversioned;
        long operationRevision = fileOperations.revision();
        var promise = ReadAction.nonBlocking(() -> {
            var nativeSnapshot = NativeChanges.read(project, showUnversioned);
            var ignores = settings.snapshot().ignoreRules(ProjectScope.of(nativeSnapshot.projectRoot()));
            var snapshot = fileOperations.apply(nativeSnapshot, showUnversioned, path -> !ignores.isIgnored(path));
            var model = ChangeTree.build(snapshot.groups(), snapshot.projectRoot(), ProgressManager::checkCanceled);
            Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
            return new PreparedSnapshot(snapshot, swingNode(model, nodes), nodes);
        }).expireWhen(() -> expired() || fileOperations.revision() != operationRevision).expireWith(this)
                .finishOnUiThread(ModalityState.nonModal(), prepared -> {
                    if (!expired()) updateTree(prepared);
                }).submit(AppExecutorUtil.getAppExecutorService());
        snapshotPromise = promise;
        promise.onError(error -> {
            if (!(error instanceof java.util.concurrent.CancellationException)
                    && !(error instanceof com.intellij.openapi.progress.ProcessCanceledException)) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!expired()) setStatus("读取 IDE 变更失败：" + error.getMessage());
                });
            }
        });
        // Also runs after cancellation/error, so the next refresh cannot get stuck behind an unfinished flag.
        promise.onProcessed(ignored -> ApplicationManager.getApplication().invokeLater(() -> {
            if (snapshotPromise != promise) return;
            snapshotPromise = null;
            if (snapshotAgain && !expired()) { snapshotAgain = false; requestSnapshot(); }
        }));
    }

    private void updateTree(PreparedSnapshot prepared) {
        var snapshot = prepared.snapshot();
        boolean first = !hasSnapshot;
        if (first || !lastGroups.equals(snapshot.groups())) {
            Set<String> previousGroups = new HashSet<>();
            lastGroups.stream().filter(group -> !group.files().isEmpty())
                    .forEach(group -> previousGroups.add("group/" + group.id()));
            Set<String> expanded = new HashSet<>();
            var enumeration = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
            if (enumeration != null) while (enumeration.hasMoreElements()) {
                Object data = ((DefaultMutableTreeNode) enumeration.nextElement().getLastPathComponent()).getUserObject();
                if (data instanceof ChangeTree.Node entry) expanded.add(entry.id());
            }
            String selection = tree.getSelectionPath() == null ? null : node(tree.getSelectionPath()).id();
            DefaultMutableTreeNode root = prepared.root();
            tree.setModel(new DefaultTreeModel(root));
            // Only revisit visible/selected nodes on EDT, rather than walking every changed file.
            for (int i = 0; i < root.getChildCount(); i++) {
                var entry = (DefaultMutableTreeNode) root.getChildAt(i);
                String id = ((ChangeTree.Node) entry.getUserObject()).id();
                if (first || !previousGroups.contains(id)) expanded.add(id);
            }
            for (String id : expanded) {
                var entry = prepared.nodes().get(id);
                if (entry != null) tree.expandPath(new TreePath(entry.getPath()));
            }
            var selected = prepared.nodes().get(selection);
            if (selected != null) tree.setSelectionPath(new TreePath(selected.getPath()));
        }
        // Always refresh native Change handles, even when paths/statuses have not changed.
        lastGroups = snapshot.groups(); targets = snapshot.targets(); hasSnapshot = true;
        long count = lastGroups.stream().mapToLong(g -> g.files().size()).sum();
        long binary = lastGroups.stream().flatMap(g -> g.files().stream()).filter(ChangedFile::binary).count();
        boolean configured = !snapshot.vcsNames().isBlank();
        boolean scoped = snapshot.projectRoot() != null;
        summary.setText(!scoped ? "无法确定当前项目目录" : configured
                ? snapshot.vcsNames() + " · 当前项目 · " + count + " 个文件 · 二进制 " + binary : "项目尚未配置版本控制");
        var scanner = project.getService(UnopenedChanges.class);
        String scanStatus = scanner == null ? "" : scanner.status();
        summary.setToolTipText(scoped ? "范围：" + snapshot.projectRoot() + (scanStatus.isBlank() ? "" : " · " + scanStatus)
                : "请打开具体项目目录；磁盘根目录不作为捕获范围");
        tree.getEmptyText().setText(!scoped ? "请打开具体项目目录" : configured
                ? "当前项目没有本地 VCS 变更" : "请先在 IDE 的 Version Control 中配置 Perforce / Git");
        if (!scoped) { setStatus("未读取变更：无法确定具体项目目录"); return; }
        if (!diffBusy && !vcsRefreshing && !fileActionBusy) setStatus(configured
                ? (scanStatus.isBlank() ? "已同步 " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " · 双击文本文件查看 diff" : scanStatus)
                : "点击工具栏的版本控制连接按钮，复用 IDE 配置");
    }

    private static DefaultMutableTreeNode swingNode(ChangeTree.Node node, Map<String, DefaultMutableTreeNode> nodes) {
        ProgressManager.checkCanceled();
        var result = new DefaultMutableTreeNode(node);
        nodes.put(node.id(), result);
        node.children().forEach(child -> result.add(swingNode(child, nodes)));
        return result;
    }
    private static ChangeTree.Node node(TreePath path) {
        return (ChangeTree.Node) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }
    private void openSelected() {
        if (expired() || tree.getSelectionPath() == null) return;
        openFileDiff(node(tree.getSelectionPath()).file());
    }
    private boolean focusExisting(ChangedFile file) {
        if (expired() || file == null || file.binary()
                || !ProjectScope.forBasePath(project.getBasePath()).contains(file.path())) return false;
        if (!NativeDiffTabs.focusExisting(project, file.path())) return false;
        setStatus("已切换到此文件的 VCS Diff"); return true;
    }
    private void openFileDiff(ChangedFile file) {
        if (expired() || file == null) return;
        if (!ProjectScope.forBasePath(project.getBasePath()).contains(file.path())) {
            setStatus("已跳过项目目录外的文件"); requestSnapshot(); return;
        }
        if (file.binary()) { setStatus("已跳过二进制文件：" + file.path().getFileName()); return; }
        if (focusExisting(file)) return;
        var target = targets.get(file.id()); if (target == null) { requestSnapshot(); return; }
        if (diffBusy) { setStatus("正在读取上一个 diff，请稍候"); return; }
        diffBusy = true;
        NativeDiffOpener.open(project, file.path(), target, this::expired, this::setStatus, () -> diffBusy = false);
    }
    private void setStatus(String text) {
        String safe = text == null ? "未知错误" : text.replace('\n', ' ').replace('\r', ' ');
        status.setText(safe.startsWith("<html>") ? " " + safe : safe); status.setToolTipText(status.getText());
    }
    @Override public void dispose() {
        disposed = true; if (polling != null) polling.cancel(false);
        if (snapshotPromise != null) snapshotPromise.cancel();
        ToolTipManager.sharedInstance().unregisterComponent(tree);
    }

    private static final class Renderer extends ColoredTreeCellRenderer {
        @Override public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                                    boolean expanded, boolean leaf, int row, boolean focused) {
            Object object = ((DefaultMutableTreeNode) value).getUserObject();
            if (!(object instanceof ChangeTree.Node data)) return;
            ChangedFile file = data.file();
            if (file == null) {
                setIcon(data.id().startsWith("group/") && !data.id().contains("/dir/")
                        ? AllIcons.Vcs.Changelist : AllIcons.Nodes.Folder);
                append(data.label()); setToolTipText(data.path() == null ? null : data.path().toString()); return;
            }
            var icon = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                    .getFileTypeByFileName(file.path().getFileName().toString()).getIcon();
            setIcon(icon == null ? AllIcons.FileTypes.Unknown : icon);
            Color color = switch (file.kind()) {
                case ADDED, UNVERSIONED -> new JBColor(0x218739, 0x73B976);
                case DELETED -> new JBColor(0xA33A3A, 0xD98282);
                default -> new JBColor(0x2874B5, 0x78A9E2);
            };
            append(data.label(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
            append("  [" + file.kind().label() + "]" + (file.binary() ? " [二进制]" : ""), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            setToolTipText(file.path().toString());
        }
    }
}
