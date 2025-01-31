package ru.tinkoff.kora.database.annotation.processor.r2dbc.extension;

import com.squareup.javapoet.*;
import ru.tinkoff.kora.annotation.processor.common.CommonUtils;
import ru.tinkoff.kora.common.annotation.Generated;
import ru.tinkoff.kora.database.annotation.processor.DbEntityReadHelper;
import ru.tinkoff.kora.database.annotation.processor.entity.DbEntity;
import ru.tinkoff.kora.database.annotation.processor.r2dbc.R2dbcNativeTypes;
import ru.tinkoff.kora.database.annotation.processor.r2dbc.R2dbcTypes;
import ru.tinkoff.kora.kora.app.annotation.processor.extension.ExtensionResult;
import ru.tinkoff.kora.kora.app.annotation.processor.extension.KoraExtension;

import javax.annotation.Nullable;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

//R2dbcRowMapper<T>

public class R2dbcTypesExtension implements KoraExtension {

    private final Types types;
    private final Elements elements;
    private final Filer filer;
    private final DbEntityReadHelper entityHelper;

    public R2dbcTypesExtension(ProcessingEnvironment env) {
        this.types = env.getTypeUtils();
        this.elements = env.getElementUtils();
        this.filer = env.getFiler();
        this.entityHelper = new DbEntityReadHelper(
            R2dbcTypes.RESULT_COLUMN_MAPPER,
            this.types,
            fd -> CodeBlock.of("this.$L.apply(_row, $S)", fd.mapperFieldName(), fd.columnName()),
            fd -> {
                var nativeType = R2dbcNativeTypes.findAndBox(TypeName.get(fd.type()));
                if (nativeType != null) {
                    return CodeBlock.of("_row.get($S, $T.class)", fd.columnName(), nativeType);
                }
                return null;
            },
            fd -> {
                if (fd.type().getKind().isPrimitive()) {
                    return CodeBlock.of("");
                }
                return CodeBlock.of("""
                    if ($L == null) {
                      throw new $T($S);
                    }
                    """, fd.fieldName(), NullPointerException.class, "Result field %s is not nullable but row has null".formatted(fd.fieldName()));
            }
        );
    }

    @Nullable
    @Override
    public KoraExtensionDependencyGenerator getDependencyGenerator(RoundEnvironment roundEnvironment, TypeMirror typeMirror) {
        if (!(typeMirror instanceof DeclaredType dt)) {
            return null;
        }
        if (typeMirror.toString().startsWith("ru.tinkoff.kora.database.r2dbc.mapper.result.R2dbcRowMapper<")) {
            return this.generateResultRowMapper(roundEnvironment, dt);
        }
        return null;
    }

    @Nullable
    private KoraExtensionDependencyGenerator generateResultRowMapper(RoundEnvironment roundEnvironment, DeclaredType typeMirror) {
        var rowType = typeMirror.getTypeArguments().get(0);
        var entity = DbEntity.parseEntity(this.types, rowType);
        if (entity == null) {
            return null;
        }

        var mapperName = CommonUtils.getOuterClassesAsPrefix(entity.typeElement()) + entity.typeElement().getSimpleName() + "R2dbcRowMapper";
        var packageElement = this.elements.getPackageOf(entity.typeElement());

        return () -> {
            var maybeGenerated = this.elements.getTypeElement(packageElement.getQualifiedName() + "." + mapperName);
            if (maybeGenerated != null) {
                var constructors = CommonUtils.findConstructors(maybeGenerated, m -> m.contains(Modifier.PUBLIC));
                if (constructors.size() != 1) {
                    throw new IllegalStateException();
                }
                return ExtensionResult.fromExecutable(constructors.get(0));
            }

            var type = TypeSpec.classBuilder(mapperName)
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "$S", R2dbcTypesExtension.class.getCanonicalName()).build())
                .addSuperinterface(ParameterizedTypeName.get(R2dbcTypes.ROW_MAPPER, TypeName.get(entity.typeMirror())))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
            var constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

            var apply = MethodSpec.methodBuilder("apply")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addParameter(R2dbcTypes.ROW, "_row")
                .returns(TypeName.get(entity.typeMirror()));

            var read = this.entityHelper.readEntity("_result", entity);
            read.enrich(type, constructor);
            apply.addCode(read.block());
            apply.addCode("return _result;\n");

            type.addMethod(constructor.build());
            type.addMethod(apply.build());
            JavaFile.builder(packageElement.getQualifiedName().toString(), type.build()).build().writeTo(this.filer);
            return ExtensionResult.nextRound();
        };
    }
}
