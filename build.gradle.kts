plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 integra Kotlin: dichiariamo KGP solo per fissarne la versione.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
