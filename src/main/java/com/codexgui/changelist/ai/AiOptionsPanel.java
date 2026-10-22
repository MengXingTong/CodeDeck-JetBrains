package com.codexgui.changelist.ai;

import com.codexgui.settings.CodexSettingsState;
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

/** Editable views of the shared GPT preferences. Editing never starts an annotation request. */
public final class AiOptionsPanel extends JPanel {
    private static final String DEFAULT_MODEL = "跟随供应商配置";
    private final JLabel provider = new JLabel();
    private final JComboBox<String> model = new JComboBox<>(), effort = new JComboBox<>();
    private final JCheckBox fast = new JCheckBox("Fast（更多额度）");
    private final CodexSettingsState settings;
    private final Runnable settingsChanged;
    private List<String> catalog;
    private AiOptions displayed, frozen;
    private boolean adjusting, catalogChanged = true;
    private Runnable changed = () -> {};
    public AiOptionsPanel() {
        this(CodexSettingsState.getInstance(), List.of(), () -> ApplicationManager.getApplication()
                .getMessageBus().syncPublisher(CodexSettingsState.GPT_OPTIONS_CHANGED).run());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var loaded = AiModelCatalog.load();
            ApplicationManager.getApplication().invokeLater(() -> {
                catalog = loaded; catalogChanged = true; refresh();
            }, ModalityState.any());
        });
    }
    AiOptionsPanel(CodexSettingsState settings, List<String> catalog, Runnable settingsChanged) {
        super(new FlowLayout(FlowLayout.LEADING, 6, 3));
        this.settings = settings; this.catalog = List.copyOf(catalog); this.settingsChanged = settingsChanged;
        model.setEditable(true); model.setPrototypeDisplayValue("gpt-6.1-sol          ");
        model.setName("annotationModel"); effort.setName("annotationEffort"); fast.setName("annotationFast");
        model.getAccessibleContext().setAccessibleName("AI 注释模型");
        effort.getAccessibleContext().setAccessibleName("AI 注释思考程度");
        model.setToolTipText("与 CodeDeck GPT 共用模型选择；可从本地缓存选择或手动输入完整模型 ID");
        fast.setToolTipText("与 CodeDeck 共用 Fast 设置；修改只影响后续请求");
        provider.setToolTipText("供应商和 CLI 路径在 CodeDeck 中管理");
        Arrays.stream(CodexSettingsState.ReasoningEffort.values()).map(CodexSettingsState.ReasoningEffort::value)
                .forEach(effort::addItem);
        add(provider); add(new JLabel("模型:")); add(model); add(new JLabel("思考:")); add(effort); add(fast);
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
        return frozen != null ? frozen : AiOptions.from(settings.snapshot(CodexSettingsState.CODEX_CHANNEL));
    }
    private String selectedModel() {
        String value = Objects.toString(model.getEditor().getItem(), "").strip();
        return DEFAULT_MODEL.equals(value) ? "" : value;
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
        var profile = settings.activeProvider(CodexSettingsState.CODEX_CHANNEL);
        boolean listed = false;
        for (int i = 0; i < model.getItemCount(); i++) if (selected.equals(model.getItemAt(i))) { listed = true; break; }
        if (!selected.isBlank() && !listed && !profile.customModels.contains(selected)) profile.customModels.add(0, selected);
        settings.setGptInferenceOptions(selected, selectedEffort, fast.isSelected());
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
            var models = new LinkedHashSet<String>(); models.add(DEFAULT_MODEL);
            var profile = settings.provider(next.provider().id());
            if (profile != null) models.addAll(profile.customModels);
            if (next.provider().builtIn()) models.addAll(catalog);
            if (!next.model().isBlank()) models.add(next.model());
            model.removeAllItems(); models.forEach(model::addItem);
            model.setSelectedItem(next.model().isBlank() ? DEFAULT_MODEL : next.model());
            effort.setSelectedItem(next.effort()); fast.setSelected(next.fast());
            provider.setText("GPT · " + next.provider().name()); catalogChanged = false;
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
