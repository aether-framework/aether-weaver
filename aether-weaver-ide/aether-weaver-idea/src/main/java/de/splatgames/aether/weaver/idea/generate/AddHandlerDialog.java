package de.splatgames.aether.weaver.idea.generate;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiFormatUtilBase;
import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.PsiMethodMember;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asks what handler to generate for a weave, and shows what will be written.
 *
 * <p>Opened by {@link AddHandlerHandler} in place of the platform's member chooser, because the
 * question is not only which member: the point, the match rule, the selector form, the visibility,
 * the name prefix, the group and a slice all change what is generated, and all of them change the
 * preview.
 *
 * <p>Exactly one thing is chosen. Which list it is chosen from depends on the point: a positional
 * point offers the target's methods, an operation point offers the operations found inside them,
 * and the two share a card layout. Everything else in the dialog - the preview, the slice bounds,
 * the captured locals - is written against that one answer, which is why neither list allows a
 * multiple selection.
 *
 * <p>Operations are read out of the target's class file, so an unbuilt project offers none and the
 * label under the list says why.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class AddHandlerDialog extends DialogWrapper {

    /** Where a preview that could not be rendered is reported. */
    private static final Logger LOG = Logger.getInstance(AddHandlerDialog.class);

    /** The dialog's title. */
    private static final String TITLE = "Generate Weave Handler";

    /** The label above the list of target methods. */
    private static final String MEMBERS_TITLE = "Generate a handler for:";

    /** The group entry standing for no group at all. */
    private static final String NO_GROUP = "(none)";

    /** The slice entry standing for an unnarrowed search. */
    private static final String NO_BOUND = "(whole method)";

    /**
     * The width the slice combo boxes are sized to.
     *
     * <p>Fixed rather than derived, because a combo sizes itself to its widest item and would drag the
     * form's whole column with it every time another method's calls were loaded.
     */
    private static final String BOUND_PROTOTYPE = "com.example.service.Ledger.commit()  ";

    /** The preview's unscaled height. */
    private static final int PREVIEW_HEIGHT = 150;

    /** The dialog's unscaled width. */
    private static final int PREFERRED_WIDTH = 620;

    /** The dialog's unscaled height. */
    private static final int PREFERRED_HEIGHT = 560;

    /** The share of the split the lists take, leaving the rest to the preview. */
    private static final float TABLE_SHARE = 0.55f;

    /** The unscaled gap between the stacked panels. */
    private static final int ROW_GAP = 8;

    /** The label above the list of operations. */
    private static final String OPERATIONS_TITLE = "Generate a handler for:";

    /** The card showing the target methods. */
    private static final String MEMBERS_CARD = "members";

    /** The card showing the operations. */
    private static final String OPERATIONS_CARD = "operations";

    /** The key the platform remembers this dialog's size under. */
    private static final String DIMENSION_KEY = "AetherWeaver.GenerateHandler";

    /** The weave the handler is generated into. */
    private final PsiClass weave;

    /** Whether that weave is a static one, which narrows the visibilities offered. */
    private final boolean staticWeave;

    /** The kind of handler; refilled whenever the point changes, since not every kind applies. */
    private final ComboBox<HandlerOptions.Kind> kindBox;

    /** The point to attach at; deciding it decides which list is shown. */
    private final ComboBox<HandlerOptions.Point> pointBox;

    /** The match rule; refilled whenever the point changes. */
    private final ComboBox<HandlerOptions.Match> matchBox;

    /** The form selectors are written in; changing it re-enumerates the operations. */
    private final ComboBox<HandlerOptions.Selector> selectorBox;

    /** The handler's visibility. */
    private final ComboBox<HandlerOptions.Visibility> visibilityBox;

    /** The group to account the injection against, or {@code null} when the weave declares none. */
    private final ComboBox<String> groupBox;

    /** The prefix the handler's name begins with. */
    private final JBTextField prefixField;

    /** Whether a callback parameter is taken; disabled for a redirect. */
    private final JCheckBox callbackBox;

    /** Whether the locals in scope are captured; disabled for a redirect. */
    private final JCheckBox localsBox;

    /** Whether a documentation comment is generated. */
    private final JCheckBox javadocBox;

    /** Whether the body is marked with a {@code TODO}. */
    private final JCheckBox todoBox;

    /** The Java editor showing what would be written. */
    private final EditorTextField preview;

    /** The target methods the action offered, in the order it offered them. */
    private final List<PsiMethod> targets;

    /** The list shown for a positional point. */
    private final JBList<PsiMethod> methods = new JBList<>();

    /** The model behind {@link #methods}, filled once from {@link #targets}. */
    private final DefaultListModel<PsiMethod> methodModel = new DefaultListModel<>();

    /** The list shown for an operation point. */
    private final JBList<AddHandlerHandler.OperationMember> operations = new JBList<>();

    /**
     * The model behind {@link #operations}, refilled whenever the point, the spelling or a bound
     * changes.
     */
    private final DefaultListModel<AddHandlerHandler.OperationMember> operationModel =
            new DefaultListModel<>();

    /** Why the operation list is empty, or an empty string when it is not. */
    private final JBLabel unavailable = new JBLabel();

    /** The slice's lower bound: {@link #NO_BOUND} or a call in the chosen method. */
    private final ComboBox<Object> sliceFromBox = new ComboBox<>();

    /** The slice's upper bound: {@link #NO_BOUND} or a call in the chosen method. */
    private final ComboBox<Object> sliceToBox = new ComboBox<>();

    /** The method the slice combo boxes currently hold the calls of. */
    private PsiMethod boundedMethod;

    /** The spelling those calls were written in, since a change of spelling rewrites them. */
    private TargetOperations.Spelling boundedSpelling;

    /**
     * Set while a control is being refilled, and read by every listener.
     *
     * <p>Emptying a combo box fires one action event per removal, so without this the listeners would
     * re-enter the refill with half a model in place.
     */
    private boolean updating;

    /** Class file lookups, cached per target class for the life of the dialog. */
    private final Map<PsiClass, CompiledClasses.Lookup> lookups = new HashMap<>();

    /**
     * Compiled methods, cached per target method through {@link HashMap#computeIfAbsent}. A method
     * not found in the class file is never entered here - {@code computeIfAbsent} records no mapping
     * for a {@code null} result - so looking it up again always recomputes it.
     */
    private final Map<PsiMethod, MethodView> compiledMethods = new HashMap<>();

    /** The calls a method contains, cached per method and spelling, for the slice combo boxes. */
    private final Map<Bounded, List<TargetOperations.Operation>> calls = new HashMap<>();

    /** The card layout holding the two lists. */
    private final JPanel lists = new JPanel(new java.awt.CardLayout());

    /**
     * Whether {@link #createCenterPanel()} has run.
     *
     * <p>The constructor refreshes the preview before the platform builds the panel, and the refresh
     * reads components that do not exist yet.
     */
    private boolean built;

    /**
     * Builds the dialog over the given targets, opened on the remembered choices.
     *
     * @param project the project the dialog belongs to
     * @param weave   the weave the handler is generated into
     * @param targets the target methods to offer
     * @param initial the choices to open with
     */
    AddHandlerDialog(@NotNull final Project project,
                     @NotNull final PsiClass weave,
                     @NotNull final List<PsiMethod> targets,
                     @NotNull final HandlerOptions initial) {
        super(project, true);
        this.weave = weave;
        this.staticWeave = WeaveDeclarations.isStaticWeave(weave);
        this.targets = List.copyOf(targets);

        this.kindBox = new ComboBox<>(HandlerOptions.Kind.values());
        this.kindBox.setSelectedItem(initial.kind());
        this.pointBox = new ComboBox<>(HandlerOptions.Point.values());
        this.pointBox.setSelectedItem(initial.point());
        this.matchBox = new ComboBox<>();
        this.selectorBox = new ComboBox<>(HandlerOptions.Selector.values());
        this.selectorBox.setSelectedItem(initial.selector());
        // A spelling is not presentation here: a simple-name owner matches by suffix, so it can
        // select a wider set and land the same instruction on a different ordinal. Changing it
        // changes the list, not just its labels.
        this.selectorBox.addActionListener(event -> onShapeChanged());
        this.visibilityBox = new ComboBox<>(visibilitiesFor(this.staticWeave));
        this.visibilityBox.setSelectedItem(initial.visibility());
        this.groupBox = groupBoxFor(weave);
        this.prefixField = new JBTextField(initial.prefix());
        this.callbackBox = new JCheckBox("Take a callback parameter", initial.callback());
        this.localsBox = new JCheckBox("Capture the locals in scope", initial.locals());
        this.javadocBox = new JCheckBox("Generate a documentation comment", initial.javadoc());
        this.todoBox = new JCheckBox("Mark the body with a TODO", initial.todo());

        final Document document = EditorFactory.getInstance().createDocument("");
        this.preview = new EditorTextField(document, project, JavaFileType.INSTANCE, true, false);

        refillMatches(initial.match());
        this.pointBox.addActionListener(event -> onShapeChanged());
        this.kindBox.addActionListener(event -> {
            if (this.updating) {
                return;
            }
            refreshParameterControls();
            refreshPreview();
        });
        this.sliceFromBox.addActionListener(event -> onBoundsChanged());
        this.sliceToBox.addActionListener(event -> onBoundsChanged());
        for (final ComboBox<Object> bound : List.of(this.sliceFromBox, this.sliceToBox)) {
            // A record renders itself as its whole state. Without this the combo showed
            // "Operation[point=INVOKE, target=Ledger.flush(), ordinal=0, index=12, label=...,
            // redirects=null]", and — because a combo sizes itself to its widest item — the row
            // grew and shrank by whatever the longest of those happened to be in the chosen method.
            bound.setRenderer(new BoundRenderer());
            // Sized once, from a width nothing in the model can push past. A combo that resizes
            // with its contents drags the whole form's column with it on every change of method.
            bound.setPrototypeDisplayValue(BOUND_PROTOTYPE);
        }
        for (final JComponent control : new JComponent[]{this.matchBox, this.selectorBox,
                this.visibilityBox, this.groupBox, this.callbackBox, this.localsBox,
                this.javadocBox, this.todoBox}) {
            if (control instanceof final ComboBox<?> combo) {
                combo.addActionListener(event -> onOptionChanged());
            } else if (control instanceof final JCheckBox check) {
                check.addActionListener(event -> onOptionChanged());
            }
        }
        this.prefixField.getDocument().addDocumentListener(new PrefixListener());

        setTitle(TITLE);
        init();
        refreshPreview();
    }

    /**
     * Returns the choices as they stand.
     *
     * <p>Read continuously to render the preview, and once more after the dialog is accepted.
     *
     * @return the current options, with {@link #NO_GROUP} reported as no group
     */
    @NotNull
    HandlerOptions options() {
        final Object group = this.groupBox == null ? null : this.groupBox.getSelectedItem();
        return new HandlerOptions(
                (HandlerOptions.Kind) this.kindBox.getSelectedItem(),
                (HandlerOptions.Point) this.pointBox.getSelectedItem(),
                (HandlerOptions.Match) this.matchBox.getSelectedItem(),
                (HandlerOptions.Selector) this.selectorBox.getSelectedItem(),
                (HandlerOptions.Visibility) this.visibilityBox.getSelectedItem(),
                prefix(),
                group == null || NO_GROUP.equals(group) ? "" : group.toString(),
                this.callbackBox.isSelected(),
                this.localsBox.isSelected(),
                this.javadocBox.isSelected(),
                this.todoBox.isSelected());
    }

    /**
     * Returns what the handler is being generated for.
     *
     * <p>One member or none, from whichever list the current point shows. An operation point with an
     * empty operation list answers with nothing rather than falling back to the target method, since
     * the two are not interchangeable.
     *
     * @return the single chosen member, or an empty list when nothing is selected
     */
    @Unmodifiable
    @NotNull
    List<ClassMember> chosen() {
        if (pointNeedsOperation()) {
            final AddHandlerHandler.OperationMember selected = this.operations.getSelectedValue();
            return selected == null ? List.of() : List.of(selected);
        }
        final PsiMethod selected = this.methods.getSelectedValue();
        return selected == null ? List.of() : List.of(new PsiMethodMember(selected));
    }

    /**
     * Reports whether the chosen point is one that needs an operation.
     *
     * @return {@code true} when the operation list is the one in front of the user
     */
    private boolean pointNeedsOperation() {
        return this.pointBox.getSelectedItem() instanceof final HandlerOptions.Point point
                && point.needsOperation();
    }

    /**
     * Builds the form, the two lists and the preview.
     *
     * <p>The method list opens on its first row, so the dialog never greets the user with a disabled
     * button over a list that looks ready. Both lists get type-to-find, which is the only practical
     * way through the several hundred operations a large target has.
     *
     * @return the dialog's centre panel
     */
    @Override
    @NotNull
    protected JComponent createCenterPanel() {
        final FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Weave:", new JBLabel(describeWeave()))
                .addLabeledComponent("Handler:", this.kindBox)
                .addLabeledComponent("Inject at:", this.pointBox)
                .addLabeledComponent("Positions:", this.matchBox)
                .addLabeledComponent("Selector:", this.selectorBox)
                .addLabeledComponent("Visibility:", this.visibilityBox);
        if (this.groupBox != null) {
            form.addLabeledComponent("Group:", this.groupBox);
        }
        form.addLabeledComponent("Slice from:", this.sliceFromBox)
                .addLabeledComponent("Slice to:", this.sliceToBox)
                .addLabeledComponent("Name prefix:", this.prefixField)
                .addComponent(this.callbackBox)
                .addComponent(this.localsBox)
                .addComponent(this.javadocBox)
                .addComponent(this.todoBox);

        // One method, the same way one operation is chosen. Both lists answer the same question
        // — what is this handler for — and the rest of the dialog is built on there being one
        // answer: the preview renders it, the slice bounds are read out of its body, the captured
        // locals are the ones live at its site.
        this.methods.setModel(this.methodModel);
        this.targets.forEach(this.methodModel::addElement);
        this.methods.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.methods.setCellRenderer(new MethodRenderer(qualifiesMethods(this.targets)));
        this.methods.addListSelectionListener(event -> onSelectionChanged());
        ListSpeedSearch.installOn(this.methods, AddHandlerDialog::labelOf);
        // Opened on something, so the dialog never greets the user with a disabled OK button and a
        // list that looks ready.
        if (!this.targets.isEmpty()) {
            this.methods.setSelectedIndex(0);
        }
        this.operations.setModel(this.operationModel);
        // One operation, not several. A handler at an operation is about that operation: its
        // ordinal, its slice and — for a redirect — its signature all belong to exactly one, and a
        // list that let several be chosen had to answer "which one is the slice for?" by picking
        // the highlighted row, which the next refill then dropped. Choosing is selecting here.
        this.operations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.operations.setCellRenderer(new OperationRenderer());
        this.operations.addListSelectionListener(event -> onSelectionChanged());
        // A weave targeting a large class offers hundreds of operations — java.lang.String alone
        // has seven hundred calls in it. Scrolling to one is not a way to choose, and type-to-find
        // is what every other list in this IDE does.
        ListSpeedSearch.installOn(this.operations, AddHandlerDialog::labelOf);

        final JPanel operationsPanel = new JPanel(new BorderLayout(0, JBUI.scale(ROW_GAP)));
        operationsPanel.add(new JBLabel(OPERATIONS_TITLE), BorderLayout.NORTH);
        operationsPanel.add(new JBScrollPane(this.operations), BorderLayout.CENTER);
        operationsPanel.add(this.unavailable, BorderLayout.SOUTH);

        final JPanel methodsPanel = new JPanel(new BorderLayout(0, JBUI.scale(ROW_GAP)));
        methodsPanel.add(new JBLabel(MEMBERS_TITLE), BorderLayout.NORTH);
        methodsPanel.add(new JBScrollPane(this.methods), BorderLayout.CENTER);

        this.lists.add(methodsPanel, MEMBERS_CARD);
        this.lists.add(operationsPanel, OPERATIONS_CARD);
        this.built = true;

        this.preview.setBorder(IdeBorderFactory.createTitledBorder("Preview", false));
        this.preview.setPreferredSize(new Dimension(PREFERRED_WIDTH, JBUI.scale(PREVIEW_HEIGHT)));

        final JBSplitter split = new JBSplitter(true, TABLE_SHARE);
        split.setFirstComponent(this.lists);
        split.setSecondComponent(this.preview);

        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(ROW_GAP)));
        panel.add(form.getPanel(), BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(PREFERRED_WIDTH, JBUI.scale(PREFERRED_HEIGHT)));
        refillKinds();
        refreshParameterControls();
        refillOperations();
        return panel;
    }

    /**
     * Returns the component to focus when the dialog opens.
     *
     * @return the method list, or {@code null} before the panel exists
     */
    @Override
    @Nullable
    public JComponent getPreferredFocusedComponent() {
        return this.built ? this.methods : null;
    }

    /**
     * Returns the key this dialog's size is remembered under.
     *
     * @return the dimension key
     */
    @Override
    @NotNull
    protected String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    /**
     * Reports what stops the dialog from being accepted.
     *
     * <p>Four things: nothing chosen; a slice that does not end after it begins, the inverted case
     * of which the engine reports as {@code AW1122}; one slice bound without the other; and a
     * prefix that cannot begin a method name.
     *
     * @return the first problem found, or {@code null} when the dialog can be accepted
     */
    @Override
    @Nullable
    protected ValidationInfo doValidate() {
        if (chosen().isEmpty()) {
            return new ValidationInfo(pointNeedsOperation()
                    ? "Choose the operation to attach the handler to"
                    : "Choose the target method to attach the handler to",
                    pointNeedsOperation() ? this.operations : this.methods);
        }
        final Object from = this.sliceFromBox.getSelectedItem();
        final Object to = this.sliceToBox.getSelectedItem();
        if (from instanceof final TargetOperations.Operation start
                && to instanceof final TargetOperations.Operation end
                && start.index() >= end.index()) {
            return new ValidationInfo("The slice ends before it begins", this.sliceToBox);
        }
        if (NO_BOUND.equals(from) != NO_BOUND.equals(to)) {
            return new ValidationInfo("A slice needs both bounds, or neither", this.sliceToBox);
        }
        final String prefix = prefix();
        if (!prefix.isEmpty()
                && !PsiNameHelper.getInstance(this.weave.getProject()).isIdentifier(prefix)) {
            return new ValidationInfo("'" + prefix + "' cannot start a method name",
                    this.prefixField);
        }
        return null;
    }

    /**
     * Reacts to a change of point or selector form, which changes what can be chosen.
     *
     * <p>Both refill the kinds and the match rules, and both re-enumerate the operations: a point
     * because it names different instructions, a spelling because a simple-name owner matches by
     * suffix and can put the same instruction on a different ordinal.
     */
    private void onShapeChanged() {
        if (this.updating) {
            return;
        }
        this.updating = true;
        try {
            refillMatches((HandlerOptions.Match) this.matchBox.getSelectedItem());
            refillKinds();
            refreshParameterControls();
        } finally {
            this.updating = false;
        }
        refillOperations();
        refreshPreview();
    }

    /** Reacts to a change of slice bound by re-enumerating the operations inside the new region. */
    private void onBoundsChanged() {
        if (this.updating) {
            return;
        }
        refillOperations();
        refreshPreview();
    }

    /** Reacts to a change that only the generated text depends on. */
    private void onOptionChanged() {
        if (this.updating) {
            return;
        }
        refreshPreview();
    }

    /** Reacts to a change of the chosen member. */
    private void onSelectionChanged() {
        if (this.updating) {
            return;
        }
        refreshPreview();
    }

    /**
     * Enables the two parameter check boxes only where they mean anything.
     *
     * <p>A redirect stands in for the operation, so neither a callback nor a capture has a place in
     * its signature.
     */
    private void refreshParameterControls() {
        final boolean injecting = this.kindBox.getSelectedItem() != HandlerOptions.Kind.REDIRECT;
        this.callbackBox.setEnabled(injecting);
        this.localsBox.setEnabled(injecting);
    }

    /**
     * Refills the kind combo box with the kinds the chosen point allows.
     *
     * <p>A kind that no longer applies falls back to {@link HandlerOptions.Kind#INJECT}, which applies
     * everywhere.
     */
    private void refillKinds() {
        final HandlerOptions.Point point = (HandlerOptions.Point) this.pointBox.getSelectedItem();
        if (point == null) {
            return;
        }
        final HandlerOptions.Kind wanted = (HandlerOptions.Kind) this.kindBox.getSelectedItem();
        this.kindBox.removeAllItems();
        for (final HandlerOptions.Kind candidate : HandlerOptions.Kind.values()) {
            if (candidate.appliesTo(point)) {
                this.kindBox.addItem(candidate);
            }
        }
        this.kindBox.setSelectedItem(wanted != null && wanted.appliesTo(point)
                ? wanted
                : HandlerOptions.Kind.INJECT);
    }

    /**
     * Refills the operation list, or shows the method list instead.
     *
     * <p>The operations are enumerated inside the chosen slice, so their ordinals are the
     * slice-relative ones the engine will count; an absolute ordinal written beside a slice names a
     * different instruction, which is the mistake a slice exists to avoid.
     *
     * <p>The selection is restored by instruction rather than by row, and it has to survive the
     * refill: setting a bound is what triggers one.
     *
     * <p>An empty list is explained under it - either the reasons the class files could not be read,
     * or that the target's methods contain no such operation.
     */
    private void refillOperations() {
        if (!this.built) {
            return;
        }
        final HandlerOptions.Point point = (HandlerOptions.Point) this.pointBox.getSelectedItem();
        final HandlerOptions.Selector form =
                (HandlerOptions.Selector) this.selectorBox.getSelectedItem();
        final java.awt.CardLayout cards = (java.awt.CardLayout) this.lists.getLayout();
        if (point == null || form == null || !point.needsOperation()) {
            cards.show(this.lists, MEMBERS_CARD);
            return;
        }
        cards.show(this.lists, OPERATIONS_CARD);

        final Instruction chosen = selectedInstruction();
        final List<AddHandlerHandler.OperationMember> rows = new ArrayList<>();
        final Set<String> reasons = new LinkedHashSet<>();
        for (final PsiMethod target : this.targets) {
            final PsiClass owner = target.getContainingClass();
            if (owner == null) {
                continue;
            }
            final CompiledClasses.Lookup lookup = lookupOf(owner);
            if (!lookup.isAvailable()) {
                reasons.add(lookup.reason());
                continue;
            }
            final MethodView method = compiledMethodOf(target);
            if (method == null) {
                continue;
            }
            // Enumerated inside the slice, so the ordinals are the slice-relative ones the
            // engine will count — an absolute ordinal written next to a slice names a different
            // instruction, which is the whole class of mistake a slice exists to avoid.
            for (final TargetOperations.Operation operation : TargetOperations.of(method,
                    point.api(), spellingOf(form), boundsFor(target))) {
                rows.add(new AddHandlerHandler.OperationMember(target, operation));
            }
        }

        this.updating = true;
        try {
            this.operationModel.clear();
            int restore = -1;
            for (final AddHandlerHandler.OperationMember row : rows) {
                if (chosen != null && chosen.equals(Instruction.of(row))) {
                    restore = this.operationModel.size();
                }
                this.operationModel.addElement(row);
            }
            // The choice survives the refill, and it has to. Setting a slice bound re-enumerates
            // the list; if the selection were dropped, the bound combos would see no method, reset
            // themselves to "whole method", and the user's bound would undo itself the instant it
            // was made. That is exactly what it did.
            this.operations.setSelectedIndex(restore);
            if (restore >= 0) {
                this.operations.ensureIndexIsVisible(restore);
            }
        } finally {
            this.updating = false;
        }
        this.unavailable.setText(rows.isEmpty()
                ? reasons.isEmpty()
                        ? "No " + point.name().toLowerCase(java.util.Locale.ROOT)
                                + " operations in the target's methods."
                        : String.join("; ", reasons)
                : "");
    }

    /**
     * Renders an operation row as its method and its label.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class OperationRenderer
            extends ColoredListCellRenderer<AddHandlerHandler.OperationMember> {

        /** Creates the renderer. */
        OperationRenderer() {
            // Stateless.
        }

        /**
         * Appends the row's label.
         *
         * @param list     the list being rendered
         * @param value    the row, or {@code null} for an empty one
         * @param index    the row's index
         * @param selected whether the row is selected
         * @param focused  whether the row has focus
         */
        @Override
        protected void customizeCellRenderer(
                @NotNull final JList<? extends AddHandlerHandler.OperationMember> list,
                @Nullable final AddHandlerHandler.OperationMember value,
                final int index,
                final boolean selected,
                final boolean focused) {
            if (value != null) {
                append(labelOf(value));
            }
        }
    }

    /**
     * One instruction, identified by the method it is in and its position in that method's code.
     *
     * <p>What survives a refill. The rows themselves do not: they are rebuilt, and an operation's
     * ordinal changes with the slice while its index does not.
     *
     * @param method the method the instruction is in
     * @param index  the instruction's position in that method
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Instruction(@NotNull PsiMethod method, int index) {

        /**
         * Returns the instruction a row stands for.
         *
         * @param row the row to identify
         * @return the instruction it names
         */
        @NotNull
        static Instruction of(@NotNull final AddHandlerHandler.OperationMember row) {
            return new Instruction(row.method(), row.operation().index());
        }
    }

    /**
     * Returns the instruction currently selected in the operation list.
     *
     * @return the instruction, or {@code null} when nothing is selected
     */
    @Nullable
    private Instruction selectedInstruction() {
        final AddHandlerHandler.OperationMember selected = this.operations.getSelectedValue();
        return selected == null ? null : Instruction.of(selected);
    }

    /**
     * Returns the text type-to-find searches an operation row by.
     *
     * @param row the row to label
     * @return the method's name and the operation's own label
     */
    @NotNull
    private static String labelOf(@NotNull final AddHandlerHandler.OperationMember row) {
        return row.method().getName() + " → " + row.getText();
    }

    /**
     * Finds the compiled method matching a source method.
     *
     * <p>Name and descriptor together: a name alone would pick an arbitrary overload, and the
     * operations inside two overloads have nothing to do with each other.
     *
     * @param view   the compiled class to search
     * @param target the source method to match
     * @return the compiled method, or {@code null} when the class file has no such method
     */
    @Nullable
    private static MethodView methodOf(@NotNull final TargetView view,
                                       @NotNull final PsiMethod target) {
        // Name and descriptor together. A name alone would pick an arbitrary overload, and the
        // operations inside two overloads are entirely different.
        final String descriptor = ClassUtil.getAsmMethodSignature(target);
        for (final MethodView candidate : view.methods()) {
            if (candidate.name().equals(target.getName())
                    && candidate.type().descriptorString().equals(descriptor)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Translates a selector form into the spelling the operation reader uses.
     *
     * @param form the chosen form
     * @return the matching spelling
     */
    @NotNull
    private static TargetOperations.Spelling spellingOf(@NotNull final HandlerOptions.Selector form) {
        return switch (form) {
            case QUALIFIED -> TargetOperations.Spelling.QUALIFIED;
            case SIMPLE -> TargetOperations.Spelling.SIMPLE;
            case DESCRIPTOR -> TargetOperations.Spelling.DESCRIPTOR;
        };
    }

    /**
     * Returns the slice chosen for the given method.
     *
     * <p>Only the method the bound combo boxes were filled for can have one, so asking about any other
     * method answers with no slice rather than with somebody else's.
     *
     * @param target the method to ask about
     * @return the bounds, or {@code null} when either bound is unset, they belong to another method, or
     *         the bounds are the same instruction or inverted
     */
    @Nullable
    TargetOperations.Bounds boundsFor(@NotNull final PsiMethod target) {
        if (!target.equals(this.boundedMethod)
                || !(this.sliceFromBox.getSelectedItem() instanceof final TargetOperations.Operation from)
                || !(this.sliceToBox.getSelectedItem() instanceof final TargetOperations.Operation to)
                || from.index() >= to.index()) {
            return null;
        }
        return new TargetOperations.Bounds(from, to);
    }

    /**
     * Refills the slice combo boxes with the calls of the given method.
     *
     * <p>A {@code null} target is a selection that did not survive a refill rather than a change of
     * method, and is treated as the method already bounded.
     *
     * <p>A bound is re-selected out of the new model by instruction index and never carried across
     * as an object, because a combo box accepts a selection that is not in its model. One that
     * survives is kept, and one that does not is cleared visibly.
     *
     * @param target the method whose calls are offered, or {@code null} to keep the current one
     */
    private void refillBounds(@Nullable final PsiMethod target) {
        // A method the caller could not name is not a method change. The operation list is
        // cleared and refilled whenever anything above it moves, and a selection that does not
        // survive the refill arrives here as null — which used to empty the combos, drop both
        // bounds and set boundedMethod to null. Setting a bound is exactly what triggers that
        // refill, and a bound that narrows the list past the selected row is exactly when the
        // selection does not survive, so the sequence "pick a call, pick a from, pick a to" undid
        // itself on the last step and left the user re-choosing all three.
        final PsiMethod bounded = target == null ? this.boundedMethod : target;
        final TargetOperations.Spelling spelling = selectedSpelling();
        final boolean sameMethod = this.boundedMethod != null && this.boundedMethod.equals(bounded);
        if (sameMethod && spelling == this.boundedSpelling) {
            return;
        }
        // Read before the combos are emptied, and only when the method has not changed: a bound
        // belongs to the body its ordinal was counted in.
        final int from = sameMethod ? indexOfBound(this.sliceFromBox) : -1;
        final int to = sameMethod ? indexOfBound(this.sliceToBox) : -1;
        this.boundedMethod = bounded;
        this.boundedSpelling = spelling;

        // Guarded, because removeAllItems fires one action event per removal and the listeners
        // would otherwise re-enter this method with one combo emptied and the other not.
        this.updating = true;
        try {
            this.sliceFromBox.removeAllItems();
            this.sliceToBox.removeAllItems();
            this.sliceFromBox.addItem(NO_BOUND);
            this.sliceToBox.addItem(NO_BOUND);
            for (final TargetOperations.Operation call : callsIn(bounded, spelling)) {
                this.sliceFromBox.addItem(call);
                this.sliceToBox.addItem(call);
            }
            // Re-selected out of the new model, never carried over as an object. A spelling that
            // cannot name an instruction does not offer it — TargetOperations only offers what its
            // resolver confirmed — and JComboBox.setSelectedItem accepts an object that is not in
            // its model, so assigning the old bound back would leave a call selected that this form
            // cannot write. An instruction that survives keeps the bound; one that does not clears
            // it, visibly.
            selectBound(this.sliceFromBox, from);
            selectBound(this.sliceToBox, to);
        } finally {
            this.updating = false;
        }
    }

    /**
     * Renders a slice bound as the call it names.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class BoundRenderer extends ColoredListCellRenderer<Object> {

        /** Creates the renderer. */
        BoundRenderer() {
            // Stateless.
        }

        /**
         * Appends the bound's label.
         *
         * @param list     the list being rendered
         * @param value    the bound, or {@code null} for an empty row
         * @param index    the row's index
         * @param selected whether the row is selected
         * @param focused  whether the row has focus
         */
        @Override
        protected void customizeCellRenderer(@NotNull final JList<?> list,
                                             @Nullable final Object value,
                                             final int index,
                                             final boolean selected,
                                             final boolean focused) {
            if (value != null) {
                append(labelOfBound(value));
            }
        }
    }

    /**
     * Returns what a slice combo box shows for one of its items.
     *
     * <p>A record renders itself as the whole of its state, which is neither readable nor a stable
     * width, so an operation is shown as its label and anything else - {@link #NO_BOUND} - as itself.
     *
     * @param value the item to label
     * @return the text to show
     */
    @NotNull
    static String labelOfBound(@NotNull final Object value) {
        return value instanceof final TargetOperations.Operation bound
                ? bound.label()
                : value.toString();
    }

    /**
     * Returns the instruction index a slice combo box currently names.
     *
     * @param combo the combo box to read
     * @return the index, or {@code -1} when no call is selected
     */
    private static int indexOfBound(@NotNull final ComboBox<Object> combo) {
        return combo.getSelectedItem() instanceof final TargetOperations.Operation bound
                ? bound.index()
                : -1;
    }

    /**
     * Selects the call at the given instruction index, or clears the bound.
     *
     * @param combo the combo box to select in
     * @param index the instruction index to look for, or {@code -1} to clear
     */
    private static void selectBound(@NotNull final ComboBox<Object> combo, final int index) {
        for (int row = 0; index >= 0 && row < combo.getItemCount(); row++) {
            if (combo.getItemAt(row) instanceof final TargetOperations.Operation candidate
                    && candidate.index() == index) {
                combo.setSelectedIndex(row);
                return;
            }
        }
        combo.setSelectedItem(NO_BOUND);
    }

    /**
     * Returns the spelling the selector box currently asks for.
     *
     * @return the chosen spelling, or the default one when the box holds nothing
     */
    @NotNull
    private TargetOperations.Spelling selectedSpelling() {
        return this.selectorBox.getSelectedItem() instanceof final HandlerOptions.Selector form
                ? spellingOf(form)
                : spellingOf(HandlerOptions.defaults().selector());
    }

    /**
     * Returns the calls inside a method, for the slice combo boxes.
     *
     * <p>Calls only: a slice is bounded by two {@code @At(INVOKE)} points regardless of the point the
     * handler itself attaches at.
     *
     * @param target   the method to read, or {@code null} for none
     * @param spelling the spelling the calls are written in
     * @return the calls, empty when there is no method or no class file behind it
     */
    @NotNull
    private List<TargetOperations.Operation> callsIn(@Nullable final PsiMethod target,
                                                     @NotNull final TargetOperations.Spelling spelling) {
        if (target == null) {
            return List.of();
        }
        return this.calls.computeIfAbsent(new Bounded(target, spelling), key -> {
            final MethodView compiled = compiledMethodOf(key.method());
            return compiled == null
                    ? List.of()
                    : TargetOperations.of(compiled, de.splatgames.aether.weaver.api.Point.INVOKE,
                            key.spelling());
        });
    }

    /**
     * The cache key of {@link #callsIn(PsiMethod, TargetOperations.Spelling)}.
     *
     * @param method   the method the calls were read from
     * @param spelling the spelling they were written in
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Bounded(@NotNull PsiMethod method, @NotNull TargetOperations.Spelling spelling) {
    }

    /**
     * Returns the compiled form of a target method, reading its class file at most once.
     *
     * @param target the method to look up
     * @return the compiled method, or {@code null} when there is no usable class file or no such
     *         method in it
     */
    @Nullable
    private MethodView compiledMethodOf(@NotNull final PsiMethod target) {
        return this.compiledMethods.computeIfAbsent(target, method -> {
            final PsiClass owner = method.getContainingClass();
            if (owner == null) {
                return null;
            }
            final CompiledClasses.Lookup lookup = lookupOf(owner);
            return lookup.isAvailable() ? methodOf(lookup.view(), method) : null;
        });
    }

    /**
     * Returns the class file lookup for a target class, performing it at most once.
     *
     * @param owner the class to look up
     * @return the lookup, which carries its own reason when it found nothing
     */
    @NotNull
    private CompiledClasses.Lookup lookupOf(@NotNull final PsiClass owner) {
        return this.lookups.computeIfAbsent(owner, CompiledClasses::of);
    }

    /**
     * Rewrites the preview from the current selection and options.
     *
     * <p>Also refills the slice bounds, since they belong to whichever method is now chosen.
     */
    private void refreshPreview() {
        if (!this.built) {
            return;
        }
        if (pointNeedsOperation()) {
            final AddHandlerHandler.OperationMember chosen = this.operations.getSelectedValue();
            refillBounds(chosen == null ? null : chosen.method());
            this.preview.setText(chosen == null
                    ? "// select an operation to see what will be generated"
                    : previewOf(chosen.method(), chosen.operation()));
            return;
        }
        final PsiMethod highlighted = highlighted();
        refillBounds(highlighted);
        this.preview.setText(highlighted == null
                ? "// select a target method to see what will be generated"
                : previewOf(highlighted, null));
    }

    /**
     * Renders what would be generated for one member.
     *
     * <p>A preview may report that it cannot render something; it may not take the dialog down with
     * it, which is what an exception thrown out of a Swing listener does. Anything the generator
     * throws is logged and shown as a comment, while the platform's own cancellation is rethrown so
     * that a cancelled read action is not stranded.
     *
     * @param target    the method the handler would attach to
     * @param operation the operation it would attach to, or {@code null} for a positional point
     * @return the generated text, or a comment saying why there is none
     */
    @NotNull
    private String previewOf(@NotNull final PsiMethod target,
                             @Nullable final TargetOperations.Operation operation) {
        try {
            final PsiMethod generated = AddHandlerHandler.handlerFor(this.weave, target, operation,
                    capturesFor(target, operation), boundsFor(target), options());
            return generated == null
                    ? "// nothing can be generated here — a type involved does not resolve"
                    : generated.getText();
        } catch (final ProcessCanceledException cancelled) {
            // The platform's own control flow. Swallowing it would strand a cancelled read action.
            throw cancelled;
        } catch (final RuntimeException failed) {
            // A preview may report that it cannot render something. It may not take the dialog
            // down with it, which is what an exception out of a Swing listener does — the user is
            // left with a stack trace over a half-drawn window and no way back to their choices.
            // This happened: a CONSTANT target produced the method name "onInt:0", which is not an
            // identifier, and the parser threw from inside the listener that was refilling a combo.
            // The name is fixed; the guard stays, because the generated text is assembled from names
            // in somebody else's class file and the next such surprise cannot be enumerated here.
            LOG.warn("the weave handler preview could not be rendered", failed);
            return "// this combination cannot be generated: " + failed.getMessage();
        }
    }

    /**
     * Returns the locals live at every site the handler would attach to.
     *
     * <p>Nothing is captured unless it was asked for, and nothing can be captured without a class
     * file: a capture names a variable by what the compiler recorded.
     *
     * @param target    the method the handler would attach to
     * @param operation the operation it would attach to, or {@code null} for a positional point
     * @return the captures, empty when they were not asked for or cannot be read
     */
    @Unmodifiable
    @NotNull
    List<TargetLocals.Capture> capturesFor(@NotNull final PsiMethod target,
                                           @Nullable final TargetOperations.Operation operation) {
        if (!this.localsBox.isSelected()
                || !(this.pointBox.getSelectedItem() instanceof final HandlerOptions.Point point)) {
            return List.of();
        }
        final PsiClass owner = target.getContainingClass();
        if (owner == null) {
            return List.of();
        }
        final CompiledClasses.Lookup lookup = CompiledClasses.of(owner);
        if (!lookup.isAvailable()) {
            return List.of();
        }
        final MethodView compiled = methodOf(lookup.view(), target);
        return compiled == null
                ? List.of()
                : TargetLocals.at(compiled, TargetOperations.sitesOf(compiled, point.api(),
                        operation, boundsFor(target)));
    }

    /**
     * Returns the target method selected in the method list.
     *
     * @return the method, or {@code null} when nothing is selected
     */
    @Nullable
    private PsiMethod highlighted() {
        return this.methods.getSelectedValue();
    }

    /**
     * Returns the name prefix as typed, trimmed.
     *
     * @return the prefix, possibly empty
     */
    @NotNull
    private String prefix() {
        return this.prefixField.getText().trim();
    }

    /**
     * Refills the match combo box with the rules the chosen point allows.
     *
     * @param wanted the rule to restore, if it still applies; otherwise
     *               {@link HandlerOptions.Match#EVERY} is selected
     */
    private void refillMatches(@Nullable final HandlerOptions.Match wanted) {
        final HandlerOptions.Point point =
                (HandlerOptions.Point) this.pointBox.getSelectedItem();
        if (point == null) {
            return;
        }
        this.matchBox.removeAllItems();
        for (final HandlerOptions.Match candidate : HandlerOptions.Match.values()) {
            if (candidate.appliesTo(point)) {
                this.matchBox.addItem(candidate);
            }
        }
        this.matchBox.setSelectedItem(wanted != null && wanted.appliesTo(point)
                ? wanted
                : HandlerOptions.Match.EVERY);
    }

    /**
     * Describes the weave and its targets for the form's first row.
     *
     * @return the weave's name, its kind and the names of its targets
     */
    @NotNull
    private String describeWeave() {
        final StringBuilder described = new StringBuilder(
                this.weave.getName() == null ? "?" : this.weave.getName());
        described.append(this.staticWeave ? " (static weave) → " : " (instance weave) → ");
        final List<PsiClass> targets = WeaveDeclarations.targetsOf(this.weave);
        for (int index = 0; index < targets.size(); index++) {
            described.append(index == 0 ? "" : ", ").append(targets.get(index).getName());
        }
        return described.toString();
    }

    /**
     * Builds the group combo box, if the weave declares any groups.
     *
     * <p>Only declared groups are offered: a grouped injection is exempt from its own {@code require},
     * so a group name nobody declared is a check that silently does not happen.
     *
     * @param weave the weave whose groups are read
     * @return the combo box, or {@code null} when the weave declares no group and the row is omitted
     */
    @Nullable
    private static ComboBox<String> groupBoxFor(@NotNull final PsiClass weave) {
        final List<String> declared = WeaveDeclarations.groupsOf(weave);
        if (declared.isEmpty()) {
            return null;
        }
        final ComboBox<String> box = new ComboBox<>();
        box.addItem(NO_GROUP);
        for (final String group : declared) {
            box.addItem(group);
        }
        return box;
    }

    /**
     * Returns the visibilities worth offering.
     *
     * <p>A static weave's handler is called across classes. {@link HandlerOptions.Visibility#AUTOMATIC}
     * and {@link HandlerOptions.Visibility#PUBLIC} are reachable regardless of package and are the only
     * two offered; the narrower visibilities are left out even though each is reachable when the weave
     * and the target happen to share a package, because this dialog does not know the target's package
     * before a member is chosen.
     *
     * @param staticWeave whether the weave is a static one
     * @return the visibilities to offer
     */
    private static HandlerOptions.Visibility @NotNull [] visibilitiesFor(final boolean staticWeave) {
        if (!staticWeave) {
            return HandlerOptions.Visibility.values();
        }
        final List<HandlerOptions.Visibility> reachable = new ArrayList<>(2);
        for (final HandlerOptions.Visibility candidate : HandlerOptions.Visibility.values()) {
            if (candidate.survivesAStaticWeave()) {
                reachable.add(candidate);
            }
        }
        return reachable.toArray(new HandlerOptions.Visibility[0]);
    }

    /**
     * Reports whether method rows need their owner's name in front of them.
     *
     * @param targets the methods being offered
     * @return {@code true} when they come from more than one class
     */
    private static boolean qualifiesMethods(@NotNull final List<PsiMethod> targets) {
        return targets.stream().map(PsiMethod::getContainingClass).distinct().count() > 1;
    }

    /**
     * Labels a target method with its parameter types.
     *
     * @param target    the method to label
     * @param qualified whether the owner's name is prefixed
     * @return the label
     */
    @NotNull
    private static String labelOf(@NotNull final PsiMethod target, final boolean qualified) {
        final PsiClass owner = target.getContainingClass();
        final String prefix = qualified && owner != null && owner.getName() != null
                ? owner.getName() + '.'
                : "";
        return prefix + PsiFormatUtil.formatMethod(target, PsiSubstitutor.EMPTY,
                PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_PARAMETERS,
                PsiFormatUtilBase.SHOW_TYPE);
    }

    /**
     * Returns the text type-to-find searches a method row by.
     *
     * @param target the method to label
     * @return the label, always carrying the owner's name
     */
    @NotNull
    private static String labelOf(@NotNull final PsiMethod target) {
        return labelOf(target, true);
    }

    /**
     * Renders a target method row with its icon and its signature.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class MethodRenderer extends ColoredListCellRenderer<PsiMethod> {

        /** Whether rows carry their owner's name, decided once from the offered methods. */
        private final boolean qualified;

        /**
         * Creates the renderer.
         *
         * @param qualified whether rows carry their owner's name
         */
        MethodRenderer(final boolean qualified) {
            this.qualified = qualified;
        }

        /**
         * Appends the method's icon and label.
         *
         * @param list     the list being rendered
         * @param value    the method, or {@code null} for an empty row
         * @param index    the row's index
         * @param selected whether the row is selected
         * @param focused  whether the row has focus
         */
        @Override
        protected void customizeCellRenderer(@NotNull final JList<? extends PsiMethod> list,
                                             @Nullable final PsiMethod value,
                                             final int index,
                                             final boolean selected,
                                             final boolean focused) {
            if (value != null) {
                setIcon(value.getIcon(0));
                append(labelOf(value, this.qualified));
            }
        }
    }

    /**
     * Rewrites the preview as the name prefix is typed.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private final class PrefixListener implements DocumentListener {

        /** Creates the listener, which reaches the dialog through its enclosing instance. */
        PrefixListener() {
            // Reaches the dialog through its enclosing instance.
        }

        /**
         * Refreshes the preview.
         *
         * @param event the document change
         */
        @Override
        public void insertUpdate(final DocumentEvent event) {
            refreshPreview();
        }

        /**
         * Refreshes the preview.
         *
         * @param event the document change
         */
        @Override
        public void removeUpdate(final DocumentEvent event) {
            refreshPreview();
        }

        /**
         * Refreshes the preview.
         *
         * @param event the document change
         */
        @Override
        public void changedUpdate(final DocumentEvent event) {
            refreshPreview();
        }
    }
}
