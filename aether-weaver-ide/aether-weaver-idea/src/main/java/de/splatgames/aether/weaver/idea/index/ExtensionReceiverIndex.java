package de.splatgames.aether.weaver.idea.index;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.text.CharArrayUtil;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the simple name of a receiver type to the files whose extension holders contribute to it.
 *
 * <p>Without it, finding what a type has gained means reading every file in the project.
 *
 * <h2>What an indexer may not do</h2>
 *
 * <p>The indexer reads the file lexically and resolves nothing. Two rules make that mandatory
 * rather than merely fast. Resolution is forbidden while indexing at all. And augmentation - which
 * is what {@code PsiClass.getMethods()} and {@code PsiClass.getFields()} run - would call this
 * plugin's own augment provider, which queries this index, which flushes the open document,
 * which indexes it: a loop that surfaces as a log line on every keystroke in a real IDE and that
 * no headless fixture reproduces. {@code IndexerDisciplineTest} reads the emitted bytecode of this
 * class and fails on any such call, including one reached indirectly.
 *
 * <p>Reading text rather than resolving is what makes the key a simple name. A holder contributing
 * to two types called {@code Money} is found for both, and the caller sorts it out with the
 * resolution it is allowed to perform.
 *
 * <h2>What is indexed</h2>
 *
 * <p>A file with no {@code Extension} in its text at all is skipped whole. In one that has it,
 * every class carrying an annotation named {@code Extension} contributes, and each of these
 * becomes a key:
 *
 * <ul>
 *   <li>the class literal on the {@code @Extension} itself, which names one receiver for every
 *       member of the holder;
 *   <li>the declared type of a {@code @Receiver} first parameter of a public static method;
 *   <li>the class literal on a {@code @Receiver} annotation on a method, which contributes a
 *       static member to that type;
 *   <li>the class literal on a {@code @Receiver} annotation on a field, for a contributed
 *       constant.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionReceiverIndex extends ScalarIndexExtension<String> {

    /** This index's identity, as the platform knows it. */
    public static final ID<String, Void> NAME =
            ID.create("de.splatgames.aether.weaver.idea.extensionReceivers");

    /** The text a file must contain before it is worth parsing. */
    private static final String EXTENSION_MARKER = ExtensionDeclarations.EXTENSION_SIMPLE_NAME;

    /** The suffix a class literal ends in, which is how one is recognised without resolving it. */
    private static final String CLASS_LITERAL = ".class";

    /** Creates the extension; the platform instantiates it. */
    public ExtensionReceiverIndex() {
        // Stateless.
    }

    /**
     * Returns the extension holders that may contribute to the given type.
     *
     * <p>The index answers per file and per simple name, so this returns every extension holder
     * declared in a file that contributes that name, whether or not each of them contributes to this
     * particular type.
     *
     * @param receiver the type to find contributions for
     * @return the holders, empty when the type has no name, while the project is indexing, or when
     *         nothing contributes the name
     */
    @Unmodifiable
    @NotNull
    public static List<PsiClass> contributingTo(@NotNull final PsiClass receiver) {
        final String simpleName = receiver.getName();
        final Project project = receiver.getProject();
        if (simpleName == null || DumbService.getInstance(project).isDumb()) {
            return List.of();
        }

        final GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        final Set<VirtualFile> files = new HashSet<>(
                FileBasedIndex.getInstance().getContainingFiles(NAME, simpleName, scope));
        if (files.isEmpty()) {
            return List.of();
        }

        final PsiManager manager = PsiManager.getInstance(project);
        final List<PsiClass> holders = new ArrayList<>();
        for (final VirtualFile file : files) {
            if (!(manager.findFile(file) instanceof final PsiJavaFile java)) {
                continue;
            }
            for (final PsiClass declared : PsiTreeUtil.findChildrenOfType(java, PsiClass.class)) {
                if (ExtensionDeclarations.isExtension(declared)) {
                    holders.add(declared);
                }
            }
        }
        return List.copyOf(holders);
    }

    /**
     * Returns every extension holder in the project.
     *
     * @param project the project to search
     * @return the holders, empty while the project is indexing
     */
    @Unmodifiable
    @NotNull
    public static List<PsiClass> allHolders(@NotNull final Project project) {
        if (DumbService.getInstance(project).isDumb()) {
            return List.of();
        }
        final FileBasedIndex index = FileBasedIndex.getInstance();
        final GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        final Set<VirtualFile> files = new HashSet<>();
        for (final String key : index.getAllKeys(NAME, project)) {
            files.addAll(index.getContainingFiles(NAME, key, scope));
        }

        final PsiManager manager = PsiManager.getInstance(project);
        final List<PsiClass> holders = new ArrayList<>();
        for (final VirtualFile file : files) {
            if (!(manager.findFile(file) instanceof final PsiJavaFile java)) {
                continue;
            }
            for (final PsiClass declared : PsiTreeUtil.findChildrenOfType(java, PsiClass.class)) {
                if (ExtensionDeclarations.isExtension(declared)) {
                    holders.add(declared);
                }
            }
        }
        return List.copyOf(holders);
    }

    /**
     * Returns this index's identity.
     *
     * @return {@link #NAME}
     */
    @Override
    @NotNull
    public ID<String, Void> getName() {
        return NAME;
    }

    /**
     * Returns the indexer that reads receiver names out of a file.
     *
     * <p>Own members only, and no resolution anywhere: see this class's documentation for why either
     * would be a defect rather than a slower path.
     *
     * @return the indexer
     */
    @Override
    @NotNull
    public DataIndexer<String, Void, FileContent> getIndexer() {
        return content -> {
            if (CharArrayUtil.indexOf(content.getContentAsText(), EXTENSION_MARKER, 0) < 0) {
                return Map.of();
            }
            final Map<String, Void> keys = new HashMap<>();
            for (final PsiClass declared
                    : PsiTreeUtil.findChildrenOfType(content.getPsiFile(), PsiClass.class)) {
                if (!carriesExtension(declared)) {
                    continue;
                }
                // getOwnMethods(), and never getMethods(). See this class's documentation:
                // getMethods() runs augmentation from inside an indexer, which is forbidden and
                // which — because this plugin's own augment provider then queries this index —
                // closes a loop through the platform's unsaved-document indexing.
                // A receiver named for the whole class is one key for all of its methods, and is
                // written as a class literal on the annotation the indexer already found.
                final String forTheClass = literalNameOf(extensionAnnotationOf(declared));
                if (forTheClass != null) {
                    keys.put(forTheClass, null);
                }
                for (final PsiMethod method : ownMethodsOf(declared)) {
                    final String receiver = receiverNameOf(method);
                    if (receiver != null) {
                        keys.put(receiver, null);
                    }
                }
                // And the fields, for the constants a holder contributes. Same rule, same lexical
                // reading: the receiver is a class literal and its name is text.
                for (final PsiField field : ownFieldsOf(declared)) {
                    final PsiAnnotation named = receiverAnnotationOf(field);
                    final String receiver = named == null ? null : literalNameOf(named);
                    if (receiver != null) {
                        keys.put(receiver, null);
                    }
                }
            }
            return keys;
        };
    }

    /**
     * Returns how a key is stored.
     *
     * @return the string descriptor
     */
    @Override
    @NotNull
    public KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    /**
     * Returns the version that decides when the stored index is discarded.
     *
     * @return the index version
     */
    @Override
    public int getVersion() {
        return 4;
    }

    /**
     * Returns the filter narrowing this index to Java files.
     *
     * @return the input filter
     */
    @Override
    @NotNull
    public FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(JavaFileType.INSTANCE);
    }

    /**
     * Reports that the keys are read from the file's content.
     *
     * @return {@code true}
     */
    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    /**
     * Returns the methods the class declares, without running augmentation.
     *
     * @param declared the class to read
     * @return its own methods, empty when the class does not expose them
     */
    @NotNull
    private static List<PsiMethod> ownMethodsOf(@NotNull final PsiClass declared) {
        return declared instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnMethods()
                : List.of();
    }

    /**
     * Reports whether the class carries an annotation named {@code Extension}.
     *
     * <p>Compared by the name as written, since an indexer cannot resolve the annotation to know
     * whether it is the right one. A class annotated with somebody else's {@code Extension}
     * contributes keys that lead nowhere, which costs a caller one file to look at.
     *
     * @param declared the class to test
     * @return {@code true} when such an annotation is written on it
     */
    private static boolean carriesExtension(@NotNull final PsiClass declared) {
        if (declared.getModifierList() == null) {
            return false;
        }
        for (final PsiAnnotation annotation : declared.getModifierList().getAnnotations()) {
            final PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference != null
                    && ExtensionDeclarations.EXTENSION_SIMPLE_NAME
                    .equals(reference.getReferenceName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the receiver a method contributes to.
     *
     * <p>Only a public static method contributes, and a constructor never does. Either the first
     * parameter is marked {@code @Receiver}, and the receiver is that parameter's type as written, or
     * the method carries {@code @Receiver} with a class literal and contributes a static member to
     * that type.
     *
     * @param method the method to read
     * @return the receiver's simple name, or {@code null} when the method contributes to nothing
     */
    @Nullable
    private static String receiverNameOf(@NotNull final PsiMethod method) {
        if (!method.hasModifierProperty(PsiModifier.PUBLIC)
                || !method.hasModifierProperty(PsiModifier.STATIC)
                || method.isConstructor()) {
            return null;
        }

        final PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length > 0 && marksReceiver(parameters[0])) {
            final PsiTypeElement type = parameters[0].getTypeElement();
            return type == null ? null : simpleNameOf(type.getText());
        }

        // The other form: @Receiver(BigDecimal.class) on the method, contributing a static method
        // to that type. Read as text like everything else here — `BigDecimal.class` is a name and a
        // suffix, and taking it apart needs no resolution.
        final PsiAnnotation onMethod = receiverAnnotationOf(method);
        return onMethod == null ? null : literalNameOf(onMethod);
    }

    /**
     * Returns the fields the class declares, without running augmentation.
     *
     * @param declared the class to read
     * @return its own fields, empty when the class does not expose them
     */
    @NotNull
    private static List<PsiField> ownFieldsOf(@NotNull final PsiClass declared) {
        // No getFields() fallback, for the reason on this class: getFields() runs augmentation,
        // and augmentation queries this index. IndexerDisciplineTest reads the emitted bytecode and
        // fails on the call, which is how this line came to be written twice.
        return declared instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnFields()
                : List.of();
    }

    /**
     * Returns the {@code @Extension} annotation written on the class.
     *
     * @param declared the class to read
     * @return the annotation, or {@code null} when none is written
     */
    @Nullable
    private static PsiAnnotation extensionAnnotationOf(@NotNull final PsiClass declared) {
        if (declared.getModifierList() == null) {
            return null;
        }
        for (final PsiAnnotation annotation : declared.getModifierList().getAnnotations()) {
            final PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference != null && ExtensionDeclarations.EXTENSION_SIMPLE_NAME
                    .equals(reference.getReferenceName())) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Returns the {@code @Receiver} annotation written on the member.
     *
     * @param member the method or field to read
     * @return the annotation, or {@code null} when none is written
     */
    @Nullable
    private static PsiAnnotation receiverAnnotationOf(@NotNull final PsiModifierListOwner member) {
        if (member.getModifierList() == null) {
            return null;
        }
        for (final PsiAnnotation annotation : member.getModifierList().getAnnotations()) {
            if (marksReceiver(annotation)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Reads the class literal out of an annotation's value.
     *
     * <p>Wherever it sits: an unnamed value and a {@code value =} beside other elements are both
     * accepted, and the last one written wins. Anything that is not a class literal - a constant, a
     * half-typed expression - is not read, because reading it would mean resolving it.
     *
     * @param annotation the annotation to read, or {@code null}
     * @return the literal's simple name, or {@code null} when there is no class literal to read
     */
    @Nullable
    private static String literalNameOf(@Nullable final PsiAnnotation annotation) {
        if (annotation == null) {
            return null;
        }
        final PsiNameValuePair[] attributes = annotation.getParameterList().getAttributes();
        // The class literal wherever it sits: @Extension(String.class) writes it unnamed, and
        // @Extension(value = String.class, scope = MODULE) writes it beside other elements.
        String written = null;
        for (final PsiNameValuePair attribute : attributes) {
            final String name = attribute.getName();
            if ((name == null || "value".equals(name)) && attribute.getValue() != null) {
                written = attribute.getValue().getText().trim();
            }
        }
        if (written == null) {
            return null;
        }
        if (!written.endsWith(CLASS_LITERAL)) {
            return null;
        }
        return simpleNameOf(written.substring(0, written.length() - CLASS_LITERAL.length()));
    }

    /**
     * Reports whether the member carries {@code @Receiver}.
     *
     * @param owner the parameter, method or field to test
     * @return {@code true} when such an annotation is written on it
     */
    private static boolean marksReceiver(@NotNull final PsiModifierListOwner owner) {
        if (owner.getModifierList() == null) {
            return false;
        }
        for (final PsiAnnotation annotation : owner.getModifierList().getAnnotations()) {
            if (marksReceiver(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether the annotation is named {@code Receiver}.
     *
     * @param annotation the annotation to test
     * @return {@code true} when that is the name as written
     */
    private static boolean marksReceiver(@NotNull final PsiAnnotation annotation) {
        final PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        return reference != null
                && ExtensionDeclarations.RECEIVER_SIMPLE_NAME.equals(reference.getReferenceName());
    }

    /**
     * Reduces a type as written to the key this index stores.
     *
     * <p>Type arguments are dropped, then everything up to the last dot or dollar, so that a nested
     * type is keyed by its own name and a qualified reference by the same key an unqualified one
     * produces.
     *
     * @param written the type as written in the source
     * @return its simple name
     */
    @NotNull
    private static String simpleNameOf(@NotNull final String written) {
        String name = written.trim();
        final int arguments = name.indexOf('<');
        if (arguments >= 0) {
            name = name.substring(0, arguments).trim();
        }
        final int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return cut < 0 ? name : name.substring(cut + 1);
    }
}
