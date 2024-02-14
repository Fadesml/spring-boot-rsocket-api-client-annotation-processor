package il.fadesml.rsocket.processor;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;
import il.fadesml.rsocket.annotation.RSocketApi;
import il.fadesml.rsocket.constant.RSocketMethodType;
import lombok.SneakyThrows;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import il.fadesml.rsocket.constant.RSocketAnnotationProcessorConstant;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;


@SupportedAnnotationTypes(RSocketAnnotationProcessorConstant.RSOCKET_API)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@AutoService(Processor.class)
public class RSocketApiAnnotationProcessor extends AbstractProcessor {

    @Override
    @SneakyThrows
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(annotation);

            for (Element annotatedElement : annotatedElements) {
                if (annotatedElement.getKind() == ElementKind.INTERFACE) {
                    TypeElement interfaceElement = (TypeElement) annotatedElement;
                    generateRSocketApiClient(interfaceElement);
                }
            }
        }

        return true;
    }

    private final String monoClassName = Mono.class.getName();
    private final String fluxClassName = Flux.class.getName();
    private final String voidClassName = Void.class.getName();

    private RSocketMethodType defineRSocketMethodType(
            String inputClassName,
            String outputClassName,
            String outputClassGenericTypeClassName
    ) {
        if (isFluxClass(inputClassName) && isFluxClass(outputClassName)) {
            return RSocketMethodType.REQUEST_CHANNEL;
        } else if (isMonoClass(inputClassName) && isMonoClass(outputClassName) && isVoidClass(outputClassGenericTypeClassName)) {
            return RSocketMethodType.FIRE_AND_FORGET;
        } else if (isMonoClass(inputClassName) && isMonoClass(outputClassName) && !isVoidClass(outputClassGenericTypeClassName)) {
            return RSocketMethodType.REQUEST_RESPONSE;
        } else if (isMonoClass(inputClassName) && isFluxClass(outputClassName)) {
            return RSocketMethodType.REQUEST_STREAM;
        } else {
            throw new IllegalArgumentException("Unable to determine RSocket method type for the given input and output types.");
        }
    }

    public boolean isMonoClass(String className) {
        return className.startsWith(monoClassName);
    }

    public boolean isFluxClass(String className) {
        return className.startsWith(fluxClassName);
    }

    public boolean isVoidClass(String className) {
        return className.startsWith(voidClassName);
    }


    private AnnotationMirror getAnnotationFromMirrorList(
            List<? extends javax.lang.model.element.AnnotationMirror> list,
            Class<? extends Annotation> annotationClass
    ) {
        return list.stream()
                .filter(mirror -> mirror.getAnnotationType().toString().equals(annotationClass.getName()))
                .findFirst()
                .orElseThrow();
    }


    @SneakyThrows
    private String getMessageMappingRouteFromAnnotationMirror(
            @DestinationVariable AnnotationMirror mirror
    ) {
        return mirror.getElementValues().entrySet().stream()
                .filter(entry -> entry.getKey().toString().equals("value()"))
                .map(item -> item.getValue().getValue().toString())
                .findFirst()
                .orElseThrow();
    }

    @SneakyThrows
    private String getRSocketApiProviderClassNameFromAnnotationMirror(
            AnnotationMirror mirror
    ) {
        return mirror.getElementValues().entrySet().stream()
                .filter(entry -> entry.getKey().toString().equals("provider()"))
                .map(item -> item.getValue().getValue().toString())
                .findFirst()
                .orElseThrow();
    }

    @SneakyThrows
    private Boolean getRSocketApiGenerateAsComponentBooleanValueFromAnnotationMirror(
            AnnotationMirror mirror
    ) {
        return mirror.getElementValues().entrySet().stream()
                .filter(entry -> entry.getKey().toString().equals("generateAsComponent()"))
                .map(item -> item.getValue().getValue().toString())
                .findFirst()
                .map(Boolean::valueOf)
                .orElseThrow();
    }
    
    

    @SneakyThrows
    private void generateRSocketApiClient(TypeElement interfaceElement) {
        String packageName = processingEnv.getElementUtils().getPackageOf(interfaceElement).toString();
        String interfaceName = interfaceElement.getSimpleName().toString();

        String implementationClassSimpleName = interfaceName + "Client";
        String implementationClassName = packageName + "." + implementationClassSimpleName;

        JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(implementationClassName);

        //Core Class
        TypeSpec.Builder typeSpecBuilder = createRSocketApiClientTypeSpec(
                packageName,
                implementationClassSimpleName,
                interfaceName
        );


        //Annotations
        typeSpecBuilder
                .addAnnotation(createGeneratedAnnotation());


        
        AnnotationMirror rsocketApiAnnotationMirror = getAnnotationFromMirrorList(
                interfaceElement.getAnnotationMirrors(),
                RSocketApi.class
        );

        if (getRSocketApiGenerateAsComponentBooleanValueFromAnnotationMirror(rsocketApiAnnotationMirror)) {
            typeSpecBuilder.addAnnotation(
                    createSpringComponentAnnotation()
            );
        }

        //Fields and Constructors
        String apiClientConfigClassName = getRSocketApiProviderClassNameFromAnnotationMirror(rsocketApiAnnotationMirror);
        typeSpecBuilder
                .addField(createConfigField(apiClientConfigClassName))
                .addMethod(createConstructor(apiClientConfigClassName));

        //Methods
        for (Element enclosedElement : interfaceElement.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.METHOD) {
                ExecutableElement methodElement = (ExecutableElement) enclosedElement;

                // Method information
                String methodName = methodElement.getSimpleName().toString();
                String methodReturnType = methodElement.getReturnType().toString();
                String methodReturnTypeGenericType = getGenericTypeArguments(methodElement.getReturnType()).toString();

                // Data parameter information
                VariableElement dataParameter = getMethodDataParameter(methodElement);
                String methodDataParameterName = dataParameter.getSimpleName().toString();
                String methodDataParameterReturnType = dataParameter.asType().toString();
                String methodDataParameterReturnTypeGenericType = getGenericTypeArguments(dataParameter.asType()).toString();

                //Route information
                String messageMappingRoute = getMessageMappingRouteFromAnnotationMirror(
                        getAnnotationFromMirrorList(
                                methodElement.getAnnotationMirrors(),
                                MessageMapping.class
                        )
                );

                //Handle destination variables
                List<VariableElement> methodDestinationVariables = getMethodDestinationVariables(methodElement);

                //RSocket Method Type
                RSocketMethodType rsocketMethodType = defineRSocketMethodType(
                        methodDataParameterReturnType,
                        methodReturnType,
                        methodReturnTypeGenericType
                );

                //Generate Method Base
                MethodSpec.Builder methodBuilder = createMethodBase(
                        methodName,
                        methodReturnTypeGenericType,
                        methodDestinationVariables,
                        methodDataParameterName,
                        methodDataParameterReturnTypeGenericType,
                        rsocketMethodType
                );

                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Destination Variables Count: " + methodDestinationVariables.size());

                typeSpecBuilder.addMethod(
                        switch (rsocketMethodType) {
                            case REQUEST_RESPONSE ->
                                    generateDefaultCode(messageMappingRoute, methodBuilder, methodDestinationVariables, methodDataParameterName, methodReturnTypeGenericType,MethodReturnCoreType.MONO);
                            case FIRE_AND_FORGET ->
                                    generateFireAndForgetCode(messageMappingRoute, methodBuilder, methodDestinationVariables, methodDataParameterName);
                            case REQUEST_STREAM ->
                                    generateDefaultCode(messageMappingRoute, methodBuilder, methodDestinationVariables, methodDataParameterName, methodReturnTypeGenericType, MethodReturnCoreType.FLUX);
                            case REQUEST_CHANNEL ->
                                    generateDefaultCode(messageMappingRoute, methodBuilder, methodDestinationVariables, methodDataParameterName, methodReturnTypeGenericType, MethodReturnCoreType.FLUX);
                        }
                );

            }
        }


        try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {
            out.write(JavaFile.builder(packageName, typeSpecBuilder.build()).build().toString());
        }
    }

    private MethodSpec.Builder generateMethodDestinationVariables(MethodSpec.Builder builder, List<VariableElement> variableElements) {
        for (VariableElement variableElement : variableElements) {
            String elementName = variableElement.getSimpleName().toString();
            String elementType = variableElement.asType().toString();

            builder = builder
                    .addParameter(
                            getTypeClass(elementType),
                            elementName
                    );
        }

        return builder;
    }

    private VariableElement getMethodDataParameter(ExecutableElement methodElement) {
        return methodElement.getParameters().stream()
                .filter(parameter ->
                        isMonoClass(parameter.asType().toString()) ||
                        isFluxClass(parameter.asType().toString())
                )
                .findFirst()
                .orElseThrow();
    }

    private List<VariableElement> getMethodDestinationVariables(ExecutableElement methodElement) {
        return methodElement.getParameters().stream()
                .filter(parameter -> {
                            try {
                                AnnotationMirror annotation = getAnnotationFromMirrorList(
                                        parameter.getAnnotationMirrors(),
                                        DestinationVariable.class
                                );

                                return annotation != null;
                            } catch (NoSuchElementException ignored) {
                                return false;
                            }
                        }
                )
                .collect(Collectors.toList());
    }

    private enum MethodReturnCoreType { MONO, FLUX }

    private String generateRSocketRequesterRouteCall(
            String route,
            List<VariableElement> destinationVariables
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append(".route(")
                .append(route);

        for (VariableElement destinationVariable : destinationVariables) {
            sb.append(",").append(" ")
                    .append(destinationVariable.getSimpleName().toString());
        }

        sb.append(")");

        return sb.toString();

    }
    private MethodSpec generateDefaultCode(
            String route,
            MethodSpec.Builder builder,
            List<VariableElement> destinationVariables,
            String methodDataParameterName,
            String methodReturnTypeGenericType,
            MethodReturnCoreType methodReturnCoreType
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "return provider.getRSocketRequester()" + "\n"
        ).append(
                generateRSocketRequesterRouteCall(route, destinationVariables) + "\n"
        ).append(
                ".data(" + methodDataParameterName + ")" + "\n"
        );

        if (methodReturnCoreType == MethodReturnCoreType.MONO) {
            sb.append(
                    ".retrieveMono(" + methodReturnTypeGenericType + ".class)"
            );
        }

        if (methodReturnCoreType == MethodReturnCoreType.FLUX) {
            sb.append(
                    ".retrieveFlux(" + methodReturnTypeGenericType + ".class)"
            );
        }

        return builder.addStatement(
                CodeBlock.builder()
                        .add(
                                sb.toString()
                        )
                        .build()
        ).build();
    }

    private MethodSpec generateFireAndForgetCode(
            String route,
            MethodSpec.Builder builder,
            List<VariableElement> destinationVariables,
            String methodDataParameterName
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "return provider.getRSocketRequester()" + "\n"
        ).append(
                generateRSocketRequesterRouteCall(route, destinationVariables) + "\n"
        ).append(
                ".data(" + methodDataParameterName + ")" + "\n"
        ).append(
                ".send()"
        );
        return builder.addStatement(
                CodeBlock.builder()
                        .add(
                                sb.toString()
                        )
                        .build()
        ).build();
    }

    private MethodSpec.Builder createMethodBase(
            String methodName,
            String methodReturnTypeGenericType,
            List<VariableElement> methodDestinationVariables,
            String methodDataParameterName,
            String methodFirstParameterGenericType,
            RSocketMethodType type
    ) {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .setName(methodName)
                .returns(
                        switch (type) {
                            case REQUEST_RESPONSE, FIRE_AND_FORGET -> getParameterizedTypeClass(getTypeClass(monoClassName), methodReturnTypeGenericType);
                            case REQUEST_STREAM, REQUEST_CHANNEL ->getParameterizedTypeClass(getTypeClass(fluxClassName), methodReturnTypeGenericType);
                        }
                )
                .addAnnotation(
                        createOverrideAnnotation()
                );

        builder.addComment("RSOCKET_METHOD_TYPE::"+type.name());

        generateMethodDestinationVariables(builder, methodDestinationVariables);

        builder.addParameter(
                switch (type) {
                    case REQUEST_RESPONSE, FIRE_AND_FORGET, REQUEST_STREAM -> getParameterizedTypeClass(getTypeClass(monoClassName), methodFirstParameterGenericType);
                    case REQUEST_CHANNEL ->getParameterizedTypeClass(getTypeClass(fluxClassName), methodFirstParameterGenericType);
                }, methodDataParameterName
        );

        return builder;
    }

    private AnnotationSpec createOverrideAnnotation() {
        return AnnotationSpec.builder(Override.class)
                .build();
    }
    private AnnotationSpec createGeneratedAnnotation() {
        return AnnotationSpec.builder(Generated.class)
                .addMember("value", "$S", this.getClass().getName())
                .build();
    }

    private AnnotationSpec createSpringComponentAnnotation() {
        return AnnotationSpec.builder(Component.class)
                .build();
    }

    private ClassName getTypeClass(String javaClassName) {
        return ClassName.get(
                javaClassName.substring(0, javaClassName.lastIndexOf(".")),
                javaClassName.substring(javaClassName.lastIndexOf(".") + 1)
        );
    }

    private ParameterizedTypeName getParameterizedTypeClass(ClassName javaClass, String parameterizedJavaClass) {
        return ParameterizedTypeName.get(
                javaClass, getTypeClass(parameterizedJavaClass)
        );
    }

    private FieldSpec createConfigField(String apiConfigClassName) {
        return FieldSpec.builder(
                        getTypeClass(apiConfigClassName),
                        "provider",
                        Modifier.PRIVATE,
                        Modifier.FINAL
                )
                .build();
    }

    private MethodSpec createConstructor(String apiConfigClassName) {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(getTypeClass(apiConfigClassName), "provider")
                .addStatement("this.provider = provider")
                .build();
    }

    // Helper method to get generic type arguments
    private List<? extends TypeMirror> getGenericTypeArguments(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType.getTypeArguments();
        }
        return Collections.emptyList();
    }

    private static TypeSpec.Builder createRSocketApiClientTypeSpec(
            String packageName,
            String apiClientClassName,
            String apiInterfaceName
    ) {
        return TypeSpec.classBuilder(apiClientClassName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(ClassName.get(packageName, apiInterfaceName));
    }

}