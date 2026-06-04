import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    eclipse
    id("com.diffplug.spotless") version "7.0.4"
    id("com.google.protobuf") version "0.9.4"
    // Shadow 9.x ships ASM that reads Java 25 bytecode (major 69); 8.x fails here.
    id("com.gradleup.shadow") version "9.4.2"
    // Publishes the jar to CurseForge (supports Hytale; resolves game versions by name).
    id("com.hypherionmc.modutils.modpublisher") version "2.2.1"
}

val grpcVersion = "1.71.0"
val protobufVersion = "4.29.3"

version = "0.2.0" // x-release-please-version

// Timestamp embedded in the jar file name. CI passes -PbuildTime; locally it is computed (UTC).
val buildTime =
    (findProperty("buildTime") as String?)
        ?: DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())

spotless {
    java {
        target("src/**/*.java")
        licenseHeader(
            """
            /*
             * PrefabsUploader — sends a player's local prefabs to the Hytale server.
             * Copyright (C) 2026 ProjectAtlasia
             *
             * This program is free software: you can redistribute it and/or modify
             * it under the terms of the GNU General Public License as published by
             * the Free Software Foundation, version 3.
             *
             * This program is distributed in the hope that it will be useful,
             * but WITHOUT ANY WARRANTY; without even the implied warranty of
             * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
             * GNU General Public License for more details.
             *
             * You should have received a copy of the GNU General Public License
             * along with this program.  If not, see <https://www.gnu.org/licenses/>.
             *
             * SPDX-License-Identifier: GPL-3.0-only
             */

            """.trimIndent(),
        )
        googleJavaFormat("1.35.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

repositories {
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    // implementation (not runtimeOnly): NettyChannelBuilder is referenced at compile time.
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    compileOnly(fileTree("libs") { include("*.jar") })
}

sourceSets {
    main {
        proto { srcDir("proto") }
    }
}

// Default hub baked into the jar. Official/CurseForge builds inject the production hub via
// -PhubDefault=host:port -PhubTlsDefault=true; the committed source stays generic (localhost).
val hubDefault = (findProperty("hubDefault") as String?) ?: "localhost:50051"
val hubTlsDefault = (findProperty("hubTlsDefault") as String?)?.toBoolean() ?: false
// Build-time gate for disabling TLS certificate validation (hub.insecure). Dev builds default to
// allowing it for local testing; official/release builds pass -PhubAllowInsecure=false so the
// shipped jar always validates the hub certificate, regardless of config.properties.
val hubAllowInsecure = (findProperty("hubAllowInsecure") as String?)?.toBoolean() ?: true
// Gera um RECURSO (não uma classe) lido pelo PluginConfig em runtime. Assim o .java compila sozinho
// na IDE (sem depender de código gerado) e o build oficial ainda injeta o hub de produção.
val generatedResDir = layout.buildDirectory.dir("generated/resources/builddefaults")
val generateBuildResource by tasks.registering {
    inputs.property("hubDefault", hubDefault)
    inputs.property("hubTlsDefault", hubTlsDefault)
    inputs.property("hubAllowInsecure", hubAllowInsecure)
    inputs.property("version", version.toString())
    outputs.dir(generatedResDir)
    doLast {
        val f = generatedResDir.get().file("prefabsuploader-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "hub.default=$hubDefault\nhub.tls=$hubTlsDefault\n"
                + "hub.insecure.allowed=$hubAllowInsecure\nversion=$version\n")
    }
}
sourceSets.named("main") { resources.srcDir(generateBuildResource) }
tasks.named("processResources") { dependsOn(generateBuildResource) }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { create("grpc") }
        }
    }
}

tasks.named<JavaExec>("runServer") {
    minHeapSize = "1g"
    maxHeapSize = "4g"
    jvmArgs(
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=100",
        "-XX:+ParallelRefProcEnabled",
        "-XX:+AlwaysPreTouch",
    )
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Final artifact name: prefabs-hub-<version>-<buildTime>.jar
    archiveBaseName.set("prefabs-hub")
    archiveVersion.set("${project.version}-$buildTime")

    dependencies {
        exclude(dependency("com.hypixel.hytale:.*:.*"))
        exclude(dependency("dev.scaffoldit:.*:.*"))
        exclude(dependency("org.projectlombok:.*:.*"))
    }

    // Relocate protobuf/guava because the server already ships them and would conflict; io.grpc is absent there, so it is left as-is.
    relocate("com.google.protobuf", "dev.atlasia.prefabuploader.shaded.protobuf")
    relocate("com.google.common", "dev.atlasia.prefabuploader.shaded.guava")
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

// Publicação no CurseForge (Hytale). Debug por padrão (NÃO sobe nada); -PcfPublish faz o upload real.
// O token vem do env CURSE_TOKEN (secret no CI). modpublisher resolve "0.5" → ID e mira o endpoint Hytale.
publisher {
    apiKeys {
        curseforge(System.getenv("CURSE_TOKEN") ?: "")
    }
    gameType.set("hytale")
    debug.set(!project.hasProperty("cfPublish"))
    curseID.set("1563303")
    versionType.set("release")
    projectVersion.set("${project.version}-$buildTime")
    displayName.set("prefabs-hub v${project.version} ($buildTime)")
    // -PcfChangelog pode ser um caminho de arquivo (lido) ou texto literal.
    changelog.set(
        (findProperty("cfChangelog") as String?)?.let { if (file(it).exists()) file(it).readText() else it }
            ?: "Automated release — see https://github.com/ProjectAtlasia/prefabs-hub/releases",
    )
    setGameVersions("0.5")
    artifact.set("build/libs/prefabs-hub-${project.version}-$buildTime.jar")
}

tasks.named("publishCurseforge") { dependsOn("shadowJar") }
