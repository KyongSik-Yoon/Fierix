package com.github.korniloval.fierix.ui.line.markers;

import com.github.kornilova_l.flamegraph.configuration.Configuration;
import com.github.korniloval.fierix.configuration.ConfigStorage;
import com.github.korniloval.fierix.configuration.PluginConfigManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.xdebugger.ui.DebuggerColors;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.github.korniloval.fierix.configuration.PluginConfigManager.*;

public class LineMarkersHolder extends AbstractProjectComponent {
    private static final Logger LOG = Logger.getInstance(LineMarkersHolder.class);
    private final HashMap<PsiMethod, RangeHighlighter> rangeHighlighters = new HashMap<>();
    private final Configuration configuration;

    protected LineMarkersHolder(Project project) {
        super(project);
        configuration = myProject.getComponent(ConfigStorage.class).getState();
    }

    public static MarkupModelEx getMarkupModel(Document document, Project project) {
        return (MarkupModelEx) DocumentMarkupModel.forDocument(document, project, true);
    }

    /**
     * For test
     */
    public int getLineMarkersCount() {
        return rangeHighlighters.size();
    }

    public synchronized void setIcon(PsiMethod psiMethod, MarkupModelEx markupModel) {
        try {
            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                    psiMethod.getTextOffset(),
                    psiMethod.getTextOffset() + 1,
                    DebuggerColors.BREAKPOINT_HIGHLIGHTER_LAYER,
                    null,
                    HighlighterTargetArea.EXACT_RANGE);
            highlighter.setGutterIconRenderer(
                    new ProfilerGutterIconRenderer(
                            configuration.getIncludingConfigs(
                                    PluginConfigManager.newMethodConfig(psiMethod)
                            )
                    )
            );
            rangeHighlighters.put(psiMethod, highlighter);
        } catch (ProcessCanceledException | IllegalArgumentException exception) {
            /* IllegalArgumentException may be thrown by markupModel if range is outdated */
            LOG.warn("Range is outdated", exception);
        }
    }

    /**
     * Before calling this method all PsiMethod instances in {@link #rangeHighlighters}
     * should be valid. To remove invalid instances call {@link #removeInvalidMethods(MarkupModelEx)}
     */
    public synchronized void removeIconIfPresent(PsiMethod method, MarkupModelEx markupModel) {
        for (PsiMethod methodWithIcon : rangeHighlighters.keySet()) {
            /* PsiMethod does not have equals() and hashCode() method
             * so we check all methods in set by isEquivalentTo() */
            try {
                if (methodWithIcon.isEquivalentTo(method)) {
                    RangeHighlighter highlighter = rangeHighlighters.get(methodWithIcon);
                    markupModel.removeHighlighter(highlighter);
                    rangeHighlighters.remove(methodWithIcon);
                    return;
                }
            } catch (AssertionError e) {
                /* it is thrown from com.intellij.psi.impl.source.tree.AstPath.ChildPath.getNode
                 * when isEquivalentTo() method is called
                 * I do not know why and how to fix it. It is okay to just ignore it */
                LOG.warn(e);
            }
        }
    }

    private void updateMethodMarker(@NotNull PsiFile psiFile, @NotNull Document document) {
        DumbService.getInstance(myProject).runWhenSmart(() ->
                new UpdatingPsiElementVisitor(myProject, getMarkupModel(document, myProject))
                        .visitElement(psiFile));
    }

    void updateMethodMarker(PsiMethod psiMethod, MarkupModelEx markupModel) {
        DumbService.getInstance(myProject).runWhenSmart(() -> {
            removeInvalidMethods(markupModel);
            if (configuration.isMethodInstrumented(getClassName(psiMethod), getMethodName(psiMethod),
                    getParametersTypesList(psiMethod.getParameterList().getParameters()))) {
                if (!hasIcon(psiMethod)) {
                    setIcon(psiMethod, markupModel);
                }
            } else {
                removeIconIfPresent(psiMethod, markupModel);
            }
        });
    }

    public synchronized void removeInvalidMethods(MarkupModelEx markupModel) {
        List<PsiMethod> invalidMethods = null;
        for (PsiMethod psiMethod : rangeHighlighters.keySet()) {
            if (!psiMethod.isValid()) {
                if (invalidMethods == null) {
                    invalidMethods = new ArrayList<>();
                }
                invalidMethods.add(psiMethod);
            }
        }
        if (invalidMethods != null) {
            for (PsiMethod invalidMethod : invalidMethods) {
                try {
                    markupModel.removeHighlighter(rangeHighlighters.get(invalidMethod));
                } catch (AssertionError ignored) {
                    /* Method is thrown if range is outdated.
                     * It means line marker was already removed. */
                }
                rangeHighlighters.remove(invalidMethod);
            }
        }
    }

    /**
     * @return true if there is an icon on gutter beside the method
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public synchronized boolean hasIcon(PsiMethod psiMethod) {
        for (PsiMethod methodWithIcon : rangeHighlighters.keySet()) {
            /* PsiMethod does not have equals() and hashCode() method
             * so we check all methods in set by isEquivalentTo() */
            if (methodWithIcon.isEquivalentTo(psiMethod)) {
                return true;
            }
        }
        return false;
    }

    void updateMethodMarker(@NotNull VirtualFile file) {
        DumbService.getInstance(myProject).runWhenSmart(() -> {
            PsiFile[] psiFiles = FilenameIndex.getFilesByName(
                    myProject,
                    file.getName(),
                    GlobalSearchScope.fileScope(myProject, file));
            if (psiFiles.length != 1) {
                return;
            }
            PsiFile psiFile = psiFiles[0];
            Document document = psiFile.getViewProvider().getDocument();
            if (document == null) {
                return;
            }
            updateMethodMarker(psiFile, document);
        });
    }


    public synchronized void updateOpenedDocuments() {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() != myProject) {
                continue;
            }
            Document document = editor.getDocument();
            VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
            if (virtualFile != null) {
                updateMethodMarker(virtualFile);
            }
        }
    }

    public synchronized void removeAllIcons(@NotNull VirtualFile file) {
        DumbService.getInstance(myProject).runWhenSmart(() -> {
            PsiFile[] psiFiles = FilenameIndex.getFilesByName(
                    myProject,
                    file.getName(),
                    GlobalSearchScope.fileScope(myProject, file));
            if (psiFiles.length != 1) {
                return;
            }
            PsiFile psiFile = psiFiles[0];
            Document document = psiFile.getViewProvider().getDocument();
            if (document == null) {
                return;
            }
            removeInvalidMethods(getMarkupModel(document, myProject));
            removeAllIcons(psiFile, document);
        });
    }

    private void removeAllIcons(PsiFile psiFile, Document document) {
        DumbService.getInstance(myProject).runWhenSmart(() ->
                new RemovingIconsPsiElementVisitor(myProject, getMarkupModel(document, myProject))
                        .visitElement(psiFile));
    }
}
