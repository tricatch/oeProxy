rootProject.name = "oe-proxy"

// gotpache-keytool is developed in the sibling ../gotpache-keytool checkout. When it is present
// Gradle builds it and substitutes it for io.github.tricatch:gotpache-keytool; otherwise the
// published artifact is used.
if (file("../gotpache-keytool").isDirectory) {
    includeBuild("../gotpache-keytool")
}
