import java.util.Properties

plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.CineAgora"
    compileSdk = 33

    defaultConfig {
        minSdk = 21

        val tmdbApiKey = project.findProperty("TMDB_API_KEY") as? String
            ?: System.getenv("TMDB_API_KEY")
            ?: getLocalProperty("TMDB_API_KEY")
            ?: "dummy_api_key"

        val tmdbAccessToken = project.findProperty("TMDB_ACCESS_TOKEN") as? String
            ?: System.getenv("TMDB_ACCESS_TOKEN")
            ?: getLocalProperty("TMDB_ACCESS_TOKEN")
            ?: "dummy_access_token"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
    }
}

fun getLocalProperty(key: String): String? {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    return if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        localProperties.getProperty(key)
    } else null
}

cloudstream {
    version = 3
    description = "Assista filmes e séries online grátis em HD no CineAgora"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://cineagora.club/templates/cineagora/images/touch-icon-180x180.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}
