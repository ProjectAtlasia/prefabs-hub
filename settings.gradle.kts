import dev.scaffoldit.hytale.wire.HytaleManifest

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.firstdark.dev/releases") }
    }
}

rootProject.name = "dev.atlasia.prefabuploader"

plugins {
    id("dev.scaffoldit") version "0.2.+"
}

// Version comes from the release tag: CI passes -PreleaseVersion=<semantic-release version>; local
// builds default to a dev placeholder. Nothing version-related is committed, so develop never drifts.
val pluginVersion =
    providers.gradleProperty("releaseVersion").orNull?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

hytale {
    usePatchline("release")
    useVersion("0.5.2")

    repositories {
    }

    dependencies {
    }

    manifest {
        Group = "ProjectAtlasia"
        Name = "PrefabsUploader"
        Main = "dev.atlasia.prefabuploader.PrefabsUploaderPlugin"
        ServerVersion = ">=0.5.0 <0.6.0"
        Version = pluginVersion
        Description = "Envio de prefabs locais pro servidor via integração com Discord (gRPC)."
        Authors = listOf(HytaleManifest.Author("astahjmo (Astaroth)", "contato@johnatan.dev", ""))
        IncludesAssetPack = true
    }
}
