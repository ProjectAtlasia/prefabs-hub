import dev.scaffoldit.hytale.wire.HytaleManifest

rootProject.name = "dev.atlasia.prefabuploader"

plugins {
    // ScaffoldIt — resolves the Hytale SDK and regenerates manifest.json from the hytale { } block.
    // Docs: https://scaffoldit.dev
    id("dev.scaffoldit") version "0.2.+"
}

hytale {
    // Keep these in sync with the main Atlasia plugin so both build against the same SDK.
    usePatchline("release")
    useVersion("0.5.2")

    repositories {
        // Any external repositories besides: MavenLocal, MavenCentral, HytaleMaven, and CurseMaven
    }

    dependencies {
        // Any external dependency you also want to include
    }

    manifest {
        // NOTE: Name must have no spaces/hyphens — the engine derives permission nodes from it
        // (e.g. projectatlasia.prefabsuploader.command.<cmd>).
        Group = "ProjectAtlasia"
        Name = "PrefabsUploader"
        Main = "dev.atlasia.prefabuploader.PrefabsUploaderPlugin"
        Version = "0.1.0"
        Description = "Envio de prefabs locais pro servidor via integração com Discord (gRPC)."
        Authors = listOf(HytaleManifest.Author("astahjmo (Astaroth)", "contato@johnatan.dev", ""))
        // OBRIGATÓRIO: faz o AssetModule registrar os assets do jar (Common/UI/Custom/Pages/*.ui)
        // como pack e entregá-los ao cliente. Sem isso o cliente erra "Could not find document".
        IncludesAssetPack = true
    }
}
