package com.github.mkulak.kortx.generate

import com.squareup.kotlinpoet.*
import io.swagger.models.*
import io.swagger.models.parameters.*
import io.swagger.models.properties.*
import io.swagger.parser.SwaggerParser

fun main(args: Array<String>) {
    SwaggerGenerator().generate("com.example", "PetStore", "https://raw.githubusercontent.com/OAI/OpenAPI-Specification/master/examples/v2.0/yaml/petstore.yaml")
}

class SwaggerGenerator {
    val generatedClasses = mutableSetOf<String>()

    fun generate(packageName: String, serviceName: String, url: String) {
        val builder = KotlinFile.builder(packageName, "domain")
        val parser = SwaggerParser()
        val swagger = parser.read(url)!!
        generateModel(swagger, builder)
        generateClientInterface(serviceName, swagger, builder)
        generateClientImpl(serviceName, swagger, builder)
        builder.build().writeTo(System.out)
        generateServer(serviceName, swagger)
        generatedClasses.clear()
    }

    private fun generateModel(swagger: Swagger, builder: KotlinFile.Builder) {
        swagger.definitions.orEmpty().forEach { (modelName, model) -> generateClass(swagger, builder, modelName, model) }
    }

    private fun generateClass(swagger: Swagger, builder: KotlinFile.Builder, modelName: String, prop: Property) {
        when (prop) {
            is RefProperty -> prop.`$ref`.extractRef().let { generateClass(swagger, builder, it, swagger.definitions.orEmpty()[it]!!) }
            is ObjectProperty -> generateClass(swagger, builder, modelName, prop.properties)
            else -> Unit
        }
    }

    private fun generateClass(swagger: Swagger, builder: KotlinFile.Builder, modelName: String, model: Model) {
        when {
            model.properties.orEmpty().isNotEmpty() -> generateClass(swagger, builder, modelName, model.properties)
            model is ArrayModel -> generateClass(swagger, builder, "${modelName}Elem", model.items)
            model is ModelImpl && modelName !in generatedClasses -> {
                val enum = model.vendorExtensions["x-extensible-enum"]
                if (enum is List<*>) {
                    val enumClass = TypeSpec.enumBuilder(modelName)
                    enum.forEach { enumClass.addEnumConstant(it.toString()) }
                    builder.addType(enumClass.build())
                    generatedClasses.add(modelName)
                }
            }
            else -> Unit
        }
    }

    private fun generateClass(swagger: Swagger, builder: KotlinFile.Builder, modelName: String, props: Map<String, Property>) {
        if (modelName !in generatedClasses) {
            val clazz = TypeSpec.classBuilder(modelName.toCamelCase()).addModifiers(KModifier.DATA)
            val ctor = FunSpec.constructorBuilder()
            props.forEach { (propName, prop) ->
                clazz.addField(ctor, propName.toCamelCase(false), swagger.resolveType(prop))
            }
            clazz.primaryConstructor(ctor.build())
            builder.addType(clazz.build())
            generatedClasses.add(modelName)
        }
    }

    private fun generateClientInterface(serviceName: String, swagger: Swagger, builder: KotlinFile.Builder) {
        val interfaceName = "${serviceName}Client"
        val clientInterface = TypeSpec.interfaceBuilder(interfaceName)
        swagger.paths.orEmpty().forEach { (pathStr, path) ->
            path.operationMap.orEmpty().forEach { (method, op) ->
                clientInterface.addFun(swagger.createFun(pathStr, method, op, false).first.build())
            }
        }
        builder.addType(clientInterface.build())
    }

