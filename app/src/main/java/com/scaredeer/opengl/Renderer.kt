package com.scaredeer.opengl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLES20
import android.opengl.GLUtils
import android.graphics.BitmapFactory
import android.opengl.GLES20.*
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig

/**
 * ゲームのメインループに相当するクラス
 * （もちろん、画面の更新を中心としたもので、ゲームモデルの論理的なループとは必ずしも同じではないが、
 * 実用上はこのクラスを中心に構成していいと思う）
 *
 * MainActivity で implement して記述を統合することも全然可能だが、
 * MainActivity はその他の UI のコードなども盛り込まれることになるので、コードの見通しが悪くなり、
 * あまり実用的ではないので、素直に分離している。
 */
class Renderer(private val mBitmap: Bitmap) : GLSurfaceView.Renderer {

    companion object {
        private val TAG = Renderer::class.simpleName

        private const val BYTES_PER_FLOAT = 4 // Java float is 32-bit = 4-byte
        private const val POSITION_COMPONENT_COUNT = 2 // x, y（※ z は常に 0 なので省略）
        private const val TEXTURE_COORDINATES_COMPONENT_COUNT = 2 // s, t

        // STRIDE は要するに、次の頂点データセットへスキップするバイト数を表したもの。
        // 一つの頂点データセットは頂点座標 x, y とテクスチャー座標の s, t の 4 つで構成されているが、
        // 頂点データを読み取ってシェーダー変数へ渡す処理と、テクスチャー座標を読み取ってシェーダー変数に渡す処理が
        // 別々であるため、次の頂点を処理する際に、4 つ分のバイト数をスキップする必要が生じる。
        private const val STRIDE =
            (POSITION_COMPONENT_COUNT + TEXTURE_COORDINATES_COMPONENT_COUNT) * BYTES_PER_FLOAT
        private val TILE = floatArrayOf(
            // x, y, s, t
            0f, 0f, 0f, 0f,    // 左下
            0f, 256f, 0f, 1f,  // 左上
            256f, 0f, 1f, 0f,  // 右下
            256f, 256f, 1f, 1f // 右上
        )

        // Attributes
        private const val A_POSITION = "a_Position"
        private const val A_TEXTURE_COORDINATES = "a_TextureCoordinates"

        // Uniforms
        private const val U_MVP_MATRIX = "u_MVPMatrix"
        private const val U_TEXTURE_UNIT = "u_TextureUnit"

        // Varyings
        private const val V_TEXTURE_COORDINATES = "v_TextureCoordinates"

        private const val VERTEX_SHADER = """
            uniform mat4 $U_MVP_MATRIX;
            attribute vec4 $A_POSITION;
            attribute vec2 $A_TEXTURE_COORDINATES;
            varying vec2 $V_TEXTURE_COORDINATES;
            void main() {
                gl_Position = $U_MVP_MATRIX * $A_POSITION;
                $V_TEXTURE_COORDINATES = $A_TEXTURE_COORDINATES;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D $U_TEXTURE_UNIT;
            varying vec2 $V_TEXTURE_COORDINATES;
            void main() {
                gl_FragColor = texture2D($U_TEXTURE_UNIT, $V_TEXTURE_COORDINATES);
            }
        """

        /**
         * Compiles a shader, returning the OpenGL object ID.
         *
         * @see <a href="https://media.pragprog.com/titles/kbogla/code/AirHockey1/src/com/airhockey/android/util/ShaderHelper.java">OpenGL ES 2 for Android</a>
         * @param type       GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
         * @param shaderCode String data of shader code
         * @return the OpenGL object ID (or 0 if compilation failed)
         */
        private fun compileShader(type: Int, shaderCode: String): Int {
            // Create a new shader object.
            val shaderObjectId = glCreateShader(type)
            if (shaderObjectId == 0) {
                Log.w(TAG, "Could not create new shader.")
                return 0
            }

            // Pass in (upload) the shader source.
            glShaderSource(shaderObjectId, shaderCode)

            // Compile the shader.
            glCompileShader(shaderObjectId)

            // Get the compilation status.
            val compileStatus = IntArray(1)
            glGetShaderiv(shaderObjectId, GL_COMPILE_STATUS, compileStatus, 0)

            // Print the shader info log to the Android log output.
            Log.v(TAG, """
            Result of compiling source:
                $shaderCode
            Log:
                ${glGetShaderInfoLog(shaderObjectId)}
            """.trimIndent())

            // Verify the compile status.
            if (compileStatus[0] == 0) {
                // If it failed, delete the shader object.
                glDeleteShader(shaderObjectId)
                Log.w(TAG, "Compilation of shader failed.")
                return 0
            }

            // Return the shader object ID.
            return shaderObjectId
        }

        /**
         * Links a vertex shader and a fragment shader together into an OpenGL
         * program. Returns the OpenGL program object ID, or 0 if linking failed.
         *
         * @see <a href="https://media.pragprog.com/titles/kbogla/code/AirHockey1/src/com/airhockey/android/util/ShaderHelper.java">OpenGL ES 2 for Android</a>
         * @param vertexShaderId   OpenGL object ID of vertex shader
         * @param fragmentShaderId OpenGL object ID of fragment shader
         * @return OpenGL program object ID (or 0 if linking failed)
         */
        private fun linkProgram(vertexShaderId: Int, fragmentShaderId: Int): Int {
            // Create a new program object.
            val programObjectId = glCreateProgram()
            if (programObjectId == 0) {
                Log.w(TAG, "Could not create new program")
                return 0
            }

            // Attach the vertex shader to the program.
            glAttachShader(programObjectId, vertexShaderId)
            // Attach the fragment shader to the program.
            glAttachShader(programObjectId, fragmentShaderId)

            // Link the two shaders together into a program.
            glLinkProgram(programObjectId)

            // Get the link status.
            val linkStatus = IntArray(1)
            glGetProgramiv(programObjectId, GL_LINK_STATUS, linkStatus, 0)

            // Print the program info log to the Android log output.
            Log.v(TAG, """
                Result log of linking program:
                ${glGetProgramInfoLog(programObjectId)}
            """.trimIndent())

            // Verify the link status.
            if (linkStatus[0] == 0) {
                // If it failed, delete the program object.
                glDeleteProgram(programObjectId)
                Log.w(TAG, "Linking of program failed.")
                return 0
            }

            // Return the program object ID.
            return programObjectId
        }

        /**
         * Validates an OpenGL program. Should only be called when developing the application.
         *
         * @see <a href="https://media.pragprog.com/titles/kbogla/code/AirHockey1/src/com/airhockey/android/util/ShaderHelper.java">OpenGL ES 2 for Android</a>
         * @param programObjectId OpenGL program object ID to validate
         * @return boolean
         */
        private fun validateProgram(programObjectId: Int): Boolean {
            glValidateProgram(programObjectId)
            val validateStatus = IntArray(1)
            glGetProgramiv(programObjectId, GL_VALIDATE_STATUS, validateStatus, 0)
            Log.v(TAG, """
                Result status code of validating program: ${validateStatus[0]}
                Log:
                ${glGetProgramInfoLog(programObjectId)}
            """.trimIndent())
            return validateStatus[0] != 0
        }

        /**
         * @param bitmap Loads a texture from a Bitmap
         * @return OpenGL ID for the texture. Returns 0 if the load failed.
         */
        fun loadTextureFromBitmap(bitmap: Bitmap): Int {
            val textureObjectIds = IntArray(1)
            glGenTextures(1, textureObjectIds, 0)
            if (textureObjectIds[0] == 0) {
                Log.w(TAG, "Could not generate a new OpenGL texture object.")
                return 0
            }

            // Bind to the texture in OpenGL
            glBindTexture(GL_TEXTURE_2D, textureObjectIds[0])

            // Set filtering: a default must be set, or the texture will be black.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            // Load the bitmap into the bound texture.
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)

            // Note: Following code may cause an error to be reported in the ADB log as follows:
            // E/IMGSRV(20095): :0: HardwareMipGen: Failed to generate texture mipmap levels (error=3)
            // No OpenGL error will be encountered (glGetError() will return0).
            // If this happens, just squash the source image to be square.
            // It will look the same because of texture coordinates, and mipmap generation will work.
            glGenerateMipmap(GL_TEXTURE_2D)

            // Unbind from the texture.
            glBindTexture(GL_TEXTURE_2D, 0)
            return textureObjectIds[0]
        }

