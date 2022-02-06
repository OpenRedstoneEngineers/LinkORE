rootProject.name = "linkore"
include(":api", ":plugin", ":web")

includeBuild("../kOREUtils")
includeBuild("../Schemati")

enableFeaturePreview("VERSION_CATALOGS")
dependencyResolutionManagement {
    repositories {
        mavenLocal()
    }

    versionCatalogs {
        create("deps") {
            from("org.openredstone.koreutils:versions:master-SNAPSHOT")
        }
    }
}
