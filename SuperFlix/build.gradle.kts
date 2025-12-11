version = 1

cloudstream {
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lietbr")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://superflix21.lol/assets/logo.png"
}

android {
    defaultConfig {
        buildConfigField "String",
"TMDB_API_KEY",
"\"${System.getenv("TMDB_API_KEY")}\""
    }
}