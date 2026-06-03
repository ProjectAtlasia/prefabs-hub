/**
 * Build for the PrefabsUploader plugin. Most of the Hytale wiring lives in `settings.gradle.kts`
 * (the `hytale { }` block handled by ScaffoldIt). This file adds: protobuf/gRPC codegen, the gRPC
 * client deps, and a SHADOW jar that relocates protobuf+guava to avoid clashing with the copies the
 * HytaleServer.jar already loads on the classpath.
 *
 * Classpath facts (verified against Server-0.5.2.jar):
 *   - io.grpc            → ABSENT  → we must bundle grpc-java.
 *   - com.google.protobuf→ PRESENT (757 cls) → relocate OURS to avoid a version clash.
 *   - io.netty           → PRESENT → do NOT reuse; use grpc-netty-shaded (already relocated).
 *   - com.google.common  → PRESENT (partial) → relocate OURS.
 */

plugins {
    eclipse
    id("com.diffplug.spotless") version "7.0.4"
    id("com.google.protobuf") version "0.9.4"
    // Shadow 9.x bundla ASM moderno (lê bytecode Java 25/major 69). Versões 8.x quebram aqui
    // porque o devtools do ScaffoldIt exige JVM 25+, então não dá pra baixar o target pra 21.
    id("com.gradleup.shadow") version "9.4.2"
}

val grpcVersion = "1.71.0"
val protobufVersion = "4.29.3"

spotless {
    java {
        target("src/**/*.java") // generated proto/grpc sources are under build/ and left untouched
        // Cabeçalho GPLv3 (SPDX) inserido/forçado em todo .java. spotlessApply adiciona; spotlessCheck cobra.
        licenseHeader(
            """
            /*
             * PrefabsUploader — envia prefabs locais do jogador para o servidor Hytale.
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
    // Any external repositories besides: MavenLocal, MavenCentral, HytaleMaven, and CurseMaven
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    // gRPC client (bundled into the shadow jar).
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    // implementation (não runtimeOnly): referenciamos NettyChannelBuilder em tempo de compilação
    // pra construir o canal por endereço direto (contorna o NameResolver no fat jar).
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    // Needed by grpc-generated stubs (javax.annotation.Generated) on JDK 9+.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // Drop any extra jars (e.g. an HTTP lib) into ./libs and they'll be on the compile classpath.
    compileOnly(fileTree("libs") { include("*.jar") })
}

// Contrato gRPC vendorado em ./proto (GPLv3, distribuído junto do plugin). generateProto compila
// os stubs Java a partir dele — o repo builda offline, sem depender de nenhum repo externo.
sourceSets {
    main {
        proto { srcDir("proto") }
    }
}

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
    archiveClassifier.set("") // o fat jar É o artefato de deploy (manifest.json entra via main output)

    // NÃO empacotar o que o servidor já provê em runtime (Server traz netty/protobuf/guava/bson
    // shadeados dentro dele; bundlá-lo gera jar de 118MB e quebra o ASM do shadow com major 69).
    dependencies {
        exclude(dependency("com.hypixel.hytale:.*:.*"))
        exclude(dependency("dev.scaffoldit:.*:.*"))
        exclude(dependency("org.projectlombok:.*:.*"))
    }

    // io.grpc NÃO é relocado (ausente no server). Só protobuf/guava (presentes → conflitariam).
    relocate("com.google.protobuf", "dev.atlasia.prefabuploader.shaded.protobuf")
    relocate("com.google.common", "dev.atlasia.prefabuploader.shaded.guava")
    mergeServiceFiles() // preserva os META-INF/services (provider do grpc-netty-shaded)
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
