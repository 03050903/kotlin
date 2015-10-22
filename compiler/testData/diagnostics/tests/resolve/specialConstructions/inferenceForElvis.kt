// !DIAGNOSTICS: -UNUSED_PARAMETER -UNUSED_VARIABLE
// !CHECK_TYPE

fun <T: Any> foo(f: (T) -> Unit): T? = null // T is used only as return type
fun test() {
    val x = foo { it checkType { _String() }} ?: "" // foo() is inferred as foo<String>, which isn't very good
    val y: Any = foo { it checkType { _Any() } } ?: "" // but for now it's fixed by specifying expected type
}