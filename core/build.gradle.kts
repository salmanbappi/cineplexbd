plugins {
    id("lib-android")
}

android {
    namespace = "extensions.core"

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.setSrcDirs(listOf("src/main/res"))
        }
    }
}

dependencies {
    val libs = versionCatalogs.named("libs")
    compileOnly(libs.findBundle("provided").get())
    compileOnly(libs.findBundle("common").get())
}