        /**
         * @param context Context オブジェクト
         * @param resourceId R.drawable.XXX
         * @return Bitmap オブジェクト
         */
        fun loadBitmap(context: Context, resourceId: Int): Bitmap? {
            val options = BitmapFactory.Options()
            options.inScaled = false

            // Read in the resource
            val bitmap = BitmapFactory.decodeResource(context.resources, resourceId, options)
            if (bitmap == null) {
                Log.w(TAG, "Resource ID $resourceId could not be decoded.")
                return null
            }
            return bitmap
        }
    }

    private var aPosition = 0
    private var aTextureCoordinates = 0
    private var uMVPMatrix = 0
    private var uTextureUnit = 0

    // JavaVM (float) -> DirectBuffer (FloatBuffer) -> OpenGL
    private val mVertexData: FloatBuffer = ByteBuffer
        .allocateDirect(TILE.size * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(TILE)

    private val mProjectionMatrix = FloatArray(16)
    private val mViewMatrix = FloatArray(16)
    private val mVPMatrix = FloatArray(16)

    private var mTexture = 0

    override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
        Log.v(TAG, "onSurfaceCreated")
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f)

        // Compile the shaders.
        val vertexShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        // Link them into a shader program.
        val program = linkProgram(vertexShader, fragmentShader)
        validateProgram(program)

        // Use this program.
        glUseProgram(program)

        // Retrieve uniform locations for the shader program.
        uMVPMatrix = glGetUniformLocation(program, U_MVP_MATRIX)
        uTextureUnit = glGetUniformLocation(program, U_TEXTURE_UNIT)

        // Retrieve attribute locations for the shader program.
        aPosition = glGetAttribLocation(program, A_POSITION)
        aTextureCoordinates = glGetAttribLocation(program, A_TEXTURE_COORDINATES)

        // Bind our data, specified by the variable vertexData, to the vertex
        // attribute at location of A_POSITION.
        mVertexData.position(0)
        glVertexAttribPointer(
            aPosition,
            POSITION_COMPONENT_COUNT,
            GL_FLOAT,
            false,
            STRIDE,
            mVertexData
        )
        glEnableVertexAttribArray(aPosition)

        // Bind our data, specified by the variable vertexData, to the vertex
        // attribute at location A_TEXTURE_COORDINATES.
        mVertexData.position(POSITION_COMPONENT_COUNT) // starts right after the vertex (x, y) coordinates
        glVertexAttribPointer(
            aTextureCoordinates,
            TEXTURE_COORDINATES_COMPONENT_COUNT,
            GL_FLOAT,
            false,
            STRIDE,
            mVertexData
        )
        glEnableVertexAttribArray(aTextureCoordinates)

        mTexture = loadTextureFromBitmap(mBitmap)
        // Recycle the bitmap, since its data has been loaded into OpenGL.
        mBitmap.recycle()
    }

    override fun onSurfaceChanged(gl10: GL10, width: Int, height: Int) {
        Log.v(TAG, "onSurfaceChanged")
        // Set the OpenGL viewport to fill the entire surface.
        glViewport(0, 0, width, height)

        /*
        frustum は lookAtM で決定される視軸に対して相対的な視界範囲を決めるという形で使われるものと思われ、
        （Web に見つかるのは perspective の用例ばかりであり、
        frustum についてはあくまでも自分で実地に色々と値を代入してテストしてみた結果による判断ではあるが）
        必ず、x 方向, y 方向は ± で指定する必要があるようである。（near/far にしても、
        絶対的な z 座標「位置」ではなくて、カメラ位置からの相対的な z 方向の「距離」を指定しているのと同じように。）

        特に near プレーンは単なるクリッピングエリアを決めるだけでなく、left/right/bottom/top と合わせて、
        視野角を決定する要因にもなるので、クリッピングエリアを変更するつもりで、気安くいじらないこと。

        far プレーンについては、等倍（ドット・バイ・ドット）の位置（z 座標は 0）を指定している。
        near プレーンはその半分の距離の位置（z 座標は -width/4）にあるとし、left/right/bottom/top を描画領域
        の 1/4 ずつの値にすることで、near プレーンにおいて縦横の幅が半分のサイズの長方形となるようにしている。

        こうすることで、far プレーンで等倍が実現できるので、そこに置くオブジェクトのサイズはそのまま画面の
        ピクセルサイズに基くことが可能となる。
         */
        Matrix.frustumM(
            mProjectionMatrix, 0,
            -width / 4f, width / 4f, -height / 4f, height / 4f,
            width / 4f, width / 2f
        )

        /*
        UV 座標系と同じに扱うには、右手系のまま、単に、カメラの上下を引っくり返せば、Y 軸が下向きになるばかりでなく、
        Z 軸も奥に向って正方向となり、色々と感覚的にシームレスになる。これが upY = -1 の理由である。
        また、centerX/centerY は視線を常に z 軸に平行とするため、eyeX/eyeY と共通にする。
        eyeZ については本来は任意なのだが、わかりやすいので画面横幅を斜辺とする直角二等辺三角形になるようにする値を
        選ぶ（つまり width/2 の距離）。かつ z = 0 が、斜辺の位置とする（つまり z 座標は -width/2）。
        eyeZ = -eyeX となっているわけである。
         */
        Matrix.setLookAtM(
            mViewMatrix, 0,
            width / 2f, height / 2f, -width / 2f,
            width / 2f, height / 2f, 1f,
            0f, -1f, 0f
        )
        Matrix.multiplyMM(
            mVPMatrix, 0,
            mProjectionMatrix, 0, mViewMatrix, 0
        )
        // Pass the matrix into the shader program.
        glUniformMatrix4fv(uMVPMatrix, 1, false, mVPMatrix, 0)

        // Set the active texture unit to texture unit 0.
        glActiveTexture(GL_TEXTURE0)
        // Bind the texture to this unit.
        glBindTexture(GL_TEXTURE_2D, mTexture)
        // Tell the texture uniform sampler to use this texture in the shader by
        // telling it to read from texture unit 0.
        glUniform1i(uTextureUnit, 0)
    }

    override fun onDrawFrame(gl10: GL10) {
        // Clear the rendering surface.
        glClear(GL_COLOR_BUFFER_BIT)

        // Draw a tile.
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4) // N 字形の順に描く
    }
}