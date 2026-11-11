import java.time.*

plugins {
    `maven-publish`
    id("net.neoforged.moddev") version "2.0.141"
    id("net.kyori.blossom") version "2.2.0"
    kotlin("jvm") version "2.1.21"
}

version = "1.0.0+1.21.1"
group = "com.lightning.northstar"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

val generatedResources = file("src/generated")

sourceSets {
    main {
        resources.srcDir(generatedResources)

        blossom.javaSources {
            property("version", version.toString())
        }
    }

    val main by getting

    val tfmgCe = create("tfmg-ce") {
        compileClasspath += (main.output + main.compileClasspath).filter { "tfmg" !in it.name }
    }

    tasks.named<Jar>("jar") {
        from(tfmgCe.output)
    }

    tasks.named<Jar>("sourcesJar") {
        from(tfmgCe.allSource)
    }

    tasks.named<Javadoc>("javadoc") {
        source(tfmgCe.allJava)
        classpath += tfmgCe.compileClasspath + tfmgCe.output
    }
}

neoForge {
    version = "21.1.230"

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    validateAccessTransformers = true
    interfaceInjectionData.from("interfaces.json")

    runs {
        configureEach {
            systemProperty("geckolib.disable_examples", "true")
            systemProperty("mixin.debug.export", "true")
            //systemProperty("forge.logging.markers", "REGISTRIES,REGISTRYDUMP")
            //systemProperty("forge.logging.console.level", "debug")
        }

        create("client") {
            client()
            gameDirectory = file("run")
            jvmArgument("-Xmx4G")
        }
        create("data") {
            data()
            gameDirectory = file("run")
            jvmArgument("-Xmx4G")
            programArguments.addAll(
                "--all",
                "--mod", "northstar",
                "--output", generatedResources.absolutePath,
                "--existing", file("src/main/resources").absolutePath
            )
        }
        create("server") {
            server()
            gameDirectory = file("run-server")
            jvmArgument("-Xmx4G")
        }
    }

    mods {
        create("northstar") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets["tfmg-ce"])
        }
    }
}

repositories {
    mavenCentral()
    maven("https://modmaven.dev/")
    maven("https://maven.tterrag.com/")
    maven("https://maven.createmod.net")
    maven("https://maven.architectury.dev/")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/") // Ponder
    maven("https://maven.blamejared.com/") // JEI
    maven("https://maven.ithundxr.dev/snapshots") // Registrate
    maven("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/") { // GeckoLib
        content {
            includeGroupByRegex("software\\.bernie.*")
            includeGroup("com.eliotlash.mclib")
        }
    }
    maven("https://maven.ftb.dev/releases")
    maven("https://cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
    maven("https://maven.parchmentmc.org/") {
        content {
            includeGroupByRegex("org\\.parchmentmc.*")
        }
    }
    maven("https://maven.latvian.dev/releases") {
        content {
            includeGroupByRegex("dev\\.latvian\\..*")
        }
    }
    maven("https://maven.theillusivec4.top/") { // Curios (transitive dep of Aeronautics/Simulated/Offroad)
        content {
            includeGroup("top.theillusivec4.curios")
        }
    }
    maven("https://maven.squiddev.cc/") { // CC:Tweaked (transitive dep of Aeronautics/Simulated/Offroad)
        content {
            includeGroup("cc.tweaked")
            includeModule("org.squiddev", "Cobalt")
        }
    }
    maven("https://maven.ryanhcode.dev/releases") {
        content {
            includeGroup("dev.ryanhcode.sable")
            includeGroup("dev.ryanhcode.sable-companion")
            includeGroup("dev.eriksonn.aeronautics")
            includeGroup("dev.simulated_team.simulated")
            includeGroup("dev.ryanhcode.offroad")
        }
    }
    flatDir { dir("run/mods-obf-1.21.1") }
}

dependencies {
    //annotationProcessor(variantOf(libs.mixin) { classifier("processor") })
    compileOnly(libs.mixin)
    //annotationProcessor(libs.mixinextras.common)
    implementation(libs.mixinextras.common)
    implementation(libs.mixinextras.neoforge)
    jarJar(libs.mixinextras.neoforge)

    implementation(variantOf(libs.create) { classifier("slim") }) { isTransitive = false }
    implementation(libs.ponder.neoforge)
    implementation(libs.registrate)
    compileOnly(libs.flywheel.neoforge.api)
    runtimeOnly(libs.flywheel.neoforge)

    implementation(libs.geckolib.neoforge)

    compileOnly(libs.iris)

    implementation(libs.jei.neoforge)
    implementation(libs.copycats)
    implementation(libs.cdg)
    implementation(libs.cca)
    implementation(libs.kubejs) { isTransitive = false }
    implementation(libs.kubejs.create)
    implementation(libs.rhino)
    implementation(libs.sable) { exclude(group = "com.simibubi.create") }
    implementation(libs.sable.companion)
    implementation(libs.tfmg)

    // Exclude create: this project uses the slim jar (without jarjar-embedded Registrate/Flywheel/Ponder)
    // directly, and the transitive default jar would result in a duplicate create mod on the classpath
    implementation(libs.aeronautics) { exclude(group = "com.simibubi.create") }
    implementation(libs.offroad) { exclude(group = "com.simibubi.create") }
    implementation(libs.simulated) { exclude(group = "com.simibubi.create") }

    //Dimensional sable is fantastic!
    //https://modrinth.com/mod/dimensional-sable/
    //NeoForge 1.1.0 build of project Iq9c4mLa (version id qU6WVBqC; WHNBfjyl is the Fabric build)
    jarJar(implementation("maven.modrinth:Iq9c4mLa:qU6WVBqC")!!)


    "tfmgCeImplementation"(libs.tfmg.ce)

    // Create a folder name "mods-obf" inside "run" and put extra mods needed for testing here
    file("run/mods-obf-1.21.1").listFiles()?.forEach { runtimeOnly("local:${it.nameWithoutExtension}") }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Specification-Title" to "northstar",
                "Specification-Vendor" to "Redstonneur1256",
                "Specification-Version" to version,
                "Implementation-Title" to project.name,
                "Implementation-Version" to version,
                "Implementation-Vendor" to "Redstonneur1256",
                "Implementation-Timestamp" to Instant.now().toString(),
                "MixinConfigs" to "northstar.mixins.json"
            )
        )
    }
}

tasks.processResources {
    val buildProps = project.properties.toMutableMap()
    buildProps["file"] = mapOf("jarVersion" to project.version)
    filesMatching(listOf("META-INF/neoforge.mods.toml")) {
        expand(buildProps)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            repositories {
                maven {
                    name = "SkyPlex"
                    credentials(PasswordCredentials::class.java)
                    url = uri("https://repo.mc-skyplex.net/releases/")
                }
            }
        }
    }
}
