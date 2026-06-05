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
        Version = "0.2.2" // x-release-please-version
        Description = "Envio de prefabs locais pro servidor via integração com Discord (gRPC)."
        Authors = listOf(HytaleManifest.Author("astahjmo (Astaroth)", "contato@johnatan.dev", ""))
        IncludesAssetPack = true
    }
}
