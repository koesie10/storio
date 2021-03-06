package com.pushtorefresh.storio.common.annotations.processor;

import com.pushtorefresh.storio.common.annotations.processor.generate.Generator;
import com.pushtorefresh.storio.common.annotations.processor.introspection.StorIOColumnMeta;
import com.pushtorefresh.storio.common.annotations.processor.introspection.StorIOTypeMeta;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * Base annotation processor for StorIO
 * <p>
 * It'll process annotations to generate StorIO Object-Mapping
 * <p>
 * Addition: Annotation Processor should work fast and be optimized because it's part of compilation
 * We don't want to annoy developers, who use StorIO
 */
// Generate file with annotation processor declaration via another Annotation Processor!
public abstract class StorIOAnnotationsProcessor
        <TypeMeta extends StorIOTypeMeta, ColumnMeta extends StorIOColumnMeta>
        extends AbstractProcessor {

    private Filer filer;
    private Elements elementUtils;
    private Messager messager;

    /**
     * Processes class annotations
     *
     * @param roundEnvironment environment
     * @return non-null unmodifiable map(element, typeMeta)
     */
    @NotNull
    private Map<TypeElement, TypeMeta> processAnnotatedClasses(@NotNull final RoundEnvironment roundEnvironment, @NotNull final Elements elementUtils) {
        final Set<? extends Element> elementsAnnotatedWithStorIOType
                = roundEnvironment.getElementsAnnotatedWith(getTypeAnnotationClass());

        final Map<TypeElement, TypeMeta> results
                = new HashMap<TypeElement, TypeMeta>(elementsAnnotatedWithStorIOType.size());

        for (final Element annotatedElement : elementsAnnotatedWithStorIOType) {
            final TypeElement classElement = validateAnnotatedClass(annotatedElement);
            final TypeMeta typeMeta = processAnnotatedClass(classElement, elementUtils);
            results.put(classElement, typeMeta);
        }

        return Collections.unmodifiableMap(results);
    }

    /**
     * Checks that annotated element satisfies all required conditions
     *
     * @param annotatedElement an annotated type
     * @return {@link TypeElement} object
     */
    @NotNull
    private TypeElement validateAnnotatedClass(@NotNull final Element annotatedElement) {
        // we expect here that annotatedElement is Class, annotation requires that via @Target
        final TypeElement annotatedTypeElement = (TypeElement) annotatedElement;

        if (annotatedTypeElement.getModifiers().contains(PRIVATE)) {
            throw new ProcessingException(
                    annotatedElement,
                    getTypeAnnotationClass().getSimpleName() + " can not be applied to private class: " + annotatedTypeElement.getQualifiedName()
            );
        }

        return annotatedTypeElement;
    }

    /**
     * Checks that element annotated with {@link StorIOColumnMeta} satisfies all required conditions
     *
     * @param annotatedField an annotated field
     */
    protected void validateAnnotatedField(@NotNull final Element annotatedField) {
        // we expect here that annotatedElement is Field, annotation requires that via @Target

        final Element enclosingElement = annotatedField.getEnclosingElement();

        if (!enclosingElement.getKind().equals(CLASS)) {
            throw new ProcessingException(
                    annotatedField,
                    "Please apply " + getTypeAnnotationClass().getSimpleName() + " to fields of class: " + annotatedField.getSimpleName()
            );
        }

        if (enclosingElement.getAnnotation(getTypeAnnotationClass()) == null) {
            throw new ProcessingException(
                    annotatedField,
                    "Please annotate class " + enclosingElement.getSimpleName() + " with " + getTypeAnnotationClass().getSimpleName()
            );
        }

        if (annotatedField.getModifiers().contains(PRIVATE)) {
            throw new ProcessingException(
                    annotatedField,
                    getColumnAnnotationClass().getSimpleName() + " can not be applied to private field: " + annotatedField.getSimpleName()
            );
        }

        if (annotatedField.getModifiers().contains(FINAL)) {
            throw new ProcessingException(
                    annotatedField,
                    getColumnAnnotationClass().getSimpleName() + " can not be applied to final field: " + annotatedField.getSimpleName()
            );
        }
    }

    @Override
    public synchronized void init(@NotNull final ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        elementUtils = processingEnv.getElementUtils(); // why class name is "Elements" but method "getElementUtils()", OKAY..
        messager = processingEnv.getMessager();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    //endregion

    /**
     * For those who don't familiar with Annotation Processing API — this is the main method of Annotation Processor lifecycle
     * <p>
     * It will be called after Java Compiler will find lang elements annotated with annotations from {@link #getSupportedAnnotationTypes()}
     *
     * @param annotations set of annotations
     * @param roundEnv    environment of current processing round
     * @return true if annotation processor should not be invoked in next rounds of annotation processing, false otherwise
     */
    @Override
    public boolean process(@Nullable final Set<? extends TypeElement> annotations, @NotNull final RoundEnvironment roundEnv) {
        try {
            final Map<TypeElement, TypeMeta> annotatedClasses = processAnnotatedClasses(roundEnv, elementUtils);

            processAnnotatedFields(roundEnv, annotatedClasses);

            validateAnnotatedClassesAndColumns(annotatedClasses);

            final Generator<TypeMeta> putResolverGenerator = createPutResolver();
            final Generator<TypeMeta> getResolverGenerator = createGetResolver();
            final Generator<TypeMeta> deleteResolverGenerator = createDeleteResolver();
            final Generator<TypeMeta> mappingGenerator = createMapping();

            for (TypeMeta typeMeta : annotatedClasses.values()) {
                putResolverGenerator.generateJavaFile(typeMeta).writeTo(filer);
                getResolverGenerator.generateJavaFile(typeMeta).writeTo(filer);
                deleteResolverGenerator.generateJavaFile(typeMeta).writeTo(filer);
                mappingGenerator.generateJavaFile(typeMeta).writeTo(filer);
            }
        } catch (ProcessingException e) {
            messager.printMessage(ERROR, e.getMessage(), e.element());
        } catch (Exception e) {
            messager.printMessage(ERROR, "Problem occurred with StorIOProcessor: " + e.getMessage());
        }

        return true;
    }

    /**
     * Processes annotated class
     *
     * @param classElement type element
     * @param elementUtils utils for working with elementUtils
     * @return result of processing as {@link TypeMeta}
     */
    @NotNull
    protected abstract TypeMeta processAnnotatedClass(@NotNull TypeElement classElement, @NotNull Elements elementUtils);

    /**
     * Processes fields
     *
     * @param roundEnvironment current processing environment
     * @param annotatedClasses map of annotated classes
     */
    protected abstract void processAnnotatedFields(@NotNull final RoundEnvironment roundEnvironment, @NotNull Map<TypeElement, TypeMeta> annotatedClasses);

    /**
     * Processes annotated field and returns result of processing or throws exception
     *
     * @param annotatedField field that was annotated as column
     * @return non-null {@link StorIOColumnMeta} with meta information about field
     */
    @NotNull
    protected abstract ColumnMeta processAnnotatedField(@NotNull final Element annotatedField);

    protected abstract void validateAnnotatedClassesAndColumns(@NotNull Map<TypeElement, TypeMeta> annotatedClasses);

    @NotNull
    protected abstract Class<? extends Annotation> getTypeAnnotationClass();

    @NotNull
    protected abstract Class<? extends Annotation> getColumnAnnotationClass();

    @NotNull
    protected abstract Generator<TypeMeta> createPutResolver();

    @NotNull
    protected abstract Generator<TypeMeta> createGetResolver();

    @NotNull
    protected abstract Generator<TypeMeta> createDeleteResolver();

    @NotNull
    protected abstract Generator<TypeMeta> createMapping();
}
