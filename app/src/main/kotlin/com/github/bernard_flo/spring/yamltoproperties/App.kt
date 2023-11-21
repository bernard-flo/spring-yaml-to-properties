package com.github.bernard_flo.spring.yamltoproperties

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.mainBody
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*


typealias Profile = String?

typealias Properties = Map<String, Any?>

data class ProfiledProperties(val profile: Profile, val properties: Properties)


fun main(
    args: Array<String>,
) {

    mainBody {
        ArgParser(args).parseInto(::Args).run {

            val profiledPropertiesList =
                readBootstrapYaml(inputDir) +
                        readDefaultYaml(inputDir) +
                        readProfiledYamls(inputDir)
            val mergedProfiledPropertiesMap = mergeProperties(profiledPropertiesList)
            val envApplied = envDir?.let { applyEnv(it, mergedProfiledPropertiesMap) } ?: mergedProfiledPropertiesMap
            writePropertiesMap(outputDir, envApplied)
        }
    }
}


fun readBootstrapYaml(
    inputDir: String,
): List<ProfiledProperties> {

    val inputPath = Paths.get(inputDir, "bootstrap.yml")
    return if (inputPath.exists()) {
        readMultiDocsYaml(inputPath)
    } else {
        listOf()
    }
}

fun readDefaultYaml(
    inputDir: String,
): List<ProfiledProperties> {

    val inputPath = Paths.get(inputDir, "application.yml")
    return readMultiDocsYaml(inputPath)
}

fun readMultiDocsYaml(
    inputPath: Path,
): List<ProfiledProperties> {

    return Yaml().loadAll(inputPath.inputStream())
        .map { documentObject ->
            val properties = documentToProperties(documentObject)
            val profile = properties["spring.profiles"]?.toString()
            ProfiledProperties(profile, properties)
        }
}

fun readProfiledYamls(
    inputDir: String,
): List<ProfiledProperties> {

    return Path(inputDir).listDirectoryEntries("application-*.yml")
        .map { readProfiledYaml(it) }
}

fun readProfiledYaml(
    inputPath: Path,
): ProfiledProperties {

    val profile = profiledYamlFileNameRegex.matchEntire(inputPath.fileName.toString())!!.groupValues[1]

    val document = Yaml().load(inputPath.inputStream()) as Any
    val properties = documentToProperties(document)
    return ProfiledProperties(profile, properties)
}

private val profiledYamlFileNameRegex = Regex("""application-(\w+)\.yml""")

fun writePropertiesMap(
    outputDir: String,
    profiledPropertiesMap: Map<Profile, ProfiledProperties>,
) {

    File(outputDir).mkdirs()

    profiledPropertiesMap.forEach { (profile, profiledProperties) ->
        val outputPath = Paths.get(outputDir, "application-$profile.properties")
        val lines = profiledProperties.properties.toList()
            .sortedBy { it.first }
            .map { (key, value) -> "$key=$value" }
        outputPath.writeLines(lines)
    }
}


fun documentToProperties(
    documentObject: Any,
): Properties {

    val documentMap = documentObject as Map<*, *>
    return documentEntriesToProperties(documentMap.entries).toMap()
}

fun documentEntriesToProperties(
    documentEntries: Set<Map.Entry<*, *>>,
    parentKey: String? = null,
): List<Pair<String, Any?>> {

    return documentEntries.flatMap { (key, value) ->
        val currentKey = if (parentKey == null) key.toString() else "$parentKey.$key"
        if (value is Map<*, *>) {
            documentEntriesToProperties(value.entries, currentKey)
        } else {
            listOf(currentKey to value)
        }
    }
}


fun mergeProperties(
    profiledPropertiesList: List<ProfiledProperties>,
): Map<Profile, ProfiledProperties> {

    val profiledPropertiesMap = profiledPropertiesList
        .groupBy { it.profile }
        .mapValues {
            it.value.reduce { (profile, mergedProperties), (_, properties) ->
                ProfiledProperties(profile, mergedProperties + properties)
            }
        }


    val defaultProperties = profiledPropertiesMap[null]?.properties ?: emptyMap()

    return profiledPropertiesMap
        .filterKeys { it != null }
        .mapValues { (_, profiledProperties) ->
            ProfiledProperties(profiledProperties.profile, defaultProperties + profiledProperties.properties)
        }
}


fun applyEnv(
    envDir: String,
    profiledPropertiesMap: Map<Profile, ProfiledProperties>,
): Map<Profile, ProfiledProperties> {

    val profileEnvMap = Path(envDir).listDirectoryEntries("*.json")
        .map { envPath ->
            val profile = envPath.fileName.toString().removeSuffix(".json")
            val envData = Json.decodeFromString(envPath.readText()) as Map<String, String>
            profile to envData
        }
        .toMap()

    return profiledPropertiesMap
        .mapValues { (profile, profiledProperties) ->
            ProfiledProperties(
                profile,
                applyEnv(
                    profiledProperties.properties,
                    profileEnvMap[profile]!!,
                ),
            )
        }
}

fun applyEnv(
    properties: Properties,
    env: Map<String, String>,
): Properties {

    return properties.entries
        .map { (key, value) ->
            key to applyEnv(value, env)
        }
        .toMap()
}

fun applyEnv(
    value: Any?,
    env: Map<String, String>,
): Any? {

    if (value !is String) {
        return value
    }

    val matched = propertyPlaceholder.matchEntire(value) ?: return value
    val envKey = matched.groupValues[1]
    val envValue = env[envKey] ?: return value

    return value.replace(matched.groupValues[0], envValue)
}

private val propertyPlaceholder = Regex("""\$\{([\w-.]+)}""")