    private fun generateClientImpl(serviceName: String, swagger: Swagger, builder: KotlinFile.Builder) {
        val clientClass = TypeSpec.classBuilder("${serviceName}ClientImpl")
        clientClass.addSuperinterface(ClassName("", "${serviceName}Client"))
        val clientClassCtor = FunSpec.constructorBuilder()
        clientClass.addField(clientClassCtor, "config", ClassName("", "${serviceName}Config"))
                .addField(clientClassCtor, "http", ClassName("", "HttpClient"))
                .primaryConstructor(clientClassCtor.build())
        swagger.paths.orEmpty().forEach { (pathStr, path) ->
            path.operationMap.orEmpty().forEach { (method, op) ->
                val (funBuilder, successType) = swagger.createFun(pathStr, method, op, true)
                val bodyParam = op.parameters.filterIsInstance(BodyParameter::class.java).firstOrNull()
                val queryParams = op.parameters.filterIsInstance(QueryParameter::class.java)
                val pathParams = op.parameters.filterIsInstance(PathParameter::class.java)
                val pathFixed = pathParams.fold(pathStr) { acc, param ->
                    acc.replace("{${param.name}}", "\${${param.name.toCamelCase(false)}}")
                }
                val req = """val req = HttpRequest(config.host.withPath("$pathFixed"), HttpMethod.$method)""" + "\n"
                val params = if (queryParams.isNotEmpty()) {
                    val queryMap = queryParams.map {
                        val suffix = if (it.type == "string") "" else ".toString()"
                        """"${it.name}" to ${it.name.toCamelCase(false)}$suffix"""
                    }.joinToString()
                    ".addParams(mapOf($queryMap))\n"
                } else ""
                val headerParams = op.parameters.filterIsInstance(HeaderParameter::class.java).filter { it.name != "Authorization" }
                val headers = if (headerParams.isNotEmpty()) {
                    val headersMap = headerParams.map { """"${it.name}" to ${it.name.toCamelCase(false)}""" }.joinToString()
                    ".addHeaders(mapOf($headersMap))\n"
                } else ""
                val oauthScopes = op.security.orEmpty().flatMap { it["oauth2"].orEmpty() }
                val oauth = if (oauthScopes.isNotEmpty()) ".withOAuth2(token.value) // ${oauthScopes.joinToString(", ")}\n" else ""
                val body = if (bodyParam != null) ".withBody(Json(${bodyParam.name.toCamelCase(false)}))\n" else ""
                val timeout = ".withTimeout(config.timeout)\n"
                val ret = if (successType.toString() == "Unit") "return http.expect(req).void()" else "return http.json<${successType}>(req)"
                val msg = """.withErrorMessage("Failed $method to $pathStr")"""
                funBuilder.addCode(CodeBlock.of((req + params + headers + oauth + body + timeout + ret + msg).formatCode()))
                clientClass.addFun(funBuilder.build())
            }
        }
        builder.addType(clientClass.build())
    }

