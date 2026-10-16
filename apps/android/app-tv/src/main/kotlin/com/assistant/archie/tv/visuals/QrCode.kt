package com.assistant.archie.tv.visuals

/**
 * A minimal QR Code encoder (ISO/IEC 18004) for "Copy link" (spec 15 §3.5: a QR code so a phone
 * can open the page) without a new dependency: byte mode, error correction level M, versions 1–10
 * (up to 213 bytes, plenty for a page URL), automatic mask choice by the standard penalty rules.
 * The algorithm follows Project Nayuki's reference implementation (MIT); `QrCodeTest` checks the
 * matrices against the Python `qrcode` package for fixed masks.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    /** Dark module at column [x], row [y]. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        private const val MAX_VERSION = 10
        // Level M, index = version (0 unused).
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val FORMAT_BITS_M = 0

        /** Encodes [text] as UTF-8 bytes; [forceMask] 0–7 for tests, else the lowest-penalty mask. */
        fun encode(text: String, forceMask: Int = -1): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            for (version in 1..MAX_VERSION) {
                val capacityBits = dataCodewords(version) * 8
                val countBits = if (version <= 9) 8 else 16
                val used = 4 + countBits + data.size * 8
                if (data.size < (1 shl countBits) && used <= capacityBits) return build(version, data, capacityBits, countBits, forceMask)
            }
            throw IllegalArgumentException("text too long for a QR code up to version $MAX_VERSION (${data.size} bytes)")
        }

        private fun rawDataModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(ver: Int) = rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

        private fun build(version: Int, data: ByteArray, capacityBits: Int, countBits: Int, forceMask: Int): QrCode {
            val bits = ArrayList<Boolean>(capacityBits)
            fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
            append(0b0100, 4)
            append(data.size, countBits)
            for (b in data) append(b.toInt() and 0xFF, 8)
            append(0, minOf(4, capacityBits - bits.size))
            append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) {
                append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            val codewords = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            return Builder(version).make(interleave(version, codewords), forceMask)
        }

        private fun interleave(version: Int, data: ByteArray): ByteArray {
            val numBlocks = NUM_BLOCKS[version]
            val eccLen = ECC_PER_BLOCK[version]
            val raw = rawDataModules(version) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val len = shortLen - eccLen + if (i < numShort) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val block = dat.copyOf(shortLen + 1)
                val ecc = rsRemainder(dat, divisor)
                System.arraycopy(ecc, 0, block, block.size - eccLen, ecc.size)
                blocks += block
            }
            val result = ByteArray(raw)
            var n = 0
            for (i in 0 until blocks[0].size) {
                for (j in blocks.indices) {
                    if (i != shortLen - eccLen || j >= numShort) result[n++] = blocks[j][i]
                }
            }
            return result
        }

        private fun rsMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    result[j] = rsMultiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = rsMultiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor rsMultiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
    }

    private class Builder(private val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun make(codewords: ByteArray, forceMask: Int): QrCode {
            drawFunctionPatterns()
            drawCodewords(codewords)
            val mask = if (forceMask in 0..7) forceMask else (0..7).minBy { m ->
                applyMask(m)
                drawFormatBits(m)
                penalty().also { applyMask(m) }
            }
            applyMask(mask)
            drawFormatBits(mask)
            return QrCode(version, mask, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, d != 2 && d != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var p = size - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = p
                p -= step
            }
            return result
        }

        fun drawFormatBits(mask: Int) {
            val data = FORMAT_BITS_M shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val b = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val c = i / 3
                set(a, c, b)
                set(c, a, b)
            }
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(m: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (m) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        private fun penalty(): Int {
            var result = 0
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    var runColor = false
                    var run = 0
                    val history = IntArray(7)
                    for (b in 0 until size) {
                        val c = if (horizontal) modules[a][b] else modules[b][a]
                        if (c == runColor) {
                            run++
                            if (run == 5) result += 3 else if (run > 5) result++
                        } else {
                            addHistory(run, history)
                            if (!runColor) result += countPatterns(history) * 40
                            runColor = c
                            run = 1
                        }
                    }
                    result += terminateAndCount(runColor, run, history) * 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            return result + k * 10
        }

        private fun countPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun terminateAndCount(color: Boolean, length: Int, h: IntArray): Int {
            var run = length
            if (color) {
                addHistory(run, h)
                run = 0
            }
            run += size
            addHistory(run, h)
            return countPatterns(h)
        }

        private fun addHistory(length: Int, h: IntArray) {
            var run = length
            if (h[0] == 0) run += size
            System.arraycopy(h, 0, h, 1, h.size - 1)
            h[0] = run
        }
    }
}
