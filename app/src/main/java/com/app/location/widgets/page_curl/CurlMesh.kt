package com.app.location.widgets.page_curl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10

/**
 * Class implementing actual curl/page rendering.
 *
 * @author harism
 */
class CurlMesh(maxCurlSplits: Int) {
    // Let's avoid using 'new' as much as possible. Meaning we introduce arrays
    // once here and reuse them on runtime. Doesn't really have very much effect
    // but avoids some garbage collections from happening.
    private lateinit var mArrDropShadowVertices: Array<ShadowVertex>
    private val mArrIntersections: Array<Vertex>
    private val mArrOutputVertices: Array<Vertex>
    private val mArrRotatedVertices: Array<Vertex>
    private val mArrScanLines: Array<Double>
    private lateinit var mArrSelfShadowVertices: Array<ShadowVertex>
    private lateinit var mArrTempShadowVertices: Array<ShadowVertex>
    private val mArrTempVertices: Array<Vertex>

    // Buffers for feeding rasterizer.
    private val mBufColors: FloatBuffer
    private lateinit var mBufCurlPositionLines: FloatBuffer
    private lateinit var mBufShadowColors: FloatBuffer
    private lateinit var mBufShadowVertices: FloatBuffer
    private lateinit var mBufTexCoords: FloatBuffer
    private val mBufVertices: FloatBuffer
    private var mCurlPositionLinesCount = 0
    private var mDropShadowCount = 0

    // Boolean for 'flipping' texture sideways.
    private var mFlipTexture = false

    // Maximum number of split lines used for creating a curl.
    private val mMaxCurlSplits: Int

    // Bounding rectangle for this mesh. mRectagle[0] = top-left corner,
    // mRectangle[1] = bottom-left, mRectangle[2] = top-right and mRectangle[3]
    // bottom-right.
    private val mRectangle = arrayOfNulls<Vertex>(4)
    private var mSelfShadowCount = 0
    private var mTextureBack = false

    // Texture ids and other variables.
    private var mTextureIds: IntArray? = null
    private val mTexturePage: CurlPage = CurlPage()
    private val mTextureRectBack = RectF()
    private val mTextureRectFront = RectF()
    private var mVerticesCountBack = 0
    private var mVerticesCountFront = 0

    /**
     * Adds vertex to buffers.
     */
    private fun addVertex(vertex: Vertex) {
        mBufVertices.put(vertex.mPosX.toFloat())
        mBufVertices.put(vertex.mPosY.toFloat())
        mBufVertices.put(vertex.mPosZ.toFloat())
        mBufColors.put(vertex.mColorFactor * Color.red(vertex.mColor) / 255f)
        mBufColors.put(vertex.mColorFactor * Color.green(vertex.mColor) / 255f)
        mBufColors.put(vertex.mColorFactor * Color.blue(vertex.mColor) / 255f)
        mBufColors.put(Color.alpha(vertex.mColor) / 255f)
        if (DRAW_TEXTURE) {
            mBufTexCoords.put(vertex.mTexX.toFloat())
            mBufTexCoords.put(vertex.mTexY.toFloat())
        }
    }

