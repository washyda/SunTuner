package io.washy.sm8750.tuner

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import androidx.annotation.Keep
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Keep
internal object GameGpuClock {
    val loaded = runCatching { System.loadLibrary("game_gpu_clock") }.isSuccess
    external fun init(): Boolean
    external fun stamp(id: Int)
    external fun result(id: Int): Long
}

internal class GameRenderer(
    private val session: GameSession,
    private val budgetNs: Long,
) : GLSurfaceView.Renderer {
    private data class ClockSample(val before: Long, val gpu: Long, val after: Long)
    private data class Query(val ids: IntArray, val submitted: ClockSample, val round: Int)

    private val queries = ArrayDeque<Query>()
    private val glValue = IntArray(1)
    private val clockValue = LongArray(1)
    private var measuredNs = 0L
    private var calibrated = false
    private var timer = false
    private var initialized = false
    private var scene = 0
    private var post = 0
    private var buffer = 0
    private var texture = 0
    private var vertexArray = 0
    private var width = 0
    private var height = 0
    private var renderWidth = 0
    private var renderHeight = 0
    private var scale = 0.48f
    private var frame = 0
    private var windowAt = 0L
    private var windowFrames = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = guarded {
        check(!initialized) { "GPU 上下文丢失或重建" }
        initialized = true
        scene = program(sceneShader)
        post = program(postShader)
        glGenVertexArrays(1, glValue, 0)
        vertexArray = glValue[0]
        glGenTextures(1, glValue, 0)
        texture = glValue[0]
        glGenFramebuffers(1, glValue, 0)
        buffer = glValue[0]
        timer = GameGpuClock.loaded && glGetString(GL_EXTENSIONS).orEmpty().split(' ')
            .contains("GL_EXT_disjoint_timer_query") && GameGpuClock.init()
        if (timer) readDisjoint()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) = guarded {
        width = w
        height = h
        resize(scale)
    }

    override fun onDrawFrame(gl: GL10?) = guarded {
        if (session.stopped || width == 0 || session.failure.get() != null) return@guarded
        val now = System.nanoTime()
        val elapsed = session.elapsedMs(now)
        val burst = gameBurst(elapsed, session.workerCount)
        drainTimers()
        if (session.beginNs != 0L && elapsed < GAME_CALIBRATION_MS && !calibrated) calibrate(now)
        val sample = timer && session.beginNs != 0L &&
            ((elapsed < GAME_CALIBRATION_MS && frame % 12 == 0) || (burst != null && frame % 3 == 0))
        val query = if (sample && queries.size < 8) {
            val clock = clockSample()
            if (clock.gpu > 0 && clock.after - clock.before <= 800_000L) {
                Query(IntArray(2).also { glGenQueries(2, it, 0) }, clock, burst?.round ?: -1).also {
                    GameGpuClock.stamp(it.ids[0])
                }
            } else null
        } else null

        val light = session.beginNs == 0L || elapsed >= 230_000
        glBindVertexArray(vertexArray)
        glBindFramebuffer(GL_FRAMEBUFFER, buffer)
        glViewport(0, 0, renderWidth, renderHeight)
        glUseProgram(scene)
        glUniform2f(glGetUniformLocation(scene, "resolution"), renderWidth.toFloat(), renderHeight.toFloat())
        glUniform1f(glGetUniformLocation(scene, "elapsed"), elapsed / 1000f)
        glUniform1i(glGetUniformLocation(scene, "steps"), if (light) 18 else if (burst != null) 76 else 40)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glUseProgram(post)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glUniform1i(glGetUniformLocation(post, "sceneTexture"), 0)
        glUniform1f(glGetUniformLocation(post, "burst"), if (burst != null) 1f else 0f)
        glUniform1f(glGetUniformLocation(post, "elapsed"), elapsed / 1000f)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        if (query != null) {
            GameGpuClock.stamp(query.ids[1])
            queries.addLast(query)
        }
        check(glGetError() == GL_NO_ERROR) { "GPU 渲染返回错误" }
        frame++
        session.lastFrameNs = System.nanoTime()
    }

    private fun calibrate(now: Long) {
        if (windowAt == 0L) windowAt = now
        windowFrames++
        if (now - windowAt < 1_000_000_000L) return
        val frameTime = (now - windowAt) / windowFrames
        val next = when {
            measuredNs > budgetNs * 78 / 100 || frameTime > budgetNs * 128 / 100 -> scale - 0.08f
            measuredNs in 1..(budgetNs * 56 / 100) -> scale + 0.08f
            measuredNs == 0L && frameTime < budgetNs * 113 / 100 -> scale + 0.04f
            else -> scale
        }.coerceIn(0.32f, 0.88f)
        if (next != scale) resize(next)
        windowAt = now
        windowFrames = 0
        if (session.elapsedMs(now) >= 17_000) calibrated = true
    }

    private fun resize(nextScale: Float) {
        scale = nextScale
        val factor = minOf(scale, 1600f / maxOf(width, height))
        renderWidth = (width * factor).toInt().coerceAtLeast(1)
        renderHeight = (height * factor).toInt().coerceAtLeast(1)
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, renderWidth, renderHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glBindFramebuffer(GL_FRAMEBUFFER, buffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "GPU 帧缓冲区初始化失败" }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun readDisjoint(): Boolean {
        glGetIntegerv(0x8FBB, glValue, 0)
        return glValue[0] != 0
    }

    private fun clockSample(): ClockSample {
        val before = System.nanoTime()
        glGetInteger64v(0x8E28, clockValue, 0)
        return ClockSample(before, clockValue[0], System.nanoTime())
    }

    private fun drainTimers() {
        if (queries.isEmpty()) return
        glGetQueryObjectuiv(queries.first().ids[1], GL_QUERY_RESULT_AVAILABLE, glValue, 0)
        if (glValue[0] == 0) return
        val clock = clockSample()
        if (readDisjoint()) {
            while (queries.isNotEmpty()) glDeleteQueries(2, queries.removeFirst().ids, 0)
            return
        }
        while (queries.isNotEmpty()) {
            val next = queries.first()
            glGetQueryObjectuiv(next.ids[1], GL_QUERY_RESULT_AVAILABLE, glValue, 0)
            if (glValue[0] == 0) break
            queries.removeFirst()
            val first = GameGpuClock.result(next.ids[0])
            val last = GameGpuClock.result(next.ids[1])
            glDeleteQueries(2, next.ids, 0)
            val duration = last - first
            if (first <= 0 || duration !in 1..500_000_000L) continue
            if (next.round < 0) {
                measuredNs = if (measuredNs == 0L) duration else (measuredNs * 3 + duration) / 4
                continue
            }
            // Keep only overlap inside both CPU/GPU clock calibration bounds.
            val low = minOf(next.submitted.before - next.submitted.gpu, clock.before - clock.gpu)
            val high = maxOf(next.submitted.after - next.submitted.gpu, clock.after - clock.gpu)
            if (clock.gpu <= 0 || high - low > 1_500_000L) continue
            val start = first + high
            val end = last + low
            if (end > start && start >= next.submitted.before && end <= clock.after) {
                session.recordGpu(GameSpan(start, end, next.round))
            }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        if (session.failure.get() != null) return
        try {
            block()
        } catch (e: Exception) {
            session.failure.compareAndSet(null, e.message ?: "GPU 渲染失败")
        }
    }

    private fun program(fragment: String): Int {
        val vs = shader(GL_VERTEX_SHADER, vertexShader)
        val fs = shader(GL_FRAGMENT_SHADER, fragment)
        val id = glCreateProgram()
        glAttachShader(id, vs)
        glAttachShader(id, fs)
        glLinkProgram(id)
        glDeleteShader(vs)
        glDeleteShader(fs)
        glGetProgramiv(id, GL_LINK_STATUS, glValue, 0)
        check(glValue[0] == GL_TRUE) { "GPU 程序链接失败 · ${glGetProgramInfoLog(id)}" }
        return id
    }

    private fun shader(type: Int, code: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, code)
        glCompileShader(id)
        glGetShaderiv(id, GL_COMPILE_STATUS, glValue, 0)
        check(glValue[0] == GL_TRUE) { "GPU 着色器编译失败 · ${glGetShaderInfoLog(id)}" }
        return id
    }

    private companion object {
        val vertexShader = """
            #version 300 es
            out vec2 uv;
            void main() {
                vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = v;
                gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        val sceneShader = """
            #version 300 es
            precision highp float;
            in vec2 uv;
            out vec4 color;
            uniform vec2 resolution;
            uniform float elapsed;
            uniform int steps;
            vec2 mapScene(vec3 p) {
                vec3 tile = p;
                tile.xz = mod(p.xz + 2.0, 4.0) - 2.0;
                vec2 id = floor((p.xz + 2.0) / 4.0);
                float shift = sin(dot(id, vec2(1.8, 2.3)) + elapsed * 0.7);
                tile.y -= 0.4 + shift * 0.55;
                vec3 q = abs(tile) - vec3(0.55, 0.8 + shift * 0.25, 0.55);
                float boxes = length(max(q, 0.0)) + min(max(q.x,max(q.y,q.z)),0.0) - 0.10;
                vec3 orbit = vec3(sin(elapsed + id.x) * 0.75, 1.55, cos(elapsed + id.y) * 0.75);
                float sphere = length(tile - orbit) - 0.22;
                float floorDist = p.y + 1.25;
                if (floorDist < min(boxes, sphere)) return vec2(floorDist, 0.0);
                return boxes < sphere ? vec2(boxes, 1.0) : vec2(sphere, 2.0);
            }
            void main() {
                vec2 xy = uv * 2.0 - 1.0;
                xy.x *= resolution.x / resolution.y;
                vec3 origin = vec3(sin(elapsed * 0.35) * 1.6, 1.6, -elapsed * 3.2);
                vec3 ray = normalize(vec3(xy.x, xy.y - 0.26, -1.5));
                vec3 sky = mix(vec3(0.03, 0.05, 0.14), vec3(0.23, 0.18, 0.35), 1.0 - abs(xy.y));
                float depth = 0.0;
                float glow = 0.0;
                vec3 result = sky;
                for (int i = 0; i < steps; i++) {
                    vec3 p = origin + ray * depth;
                    vec2 h = mapScene(p);
                    glow += 0.0015 / (0.04 + h.x * h.x);
                    if (h.x < 0.012) {
                        vec3 n = normalize(vec3(mapScene(p+vec3(0.02,0,0)).x-h.x,
                            mapScene(p+vec3(0,0.02,0)).x-h.x, mapScene(p+vec3(0,0,0.02)).x-h.x));
                        float light = 0.3 + 0.7 * max(dot(n, normalize(vec3(-0.5,1.0,0.8))),0.0);
                        vec3 city = h.y > 1.5 ? vec3(0.8,0.35,0.14) : vec3(0.14,0.76,0.86);
                        if (h.y < 0.5) {
                            vec2 grid = abs(fract(p.xz * 0.5 - 0.5) - 0.5);
                            city = vec3(0.08,0.11,0.23) + vec3(0.2,0.35,0.48) *
                                (1.0 - smoothstep(0.0,0.05,min(grid.x, grid.y)));
                        } else if (h.y < 1.5) {
                            city *= 0.55 + 0.45 * sin(p.y * 28.0);
                        }
                        result = mix(city * light, sky, 1.0 - exp(-depth * 0.055));
                        break;
                    }
                    depth += h.x * 0.78;
                    if (depth > 65.0) break;
                }
                color = vec4(result + glow * vec3(0.12,0.25,0.38), 1.0);
            }
        """.trimIndent()

        val postShader = """
            #version 300 es
            precision highp float;
            in vec2 uv;
            out vec4 color;
            uniform sampler2D sceneTexture;
            uniform float burst;
            uniform float elapsed;
            void main() {
                vec3 base = texture(sceneTexture, uv).rgb;
                if (burst > 0.5) {
                    vec2 d = 1.0 / vec2(textureSize(sceneTexture, 0));
                    vec3 bloom = vec3(0.0);
                    for (int i = -4; i <= 4; i++) {
                        float f = float(i);
                        bloom += max(texture(sceneTexture, uv + vec2(f, f) * d * 2.0).rgb - 0.35, 0.0);
                        bloom += max(texture(sceneTexture, uv + vec2(f, -f) * d * 2.0).rgb - 0.35, 0.0);
                    }
                    vec2 grid = uv * vec2(37.0,60.0);
                    grid.y += elapsed * 13.0;
                    float rnd = fract(sin(dot(floor(grid), vec2(28.12, 73.94))) * 42113.33);
                    float particle = step(0.96,rnd) * (1.0 - smoothstep(0.05,0.2,length(fract(grid)-0.5)));
                    base += bloom * 0.075 + particle * vec3(0.42,0.65,0.82);
                }
                color = vec4(base * (0.76 + 0.24 * sin(uv.y * 3.14159)), 1.0);
            }
        """.trimIndent()
    }
}
