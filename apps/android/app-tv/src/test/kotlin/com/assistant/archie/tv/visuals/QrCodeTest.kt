package com.assistant.archie.tv.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Copy link" QR encoder against the Python `qrcode` package (byte mode, level M, fixed masks):
 * the same version and the same module matrix, bit for bit.
 */
class QrCodeTest {
    private data class Case(val text: String, val version: Int, val mask: Int, val hex: String)

    private val cases = listOf(
        Case("https://192.168.0.200/visualizations/solar-dashboard.html", 4, 3, "feeaf7bfc17d54d06e97a40bb7590855dba4c9aaec11cfa107faaaaafe0137c300b703fea588f26bdb65a113577e1802368da72c3dc88ff3a49a8b69cad848f668ecc750242e30cb8396dab8e13ecd88cb0512f9efc1664f1fcd24391d1cfeba166628a79c48fc8050dd46bfbc8dea105fb391eba503ffadd7545a86eafdb1d1048de179fe9d670a0"),
        Case("HELLO", 1, 0, "fe73fc15906e88bb7465dbaaaec13507faafe00b00aa50954310819a3f5ac42676aa005a9bf85cf047b0bab73dd219aeaa2b04c52feab38"),
        Case("http://192.168.0.28:8777/tv-app/index.html?x=1&y=caf\u00e9-long-path-to-push-the-version-up-a-bit", 6, 6, "fea406ac3fc150a845906eb93f204bb745b363d5dbadcf2eb2ec1038587107faaaaaaafe007c10d2009fba7b17cbaeb79cfccc109419650c9bb97b168cadef79399b1fe00de103446c7f77e726a24152041f6b668ad1c1d819fa19cf2799dc5f66e419d2b93d53fe0b455269c51ebe9841ad32074a591158971f8b4384822274400945cb5de581f63ced22be3e656ecd6cf4a7b855caf34d9a43c8a135de6dea1101c7d7eeed4027fb807929a9c6bfb12356ea905db428f19bade4749fcdd6c17bf48ae81279736b0483d6641dfe8110eb880"),
        Case("https://192.168.0.200/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 9, 1, "fec50612f2e3fc1105059313906eb57a83dcd4bb74abf90aa6d5dba68ecfaaea2ec157f045131107faaaaaaaaaafe00566b1111000a32963faaab128e4a5906aa88cc8b46fcb77626af124eaa111018ba6102daaab2d0e7a7506aaaa86481ebcb77736bf91ceaa11301ad72602daabb2d8ca87906aaaa86eb039cb77736a8a54daa13101a2a4cb6dafaa2d864cda0286a287cb893af61716a6b7b88a11531a8ff5fafaaebfdc46e3546a8ac646bbf1eb7772ba516c29111151acfecbbfaaaaf9862465d2aa82847d191dd7766aa50ace8a111348e634d3eaaab44020865e2aaa1869d317dd7777ea30b0e8a11334a67f453aaabb75fa4a65d2aa6286d9a745d7716aa32bcd4a11134ae2e3e1aafab75dc0fc4d282a2877be09dd7776aac0f338a11d24a130dbbfaacbfd8061bd46e8ac67fbe9beb1672bb04dbab111131aba0b3efaaacf9dd2dce6aaa8446ea2b2977772ad0429a511112a8fe9300aaaabdc8"),
    )

    private fun bits(c: Case): String {
        val n = (c.version * 4 + 17).let { it * it }
        return c.hex.map { Integer.toBinaryString(Character.digit(it, 16)).padStart(4, '0') }.joinToString("").take(n)
    }

    @Test fun matchesTheReferenceEncoder() {
        for (c in cases) {
            val q = QrCode.encode(c.text, forceMask = c.mask)
            assertEquals(c.text, c.version, q.version)
            val got = buildString { for (y in 0 until q.size) for (x in 0 until q.size) append(if (q[x, y]) '1' else '0') }
            assertEquals(c.text, bits(c), got)
        }
    }

    @Test fun picksAMaskAndRefusesWhatDoesNotFit() {
        val q = QrCode.encode("https://192.168.0.200/visualizations/solar-dashboard.html")
        assertTrue(q.mask in 0..7)
        assertEquals(33, q.size)
        assertTrue(runCatching { QrCode.encode("x".repeat(300)) }.isFailure)
    }
}
