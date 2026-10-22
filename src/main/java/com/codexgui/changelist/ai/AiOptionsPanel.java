package com.codexgui.changelist.ai;

import com.codexgui.i18n.UiMessages;

import com.codexgui.settings.CodexSettingsState;
import com.codexgui.service.CodexModelCatalog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Each annotation context saves its own inference preferences. Editing never starts a request. */
public final class AiOptionsPanel extends JPanel {
    private final String defaultModel = UiMessages.message("CLI 默认模型");
    private final JLabel provider = new JLabel();
    private final JComboBox<String> model = new JComboBox<>(), effort = new JComboBox<>();
    private final JCheckBox fast = new JCheckBox(UiMessages.message("Fast（更多额度）"));
    private final CodexSettingsState settings;
    private final CodexSettingsState.AnnotationScope scope;
    private final Runnable settingsChanged;
    private List<String> catalog;
    private AiOptions displayed, frozen;
    private boolean adjusting, catalogChanged = true;
    private Runnable changed = () -> {};
    public AiOptionsPanel(CodexSettingsState.AnnotationScope scope) {
        this(CodexSettingsState.getInstance(), scope, List.of(), () -> {});
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var loaded = CodexModelCatalog.load();
            ApplicationManager.getApplication().invokeLater(() -> {
                catalog = loaded; catalogChanged = true; refresh();
            }, ModalityState.any());
        });
    }
    AiOptionsPanel(CodexSettingsState settings, CodexSettingsState.AnnotationScope scope,
                   List<String> catalog, Runnable settingsChanged) {
        super(new FlowLayout(FlowLayout.LEADING, 6, 3));
        this.settings = settings; this.scope = scope; this.catalog = List.copyOf(catalog); this.settingsChanged = settingsChanged;
        model.setEditable(true); model.setPrototypeDisplayValue("gpt-6.1-sol          ");
        model.setName("annotationModel"); effort.setName("annotationEffort"); fast.setName("annotationFast");
        model.getAccessibleContext().setAccessibleName(UiMessages.message("AI 注释模型"));
        effort.getAccessibleContext().setAccessibleName(UiMessages.message("AI 注释思考程度"));
        var context = scope == CodexSettingsState.AnnotationScope.DIFF ? UiMessages.message("单文件 Diff 注释") : UiMessages.message("批量 AI 注释");
        model.setToolTipText(context + UiMessages.message("独立保存模型；可从本地缓存选择或手动输入完整模型 ID"));
        effort.setToolTipText(context + UiMessages.message("独立保存思考程度，不随聊天设置变化"));
        fast.setToolTipText(context + UiMessages.message("独立保存 Fast；修改只影响后续请求"));
        provider.setToolTipText(UiMessages.message("供应商和 CLI 路径在 CodeDeck 中管理"));
        Arrays.stream(CodexSettingsState.ReasoningEffort.values()).map(CodexSettingsState.ReasoningEffort::value)
                .forEach(effort::addItem);
        add(provider); add(new JLabel(UiMessages.message("模型:"))); add(model); add(new JLabel(UiMessages.message("思考:"))); add(effort); add(fast);
        model.addActionListener(e -> save()); effort.addActionListener(e -> save()); fast.addActionListener(e -> save());
        model.getEditor().getEditorComponent().addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { save(); }
        });
        refresh();
    }
    public AiOptions options() {
        // Also commit an edited ID when an action reads the options before Swing transfers focus.
        if (frozen == null) save();
        return current();
    }
    private AiOptions current() {
        return frozen != null ? frozen : AiOptions.from(settings, scope);
    }
    private String selectedModel() {
        String value = Objects.toString(model.getEditor().getItem(), "").strip();
        return defaultModel.equals(value) ? "" : value;
    }
    private void save() {
        if (adjusting || displayed == null || frozen != null || !model.isEnabled()) return;
        String selected = selectedModel();
        String selectedEffort = Objects.toString(effort.getSelectedItem(), displayed.effort());
        if (selected.equals(displayed.model()) && selectedEffort.equals(displayed.effort()) && fast.isSelected() == displayed.fast()) return;
        // Refresh a provider switched elsewhere before writing, so an old model cannot leak into it.
        var current = current();
        if (!current.provider().id().equals(displayed.provider().id())
                || current.provider().revision() != displayed.provider().revision()) { refresh(); return; }
        var profile = settings.annotationModel(scope);
        boolean listed = false;
        for (int i = 0; i < model.getItemCount(); i++) if (selected.equals(model.getItemAt(i))) { listed = true; break; }
        if (!selected.isBlank() && !listed && !profile.customModels.contains(selected)) profile.customModels.add(0, selected);
        settings.setAnnotationOptions(scope, selected, selectedEffort, fast.isSelected());
        refresh(); settingsChanged.run();
    }
    private void refresh() {
        var next = current();
        boolean different = !Objects.equals(next, displayed);
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean editing = focus != null && SwingUtilities.isDescendingFrom(focus, model);
        if (!different && (!catalogChanged || editing || model.isPopupVisible() || !model.isEnabled())) return;
        displayed = next;
        adjusting = true;
        try {
            var models = new LinkedHashSet<String>(); models.add(defaultModel);
            var profile = settings.provider(next.provider().id());
            if (profile != null) models.addAll(profile.customModels);
            models.addAll(settings.annotationModel(scope).customModels);
            if (next.provider().builtIn()) models.addAll(catalog);
            if (!next.model().isBlank()) models.add(next.model());
            model.removeAllItems(); models.forEach(model::addItem);
            model.setSelectedItem(next.model().isBlank() ? defaultModel : next.model());
            effort.setSelectedItem(next.effort()); fast.setSelected(next.fast());
            provider.setText("GPT · " + (next.provider().builtIn()
                ? UiMessages.message("Codex 本地配置") : next.provider().name())); catalogChanged = false;
        } finally { adjusting = false; }
        if (different) changed.run();
    }
    public void onChanged(Runnable callback) { changed = callback; }
    void restore(AiOptions options) { frozen = options; refresh(); }
    public void setControlsEnabled(boolean enabled) {
        if (enabled) frozen = null;
        else if (frozen == null) frozen = displayed;
        model.setEnabled(enabled); effort.setEnabled(enabled); fast.setEnabled(enabled); refresh();
    }
}
