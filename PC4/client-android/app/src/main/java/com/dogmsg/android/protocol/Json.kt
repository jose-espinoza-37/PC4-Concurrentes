package com.dogmsg.android.protocol

/**
 * Codificador / decodificador JSON minimo y SIN dependencias externas.
 *
 * Espejo de Json.java. Solo soporta objetos planos {"clave":valor,...} donde
 * los valores son string, numero o booleano. Suficiente para los payloads del
 * protocolo (AUTH_REQUEST, QR_GENERATE, metadata de archivo, comandos de grupo).
 * No soporta arrays ni anidacion a proposito, para mantenerlo predecible entre
 * Java / Kotlin / Python.
 */
object Json {

    /** Construye JSON a partir de un mapa ordenado clave -> valor. */
    fun encode(map: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append('"').append(':')
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(escape(v.toString())).append('"')
            }
        }
        return sb.append('}').toString()
    }

    /** Builder fluido de conveniencia. */
    fun obj(): Builder = Builder()

    class Builder {
        private val map = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?): Builder {
            map[k] = v
            return this
        }
        fun build(): String = encode(map)
    }

    /**
     * Parsea un objeto plano JSON a un mapa. Todos los valores se devuelven como
     * String (el llamador convierte a numero/bool si lo necesita).
     */
    fun decode(json: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (json == null) return result
        val s = json.trim()
        if (s.isEmpty() || s[0] != '{') return result

        var i = 1
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i < n && s[i] == '}') break
            if (i >= n) break

            if (s[i] != '"') break
            val key = StringBuilder()
            i = readString(s, i, key)

            while (i < n && s[i].isWhitespace()) i++
            if (i < n && s[i] == ':') i++
            while (i < n && s[i].isWhitespace()) i++

            val value = StringBuilder()
            if (i < n && s[i] == '"') {
                i = readString(s, i, value)
            } else {
                while (i < n && s[i] != ',' && s[i] != '}') {
                    value.append(s[i])
                    i++
                }
            }
            result[key.toString()] = value.toString().trim()
        }
        return result
    }

    /**
     * Parsea un array JSON de objetos planos, como el payload de HISTORY_SYNC
     * (protocol_spec.md 3.13): [{"id":1,"from":3,...}, {...}]. Cada elemento se
     * decodifica con [decode]. No soporta anidamiento mas profundo a proposito.
     */
    fun decodeArray(json: String?): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        if (json == null) return out
        val s = json.trim()
        if (s.isEmpty() || s[0] != '[') return out

        var i = 1
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= n || s[i] == ']') break
            if (s[i] != '{') break

            val start = i
            var depth = 0
            var inStr = false
            while (i < n) {
                val c = s[i]
                if (inStr) {
                    if (c == '\\' && i + 1 < n) { i += 2; continue }
                    if (c == '"') inStr = false
                } else {
                    if (c == '"') inStr = true
                    else if (c == '{') depth++
                    else if (c == '}') { depth--; if (depth == 0) { i++; break } }
                }
                i++
            }
            out.add(decode(s.substring(start, i)))
        }
        return out
    }

    /** Lee un literal string JSON empezando en la comilla de apertura. */
    private fun readString(s: String, start: Int, out: StringBuilder): Int {
        var i = start + 1 // saltar comilla inicial
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n) {
                when (s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else if (c == '"') {
                return i + 1
            } else {
                out.append(c)
                i++
            }
        }
        return i
    }

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}