    /**
     * Sets curl for this mesh.
     *
     * @param curlPos
     * Position for curl 'center'. Can be any point on line collinear
     * to curl.
     * @param curlDir
     * Curl direction, should be normalized.
     * @param radius
     * Radius of curl.
     */
    @Synchronized
    fun curl(curlPos: PointF, curlDir: PointF, radius: Double) {

        // First add some 'helper' lines used for development.
        if (DRAW_CURL_POSITION) {
            mBufCurlPositionLines.position(0)
            mBufCurlPositionLines.put(curlPos.x)
            mBufCurlPositionLines.put(curlPos.y - 1.0f)
            mBufCurlPositionLines.put(curlPos.x)
            mBufCurlPositionLines.put(curlPos.y + 1.0f)
            mBufCurlPositionLines.put(curlPos.x - 1.0f)
            mBufCurlPositionLines.put(curlPos.y)
            mBufCurlPositionLines.put(curlPos.x + 1.0f)
            mBufCurlPositionLines.put(curlPos.y)
            mBufCurlPositionLines.put(curlPos.x)
            mBufCurlPositionLines.put(curlPos.y)
            mBufCurlPositionLines.put(curlPos.x + curlDir.x * 2)
            mBufCurlPositionLines.put(curlPos.y + curlDir.y * 2)
            mBufCurlPositionLines.position(0)
        }

        // Actual 'curl' implementation starts here.
        mBufVertices.position(0)
        mBufColors.position(0)
        if (DRAW_TEXTURE) {
            mBufTexCoords.position(0)
        }

        // Calculate curl angle from direction.
        var curlAngle = Math.acos(curlDir.x.toDouble())
        curlAngle = if (curlDir.y > 0) -curlAngle else curlAngle

        // Initiate rotated rectangle which's is translated to curlPos and
        // rotated so that curl direction heads to right (1,0). Vertices are
        // ordered in ascending order based on x -coordinate at the same time.
        // And using y -coordinate in very rare case in which two vertices have
        // same x -coordinate.
        mArrTempVertices.addAll(mArrRotatedVertices)
        mArrRotatedVertices.clear()
        for (i in 0..3) {
            val v = mArrTempVertices.remove(0)!!
            v.set(mRectangle[i])
            v.translate(-curlPos.x.toDouble(), -curlPos.y.toDouble())
            v.rotateZ(-curlAngle)
            var j = 0
            while (j < mArrRotatedVertices.size()) {
                val v2 = mArrRotatedVertices[j]!!
                if (v.mPosX > v2.mPosX) {
                    break
                }
                if (v.mPosX == v2.mPosX && v.mPosY > v2.mPosY) {
                    break
                }
                ++j
            }
            mArrRotatedVertices.add(j, v)
        }

        // Rotated rectangle lines/vertex indices. We need to find bounding
        // lines for rotated rectangle. After sorting vertices according to
        // their x -coordinate we don't have to worry about vertices at indices
        // 0 and 1. But due to inaccuracy it's possible vertex 3 is not the
        // opposing corner from vertex 0. So we are calculating distance from
        // vertex 0 to vertices 2 and 3 - and altering line indices if needed.
        // Also vertices/lines are given in an order first one has x -coordinate
        // at least the latter one. This property is used in getIntersections to
        // see if there is an intersection.
        val lines = arrayOf(intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2, 3))
        run {

            // TODO: There really has to be more 'easier' way of doing this -
            // not including extensive use of sqrt.
            val v0 = mArrRotatedVertices[0]!!
            val v2 = mArrRotatedVertices[2]!!
            val v3 = mArrRotatedVertices[3]!!
            val dist2 = Math.sqrt((v0.mPosX - v2.mPosX)
                    * (v0.mPosX - v2.mPosX) + (v0.mPosY - v2.mPosY)
                    * (v0.mPosY - v2.mPosY))
            val dist3 = Math.sqrt((v0.mPosX - v3.mPosX)
                    * (v0.mPosX - v3.mPosX) + (v0.mPosY - v3.mPosY)
                    * (v0.mPosY - v3.mPosY))
            if (dist2 > dist3) {
                lines[1][1] = 3
                lines[2][1] = 2
            }
        }
        mVerticesCountBack = 0
        mVerticesCountFront = mVerticesCountBack
        if (DRAW_SHADOW) {
            mArrTempShadowVertices.addAll(mArrDropShadowVertices)
            mArrTempShadowVertices.addAll(mArrSelfShadowVertices)
            mArrDropShadowVertices.clear()
            mArrSelfShadowVertices.clear()
        }

        // Length of 'curl' curve.
        val curlLength = Math.PI * radius
        // Calculate scan lines.
        // TODO: Revisit this code one day. There is room for optimization here.
        mArrScanLines.clear()
        if (mMaxCurlSplits > 0) {
            mArrScanLines.add(0.toDouble())
        }
        for (i in 1 until mMaxCurlSplits) {
            mArrScanLines.add(-curlLength * i / (mMaxCurlSplits - 1))
        }
        // As mRotatedVertices is ordered regarding x -coordinate, adding
        // this scan line produces scan area picking up vertices which are
        // rotated completely. One could say 'until infinity'.
        mArrScanLines.add(mArrRotatedVertices[3]!!.mPosX - 1)

