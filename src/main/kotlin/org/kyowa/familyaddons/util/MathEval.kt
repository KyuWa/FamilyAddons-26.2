package org.kyowa.familyaddons.util

object MathEval {

    fun evaluate(expr: String?): Double? {
        if (expr.isNullOrBlank()) return null

        // Normalize multiply symbols
        var normalized = expr.replace(Regex("[xX×]"), "*")

        // Expand k/m/b suffixes: 1.5k -> 1500, 2m -> 2000000
        normalized = normalized.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*([kKmMbB])")) { mr ->
            val num = mr.groupValues[1].toDouble()
            when (mr.groupValues[2].lowercase()) {
                "k" -> (num * 1_000).toBigDecimal().toPlainString()
                "m" -> (num * 1_000_000).toBigDecimal().toPlainString()
                "b" -> (num * 1_000_000_000).toBigDecimal().toPlainString()
                else -> mr.value
            }
        }

        val cleaned = normalized.replace(Regex("\\s+"), "")
        if (!cleaned.matches(Regex("[0-9+\\-*/().]+"))) return null

        val noLeadingMinus = cleaned.removePrefix("-")
        if (!noLeadingMinus.contains(Regex("[+\\-*/]"))) return null

        return try {
            val result = parseExpr(cleaned, intArrayOf(0))
            if (result.isFinite()) result else null
        } catch (e: Exception) {
            null
        }
    }

    // Recursive descent parser
    private fun parseExpr(s: String, pos: IntArray): Double {
        var left = parseTerm(s, pos)
        while (pos[0] < s.length && (s[pos[0]] == '+' || s[pos[0]] == '-')) {
            val op = s[pos[0]++]
            val right = parseTerm(s, pos)
            left = if (op == '+') left + right else left - right
        }
        return left
    }

    private fun parseTerm(s: String, pos: IntArray): Double {
        var left = parseFactor(s, pos)
        while (pos[0] < s.length && (s[pos[0]] == '*' || s[pos[0]] == '/')) {
            val op = s[pos[0]++]
            val right = parseFactor(s, pos)
            left = if (op == '*') left * right else left / right
        }
        return left
    }

    private fun parseFactor(s: String, pos: IntArray): Double {
        if (pos[0] >= s.length) throw IllegalArgumentException("Unexpected end")

        // Unary minus
        if (s[pos[0]] == '-') {
            pos[0]++
            return -parseFactor(s, pos)
        }

        // Parentheses
        if (s[pos[0]] == '(') {
            pos[0]++ // consume '('
            val result = parseExpr(s, pos)
            if (pos[0] >= s.length || s[pos[0]] != ')') throw IllegalArgumentException("Missing )")
            pos[0]++ // consume ')'
            return result
        }

        // Number
        val start = pos[0]
        while (pos[0] < s.length && (s[pos[0]].isDigit() || s[pos[0]] == '.')) pos[0]++
        if (pos[0] == start) throw IllegalArgumentException("Expected number at ${pos[0]}")
        return s.substring(start, pos[0]).toDouble()
    }
}