    fun generateServer(serviceName: String, swagger: Swagger) {
        val classDecl = "class ${serviceName}Server(oauth: OAuthProtector) : HttpApi({\n"
        val routes = swagger.paths.orEmpty().flatMap { (pathStr, path) ->
            path.operationMap.orEmpty().map { (method, op) -> Triple(pathStr, method, op) }
        }.fold("") { acc, (pathStr, method, op) ->
            val pathFixed = pathStr.replace("\\{([^}]+)\\}".toRegex(), ":$1")
            val oauthScopes = op.security.orEmpty().flatMap { it["oauth2"].orEmpty() }
            val oauth = if (oauthScopes.isNotEmpty()) ", oauth.protect(${oauthScopes.map { "\"$it\"" }.joinToString(", ")}" else ""
            val route = "${method.name.toLowerCase()}(\"$pathFixed\"$oauth) { ctx ->\n"
            val params = op.parameters.filter { it.`in` in setOf("query", "path") }
            val headers = op.parameters.filter { it.`in` == "header" && it.name != "Authorization" }
            val bodyParam = op.parameters.filterIsInstance(BodyParameter::class.java).firstOrNull()
            val req = "        val req = ctx.request()\n"
            val paramDecls = params.fold("") { acc, param ->
                val type = if (param is QueryParameter && param.type == "integer") ".toInt()" else ""
                acc + "        val ${param.name.toCamelCase(false)} = req.getParam(\"${param.name}\")$type\n"
            }
            val headerDecls = headers.fold("") { acc, param ->
                acc + "        val ${param.name.toCamelCase(false)} = req.getHeader(\"${param.name}\")\n"
            }
            val body = if (bodyParam != null) "        req.bodyHandler { buffer ->\n        }\n" else ""
            val response = " \n       ctx.response().endWithJson(\"ok\")\n"
            val suffix = "    }\n"
            acc + route + req + paramDecls + headerDecls + body + response + suffix
        }
        val suffix = "})\n"
        val code = "\n" + classDecl + routes + suffix
        println(code)
    }

    private fun Swagger.createFun(pathStr: String, method: HttpMethod, op: Operation, override: Boolean): Pair<FunSpec.Builder, TypeName> {
        val response = op.responses["200"] ?: op.responses["201"] ?: op.responses["202"] ?: op.responses["203"] ?: op.responses["204"]
        val successType = resolveType(response?.schema, false)
        val funSpec = FunSpec.builder(getFunName(pathStr, method, op))
                .addModifiers(KModifier.PUBLIC, if (override) KModifier.OVERRIDE else KModifier.ABSTRACT)
                .returns(ParameterizedTypeName.get(ClassName("", "Future"), successType))
        op.parameters.orEmpty().forEach {
            val (name, type) = when {
                it.name == "Authorization" -> "token" to ClassName("", "Token")
                it is RefParameter -> it.name to ClassName("", it.`$ref`.extractRef().toCamelCase())
                it is QueryParameter -> it.name to ClassName("", if (it.type == "integer") "Int" else "String")
                else -> it.name to ClassName("", "String")
            }
            funSpec.addParameter(name.toCamelCase(false), type)
        }
        return funSpec to successType
    }

    private fun TypeSpec.Builder.addField(ctor: FunSpec.Builder, name: String, type: TypeName): TypeSpec.Builder {
        ctor.addParameter(name, type)
        return addProperty(PropertySpec.builder(name, type).initializer(name).build())
    }

    private fun Swagger.resolveType(prop: Property?, nullable: Boolean = !(prop?.required ?: true)): TypeName {
        val type = when (prop?.type) {
            "ref" -> {
                val name = (prop as RefProperty).`$ref`.extractRef().toCamelCase()
                val model = definitions.orEmpty()[name]
                if (model is ArrayModel) ParameterizedTypeName.get(ClassName("", "List"), resolveType(model.items, false))
                else ClassName("", name)
            }
            "array" -> {
                val items = (prop as ArrayProperty).items
                ParameterizedTypeName.get(ClassName("", "List"), resolveType(items, false))
            }
            "object" -> {
                ClassName("", "Any")
            }
            "number" -> ClassName("", "Int")
            "integer" -> ClassName("", if (prop.format == "int64") "Long" else "Int")
            "string" -> ClassName("", "String")
            "boolean" -> ClassName("", "Boolean")
            null -> ClassName("", "Unit")
            else -> {
                println("Unsupported type ${prop.type}")
                ClassName("", "Any")
            }
        }
        return if (nullable) type.asNullable() else type
    }

    fun getFunName(path: String, method: HttpMethod, op: Operation): String {
        val name = if (op.operationId != null) op.operationId else method.name.toLowerCase() + path.replace("\\{[^}]+\\}".toRegex(), "")
        return name.toCamelCase(false)
    }

    private fun String.extractRef() = replace("#/definitions/", "")

    private fun String.formatCode() = trim().split("\n").map { it.trim() }.joinToString("\n")

    private fun String.toCamelCase(capitalize: Boolean = true): String {
        val s = split("[/\\-_]".toRegex()).map { it.capitalize() }.joinToString("")
        return if (capitalize) s.capitalize() else s.decapitalize()
    }
}