        // Start from right most vertex. Pretty much the same as first scan area
        // is starting from 'infinity'.
        var scanXmax = mArrRotatedVertices[0]!!.mPosX + 1
        for (i in 0 until mArrScanLines.size()) {
            // Once we have scanXmin and scanXmax we have a scan area to start
            // working with.
            val scanXmin = mArrScanLines[i]!!
            // First iterate 'original' rectangle vertices within scan area.
            for (j in 0 until mArrRotatedVertices.size()) {
                val v = mArrRotatedVertices[j]!!
                // Test if vertex lies within this scan area.
                // TODO: Frankly speaking, can't remember why equality check was
                // added to both ends. Guessing it was somehow related to case
                // where radius=0f, which, given current implementation, could
                // be handled much more effectively anyway.
                if (v.mPosX >= scanXmin && v.mPosX <= scanXmax) {
                    // Pop out a vertex from temp vertices.
                    val n = mArrTempVertices.remove(0)!!
                    n.set(v)
                    // This is done solely for triangulation reasons. Given a
                    // rotated rectangle it has max 2 vertices having
                    // intersection.
                    val intersections = getIntersections(
                            mArrRotatedVertices, lines, n.mPosX)
                    // In a sense one could say we're adding vertices always in
                    // two, positioned at the ends of intersecting line. And for
                    // triangulation to work properly they are added based on y
                    // -coordinate. And this if-else is doing it for us.
                    if (intersections.size() == 1
                            && intersections[0]!!.mPosY > v.mPosY) {
                        // In case intersecting vertex is higher add it first.
                        mArrOutputVertices.addAll(intersections)
                        mArrOutputVertices.add(n)
                    } else if (intersections.size() <= 1) {
                        // Otherwise add original vertex first.
                        mArrOutputVertices.add(n)
                        mArrOutputVertices.addAll(intersections)
                    } else {
                        // There should never be more than 1 intersecting
                        // vertex. But if it happens as a fallback simply skip
                        // everything.
                        mArrTempVertices.add(n)
                        mArrTempVertices.addAll(intersections)
                    }
                }
            }

            // Search for scan line intersections.
            val intersections = getIntersections(mArrRotatedVertices,
                    lines, scanXmin)

            // We expect to get 0 or 2 vertices. In rare cases there's only one
            // but in general given a scan line intersecting rectangle there
            // should be 2 intersecting vertices.
            if (intersections.size() == 2) {
                // There were two intersections, add them based on y
                // -coordinate, higher first, lower last.
                val v1 = intersections[0]!!
                val v2 = intersections[1]!!
                if (v1.mPosY < v2.mPosY) {
                    mArrOutputVertices.add(v2)
                    mArrOutputVertices.add(v1)
                } else {
                    mArrOutputVertices.addAll(intersections)
                }
            } else if (intersections.size() != 0) {
                // This happens in a case in which there is a original vertex
                // exactly at scan line or something went very much wrong if
                // there are 3+ vertices. What ever the reason just return the
                // vertices to temp vertices for later use. In former case it
                // was handled already earlier once iterating through
                // mRotatedVertices, in latter case it's better to avoid doing
                // anything with them.
                mArrTempVertices.addAll(intersections)
            }

            // Add vertices found during this iteration to vertex etc buffers.
            while (mArrOutputVertices.size() > 0) {
                val v = mArrOutputVertices.remove(0)!!
                mArrTempVertices.add(v)

                // Local texture front-facing flag.
                var textureFront: Boolean

                // Untouched vertices.
                if (i == 0) {
                    textureFront = true
                    mVerticesCountFront++
                } else if (i == mArrScanLines.size() - 1 || curlLength == 0.0) {
                    v.mPosX = -(curlLength + v.mPosX)
                    v.mPosZ = 2 * radius
                    v.mPenumbraX = -v.mPenumbraX
                    textureFront = false
                    mVerticesCountBack++
                } else {
                    // Even though it's not obvious from the if-else clause,
                    // here v.mPosX is between [-curlLength, 0]. And we can do
                    // calculations around a half cylinder.
                    val rotY = Math.PI * (v.mPosX / curlLength)
                    v.mPosX = radius * Math.sin(rotY)
                    v.mPosZ = radius - radius * Math.cos(rotY)
                    v.mPenumbraX *= Math.cos(rotY)
                    // Map color multiplier to [.1f, 1f] range.
                    v.mColorFactor = (.1f + .9f * Math.sqrt(Math
                            .sin(rotY) + 1)).toFloat()
                    if (v.mPosZ >= radius) {
                        textureFront = false
                        mVerticesCountBack++
                    } else {
                        textureFront = true
                        mVerticesCountFront++
                    }
                }

                // We use local textureFront for flipping backside texture
                // locally. Plus additionally if mesh is in flip texture mode,
                // we'll make the procedure "backwards". Also, until this point,
                // texture coordinates are within [0, 1] range so we'll adjust
                // them to final texture coordinates too.
                if (textureFront != mFlipTexture) {
                    v.mTexX *= mTextureRectFront.right.toDouble()
                    v.mTexY *= mTextureRectFront.bottom.toDouble()
                    v.mColor = mTexturePage.getColor(CurlPage.SIDE_FRONT)
                } else {
                    v.mTexX *= mTextureRectBack.right.toDouble()
                    v.mTexY *= mTextureRectBack.bottom.toDouble()
                    v.mColor = mTexturePage.getColor(CurlPage.SIDE_BACK)
                }

                // Move vertex back to 'world' coordinates.
                v.rotateZ(curlAngle)
                v.translate(curlPos.x.toDouble(), curlPos.y.toDouble())
                addVertex(v)

                // Drop shadow is cast 'behind' the curl.
                if (DRAW_SHADOW && v.mPosZ > 0 && v.mPosZ <= radius) {
                    val sv = mArrTempShadowVertices!!.remove(0)!!
                    sv.mPosX = v.mPosX
                    sv.mPosY = v.mPosY
                    sv.mPosZ = v.mPosZ
                    sv.mPenumbraX = v.mPosZ / 2 * -curlDir.x
                    sv.mPenumbraY = v.mPosZ / 2 * -curlDir.y
                    sv.mPenumbraColor = v.mPosZ / radius
                    val idx = (mArrDropShadowVertices.size() + 1) / 2
                    mArrDropShadowVertices.add(idx, sv)
                }
                // Self shadow is cast partly over mesh.
                if (DRAW_SHADOW && v.mPosZ > radius) {
                    val sv = mArrTempShadowVertices.remove(0)!!
                    sv.mPosX = v.mPosX
                    sv.mPosY = v.mPosY
                    sv.mPosZ = v.mPosZ
                    sv.mPenumbraX = (v.mPosZ - radius) / 3 * v.mPenumbraX
                    sv.mPenumbraY = (v.mPosZ - radius) / 3 * v.mPenumbraY
                    sv.mPenumbraColor = (v.mPosZ - radius) / (2 * radius)
                    val idx = (mArrSelfShadowVertices.size() + 1) / 2
                    mArrSelfShadowVertices.add(idx, sv)
                }
            }

            // Switch scanXmin as scanXmax for next iteration.
            scanXmax = scanXmin
        }
        mBufVertices.position(0)
        mBufColors.position(0)
        if (DRAW_TEXTURE) {
            mBufTexCoords.position(0)
        }

