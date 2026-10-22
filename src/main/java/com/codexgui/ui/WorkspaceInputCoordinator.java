package com.codexgui.ui;

import com.codexgui.conversation.ConversationSession;
import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;
import com.codexgui.service.ProjectFileSearch;
import com.codexgui.service.WorkspacePathPolicy;
import com.google.gson.JsonArray;
import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDSupport;
import com.intellij.ide.dnd.FileCopyPasteUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.JFileChooser;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class WorkspaceInputCoordinator implements Disposable {
    private static final long PROJECT_FILE_CACHE_MILLIS = 2_000L;

    interface Listener {
        void attachmentsChanged(ConversationSession session);
        void fileReferencesChanged(ConversationSession session);
        void nativeDragChanged(boolean active);
        void nativeDrop(double x, double y);
        void projectFilesLoaded(long requestId, List<ProjectFileSearch.Candidate> files);
    }

    private final Project project;
    private final JPanel owner;
    private final JBCefBrowser browser;
    private final Path root;
    private final Supplier<ConversationSession> activeSession;
    private final Listener listener;

    private volatile List<Path> pendingDraggedPaths = List.of();
    private volatile ComposerDropRegion composerDropRegion;
    private volatile boolean nativeDragActive;
    private volatile List<ProjectFileSearch.Candidate> projectFileCatalog = List.of();
    private volatile long projectFileCatalogLoadedAt;

    WorkspaceInputCoordinator(
        Project project,
        JPanel owner,
        JBCefBrowser browser,
        Path root,
        Supplier<ConversationSession> activeSession,
        Listener listener
    ) {
        this.project = project;
        this.owner = owner;
        this.browser = browser;
        this.root = root;
        this.activeSession = activeSession;
        this.listener = listener;
    }

    void install(Disposable parent) {
        // CEF 提供本地绝对路径，页面 drop 事件再确认文件确实落在输入卡内。
        browser.getJBCefClient().addDragHandler((cefBrowser, dragData, mask) -> {
            var fileNames = new Vector<String>();
            var paths = dragData.isFile() && dragData.getFileNames(fileNames)
                ? fileNames.stream().map(this::resolvePath).filter(Objects::nonNull).toList()
                : List.<Path>of();
            if (paths.isEmpty() && dragData.isFragment()) paths = resolveTextPaths(dragData.getFragmentText());
            pendingDraggedPaths = paths;
            publishNativeDragState(!paths.isEmpty());
            return false;
        }, browser.getCefBrowser());

        installDropTarget(browser.getComponent(), parent);
    }

    void installDropTarget(JComponent component, Disposable parent) {
        // Project View 使用内部 DnD，ChangeList / 系统文件使用标准 Transferable；两条入口都要接收。
        DnDSupport.createBuilder(component)
            .disableAsSource()
            .enableAsNativeTarget()
            .setTargetChecker(event -> {
                var canDrop = isInsideComposer(event, component) && !nativeDroppedFiles(event).isEmpty();
                event.setDropPossible(canDrop, canDrop ? "添加文件引用" : "请拖到聊天输入框");
                publishNativeDragState(canDrop);
                return true;
            })
            .setDropHandler(event -> {
                // 原生项目树拖放不经过浏览器 drop 事件，先把释放坐标交给输入框定位逻辑。
                publishNativeDropPosition(event, component);
                publishNativeDragState(false);
                addDroppedInputs(nativeDroppedFiles(event));
            })
            .setCleanUpOnLeaveCallback(() -> publishNativeDragState(false))
            .setDisposableParent(parent)
            .install();
    }

    void chooseAttachment(boolean image) {
        var session = activeSession.get();
        var chooser = new JFileChooser(root.toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle(image ? "选择图片" : "选择引用文件");
        if (image) chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "webp"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        for (var file : chooser.getSelectedFiles()) {
            var path = file.toPath();
            var kind = image || isImageAttachment(path) ? Attachment.Kind.IMAGE : Attachment.Kind.FILE;
            session.attachments().add(new Attachment(kind, file.getName(), path));
        }
        listener.attachmentsChanged(session);
    }

    void cancelDrop() {
        pendingDraggedPaths = List.of();
        publishNativeDragState(false);
    }

    void updateComposerDropRegion(double left, double top, double right, double bottom) {
        composerDropRegion = left >= 0 && top >= 0 && right > left && bottom > top
            ? new ComposerDropRegion(left, top, right, bottom)
            : null;
    }

    void addPendingDroppedInputs() {
        var droppedPaths = pendingDraggedPaths;
        pendingDraggedPaths = List.of();
        addDroppedInputs(droppedPaths);
    }

    void searchProjectFiles(String query, long requestId) {
        CompletableFuture.supplyAsync(
            () -> ProjectFileSearch.filter(projectFileCatalog(), query, 60),
            AppExecutorUtil.getAppExecutorService()
        ).thenAccept(files -> listener.projectFilesLoaded(requestId, files));
    }

    void removeAttachment(int index) {
        var session = activeSession.get();
        if (index < 0 || index >= session.attachments().size()) return;
        session.attachments().remove(index);
        listener.attachmentsChanged(session);
    }

    void removeFileReference(String id) {
        var session = activeSession.get();
        if (id.isBlank()) return;
        session.fileReferences().stream()
            .filter(reference -> reference.id().equals(id))
            .findFirst()
            .ifPresent(session::rememberFileReference);
        if (!session.fileReferences().removeIf(reference -> reference.id().equals(id))) return;
        listener.fileReferencesChanged(session);
    }

    void removeFileReferences(JsonArray ids) {
        var session = activeSession.get();
        if (ids == null || ids.isEmpty()) return;
        var removed = false;
        for (var value : ids) {
            var id = value.getAsString();
            session.fileReferences().stream()
                .filter(reference -> reference.id().equals(id))
                .findFirst()
                .ifPresent(session::rememberFileReference);
            removed |= session.fileReferences().removeIf(reference -> reference.id().equals(id));
        }
        if (removed) listener.fileReferencesChanged(session);
    }

    void addFileReferences(JsonArray paths) {
        var session = activeSession.get();
        if (paths == null) return;
        var added = 0;
        for (var value : paths) {
            var path = resolvePath(value.getAsString());
            if (!canReferencePath(root, path)) continue;
            session.addFileReference(FileReference.fromPath(path.toAbsolutePath().normalize()));
            added++;
        }
        if (added > 0) listener.fileReferencesChanged(session);
    }

    void reorderFileReferences(JsonArray ids) {
        var session = activeSession.get();
        if (ids == null) return;
        var ordered = new ArrayList<FileReference>();
        var seen = new HashSet<String>();
        for (var value : ids) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
            var id = value.getAsString();
            if (!seen.add(id)) return;
            var reference = session.knownFileReference(id);
            if (reference == null) return;
            ordered.add(reference);
        }
        // 草稿生命周期内保留已删除引用的元数据，使编辑器撤销可以恢复同一个稳定 ID。
        session.fileReferences().forEach(session::rememberFileReference);
        session.fileReferences().clear();
        session.fileReferences().addAll(ordered);
        listener.fileReferencesChanged(session);
    }

    boolean applySendReferenceOrder(ConversationSession session, String text, JsonArray ids) {
        var ordered = orderedSendReferences(session.fileReferences(), text, ids);
        if (ordered == null) return false;
        session.fileReferences().forEach(session::rememberFileReference);
        session.fileReferences().clear();
        session.fileReferences().addAll(ordered);
        return true;
    }

    void clearInputs(ConversationSession session) {
        session.attachments().clear();
        session.clearFileReferences();
        listener.attachmentsChanged(session);
        listener.fileReferencesChanged(session);
    }

    Path resolvePath(String rawPath) {
        try {
            var value = Objects.requireNonNullElse(rawPath, "").trim();
            if (value.startsWith("@")) value = value.substring(1).trim();
            var path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
            if (path.isAbsolute()) return path.normalize();
            return root == null ? path.toAbsolutePath().normalize() : root.resolve(path).normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static List<FileReference> orderedSendReferences(List<FileReference> references, String text, JsonArray ids) {
        var markerCount = text.chars().filter(character -> character == '\uFFFC').count();
        if (markerCount != ids.size() || ids.size() != references.size()) return null;
        var remaining = new ArrayList<>(references);
        var ordered = new ArrayList<FileReference>();
        for (var value : ids) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            var id = value.getAsString();
            var match = remaining.stream().filter(reference -> reference.id().equals(id)).findFirst().orElse(null);
            if (match == null) return null;
            ordered.add(match);
            remaining.remove(match);
        }
        return List.copyOf(ordered);
    }

    private boolean isImageAttachment(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".gif") || name.endsWith(".webp");
    }

    private List<Path> resolveTextPaths(String text) {
        if (text == null || text.isBlank()) return List.of();
        return text.lines().map(this::resolvePath).filter(Objects::nonNull).toList();
    }

    private List<Path> nativeDroppedFiles(DnDEvent event) {
        var paths = new LinkedHashSet<Path>();
        for (var file : FileCopyPasteUtil.getFileListFromAttachedObject(event.getAttachedObject())) {
            paths.add(file.toPath());
        }
        var transferred = FileCopyPasteUtil.getFiles(event);
        if (transferred != null) paths.addAll(transferred);
        return paths.stream()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filter(path -> canReferencePath(root, path))
            .toList();
    }

    private boolean isInsideComposer(DnDEvent event, JComponent component) {
        var region = composerDropRegion;
        if (region == null || component.getWidth() <= 0 || component.getHeight() <= 0) return false;
        var point = event.getPointOn(component);
        var x = point.getX() / component.getWidth();
        var y = point.getY() / component.getHeight();
        return region.contains(x, y);
    }

    private void publishNativeDragState(boolean active) {
        if (nativeDragActive == active) return;
        nativeDragActive = active;
        listener.nativeDragChanged(active);
    }

    private void publishNativeDropPosition(DnDEvent dragEvent, JComponent component) {
        if (component.getWidth() <= 0 || component.getHeight() <= 0) return;
        var point = dragEvent.getPointOn(component);
        listener.nativeDrop(point.getX() / component.getWidth(), point.getY() / component.getHeight());
    }

    private void addDroppedInputs(List<Path> droppedPaths) {
        var session = activeSession.get();
        publishNativeDragState(false);
        var addedReferences = 0;
        var addedImages = 0;
        // 图片保留附件行为，普通文件和目录改为可移除的文件引用。
        for (var path : droppedPaths) {
            var normalized = path.toAbsolutePath().normalize();
            if (!canReferencePath(root, normalized)) continue;
            // 图片继续作为附件插入，供发送前预览和移除。
            if (Files.isRegularFile(normalized) && isImageAttachment(normalized)) {
                if (session.attachments().stream().anyMatch(item -> item.path().toAbsolutePath().normalize().equals(normalized))) continue;
                session.attachments().add(new Attachment(Attachment.Kind.IMAGE, normalized.getFileName().toString(), normalized));
                addedImages++;
                continue;
            }
            // 普通文件或目录作为引用标签插入，重复路径保持幂等。
            if (session.fileReferences().stream().anyMatch(item -> item.path().equals(normalized))) continue;
            session.addFileReference(FileReference.fromPath(normalized));
            addedReferences++;
        }
        // 拖拽结果已经通过输入框标签或附件列表展示，不再弹出遮挡输入框的提示。
        if (addedReferences > 0) listener.fileReferencesChanged(session);
        if (addedImages > 0) listener.attachmentsChanged(session);
    }

    private synchronized List<ProjectFileSearch.Candidate> projectFileCatalog() {
        var now = System.currentTimeMillis();
        if (now - projectFileCatalogLoadedAt < PROJECT_FILE_CACHE_MILLIS) return projectFileCatalog;
        projectFileCatalog = ProjectFileSearch.list(project);
        projectFileCatalogLoadedAt = now;
        return projectFileCatalog;
    }

    static boolean canReferencePath(Path root, Path path) {
        if (path == null) return false;
        if (Files.isRegularFile(path) || Files.isDirectory(path)) return true;
        // Deleted ChangeList entries still carry meaningful paths, but cannot become image attachments.
        return root != null && !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            && new WorkspacePathPolicy(root).normalize(path) != null;
    }

    @Override
    public void dispose() {
        pendingDraggedPaths = List.of();
        composerDropRegion = null;
        projectFileCatalog = List.of();
    }

    private record ComposerDropRegion(double left, double top, double right, double bottom) {
        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }
}
