package de.splatgames.aether.weaver.idea.generate;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiFormatUtilBase;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.WeaveSpot;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Function;

/**
 * Asks which of the positions found at the caret a handler should attach to, and into which weave.
 *
 * <p>Opened by {@link WeaveHereIntention} and by {@link CaptureLocalIntention}, over a list of
 * {@link WeaveSpot}s that a search has already ordered by how well each answers "here". The first
 * is selected on opening, because that is what the author asked for; everything below the list
 * refines that answer rather than standing in front of it.
 *
 * <p>The point is not a control: it comes from the chosen spot. What the user does choose is the
 * weave, the kind, whether to narrow to the spot's own slice, the match rule, the selector form,
 * the visibility, the group, the name prefix and the four generation flags.
 *
 * <p>The dialog opens whether or not the target has a class file. Without one the spots are read
 * from the source, which names members exactly but numbers no instruction; where such a spot names
 * a member, the match rule is insisted on rather than offered, since an injection that pins no
 * ordinal and demands no count is the one that binds silently to the wrong call.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class WeaveHereDialog extends DialogWrapper {

    /** Where a preview that could not be rendered is reported. */
    private static final Logger LOG = Logger.getInstance(WeaveHereDialog.class);

    /** The dialog's title. */
    private static final String TITLE = "Weave Here";

    /** The label above the list of spots. */
    private static final String SPOTS_TITLE = "Attach the handler at:";

    /** The group entry standing for no group at all. */
    private static final String NO_GROUP = "(none)";

    /** The narrowing entry standing for an unnarrowed search. */
    private static final String NO_SLICE = "(the whole method)";

    /** The weave entry standing for a weave that does not exist yet. */
    private static final String NEW_WEAVE = "(create a weave for the target)";

    /** The preview's unscaled height. */
    private static final int PREVIEW_HEIGHT = 150;

    /** The dialog's unscaled width. */
    private static final int PREFERRED_WIDTH = 660;

    /** The dialog's unscaled height. */
    private static final int PREFERRED_HEIGHT = 600;

    /** The share of the split the list takes, leaving the rest to the preview. */
    private static final float LIST_SHARE = 0.5f;

    /** The unscaled gap between the stacked panels. */
    private static final int ROW_GAP = 8;

    /** The key the platform remembers this dialog's size under. */
    private static final String DIMENSION_KEY = "AetherWeaver.WeaveHere";

    /** The method the caret was in, which every spot is inside. */
    private final PsiMethod target;

    /** The target's compiled form, or {@code null} when there is no usable class file. */
    @Nullable
    private final MethodView compiled;

    /**
     * The search that produced the spots, kept so that it can be run again.
     *
     * <p>A change of spelling re-runs it rather than relabelling the rows, and the caller's own filter
     * - the variable a capture has to be live at - is inside it rather than applied to its result.
     */
    private final Function<TargetOperations.Spelling, List<WeaveSpot>> search;

    /** The list of positions the handler can attach at. */
    private final JBList<WeaveSpot> spots = new JBList<>();

    /** The model behind {@link #spots}, refilled whenever the spelling changes. */
    private final DefaultListModel<WeaveSpot> spotModel = new DefaultListModel<>();

    /** The weave to write into: a {@code WeaveChoice}, or {@link #NEW_WEAVE}. */
    private final ComboBox<Object> weaveBox = new ComboBox<>();

    /** Whether to narrow to the spot's own slice; enabled only when the spot offers one. */
    private final ComboBox<Object> sliceBox = new ComboBox<>();

    /** The kind of handler; a redirect is offered only for a redirectable operation. */
    private final ComboBox<HandlerOptions.Kind> kindBox = new ComboBox<>();

    /** The match rule; disabled where the spot insists on one. */
    private final ComboBox<HandlerOptions.Match> matchBox = new ComboBox<>();

    /** The form selectors are written in; changing it re-runs the search. */
    private final ComboBox<HandlerOptions.Selector> selectorBox;

    /** The handler's visibility; refilled when the chosen weave changes. */
    private final ComboBox<HandlerOptions.Visibility> visibilityBox = new ComboBox<>();

    /** The group to account the injection against; refilled when the chosen weave changes. */
    private final ComboBox<String> groupBox = new ComboBox<>();

    /** The prefix the handler's name begins with. */
    private final JBTextField prefixField;

    /** Whether a callback parameter is taken; disabled for a redirect. */
    private final JCheckBox callbackBox;

    /** Whether the locals in scope are captured; disabled for a redirect or a nameless target. */
    private final JCheckBox localsBox;

    /** Whether a documentation comment is generated. */
    private final JCheckBox javadocBox;

    /** Whether the body is marked with a {@code TODO}. */
    private final JCheckBox todoBox;

    /** The Java editor showing what would be written. */
    private final EditorTextField preview;

    /**
     * What the class file could not answer, and what was done instead.
     *
     * <p>Grey where the source could still name the members, red where only positions are left.
     */
    private final JBLabel unavailable = new JBLabel();

    /** Set while a control is being refilled, so that its own listeners do not re-enter the refill. */
    private boolean updating;

    /** Whether {@link #createCenterPanel()} has run and the components it fills exist. */
    private boolean built;

    /**
     * Builds the dialog over the spots a search has already found.
     *
     * @param project the project the dialog belongs to
     * @param target  the method the caret was in
     * @param lookup  the class file lookup for that method, which supplies its own reason when it
     *                found nothing
     * @param found   the spots to offer, best first
     * @param weaves  the weaves that already target the method's class
     * @param search  the search, to be re-run when the spelling changes
     * @param initial the choices to open with
     */
    WeaveHereDialog(@NotNull final Project project,
                    @NotNull final PsiMethod target,
                    @NotNull final CompiledClasses.MethodLookup lookup,
                    @NotNull final List<WeaveSpot> found,
                    @NotNull final List<PsiClass> weaves,
                    @NotNull final Function<TargetOperations.Spelling, List<WeaveSpot>> search,
                    @NotNull final HandlerOptions initial) {
        super(project, true);
        this.target = target;
        this.compiled = lookup.method();
        this.search = search;
        // Shown, not swallowed, and it has to say which of two things happened. Without a class
        // file the source can still name the members a handler attaches to — it just cannot number
        // the instructions, so the injection declares how many positions it expects instead. That
        // is a working answer and must not read like a failure. Only when the source names nothing
        // either is the list really down to the three positional points, and then a reader who is
        // not told sees an action that appears to know only HEAD.
        this.unavailable.setText(noteFor(lookup, found));
        this.unavailable.setForeground(namesSomething(found) ? JBColor.GRAY : JBColor.RED);

        this.selectorBox = new ComboBox<>(HandlerOptions.Selector.values());
        this.selectorBox.setSelectedItem(initial.selector());
        this.prefixField = new JBTextField(initial.prefix());
        this.callbackBox = new JCheckBox("Take a callback parameter", initial.callback());
        this.localsBox = new JCheckBox("Capture the locals in scope", initial.locals());
        this.javadocBox = new JCheckBox("Generate a documentation comment", initial.javadoc());
        this.todoBox = new JCheckBox("Mark the body with a TODO", initial.todo());

        found.forEach(this.spotModel::addElement);
        fillWeaves(weaves);

        final Document document = EditorFactory.getInstance().createDocument("");
        this.preview = new EditorTextField(document, project, JavaFileType.INSTANCE, true, false);

        // A spelling is not presentation. A simple-name owner matches by suffix, so it can select
        // a wider set and land the same instruction on a different ordinal — changing it re-runs the
        // search rather than relabelling what is already there.
        this.selectorBox.addActionListener(event -> onSpellingChanged());
        this.weaveBox.addActionListener(event -> onWeaveChanged());
        this.sliceBox.addActionListener(event -> onOptionChanged());
        this.kindBox.addActionListener(event -> onKindChanged());
        for (final JComponent control : new JComponent[]{this.matchBox, this.visibilityBox,
                this.groupBox, this.callbackBox, this.localsBox, this.javadocBox, this.todoBox}) {
            if (control instanceof final ComboBox<?> combo) {
                combo.addActionListener(event -> onOptionChanged());
            } else if (control instanceof final JCheckBox check) {
                check.addActionListener(event -> onOptionChanged());
            }
        }
        this.prefixField.getDocument().addDocumentListener(new PrefixListener());

        setTitle(TITLE);
        setOKButtonText("Generate");
        init();
    }

    /**
     * Returns the spot the handler will be generated for.
     *
     * <p>The narrowed form of the selected spot when narrowing was asked for and the spot offers it,
     * and the spot itself otherwise.
     *
     * @return the chosen spot, or {@code null} when nothing is selected
     */
    @Nullable
    WeaveSpot spot() {
        final WeaveSpot selected = this.spots.getSelectedValue();
        if (selected == null) {
            return null;
        }
        return this.sliceBox.getSelectedItem() instanceof WeaveSpot && selected.isNarrowable()
                ? selected.narrowed()
                : selected;
    }

    /**
     * Phrases what the missing class file cost.
     *
     * <p>Two different things, and the note has to say which. Where the source still named members the
     * result is a working answer that must not read like a failure; where it named nothing the list is
     * down to the positional points, and a reader who is not told sees a feature that appears to know
     * only the head of a method.
     *
     * @param lookup the lookup, whose reason is quoted
     * @param found  the spots the search returned
     * @return the note, or an empty string when the class file was read
     */
    @NotNull
    private static String noteFor(@NotNull final CompiledClasses.MethodLookup lookup,
                                  @NotNull final List<WeaveSpot> found) {
        if (lookup.isAvailable()) {
            return "";
        }
        return namesSomething(found)
                ? "Read from the source — " + lookup.reason() + ". The members are exact; which "
                        + "instruction they are is not, so the injection insists on the number of "
                        + "positions it expects rather than pinning an ordinal. Build for exact "
                        + "ordinals and slices."
                : "Only positions can be offered: " + lookup.reason() + '.';
    }

    /**
     * Reports whether any spot names an operation rather than only a position.
     *
     * @param found the spots to examine
     * @return {@code true} when at least one carries an operation
     */
    private static boolean namesSomething(@NotNull final List<WeaveSpot> found) {
        for (final WeaveSpot spot : found) {
            if (spot.operation() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the note shown under the list.
     *
     * @return the note, empty when the class file was read
     */
    @NotNull
    String unavailableMessage() {
        return this.unavailable.getText();
    }

    /**
     * Returns the weave to write into.
     *
     * @return the chosen weave, or {@code null} when {@link #NEW_WEAVE} is selected and one is to be
     *         created
     */
    @Nullable
    PsiClass weave() {
        return this.weaveBox.getSelectedItem() instanceof final WeaveChoice choice
                ? choice.weave()
                : null;
    }

    /**
     * Returns the choices as they stand.
     *
     * <p>The point is not among the controls: it is the chosen spot's, and
     * {@link HandlerOptions.Point#HEAD} only when nothing is chosen.
     *
     * @return the current options
     */
    @NotNull
    HandlerOptions options() {
        final WeaveSpot spot = spot();
        final Object group = this.groupBox.getSelectedItem();
        return new HandlerOptions(
                orDefault(this.kindBox.getSelectedItem(), HandlerOptions.Kind.class,
                        HandlerOptions.Kind.INJECT),
                spot == null
                        ? HandlerOptions.Point.HEAD
                        : HandlerOptions.Point.of(spot.point()),
                orDefault(this.matchBox.getSelectedItem(), HandlerOptions.Match.class,
                        HandlerOptions.Match.EVERY),
                orDefault(this.selectorBox.getSelectedItem(), HandlerOptions.Selector.class,
                        HandlerOptions.Selector.QUALIFIED),
                orDefault(this.visibilityBox.getSelectedItem(), HandlerOptions.Visibility.class,
                        HandlerOptions.Visibility.AUTOMATIC),
                prefix(),
                group == null || NO_GROUP.equals(group) ? "" : group.toString(),
                this.callbackBox.isSelected(),
                this.localsBox.isSelected(),
                this.javadocBox.isSelected(),
                this.todoBox.isSelected());
    }

    /**
     * Returns the locals live at every site the chosen spot resolves to.
     *
     * @return the captures, empty when they were not asked for, nothing is chosen, or there is no
     *         class file to read names from
     */
    @Unmodifiable
    @NotNull
    List<TargetLocals.Capture> captures() {
        final WeaveSpot spot = spot();
        if (!this.localsBox.isSelected() || spot == null || this.compiled == null) {
            return List.of();
        }
        return TargetLocals.at(this.compiled, TargetOperations.sitesOf(this.compiled, spot.point(),
                spot.operation(), spot.slice()));
    }

    /**
     * Returns the slice the chosen spot carries.
     *
     * @return the bounds, or {@code null} when nothing is chosen or the spot is not narrowed
     */
    @Nullable
    TargetOperations.Bounds bounds() {
        final WeaveSpot spot = spot();
        return spot == null ? null : spot.slice();
    }

    /**
     * Builds the list, the form and the preview.
     *
     * <p>The list opens on its first row, which is the best answer the search found, and carries
     * type-to-find.
     *
     * @return the dialog's centre panel
     */
    @Override
    @NotNull
    protected JComponent createCenterPanel() {
        this.spots.setModel(this.spotModel);
        this.spots.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.spots.setCellRenderer(new SpotRenderer());
        this.spots.addListSelectionListener(event -> onSpotChanged());
        ListSpeedSearch.installOn(this.spots, WeaveHereDialog::labelOf);
        if (!this.spotModel.isEmpty()) {
            // Opened on the best answer, which is what "here" asked for. Everything below it is a
            // refinement of that answer rather than a question standing in front of it.
            this.spots.setSelectedIndex(0);
        }

        final JPanel list = new JPanel(new BorderLayout(0, JBUI.scale(ROW_GAP)));
        list.add(new JBLabel(SPOTS_TITLE), BorderLayout.NORTH);
        list.add(new JBScrollPane(this.spots), BorderLayout.CENTER);
        list.add(this.unavailable, BorderLayout.SOUTH);

        final JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Target:", new JBLabel(describeTarget()))
                .addLabeledComponent("Weave:", this.weaveBox)
                .addLabeledComponent("Handler:", this.kindBox)
                .addLabeledComponent("Narrow to:", this.sliceBox)
                .addLabeledComponent("Positions:", this.matchBox)
                .addLabeledComponent("Selector:", this.selectorBox)
                .addLabeledComponent("Visibility:", this.visibilityBox)
                .addLabeledComponent("Group:", this.groupBox)
                .addLabeledComponent("Name prefix:", this.prefixField)
                .addComponent(this.callbackBox)
                .addComponent(this.localsBox)
                .addComponent(this.javadocBox)
                .addComponent(this.todoBox)
                .getPanel();

        this.preview.setBorder(IdeBorderFactory.createTitledBorder("Preview", false));
        this.preview.setPreferredSize(new Dimension(PREFERRED_WIDTH, JBUI.scale(PREVIEW_HEIGHT)));

        final JBSplitter split = new JBSplitter(true, LIST_SHARE);
        split.setFirstComponent(list);
        split.setSecondComponent(this.preview);

        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(ROW_GAP)));
        panel.add(form, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(PREFERRED_WIDTH, JBUI.scale(PREFERRED_HEIGHT)));

        this.built = true;
        refreshForWeave();
        refreshForSpot();
        return panel;
    }

    /**
     * Returns the component to focus when the dialog opens.
     *
     * @return the spot list, or {@code null} before the panel exists
     */
    @Override
    @Nullable
    public JComponent getPreferredFocusedComponent() {
        return this.built ? this.spots : null;
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
     * @return the first problem found - nothing selected, or a prefix that cannot begin a method name
     *         - or {@code null} when the dialog can be accepted
     */
    @Override
    @Nullable
    protected ValidationInfo doValidate() {
        if (this.spots.getSelectedValue() == null) {
            return new ValidationInfo("Choose where the handler attaches", this.spots);
        }
        final String prefix = prefix();
        if (!prefix.isEmpty()
                && !PsiNameHelper.getInstance(this.target.getProject()).isIdentifier(prefix)) {
            return new ValidationInfo("'" + prefix + "' cannot start a method name",
                    this.prefixField);
        }
        return null;
    }

    // --- the four entry points ---------------------------------------------------------------

    /**
     * Re-runs the search in the newly chosen spelling.
     *
     * <p>The chosen instruction survives it. A spelling changes the ordinals rather than the
     * instruction, and dropping the selection would answer a question about how to write a target by
     * moving where the handler goes.
     */
    private void onSpellingChanged() {
        if (this.updating) {
            return;
        }
        // The chosen instruction survives the re-search, and it has to. A spelling changes the
        // ordinals, not the instruction, and dropping the selection here would answer a question
        // about how to write a target by silently moving where the handler goes.
        final int index = indexOfInstruction(this.spots.getSelectedValue());
        this.updating = true;
        try {
            this.spotModel.clear();
            this.search.apply(spellingOf(options().selector())).forEach(this.spotModel::addElement);
            selectInstruction(index);
        } finally {
            this.updating = false;
        }
        refreshForSpot();
    }

    /** Reacts to a change of weave, which decides the visibilities and the groups on offer. */
    private void onWeaveChanged() {
        if (this.updating) {
            return;
        }
        refreshForWeave();
        refreshPreview();
    }

    /** Reacts to a change of spot, which decides the point, the kinds, the slice and the match rule. */
    private void onSpotChanged() {
        if (this.updating) {
            return;
        }
        refreshForSpot();
    }

    /** Reacts to a change of kind, which decides whether the parameter check boxes mean anything. */
    private void onKindChanged() {
        if (this.updating) {
            return;
        }
        refreshParameterControls();
        refreshPreview();
    }

    /** Reacts to a change that only the generated text depends on. */
    private void onOptionChanged() {
        if (this.updating) {
            return;
        }
        refreshPreview();
    }

    // --- what each change rebuilds -----------------------------------------------------------

    /**
     * Refills the controls that belong to the chosen weave.
     *
     * <p>A static weave is offered only the visibilities its target can reach. A group is only
     * meaningful against the weave that declared it, so one is carried over only if the new weave
     * declares it too: naming a group that does not exist there would be a check that silently does
     * not happen, and naming one that does exist would be a check about something else.
     */
    private void refreshForWeave() {
        if (!this.built) {
            return;
        }
        final PsiClass weave = weave();
        final boolean isStatic = weave != null && WeaveDeclarations.isStaticWeave(weave);
        this.updating = true;
        try {
            final Object visibility = this.visibilityBox.getSelectedItem();
            this.visibilityBox.removeAllItems();
            for (final HandlerOptions.Visibility candidate : HandlerOptions.Visibility.values()) {
                if (!isStatic || candidate.survivesAStaticWeave()) {
                    this.visibilityBox.addItem(candidate);
                }
            }
            this.visibilityBox.setSelectedItem(
                    visibility instanceof final HandlerOptions.Visibility wanted
                            && (!isStatic || wanted.survivesAStaticWeave())
                            ? wanted
                            : HandlerOptions.Visibility.AUTOMATIC);

            final Object group = this.groupBox.getSelectedItem();
            this.groupBox.removeAllItems();
            this.groupBox.addItem(NO_GROUP);
            final List<String> declared =
                    weave == null ? List.of() : WeaveDeclarations.groupsOf(weave);
            declared.forEach(this.groupBox::addItem);
            // A group name is only meaningful against the weave that declared it. Carrying one
            // across to another weave would name a group that does not exist there — silently
            // weaker than no group — or, worse, one that does and means something else.
            this.groupBox.setSelectedItem(
                    group != null && declared.contains(group.toString()) ? group : NO_GROUP);
            this.groupBox.setEnabled(!declared.isEmpty());
        } finally {
            this.updating = false;
        }
    }

    /** Refills the slice, kind and match controls for the chosen spot, then redraws the preview. */
    private void refreshForSpot() {
        if (!this.built) {
            return;
        }
        final WeaveSpot selected = this.spots.getSelectedValue();
        this.updating = true;
        try {
            refillSlices(selected);
            refillKinds(selected);
            refillMatches(selected);
            this.matchBox.setEnabled(insistedOn(selected) == null);
        } finally {
            this.updating = false;
        }
        refreshParameterControls();
        refreshPreview();
    }

    /**
     * Offers the spot's own narrowing, if it has one, and selects it.
     *
     * @param spot the chosen spot, or {@code null} for none
     */
    private void refillSlices(@Nullable final WeaveSpot spot) {
        this.sliceBox.removeAllItems();
        this.sliceBox.addItem(NO_SLICE);
        if (spot != null && spot.isNarrowable()) {
            this.sliceBox.addItem(spot.narrowed());
            this.sliceBox.setSelectedIndex(1);
        }
        this.sliceBox.setEnabled(this.sliceBox.getItemCount() > 1);
    }

    /**
     * Offers the kinds the spot allows.
     *
     * <p>An inject always; a redirect only where the spot carries an operation that can be stood in
     * for.
     *
     * @param spot the chosen spot, or {@code null} for none
     */
    private void refillKinds(@Nullable final WeaveSpot spot) {
        final HandlerOptions.Point point = spot == null
                ? HandlerOptions.Point.HEAD
                : HandlerOptions.Point.of(spot.point());
        final Object wanted = this.kindBox.getSelectedItem();
        this.kindBox.removeAllItems();
        for (final HandlerOptions.Kind candidate : HandlerOptions.Kind.values()) {
            if (candidate == HandlerOptions.Kind.INJECT
                    || candidate.appliesTo(point) && spot != null && spot.operation() != null
                            && spot.operation().isRedirectable()) {
                this.kindBox.addItem(candidate);
            }
        }
        this.kindBox.setSelectedItem(wanted instanceof final HandlerOptions.Kind kind
                && indexOf(this.kindBox, kind) >= 0 ? kind : HandlerOptions.Kind.INJECT);
    }

    /**
     * Offers the match rules the spot's point allows, or insists on one.
     *
     * <p>Where {@link #insistedOn(WeaveSpot)} answers, that answer is not a preference the last choice
     * may override: a spot read from the source names a member rather than an instruction, and the
     * only thing keeping such an injection from binding to a call the author never looked at is its
     * saying how many positions it expects.
     *
     * @param spot the chosen spot, or {@code null} for none
     */
    private void refillMatches(@Nullable final WeaveSpot spot) {
        final HandlerOptions.Point point = spot == null
                ? HandlerOptions.Point.HEAD
                : HandlerOptions.Point.of(spot.point());
        final Object wanted = this.matchBox.getSelectedItem();
        this.matchBox.removeAllItems();
        for (final HandlerOptions.Match candidate : HandlerOptions.Match.values()) {
            if (candidate.appliesTo(point)) {
                this.matchBox.addItem(candidate);
            }
        }
        final HandlerOptions.Match insisted = insistedOn(spot);
        if (insisted != null) {
            // Not a preference the user's last choice may override. A spot read from the source
            // carries no ordinal — it names a member, not an instruction — so the only thing keeping
            // it from binding to a call the author never looked at is the injection declaring how
            // many positions it expects. Left at "every matching position, no complaint", the same
            // annotation is a handler that silently runs somewhere else.
            this.matchBox.setSelectedItem(insisted);
            return;
        }
        this.matchBox.setSelectedItem(wanted instanceof final HandlerOptions.Match match
                && match.appliesTo(point) ? match : HandlerOptions.Match.EVERY);
    }

    /**
     * Returns the match rule a source-read spot forces.
     *
     * <p>One match in the source becomes {@link HandlerOptions.Match#EXACTLY_ONE}, so that a build with
     * the class file present fails rather than binding elsewhere; several become
     * {@link HandlerOptions.Match#EVERY_REQUIRED}, since insisting on one would fail the build on
     * correct code.
     *
     * @param spot the chosen spot, or {@code null} for none
     * @return the rule to insist on, or {@code null} when the user is free to choose
     */
    @Nullable
    private static HandlerOptions.Match insistedOn(@Nullable final WeaveSpot spot) {
        if (spot == null || spot.confidence() != WeaveSpot.Confidence.FROM_SOURCE
                || spot.operation() == null) {
            return null;
        }
        return spot.matches() == 1
                ? HandlerOptions.Match.EXACTLY_ONE
                : HandlerOptions.Match.EVERY_REQUIRED;
    }

    /**
     * Enables the two parameter check boxes only where they mean anything.
     *
     * <p>A redirect has no use for either. A capture needs a name, and a target compiled without a
     * local variable table has none, so every capture would be {@code AW1052} on a handler that reads
     * perfectly; the check box says so in its tooltip instead of offering it.
     */
    private void refreshParameterControls() {
        final boolean injecting =
                this.kindBox.getSelectedItem() != HandlerOptions.Kind.REDIRECT;
        // A capture names a variable by what the compiler recorded, so without a local variable
        // table there is no name for @Local to bind to and every capture would be AW1052 on a
        // handler that reads perfectly. The checkbox says so instead of offering it.
        final boolean nameable = this.compiled != null && TargetLocals.isAvailable(this.compiled);
        this.callbackBox.setEnabled(injecting);
        this.localsBox.setEnabled(injecting && nameable);
        this.localsBox.setToolTipText(nameable
                ? null
                : "The target carries no local variable table, so @Local has no name to bind to — "
                        + "recompile it with -g.");
    }

    /** Rewrites the preview from the chosen spot and the current options. */
    private void refreshPreview() {
        if (!this.built) {
            return;
        }
        final WeaveSpot spot = spot();
        this.preview.setText(spot == null
                ? "// choose where the handler attaches to see what will be generated"
                : previewOf(spot));
    }

    /**
     * Renders what would be generated for one spot.
     *
     * <p>Where no weave is chosen the text is generated against the target's own class, which is what
     * a weave created for it will resemble. Anything the generator throws is logged and shown as a
     * comment rather than allowed out of a Swing listener, while the platform's own cancellation is
     * rethrown.
     *
     * @param spot the spot to render
     * @return the generated text, or a comment saying why there is none
     */
    @NotNull
    private String previewOf(@NotNull final WeaveSpot spot) {
        final PsiClass into = weave() == null ? this.target.getContainingClass() : weave();
        if (into == null) {
            return "// the target's class cannot be read";
        }
        try {
            final PsiMethod generated = AddHandlerHandler.handlerFor(into, this.target,
                    spot.operation(), captures(), spot.slice(), options());
            return generated == null
                    ? "// nothing can be generated here — a type involved does not resolve"
                    : generated.getText();
        } catch (final ProcessCanceledException cancelled) {
            // The platform's own control flow. Swallowing it would strand a cancelled read action.
            throw cancelled;
        } catch (final RuntimeException failed) {
            // A preview may report that it cannot render something. It may not take the dialog
            // down with it, which is what an exception out of a Swing listener does: the user is
            // left with a stack trace over a half-drawn window and no way back to their choices.
            LOG.warn("the weave here preview could not be rendered", failed);
            return "// this combination cannot be generated: " + failed.getMessage();
        }
    }

    // --- the small helpers ---------------------------------------------------------------------

    /**
     * Fills the weave combo box and decides what it opens on.
     *
     * <p>A single weave is preselected. Several are not: a project with two weaves on one class has
     * them for a reason, and this is the one decision here that reading the preview cannot undo,
     * because the preview looks the same either way. The entry that creates a weave is always last.
     *
     * @param weaves the weaves that already target the method's class
     */
    private void fillWeaves(@NotNull final List<PsiClass> weaves) {
        this.weaveBox.setRenderer(new ChoiceRenderer());
        for (final PsiClass weave : weaves) {
            final String qualified = weave.getQualifiedName();
            this.weaveBox.addItem(new WeaveChoice(weave,
                    qualified == null ? String.valueOf(weave.getName()) : qualified));
        }
        this.weaveBox.addItem(NEW_WEAVE);
        // Not preselected when there are several. A project with two weaves on a class has them
        // for a reason, and picking the first would put an audit handler in the caching weave — the
        // one decision here that cannot be undone by reading the preview, because the preview looks
        // the same either way.
        this.weaveBox.setSelectedIndex(weaves.size() == 1 ? 0 : this.weaveBox.getItemCount() - 1);
    }

    /**
     * A weave offered in the combo box, with the text it is shown as.
     *
     * @param weave the weave to write into
     * @param label its qualified name, or its simple name when it has none
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record WeaveChoice(@NotNull PsiClass weave, @NotNull String label) {
    }

    /**
     * Returns the instruction index a spot names.
     *
     * @param spot the spot to identify, or {@code null}
     * @return the index, or {@code -1} when the spot names no operation
     */
    private static int indexOfInstruction(@Nullable final WeaveSpot spot) {
        return spot == null || spot.operation() == null ? -1 : spot.operation().index();
    }

    /**
     * Selects the row naming the given instruction, falling back to the first row.
     *
     * @param index the instruction index to look for, or {@code -1} for none
     */
    private void selectInstruction(final int index) {
        for (int row = 0; index >= 0 && row < this.spotModel.size(); row++) {
            if (indexOfInstruction(this.spotModel.get(row)) == index) {
                this.spots.setSelectedIndex(row);
                this.spots.ensureIndexIsVisible(row);
                return;
            }
        }
        this.spots.setSelectedIndex(this.spotModel.isEmpty() ? -1 : 0);
    }

    /**
     * Returns the row an item occupies in a combo box.
     *
     * @param combo the combo box to search
     * @param item  the item to look for
     * @return the row, or {@code -1} when the item is not in the model
     */
    private static int indexOf(@NotNull final ComboBox<?> combo, @NotNull final Object item) {
        for (int row = 0; row < combo.getItemCount(); row++) {
            if (item.equals(combo.getItemAt(row))) {
                return row;
            }
        }
        return -1;
    }

    /**
     * Returns the selected item when it is of the expected type.
     *
     * <p>A combo box that has just been emptied holds {@code null}, and every reader of one here wants
     * the same answer for that as for a stale value of another type.
     *
     * @param <T>      the type wanted
     * @param selected the selected item, possibly {@code null}
     * @param type     the type wanted
     * @param fallback what to return otherwise
     * @return the item, or {@code fallback}
     */
    @NotNull
    private static <T> T orDefault(@Nullable final Object selected,
                                   @NotNull final Class<T> type,
                                   @NotNull final T fallback) {
        return type.isInstance(selected) ? type.cast(selected) : fallback;
    }

    /**
     * Translates a selector form into the spelling the search uses.
     *
     * @param form the chosen form
     * @return the matching spelling
     */
    @NotNull
    private static TargetOperations.Spelling spellingOf(
            @NotNull final HandlerOptions.Selector form) {
        return switch (form) {
            case QUALIFIED -> TargetOperations.Spelling.QUALIFIED;
            case SIMPLE -> TargetOperations.Spelling.SIMPLE;
            case DESCRIPTOR -> TargetOperations.Spelling.DESCRIPTOR;
        };
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
     * Describes the target method for the form's first row.
     *
     * @return the owner's name and the method's signature
     */
    @NotNull
    private String describeTarget() {
        final PsiClass owner = this.target.getContainingClass();
        return (owner == null || owner.getName() == null ? "" : owner.getName() + '.')
                + PsiFormatUtil.formatMethod(this.target, PsiSubstitutor.EMPTY,
                        PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_PARAMETERS,
                        PsiFormatUtilBase.SHOW_TYPE);
    }

    /**
     * Returns the text type-to-find searches a spot by.
     *
     * @param spot the spot to label
     * @return the spot's own label
     */
    @NotNull
    private static String labelOf(@NotNull final WeaveSpot spot) {
        return spot.label();
    }

    /**
     * Renders a spot as what it is, followed by why it was offered.
     *
     * <p>The reason is on the row rather than in a tooltip, and an exact match is bold. An author who
     * cannot see how the caret was read has to take an ordinal on trust, and an ordinal is the one
     * thing in this annotation that fails silently when it is wrong.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class SpotRenderer extends ColoredListCellRenderer<WeaveSpot> {

        /** Creates the renderer. */
        SpotRenderer() {
            // Stateless.
        }

        /**
         * Appends the spot's label and, greyed, its reason.
         *
         * @param list     the list being rendered
         * @param value    the spot, or {@code null} for an empty row
         * @param index    the row's index
         * @param selected whether the row is selected
         * @param focused  whether the row has focus
         */
        @Override
        protected void customizeCellRenderer(@NotNull final JList<? extends WeaveSpot> list,
                                             @Nullable final WeaveSpot value,
                                             final int index,
                                             final boolean selected,
                                             final boolean focused) {
            if (value == null) {
                return;
            }
            // The reason is on the row, not in a tooltip. An author who cannot check that the
            // tool read their caret the way they meant it has to take an ordinal on trust, and an
            // ordinal is the one thing in this annotation that fails silently when it is wrong.
            append(value.label(), value.confidence() == WeaveSpot.Confidence.EXACT
                    ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("   " + value.why(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }

    /**
     * Renders the weave combo box, greying everything that is not a weave.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class ChoiceRenderer extends ColoredListCellRenderer<Object> {

        /** Creates the renderer. */
        ChoiceRenderer() {
            // Stateless.
        }

        /**
         * Appends a weave's label, or greys anything else.
         *
         * @param list     the list being rendered
         * @param value    the item, or {@code null} for an empty row
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
            if (value instanceof final WeaveChoice choice) {
                append(choice.label());
            } else if (value != null) {
                append(value.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
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