        // Add shadow Vertices.
        if (DRAW_SHADOW) {
            mBufShadowColors.position(0)
            mBufShadowVertices.position(0)
            mDropShadowCount = 0
            for (i in 0 until mArrDropShadowVertices.size()) {
                val sv = mArrDropShadowVertices[i]!!
                mBufShadowVertices.put(sv.mPosX.toFloat())
                mBufShadowVertices.put(sv.mPosY.toFloat())
                mBufShadowVertices.put(sv.mPosZ.toFloat())
                mBufShadowVertices.put((sv.mPosX + sv.mPenumbraX).toFloat())
                mBufShadowVertices.put((sv.mPosY + sv.mPenumbraY).toFloat())
                mBufShadowVertices.put(sv.mPosZ.toFloat())
                for (j in 0..3) {
                    val color = (SHADOW_OUTER_COLOR[j]
                            + (SHADOW_INNER_COLOR[j] - SHADOW_OUTER_COLOR[j])
                            * sv.mPenumbraColor)
                    mBufShadowColors.put(color.toFloat())
                }
                mBufShadowColors.put(SHADOW_OUTER_COLOR)
                mDropShadowCount += 2
            }
            mSelfShadowCount = 0
            for (i in 0 until mArrSelfShadowVertices.size()) {
                val sv = mArrSelfShadowVertices[i]!!
                mBufShadowVertices.put(sv.mPosX.toFloat())
                mBufShadowVertices.put(sv.mPosY.toFloat())
                mBufShadowVertices.put(sv.mPosZ.toFloat())
                mBufShadowVertices.put((sv.mPosX + sv.mPenumbraX).toFloat())
                mBufShadowVertices.put((sv.mPosY + sv.mPenumbraY).toFloat())
                mBufShadowVertices.put(sv.mPosZ.toFloat())
                for (j in 0..3) {
                    val color = (SHADOW_OUTER_COLOR[j]
                            + (SHADOW_INNER_COLOR[j] - SHADOW_OUTER_COLOR[j])
                            * sv.mPenumbraColor)
                    mBufShadowColors.put(color.toFloat())
                }
                mBufShadowColors.put(SHADOW_OUTER_COLOR)
                mSelfShadowCount += 2
            }
            mBufShadowColors.position(0)
            mBufShadowVertices.position(0)
        }
    }

    /**
     * Calculates intersections for given scan line.
     */
    private fun getIntersections(vertices: Array<Vertex>,
                                 lineIndices: kotlin.Array<IntArray>, scanX: Double): Array<Vertex> {
        mArrIntersections.clear()
        // Iterate through rectangle lines each re-presented as a pair of
        // vertices.
        for (j in lineIndices.indices) {
            val v1 = vertices[lineIndices[j][0]]!!
            val v2 = vertices[lineIndices[j][1]]!!
            // Here we expect that v1.mPosX >= v2.mPosX and wont do intersection
            // test the opposite way.
            if (v1.mPosX > scanX && v2.mPosX < scanX) {
                // There is an intersection, calculate coefficient telling 'how
                // far' scanX is from v2.
                val c = (scanX - v2.mPosX) / (v1.mPosX - v2.mPosX)
                val n = mArrTempVertices.remove(0)!!
                n.set(v2)
                n.mPosX = scanX
                n.mPosY += (v1.mPosY - v2.mPosY) * c
                if (DRAW_TEXTURE) {
                    n.mTexX += (v1.mTexX - v2.mTexX) * c
                    n.mTexY += (v1.mTexY - v2.mTexY) * c
                }
                if (DRAW_SHADOW) {
                    n.mPenumbraX += (v1.mPenumbraX - v2.mPenumbraX) * c
                    n.mPenumbraY += (v1.mPenumbraY - v2.mPenumbraY) * c
                }
                mArrIntersections.add(n)
            }
        }
        return mArrIntersections
    }

    /**
     * Getter for textures page for this mesh.
     */
    @get:Synchronized
    val texturePage: CurlPage
        get() = mTexturePage

    /**
     * Renders our page curl mesh.
     */
    @Synchronized
    fun onDrawFrame(gl: GL10) {
        // First allocate texture if there is not one yet.
        if (DRAW_TEXTURE && mTextureIds == null) {
            // Generate texture.
            mTextureIds = IntArray(2)
            gl.glGenTextures(2, mTextureIds, 0)
            for (textureId in mTextureIds!!) {
                // Set texture attributes.
                gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
                gl.glTexParameterf(GL10.GL_TEXTURE_2D,
                        GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
                gl.glTexParameterf(GL10.GL_TEXTURE_2D,
                        GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_NEAREST.toFloat())
                gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S,
                        GL10.GL_CLAMP_TO_EDGE.toFloat())
                gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T,
                        GL10.GL_CLAMP_TO_EDGE.toFloat())
            }
        }
        if (DRAW_TEXTURE && mTexturePage.texturesChanged) {
            gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureIds!![0])
            var texture: Bitmap = mTexturePage.getTexture(mTextureRectFront, CurlPage.SIDE_FRONT)
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, texture, 0)
            texture.recycle()
            mTextureBack = mTexturePage.hasBackTexture()
            if (mTextureBack) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureIds!![1])
                texture = mTexturePage.getTexture(mTextureRectBack, CurlPage.SIDE_BACK)
                GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, texture, 0)
                texture.recycle()
            } else {
                mTextureRectBack.set(mTextureRectFront)
            }
            mTexturePage.recycle()
            reset()
        }

        // Some 'global' settings.
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)

        // TODO: Drop shadow drawing is done temporarily here to hide some
        // problems with its calculation.
        if (DRAW_SHADOW) {
            gl.glDisable(GL10.GL_TEXTURE_2D)
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
            gl.glColorPointer(4, GL10.GL_FLOAT, 0, mBufShadowColors)
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, mBufShadowVertices)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, mDropShadowCount)
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
            gl.glDisable(GL10.GL_BLEND)
        }
        if (DRAW_TEXTURE) {
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, mBufTexCoords)
        }
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, mBufVertices)
        // Enable color array.
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
        gl.glColorPointer(4, GL10.GL_FLOAT, 0, mBufColors)

        // Draw front facing blank vertices.
        gl.glDisable(GL10.GL_TEXTURE_2D)
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, mVerticesCountFront)

        // Draw front facing texture.
        if (DRAW_TEXTURE) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glEnable(GL10.GL_TEXTURE_2D)
            if (!mFlipTexture || !mTextureBack) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureIds!![0])
            } else {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureIds!![1])
            }
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, mVerticesCountFront)
            gl.glDisable(GL10.GL_BLEND)
            gl.glDisable(GL10.GL_TEXTURE_2D)
        }
        val backStartIdx = Math.max(0, mVerticesCountFront - 2)
        val backCount = mVerticesCountFront + mVerticesCountBack - backStartIdx

        // Draw back facing blank vertices.
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, backStartIdx, backCount)

        // Draw back facing texture.
        if (DRAW_TEXTURE) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glEnable(GL10.GL_TEXTURE_2D)
            if (mFlipTexture || !mTextureBack) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureIds!![0])
            } else {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureIds!![1])
            }
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, backStartIdx, backCount)
            gl.glDisable(GL10.GL_BLEND)
            gl.glDisable(GL10.GL_TEXTURE_2D)
        }

        // Disable textures and color array.
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
        if (DRAW_POLYGON_OUTLINES) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glLineWidth(1.0f)
            gl.glColor4f(0.5f, 0.5f, 1.0f, 1.0f)
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, mBufVertices)
            gl.glDrawArrays(GL10.GL_LINE_STRIP, 0, mVerticesCountFront)
            gl.glDisable(GL10.GL_BLEND)
        }
        if (DRAW_CURL_POSITION) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glLineWidth(1.0f)
            gl.glColor4f(1.0f, 0.5f, 0.5f, 1.0f)
            gl.glVertexPointer(2, GL10.GL_FLOAT, 0, mBufCurlPositionLines)
            gl.glDrawArrays(GL10.GL_LINES, 0, mCurlPositionLinesCount * 2)
            gl.glDisable(GL10.GL_BLEND)
        }
        if (DRAW_SHADOW) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
            gl.glColorPointer(4, GL10.GL_FLOAT, 0, mBufShadowColors)
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, mBufShadowVertices)
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, mDropShadowCount,
                    mSelfShadowCount)
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
            gl.glDisable(GL10.GL_BLEND)
        }
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY)
    }

    /**
     * Resets mesh to 'initial' state. Meaning this mesh will draw a plain
     * textured rectangle after call to this method.
     */
    @Synchronized
    fun reset() {
        mBufVertices.position(0)
        mBufColors.position(0)
        if (DRAW_TEXTURE) {
            mBufTexCoords.position(0)
        }
        for (i in 0..3) {
            val tmp = mArrTempVertices[0]!!
            tmp.set(mRectangle[i])
            if (mFlipTexture) {
                tmp.mTexX *= mTextureRectBack.right.toDouble()
                tmp.mTexY *= mTextureRectBack.bottom.toDouble()
                tmp.mColor = mTexturePage.getColor(CurlPage.SIDE_BACK)
            } else {
                tmp.mTexX *= mTextureRectFront.right.toDouble()
                tmp.mTexY *= mTextureRectFront.bottom.toDouble()
                tmp.mColor = mTexturePage.getColor(CurlPage.SIDE_FRONT)
            }
            addVertex(tmp)
        }
        mVerticesCountFront = 4
        mVerticesCountBack = 0
        mBufVertices.position(0)
        mBufColors.position(0)
        if (DRAW_TEXTURE) {
            mBufTexCoords.position(0)
        }
        mSelfShadowCount = 0
        mDropShadowCount = mSelfShadowCount
    }

    /**
     * Resets allocated texture id forcing creation of new one. After calling
     * this method you most likely want to set bitmap too as it's lost. This
     * method should be called only once e.g GL context is re-created as this
     * method does not release previous texture id, only makes sure new one is
     * requested on next render.
     */
    @Synchronized
    fun resetTexture() {
        mTextureIds = null
    }

    /**
     * If true, flips texture sideways.
     */
    @Synchronized
    fun setFlipTexture(flipTexture: Boolean) {
        mFlipTexture = flipTexture
        if (flipTexture) {
            setTexCoords(1f, 0f, 0f, 1f)
        } else {
            setTexCoords(0f, 0f, 1f, 1f)
        }
    }

    /**
     * Update mesh bounds.
     */
    fun setRect(r: RectF) {
        mRectangle[0]!!.mPosX = r.left.toDouble()
        mRectangle[0]!!.mPosY = r.top.toDouble()
        mRectangle[1]!!.mPosX = r.left.toDouble()
        mRectangle[1]!!.mPosY = r.bottom.toDouble()
        mRectangle[2]!!.mPosX = r.right.toDouble()
        mRectangle[2]!!.mPosY = r.top.toDouble()
        mRectangle[3]!!.mPosX = r.right.toDouble()
        mRectangle[3]!!.mPosY = r.bottom.toDouble()
    }

    /**
     * Sets texture coordinates to mRectangle vertices.
     */
    @Synchronized
    private fun setTexCoords(left: Float, top: Float, right: Float, bottom: Float) {
        mRectangle[0]!!.mTexX = left.toDouble()
        mRectangle[0]!!.mTexY = top.toDouble()
        mRectangle[1]!!.mTexX = left.toDouble()
        mRectangle[1]!!.mTexY = bottom.toDouble()
        mRectangle[2]!!.mTexX = right.toDouble()
        mRectangle[2]!!.mTexY = top.toDouble()
        mRectangle[3]!!.mTexX = right.toDouble()
        mRectangle[3]!!.mTexY = bottom.toDouble()
    }

    /**
     * Simple fixed size array implementation.
     */
    private inner class Array<T>(private val mCapacity: Int) {
        private val mArray: kotlin.Array<Any?>
        private var mSize = 0
        fun add(index: Int, item: T) {
            if (index < 0 || index > mSize || mSize >= mCapacity) {
                throw IndexOutOfBoundsException()
            }
            for (i in mSize downTo index + 1) {
                mArray[i] = mArray[i - 1]
            }
            mArray[index] = item
            ++mSize
        }

        fun add(item: T) {
            if (mSize >= mCapacity) {
                throw IndexOutOfBoundsException()
            }
            mArray[mSize++] = item
        }

        fun addAll(array: Array<T>) {
            if (mSize + array.size() > mCapacity) {
                throw IndexOutOfBoundsException()
            }
            for (i in 0 until array.size()) {
                mArray[mSize++] = array[i]
            }
        }

        fun clear() {
            mSize = 0
        }

        operator fun get(index: Int): T? {
            if (index < 0 || index >= mSize) {
                throw IndexOutOfBoundsException()
            }
            return mArray[index] as T?
        }

        fun remove(index: Int): T? {
            if (index < 0 || index >= mSize) {
                throw IndexOutOfBoundsException()
            }
            val item = mArray[index] as T?
            for (i in index until mSize - 1) {
                mArray[i] = mArray[i + 1]
            }
            --mSize
            return item
        }

        fun size(): Int {
            return mSize
        }

        init {
            mArray = arrayOfNulls(mCapacity)
        }
    }

    /**
     * Holder for shadow vertex information.
     */
    private inner class ShadowVertex {
        var mPenumbraColor = 0.0
        var mPenumbraX = 0.0
        var mPenumbraY = 0.0
        var mPosX = 0.0
        var mPosY = 0.0
        var mPosZ = 0.0
    }

    /**
     * Holder for vertex information.
     */
    private inner class Vertex {
        var mColor = 0
        var mColorFactor: Float
        var mPenumbraX = 0.0
        var mPenumbraY = 0.0
        var mPosX: Double
        var mPosY: Double
        var mPosZ: Double
        var mTexX: Double
        var mTexY = 0.0
        fun rotateZ(theta: Double) {
            val cos = Math.cos(theta)
            val sin = Math.sin(theta)
            val x = mPosX * cos + mPosY * sin
            val y = mPosX * -sin + mPosY * cos
            mPosX = x
            mPosY = y
            val px = mPenumbraX * cos + mPenumbraY * sin
            val py = mPenumbraX * -sin + mPenumbraY * cos
            mPenumbraX = px
            mPenumbraY = py
        }

        fun set(vertex: Vertex?) {
            mPosX = vertex!!.mPosX
            mPosY = vertex.mPosY
            mPosZ = vertex.mPosZ
            mTexX = vertex.mTexX
            mTexY = vertex.mTexY
            mPenumbraX = vertex.mPenumbraX
            mPenumbraY = vertex.mPenumbraY
            mColor = vertex.mColor
            mColorFactor = vertex.mColorFactor
        }

        fun translate(dx: Double, dy: Double) {
            mPosX += dx
            mPosY += dy
        }

        init {
            mTexX = mTexY
            mPosZ = mTexX
            mPosY = mPosZ
            mPosX = mPosY
            mColorFactor = 1.0f
        }
    }

    companion object {
        // Flag for rendering some lines used for developing. Shows
        // curl position and one for the direction from the
        // position given. Comes handy once playing around with different
        // ways for following pointer.
        private const val DRAW_CURL_POSITION = false

        // Flag for drawing polygon outlines. Using this flag crashes on emulator
        // due to reason unknown to me. Leaving it here anyway as seeing polygon
        // outlines gives good insight how original rectangle is divided.
        private const val DRAW_POLYGON_OUTLINES = false

        // Flag for enabling shadow rendering.
        private const val DRAW_SHADOW = true

        // Flag for texture rendering. While this is likely something you
        // don't want to do it's been used for development purposes as texture
        // rendering is rather slow on emulator.
        private const val DRAW_TEXTURE = true

        // Colors for shadow. Inner one is the color drawn next to surface where
        // shadowed area starts and outer one is color shadow ends to.
        private val SHADOW_INNER_COLOR = floatArrayOf(0f, 0f, 0f, .5f)
        private val SHADOW_OUTER_COLOR = floatArrayOf(0f, 0f, 0f, .0f)
    }

    /**
     * Constructor for mesh object.
     *
     * @param maxCurlSplits
     * Maximum number curl can be divided into. The bigger the value
     * the smoother curl will be. With the cost of having more
     * polygons for drawing.
     */
    init {
        // There really is no use for 0 splits.
        mMaxCurlSplits = if (maxCurlSplits < 1) 1 else maxCurlSplits
        mArrScanLines = Array(maxCurlSplits + 2)
        mArrOutputVertices = Array(7)
        mArrRotatedVertices = Array(4)
        mArrIntersections = Array(2)
        mArrTempVertices = Array(7 + 4)
        for (i in 0 until 7 + 4) {
            mArrTempVertices.add(Vertex())
        }
        if (DRAW_SHADOW) {
            mArrSelfShadowVertices = Array(
                    (mMaxCurlSplits + 2) * 2)
            mArrDropShadowVertices = Array(
                    (mMaxCurlSplits + 2) * 2)
            mArrTempShadowVertices = Array(
                    (mMaxCurlSplits + 2) * 2)
            for (i in 0 until (mMaxCurlSplits + 2) * 2) {
                mArrTempShadowVertices.add(ShadowVertex())
            }
        }

        // Rectangle consists of 4 vertices. Index 0 = top-left, index 1 =
        // bottom-left, index 2 = top-right and index 3 = bottom-right.
        for (i in 0..3) {
            mRectangle[i] = Vertex()
        }
        // Set up shadow penumbra direction to each vertex. We do fake 'self
        // shadow' calculations based on this information.
        mRectangle[3]!!.mPenumbraY = -1.0
        mRectangle[1]!!.mPenumbraY = mRectangle[3]!!.mPenumbraY
        mRectangle[1]!!.mPenumbraX = mRectangle[1]!!.mPenumbraY
        mRectangle[0]!!.mPenumbraX = mRectangle[1]!!.mPenumbraX
        mRectangle[3]!!.mPenumbraX = 1.0
        mRectangle[2]!!.mPenumbraY = mRectangle[3]!!.mPenumbraX
        mRectangle[2]!!.mPenumbraX = mRectangle[2]!!.mPenumbraY
        mRectangle[0]!!.mPenumbraY = mRectangle[2]!!.mPenumbraX
        if (DRAW_CURL_POSITION) {
            mCurlPositionLinesCount = 3
            val hvbb = ByteBuffer.allocateDirect(mCurlPositionLinesCount * 2 * 2 * 4)
            hvbb.order(ByteOrder.nativeOrder())
            mBufCurlPositionLines = hvbb.asFloatBuffer()
            mBufCurlPositionLines.position(0)
        }

        // There are 4 vertices from bounding rect, max 2 from adding split line
        // to two corners and curl consists of max mMaxCurlSplits lines each
        // outputting 2 vertices.
        val maxVerticesCount = 4 + 2 + 2 * mMaxCurlSplits
        val vbb = ByteBuffer.allocateDirect(maxVerticesCount * 3 * 4)
        vbb.order(ByteOrder.nativeOrder())
        mBufVertices = vbb.asFloatBuffer()
        mBufVertices.position(0)
        if (DRAW_TEXTURE) {
            val tbb = ByteBuffer.allocateDirect(maxVerticesCount * 2 * 4)
            tbb.order(ByteOrder.nativeOrder())
            mBufTexCoords = tbb.asFloatBuffer()
            mBufTexCoords.position(0)
        }
        val cbb = ByteBuffer.allocateDirect(maxVerticesCount * 4 * 4)
        cbb.order(ByteOrder.nativeOrder())
        mBufColors = cbb.asFloatBuffer()
        mBufColors.position(0)
        if (DRAW_SHADOW) {
            val maxShadowVerticesCount = (mMaxCurlSplits + 2) * 2 * 2
            val scbb = ByteBuffer.allocateDirect(maxShadowVerticesCount * 4 * 4)
            scbb.order(ByteOrder.nativeOrder())
            mBufShadowColors = scbb.asFloatBuffer()
            mBufShadowColors.position(0)
            val sibb = ByteBuffer.allocateDirect(maxShadowVerticesCount * 3 * 4)
            sibb.order(ByteOrder.nativeOrder())
            mBufShadowVertices = sibb.asFloatBuffer()
            mBufShadowVertices.position(0)
            mSelfShadowCount = 0
            mDropShadowCount = mSelfShadowCount
        }
    }
}