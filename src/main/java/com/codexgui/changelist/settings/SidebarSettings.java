package com.codexgui.changelist.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import com.codexgui.changelist.core.IgnoreRules;
import com.codexgui.changelist.core.ProjectScope;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
@State(name = "VcsChangelistSidebar", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SidebarSettings implements PersistentStateComponent<SidebarSettings.Data> {
    public interface Listener { void changed(); }
    public static final Topic<Listener> CHANGED = Topic.create("VCS sidebar settings changed", Listener.class);
    public static final class Data {
        public boolean autoRefresh = false;
        public int refreshSeconds = 60;
        public boolean showUnversioned = true;
        public boolean scanUnopened = false;
        // Kept only to read old standalone workspace files. AI requests use CodexSettingsState.
        public String codexExecutable = "";
        public String aiModel = "";
        public String aiEffort = "";
        public boolean aiFast = false;
        public List<String> ignoreTemplates = new ArrayList<>();
        public String ignorePatterns = "";
        public List<String> ignoredPaths = new ArrayList<>();
        public List<String> restoredPaths = new ArrayList<>();
        public int placementVersion;
        public int projectScopeVersion;
        public int networkActivityVersion;
        public Data copy() {
            Data copy = new Data(); copy.autoRefresh = autoRefresh;
            copy.refreshSeconds = refreshSeconds; copy.showUnversioned = showUnversioned;
            copy.scanUnopened = scanUnopened;
            copy.codexExecutable = codexExecutable == null ? "" : codexExecutable;
            copy.aiModel = aiModel == null ? "" : aiModel;
            copy.aiEffort = aiEffort == null ? "" : aiEffort;
            copy.aiFast = aiFast;
            copy.ignoreTemplates = new ArrayList<>(ignoreTemplates == null ? List.of() : ignoreTemplates);
            copy.ignorePatterns = ignorePatterns == null ? "" : ignorePatterns;
            copy.ignoredPaths = new ArrayList<>(IgnoreRules.normalizePaths(ignoredPaths));
            copy.restoredPaths = new ArrayList<>(IgnoreRules.normalizePaths(restoredPaths));
            copy.placementVersion = placementVersion; copy.projectScopeVersion = projectScopeVersion;
            copy.networkActivityVersion = networkActivityVersion; return copy;
        }
        public IgnoreRules ignoreRules(ProjectScope scope) {
            return new IgnoreRules(scope, ignoreTemplates, ignorePatterns, ignoredPaths, restoredPaths);
        }
    }
    private static Data migrate(Data data) {
        Data copy = data.copy();
        if (copy.projectScopeVersion == 1) {
            // 1.0.2/1.0.3 incorrectly disabled this section while restricting the project boundary.
            // Recover its visibility once; later explicit choices must survive restarts.
            copy.showUnversioned = true;
        }
        if (copy.projectScopeVersion < 2) copy.projectScopeVersion = 2;
        if (copy.networkActivityVersion < 1) {
            // Older defaults continuously queried the server. Require a fresh opt-in after upgrading.
            copy.autoRefresh = false;
            copy.scanUnopened = false;
            copy.networkActivityVersion = 1;
        }
        return copy;
    }
    private volatile Data state = migrate(new Data());
    @Override public @NotNull Data getState() { return state; }
    @Override public void loadState(@NotNull Data data) { state = migrate(data); }
    public Data snapshot() { return state.copy(); }
    public void update(Project project, Data data) {
        state = data.copy(); project.getMessageBus().syncPublisher(CHANGED).changed();
    }
}
