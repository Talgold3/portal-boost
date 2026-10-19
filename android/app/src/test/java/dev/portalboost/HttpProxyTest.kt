package dev.portalboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class HttpProxyTest {
    private lateinit var proxy: HttpProxy
    private lateinit var target: ServerSocket

    @Before
    fun setUp() {
        proxy = HttpProxy(0, null, { false }, InetAddress.getByName("10.255.255.1"), { println(it) })
        proxy.start()
        target = ServerSocket(0)
    }

    @After
    fun tearDown() {
        proxy.stop(); target.close()
    }

    private fun client() = Socket("127.0.0.1", proxy.localPort).apply { soTimeout = 5000 }

    private fun readLine(s: Socket): String {
        val sb = StringBuilder()
        val i = s.getInputStream()
        while (true) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    @Test
    fun connectTunnelsBytes() {
        thread {
            target.accept().use { s -> val b = ByteArray(4); s.getInputStream().read(b); s.getOutputStream().write(b) }
        }
        client().use { c ->
            c.getOutputStream().write("CONNECT 127.0.0.1:${target.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            assertEquals("HTTP/1.1 200 Connection established", readLine(c))
            assertEquals("", readLine(c))
            c.getOutputStream().write("ping".toByteArray())
            val b = ByteArray(4); c.getInputStream().read(b)
            assertEquals("ping", String(b))
        }
    }

    @Test
    fun plainHttpIsRewrittenToOriginForm() {
        var seen = ""
        val t = thread {
            target.accept().use { s ->
                seen = readLine(s)
                while (readLine(s).isNotEmpty()) {}
                s.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
        client().use { c ->
            c.getOutputStream().write(
                "GET http://127.0.0.1:${target.localPort}/check?x=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray(),
            )
            assertTrue(readLine(c).startsWith("HTTP/1.1 204"))
        }
        t.join(2000)
        assertEquals("GET /check?x=1 HTTP/1.1", seen)
    }
}
