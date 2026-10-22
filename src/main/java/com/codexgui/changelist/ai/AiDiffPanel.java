package com.codexgui.changelist.ai;

import com.intellij.diff.EditorDiffViewer;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.base.DiffViewerListener;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only the native Diff header and inline annotations. The project service owns requests. */
public final class AiDiffPanel extends JPanel implements Disposable {
    private final Project project;
    private final Path path;
    private final ContentDiffRequest request;
    private final EditorDiffViewer viewer;
    private final AiSessionStore store;
    private final AiOptionsPanel options;
    private final JButton generate = new JButton("AI 注释"), regenerate = new JButton("重新生成"), stop = new JButton("停止注释");
    private final JLabel status = new JLabel("点击 AI 注释生成当前文件的变更说明");
    private final List<Inlay<?>> inlays = new ArrayList<>();
    private final Timer timer = new Timer(1000, e -> updateStatus());
    private AiDiffSnapshot snapshot;
    private AiOptions activeOptions;
    private volatile boolean stale, disposed;
    private boolean attached;
    private String contextError;
    private final AtomicBoolean contextUpdatePending = new AtomicBoolean();
    private final AtomicBoolean layoutUpdatePending = new AtomicBoolean();

    AiDiffPanel(Project project, Path path, ContentDiffRequest request, EditorDiffViewer viewer) {
        super(new BorderLayout()); this.project = project; this.path = path; this.request = request; this.viewer = viewer;
        store = project.getService(AiSessionStore.class);
        options = new AiOptionsPanel(); options.onChanged(this::loadContext);
        add(options, BorderLayout.NORTH);
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 3)); buttons.add(generate); buttons.add(regenerate); buttons.add(stop);
        var actions = new JPanel(new BorderLayout()); actions.add(buttons, BorderLayout.WEST);
        actions.add(status, BorderLayout.CENTER); add(actions, BorderLayout.SOUTH);
        generate.addActionListener(e -> generate(false)); regenerate.addActionListener(e -> generate(true));
        regenerate.setToolTipText("忽略缓存，手动消耗额度重新生成当前文件注释");
        stop.addActionListener(e -> {
            var current = store.activeFile(path);
            if (current != null) store.cancel(project, current.task());
            updateStatus();
        });
        stop.setToolTipText("只停止此文件的注释任务；此文件由批量队列处理时停止所属队列");
        stop.setVisible(false);
        status.setBorder(JBUI.Borders.emptyLeft(6));
    }
    void attach() {
        attached = true;
        project.getMessageBus().connect(this).subscribe(AiSessionStore.CHANGED, key -> {
            if (disposed) return;
            if (!stale && snapshot != null && key.startsWith(snapshot.fingerprint() + ":")) renderInlays();
            updateStatus();
        });
        for (var content : request.getContents()) if (content instanceof DocumentContent document)
            document.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void documentChanged(@NotNull DocumentEvent event) {
                    // Unified Diff is still synchronizing its synthetic and source documents here.
                    // Defer all inlay/UI mutations until that document update has fully completed.
                    stale = true;
                    if (!contextUpdatePending.compareAndSet(false, true)) return;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        contextUpdatePending.set(false);
                        if (!disposed && stale) { clearInlays(); updateStatus(); }
                    }, ModalityState.any());
                }
            }, this);
        if (viewer instanceof DiffViewerBase base) base.addListener(new DiffViewerListener() {
            @Override protected void onAfterRediff() { if (!disposed) { if (stale) loadContext(); else renderInlays(); } }
        });
        for (var editor : viewer.getEditors()) {
            if (editor instanceof EditorEx extended) extended.addPropertyChangeListener(event -> scheduleInlayLayout(), this);
            editor.getScrollingModel().addVisibleAreaListener(event -> {
                var before = event.getOldRectangle();
                if (before == null || before.width != event.getNewRectangle().width
                        || before.x != event.getNewRectangle().x) scheduleInlayLayout();
            }, this);
            var listener = new ComponentAdapter() {
                @Override public void componentShown(ComponentEvent event) { scheduleInlayLayout(); }
                @Override public void componentResized(ComponentEvent event) { scheduleInlayLayout(); }
            };
            editor.getComponent().addComponentListener(listener);
            com.intellij.openapi.util.Disposer.register(this, () -> editor.getComponent().removeComponentListener(listener));
        }
        loadContext(); timer.start();
    }
    private void loadContext() {
        if (!attached || disposed) return;
        try {
            snapshot = AiNativeSnapshot.read(Path.of(project.getBasePath()), path, request);
            activeOptions = options.options(); stale = false; contextError = null;
            renderInlays(); updateStatus();
        } catch (RuntimeException error) { stale = true; contextError = error.getMessage(); clearInlays(); updateStatus(); }
    }
    private void generate(boolean force) {
        if (store.activeFile(path) != null) { updateStatus(); return; }
        loadContext(); if (stale || snapshot == null) return;
        if (!force && store.session(activeOptions.cacheKey(snapshot)).annotations != null) {
            status.setText("已显示缓存注释，未消耗额度；需要更新时点击重新生成"); return;
        }
        store.startRequest(project, request, snapshot, activeOptions); updateStatus();
    }
    private void updateStatus() {
        if (disposed) return;
        var current = store.activeFile(path);
        var active = current == null ? null : current.task();
        generate.setEnabled(active == null); regenerate.setEnabled(active == null);
        if (current != null) options.restore(current.options());
        options.setControlsEnabled(active == null);
        stop.setVisible(active != null); stop.setEnabled(active != null && active.running() && !active.canceled());
        if (active != null) {
            status.setText(active.displayStatus());
            status.setToolTipText(active.canceled() ? "正在终止当前请求；不会继续队列或自动重试"
                    : "点击停止注释可终止此文件的任务；切换文件可并行生成，关闭窗口后继续在后台运行");
        } else if (stale) {
            status.setText(contextError == null ? "代码已变化，旧注释已过期；点击 AI 注释可为当前内容生成" : contextError);
            status.setToolTipText(null);
        } else if (snapshot != null) {
            var session = store.session(activeOptions.cacheKey(snapshot));
            var displayed = store.displayedSession(snapshot, activeOptions);
            if (session.task != null) status.setText(session.task.displayStatus());
            else status.setText(displayed.annotations == null ? "点击 AI 注释生成当前文件的变更说明" : "已显示缓存注释，未请求 AI");
            status.setToolTipText(displayed.annotations == null ? null : displayed.annotations.summary());
        }
    }
    private void renderInlays() {
        clearInlays();
        if (!attached || stale || disposed || snapshot == null) return;
        var response = store.displayedSession(snapshot, activeOptions).annotations;
        if (response == null || viewer instanceof UnifiedDiffViewer unified && unified.isStateIsOutOfDate()) return;
        var beforeAnchor = new AiAnnotationAnchor(snapshot.before()); var afterAnchor = new AiAnnotationAnchor(snapshot.after());
        for (var annotation : response.annotations()) {
            Side side = annotation.side().equals("before") ? Side.LEFT : Side.RIGHT;
            Editor editor; int line = (side == Side.LEFT ? beforeAnchor : afterAnchor).endLine(annotation) - 1;
            if (viewer instanceof TwosideTextDiffViewer two) editor = two.getEditor(side);
            else if (viewer instanceof OnesideTextDiffViewer one && one.getSide() == side) editor = one.getEditor();
            else if (viewer instanceof UnifiedDiffViewer unified) {
                line = unified.transferLineToOnesideStrict(side, line); editor = unified.getEditor();
            } else continue;
            if (line < 0 || line >= editor.getDocument().getLineCount() || editor.isDisposed()) continue;
            int offset = editor.getDocument().getLineEndOffset(line);
            var inlay = ComponentInlayKt.addComponentInlay(editor, offset,
                    new InlayProperties().relatesToPrecedingText(true).showAbove(false),
                    new AiAnnotationComponent(editor, annotation), ComponentInlayAlignment.FIT_VIEWPORT_X_SPAN);
            if (inlay != null) inlays.add(inlay);
        }
        scheduleInlayLayout();
    }
    private void scheduleInlayLayout() {
        if (disposed || inlays.isEmpty() || !layoutUpdatePending.compareAndSet(false, true)) return;
        // Reopened viewers can report a zero-width viewport until Swing has laid out the editor.
        ApplicationManager.getApplication().invokeLater(() -> {
            layoutUpdatePending.set(false);
            if (disposed || stale) return;
            var hosts = new LinkedHashSet<Container>();
            for (var inlay : inlays) if (inlay.isValid() && !inlay.getEditor().isDisposed()) {
                if (inlay.getRenderer() instanceof ComponentInlayRenderer<?> renderer
                        && renderer.getComponent() instanceof JComponent component) {
                    if (component instanceof AiAnnotationComponent annotation) annotation.refreshFont();
                    component.revalidate();
                    var parent = component.getParent();
                    if (parent != null) hosts.add(parent);
                }
            }
            // The parent owns the renderer's cached size. Lay out each host once, before updating blocks.
            for (var host : hosts) { host.invalidate(); host.doLayout(); host.repaint(); }
            for (var inlay : inlays) if (inlay.isValid() && !inlay.getEditor().isDisposed()) inlay.update();
        }, ModalityState.any());
    }
    private void clearInlays() { disposeInlays(inlays); }
    static void disposeInlays(List<? extends Inlay<?>> inlays) {
        // dispose() removes editor space only. Disposer also removes component hosts and their listeners.
        // Invalidated inlays may still own live Swing components, so they need the same cleanup.
        for (var inlay : inlays) Disposer.dispose(inlay);
        inlays.clear();
    }
    @Override public void dispose() { disposed = true; timer.stop(); clearInlays(); }
}
