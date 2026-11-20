version = 3

cloudstream {
    setRepo("https://github.com/guilhermehanti/lietrepo")
    description = "Filmes e séries do UltraCineTV via Abyss e Byse. Abyss reproduz localmente, sem casting."
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 3
    tvTypes = listOf("Movies", "Series")
    iconUrl = "https://www.ultracinetv.lat/favicon.ico"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

if (project.findProperty("ultraValidation") == "true") {
    val validationDir = layout.buildDirectory.dir("generated/android-validation")
    val prepareValidation by tasks.registering(Sync::class) {
        filteringCharset = "UTF-8"
        from("src/main/kotlin") {
            filter { line -> if (line.trim() == "registerMainAPI(api)") "$line\n        UltraAndroidValidation.start(context, api)" else line }
        }
        from(rootProject.file("tools/android-validation"))
        into(validationDir)
    }
    android { sourceSets["main"].java.setSrcDirs(listOf(validationDir)) }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
        sourceSets.getByName("main").kotlin.setSrcDirs(listOf(validationDir))
    }
    tasks.named("preBuild") { dependsOn(prepareValidation) }
    dependencies {
        compileOnly("androidx.media3:media3-exoplayer:1.6.1")
        compileOnly("androidx.media3:media3-datasource:1.6.1")
    }
}
