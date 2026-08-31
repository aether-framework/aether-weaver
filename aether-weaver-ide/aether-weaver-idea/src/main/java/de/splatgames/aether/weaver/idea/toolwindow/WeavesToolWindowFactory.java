package de.splatgames.aether.weaver.idea.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.ui.ColoredTreeCellRenderer;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Builds the Weaves tool window: every weave and every extension holder the project can see.
 *
 * <p>Declared in {@code plugin.xml} as the factory of the {@code Weaves} tool window. It is
 * {@link DumbAware} so that the window can be opened while the project is indexing; what it shows
 * then is a placeholder, because both models read a file-based index.
 *
 * <p>The content is a snapshot. Nothing listens for changes, and the tree is rebuilt only when the
 * window is created and by the toolbar's refresh action: a rebuild runs two index-wide queries and
 * resolves every declaration they return.
 *
 * <p>Everything shown is a plan rather than a result, which the footer states: which positions an
 * injection point matched is decided by the build, against compiled bytes the editor does not have.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeavesModel
 * @see ExtensionsModel
 */
public final class WeavesToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** The standing caveat under the tree: the window shows what is declared, not what was woven. */
    private static final String FOOTER =
            "Plan only. Which positions each injection point matched is decided by the build, "
                    + "against compiled bytes this editor does not have.";

    /** Creates the factory; the platform requires a no-argument constructor. */
    public WeavesToolWindowFactory() {
        // Stateless.
    }

    /**
     * Builds the window's single content: a toolbar, the tree, and the footer.
     *
     * <p>Called by the platform when the window is first opened. The tree is filled before the
     * content is added, so the window is never shown empty and then populated.
     *
     * @param project    the project the window belongs to
     * @param toolWindow the window to fill
     */
    @Override
    public void createToolWindowContent(@NotNull final Project project,
                                        @NotNull final ToolWindow toolWindow) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        final Tree tree = new Tree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setCellRenderer(new Renderer());
        navigateOnDoubleClick(tree);

        final JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolbar(project, tree, panel), BorderLayout.WEST);
        panel.add(new JBScrollPane(tree), BorderLayout.CENTER);
        panel.add(footer(), BorderLayout.SOUTH);
        refresh(project, tree);

        final Content content = ContentFactory.getInstance().createContent(panel, null, false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * Builds the vertical toolbar holding the refresh action.
     *
     * <p>The action captures the project and the tree it rebuilds, so it takes nothing from the
     * data context of whatever happens to be focused.
     *
     * @param project the project to refresh against
     * @param tree    the tree the action rebuilds
     * @param parent  the component the toolbar reports as its target
     * @return the toolbar component
     */
    @NotNull
    private static JComponent toolbar(@NotNull final Project project,
                                      @NotNull final Tree tree,
                                      @NotNull final JComponent parent) {
        final DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(new AnAction("Refresh", "Read the project's weaves again", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull final AnActionEvent event) {
                refresh(project, tree);
            }
        });
        final var toolbar = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, false);
        toolbar.setTargetComponent(parent);
        return toolbar.getComponent();
    }

    /**
     * Builds the caveat under the tree.
     *
     * @return the label, in the platform's small and dimmed style so that it reads as a note rather
     *         than as a row of the tree
     */
    @NotNull
    private static JComponent footer() {
        final JBLabel label = new JBLabel(FOOTER);
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        label.setBorder(BorderFactory.createEmptyBorder(
                JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));
        return label;
    }

    /**
     * Reads both models and replaces the tree with what they returned.
     *
     * <p>Called while the window is being built and from the refresh action. Both models are
     * computed on the calling thread inside a read action, which resolving PSI and querying an index
     * both require.
     *
     * <p>While the project is indexing, no model is read at all and a single row says so. Each kind
     * keeps its own group node even when it is the only one present, so that the absent half reads
     * as unused rather than as unsupported, and a project with neither gets one row saying that
     * instead of an empty window.
     *
     * <p>Every row is expanded afterwards. The loop walks the visible rows as it grows, so expanding
     * a group brings its children into range and the whole tree ends up open.
     *
     * @param project the project to read
     * @param tree    the tree to replace the model of
     */
    private static void refresh(@NotNull final Project project, @NotNull final Tree tree) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (DumbService.getInstance(project).isDumb()) {
            root.add(new DefaultMutableTreeNode(
                    "Indexing — weaves and extensions are listed once it finishes"));
        } else {
            final List<WeavesModel.Weave> weaves =
                    ApplicationManager.getApplication().runReadAction(
                            (com.intellij.openapi.util.Computable<List<WeavesModel.Weave>>)
                                    () -> WeavesModel.of(project));
            final List<ExtensionsModel.Holder> holders =
                    ApplicationManager.getApplication().runReadAction(
                            (com.intellij.openapi.util.Computable<List<ExtensionsModel.Holder>>)
                                    () -> ExtensionsModel.of(project));

            if (weaves.isEmpty() && holders.isEmpty()) {
                root.add(new DefaultMutableTreeNode(
                        "No weaves and no extensions in this project"));
            }
            // Grouped even when one side is empty. A window that changed shape depending on what a
            // project happened to contain would make the absent half look unsupported rather than
            // unused.
            if (!weaves.isEmpty()) {
                final DefaultMutableTreeNode group = new DefaultMutableTreeNode(
                        "Weaves (" + weaves.size() + ')');
                for (final WeavesModel.Weave weave : weaves) {
                    group.add(nodeFor(weave));
                }
                root.add(group);
            }
            if (!holders.isEmpty()) {
                final DefaultMutableTreeNode group = new DefaultMutableTreeNode(
                        "Extensions (" + holders.size() + ')');
                for (final ExtensionsModel.Holder holder : holders) {
                    group.add(nodeFor(holder));
                }
                root.add(group);
            }
        }

        tree.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    /**
     * Builds the subtree of one weave: its targets first, then its handlers.
     *
     * <p>A weave whose targets do not resolve gets a row naming {@code AW1001} instead of no rows at
     * all — the build reports that code for a weave with no targets, and a node that simply had no
     * children would read as a weave that declares nothing.
     *
     * @param weave the weave to render
     * @return the node, holding the weave as its user object
     */
    @NotNull
    private static DefaultMutableTreeNode nodeFor(@NotNull final WeavesModel.Weave weave) {
        final DefaultMutableTreeNode node = new DefaultMutableTreeNode(weave);
        for (final String target : weave.targets()) {
            node.add(new DefaultMutableTreeNode("→ " + target));
        }
        if (weave.targets().isEmpty()) {
            node.add(new DefaultMutableTreeNode("→ no target resolves (AW1001)"));
        }
        for (final WeavesModel.Handler handler : weave.handlers()) {
            node.add(new DefaultMutableTreeNode(handler));
        }
        return node;
    }

    /**
     * Builds the subtree of one extension holder: one row per contributed member.
     *
     * <p>A holder that contributes nothing gets a row saying so, for the same reason a weave without
     * targets does: an empty node looks like a node that has not been expanded.
     *
     * @param holder the holder to render
     * @return the node, holding the holder as its user object
     */
    @NotNull
    private static DefaultMutableTreeNode nodeFor(@NotNull final ExtensionsModel.Holder holder) {
        final DefaultMutableTreeNode node = new DefaultMutableTreeNode(holder);
        for (final ExtensionsModel.Contribution contribution : holder.contributions()) {
            node.add(new DefaultMutableTreeNode(contribution));
        }
        if (holder.contributions().isEmpty()) {
            node.add(new DefaultMutableTreeNode("→ contributes nothing"));
        }
        return node;
    }

    /**
     * Makes a double-click on a row open the declaration behind it.
     *
     * <p>A row with nothing to open — a group heading, a target line, or anything read from a
     * dependency's manifest — is left to the platform, which then treats the double-click as an
     * expand or collapse.
     *
     * @param tree the tree to install the listener on
     */
    private static void navigateOnDoubleClick(@NotNull final Tree tree) {
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull final MouseEvent event) {
                final TreePath path = tree.getSelectionPath();
                final Object node = path == null ? null : path.getLastPathComponent();
                final Object value = node instanceof final DefaultMutableTreeNode mutable
                        ? mutable.getUserObject()
                        : null;
                // A weave read out of a dependency's manifest has no element: there is no source to
                // open, and the manifest is not a place a reader can be sent.
                final PsiElement element = elementOf(value);
                if (element instanceof final Navigatable navigatable && navigatable.canNavigate()) {
                    navigatable.navigate(true);
                    return true;
                }
                return false;
            }
        }.installOn(tree);
    }

    /**
     * Returns the declaration a row stands for.
     *
     * @param value the row's user object
     * @return the element to navigate to, or {@code null} for a row that is a heading, a plain
     *         string, or a weave or extension read from a library manifest
     */
    @Nullable
    private static PsiElement elementOf(@Nullable final Object value) {
        if (value instanceof final WeavesModel.Weave weave) {
            return weave.element();
        }
        if (value instanceof final WeavesModel.Handler handler) {
            return handler.element();
        }
        if (value instanceof final ExtensionsModel.Holder holder) {
            return holder.element();
        }
        if (value instanceof final ExtensionsModel.Contribution contribution) {
            return contribution.element();
        }
        return null;
    }

    /**
     * Renders each row from the model object behind it.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Renderer extends ColoredTreeCellRenderer {

        /** Creates the renderer; it holds no state and one instance serves the whole tree. */
        Renderer() {
            // Stateless.
        }

        /**
         * Appends the text of one row.
         *
         * <p>Each model type is rendered as a name in the ordinary attributes followed by its
         * details in grey, so that a column of names stays readable. The one exception is a
         * handler's binding, which is marked wherever it is not {@link WeavesModel.Binding#BOUND}:
         * that is the only thing in the window a reader is expected to act on.
         *
         * <p>Anything else — the group headings and the placeholder rows — is rendered grey through
         * its {@code toString}.
         *
         * @param tree     the tree being rendered
         * @param value    the node, whose user object carries the model row
         * @param selected whether the row is selected, unused
         * @param expanded whether the row is expanded, unused
         * @param leaf     whether the row is a leaf, unused
         * @param row      the row index, unused
         * @param hasFocus whether the row has focus, unused
         */
        @Override
        public void customizeCellRenderer(@NotNull final JTree tree,
                                          final Object value,
                                          final boolean selected,
                                          final boolean expanded,
                                          final boolean leaf,
                                          final int row,
                                          final boolean hasFocus) {
            final Object node = value instanceof final DefaultMutableTreeNode mutable
                    ? mutable.getUserObject()
                    : value;
            if (node instanceof final WeavesModel.Weave weave) {
                setIcon(AllIcons.Nodes.Class);
                append(weave.name());
                append("  " + (weave.merged() ? "instance" : "static")
                                + (weave.priority() == 0 ? "" : ", priority " + weave.priority())
                                + (weave.module().isEmpty() ? "" : ", " + weave.module()),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            if (node instanceof final ExtensionsModel.Holder holder) {
                setIcon(AllIcons.Nodes.Class);
                append(holder.name());
                append("  " + holder.contributions().size() + " contributed"
                                + (holder.module().isEmpty() ? "" : ", " + holder.module())
                                + (holder.fromLibrary() ? ", from " + holder.origin() : ""),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            if (node instanceof final ExtensionsModel.Contribution contribution) {
                final boolean constant =
                        contribution.kind() == WeaveManifest.Extension.Kind.CONSTANT;
                setIcon(constant ? AllIcons.Nodes.Field : AllIcons.Nodes.Method);
                append("→ " + contribution.receiver() + '.' + contribution.signature());
                // Only the two shapes that are not the ordinary one. A row saying "instance" beside
                // every other row would be noise on the case a reader already assumes.
                switch (contribution.kind()) {
                    case STATIC -> append("  static", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    case CONSTANT -> append("  constant", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    default -> { }
                }
                return;
            }
            if (node instanceof final WeavesModel.Handler handler) {
                setIcon(AllIcons.Nodes.Method);
                append(handler.name());
                append("  " + handler.selector(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  " + handler.binding(),
                        handler.binding() == WeavesModel.Binding.BOUND
                                ? SimpleTextAttributes.GRAYED_ATTRIBUTES
                                : SimpleTextAttributes.ERROR_ATTRIBUTES);
                return;
            }
            append(String.valueOf(node), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
